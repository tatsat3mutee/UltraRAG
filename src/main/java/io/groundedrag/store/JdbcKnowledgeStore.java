package io.groundedrag.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.groundedrag.domain.Chunk;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.ParentChunk;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.domain.TextAnalyzer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres adapter: pgvector HNSW for dense search, a generated tsvector + GIN index for keyword
 * search, and ordinary transactions for the versioned swap.
 *
 * <p>Space filters use {@code = ANY(...)}: a list of spaces must mean "any of", never "all of".
 */
public class JdbcKnowledgeStore implements KnowledgeStore {

    private static final String HIT_COLUMNS =
            "c.id, c.document_id, c.parent_id, c.space_id, c.content, c.source_name, c.page, c.section";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public JdbcKnowledgeStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static final RowMapper<DocumentRecord> DOC = (rs, i) -> new DocumentRecord(
            rs.getObject("id", UUID.class),
            new SpaceId(rs.getString("space_id")),
            rs.getString("source_name"),
            rs.getString("content_hash"),
            rs.getInt("version"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("content_type"),
            rs.getInt("chunk_count"),
            rs.getString("embedding_model"),
            rs.getString("error"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    @Override
    public DocumentRecord createDocument(SpaceId space, String sourceName, String contentHash, String contentType) {
        return tx.execute(status -> {
            // Serialise version allocation per (space, source) without a table lock.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?))", Object.class,
                    space.value() + "/" + sourceName);
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(version), 0) FROM documents WHERE space_id = ? AND source_name = ?",
                    Integer.class, space.value(), sourceName);
            Instant now = Instant.now();
            DocumentRecord doc = new DocumentRecord(UUID.randomUUID(), space, sourceName, contentHash,
                    (max == null ? 0 : max) + 1, DocumentStatus.QUEUED, contentType, 0, null, null, now, now);
            jdbc.update("""
                    INSERT INTO documents (id, space_id, source_name, content_hash, version, status, content_type,
                                           chunk_count, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)""",
                    doc.id(), space.value(), sourceName, contentHash, doc.version(), doc.status().name(),
                    contentType, Timestamp.from(now), Timestamp.from(now));
            return doc;
        });
    }

    @Override
    public Optional<DocumentRecord> findDocument(SpaceId space, UUID id) {
        return jdbc.query("SELECT * FROM documents WHERE id = ? AND space_id = ?", DOC, id, space.value())
                .stream().findFirst();
    }

    @Override
    public Optional<DocumentRecord> findActiveByHash(SpaceId space, String contentHash) {
        return jdbc.query("SELECT * FROM documents WHERE space_id = ? AND content_hash = ? AND status = 'ACTIVE'",
                DOC, space.value(), contentHash).stream().findFirst();
    }

    @Override
    public List<DocumentRecord> listDocuments(SpaceId space) {
        return jdbc.query("SELECT * FROM documents WHERE space_id = ? ORDER BY source_name, version",
                DOC, space.value());
    }

    @Override
    public void updateStatus(UUID documentId, DocumentStatus status, String error) {
        jdbc.update("UPDATE documents SET status = ?, error = ?, updated_at = now() WHERE id = ?",
                status.name(), error, documentId);
    }

    @Override
    public void saveChunks(UUID documentId, List<ParentChunk> parents, List<Chunk> chunks) {
        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate(
                    "INSERT INTO parent_chunks (id, document_id, space_id, ordinal, content) VALUES (?, ?, ?, ?, ?)",
                    parents, 500, (ps, p) -> {
                        ps.setObject(1, p.id());
                        ps.setObject(2, p.documentId());
                        ps.setString(3, p.space().value());
                        ps.setInt(4, p.ordinal());
                        ps.setString(5, p.text());
                    });
            jdbc.batchUpdate("""
                    INSERT INTO chunks (id, document_id, parent_id, space_id, ordinal, content, source_name,
                                        page, section, embedding)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)""",
                    chunks, 200, (ps, c) -> {
                        ps.setObject(1, c.id());
                        ps.setObject(2, c.documentId());
                        ps.setObject(3, c.parentId());
                        ps.setString(4, c.space().value());
                        ps.setInt(5, c.ordinal());
                        ps.setString(6, c.text());
                        ps.setString(7, c.sourceName());
                        ps.setObject(8, c.page());
                        ps.setString(9, c.section());
                        ps.setString(10, toVectorLiteral(c.embedding()));
                    });
        });
    }

    @Override
    public void activate(UUID documentId, String embeddingModel, int chunkCount) {
        tx.executeWithoutResult(status -> {
            DocumentRecord doc = jdbc.queryForObject("SELECT * FROM documents WHERE id = ? FOR UPDATE", DOC, documentId);
            List<UUID> previous = jdbc.queryForList("""
                    SELECT id FROM documents
                    WHERE space_id = ? AND source_name = ? AND status = 'ACTIVE' AND id <> ? FOR UPDATE""",
                    UUID.class, doc.space().value(), doc.sourceName(), documentId);
            for (UUID old : previous) {
                jdbc.update("DELETE FROM chunks WHERE document_id = ?", old);
                jdbc.update("DELETE FROM parent_chunks WHERE document_id = ?", old);
                jdbc.update("UPDATE documents SET status = 'SUPERSEDED', updated_at = now() WHERE id = ?", old);
            }
            jdbc.update("UPDATE chunks SET active = TRUE WHERE document_id = ?", documentId);
            jdbc.update("""
                    UPDATE documents SET status = 'ACTIVE', embedding_model = ?, chunk_count = ?, error = NULL,
                                         updated_at = now()
                    WHERE id = ?""", embeddingModel, chunkCount, documentId);
            bumpCorpusVersion(doc.space());
        });
    }

    @Override
    public void discardChunks(UUID documentId) {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM chunks WHERE document_id = ?", documentId);
            jdbc.update("DELETE FROM parent_chunks WHERE document_id = ?", documentId);
        });
    }

    @Override
    public boolean deleteDocument(SpaceId space, UUID documentId) {
        Boolean deleted = tx.execute(status -> {
            List<String> source = jdbc.queryForList(
                    "SELECT source_name FROM documents WHERE id = ? AND space_id = ?", String.class,
                    documentId, space.value());
            if (source.isEmpty()) {
                return false;
            }
            jdbc.update("DELETE FROM documents WHERE space_id = ? AND source_name = ?", space.value(), source.get(0));
            bumpCorpusVersion(space);
            return true;
        });
        return Boolean.TRUE.equals(deleted);
    }

    private void bumpCorpusVersion(SpaceId space) {
        jdbc.update("""
                INSERT INTO corpus_versions (space_id, version) VALUES (?, 1)
                ON CONFLICT (space_id) DO UPDATE SET version = corpus_versions.version + 1""", space.value());
    }

    @Override
    public List<RetrievedChunk> vectorSearch(Set<SpaceId> spaces, float[] queryEmbedding, int k) {
        String vector = toVectorLiteral(queryEmbedding);
        return tx.execute(status -> {
            // pgvector >= 0.8: keep scanning the HNSW graph until LIMIT rows pass the space filter,
            // instead of returning fewer than k results for small spaces.
            jdbc.execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'");
            jdbc.execute("SET LOCAL hnsw.ef_search = " + Math.max(40, k * 2));
            return jdbc.query("SELECT " + HIT_COLUMNS + ", 1 - (c.embedding <=> ?::vector) AS cosine, 0.0 AS kw "
                            + "FROM chunks c WHERE c.active AND c.space_id = ANY(string_to_array(?, ',')) "
                            + "ORDER BY c.embedding <=> ?::vector LIMIT ?",
                    JdbcKnowledgeStore::hit, vector, joinSpaces(spaces), vector, k);
        });
    }

    @Override
    public List<RetrievedChunk> keywordSearch(Set<SpaceId> spaces, String query, float[] queryEmbedding, int k) {
        String tsQuery = toOrTsQuery(query);
        if (tsQuery.isEmpty()) {
            return List.of();
        }
        // Rank first, then compute cosine only for the k survivors: an OR query can match thousands
        // of rows, and a 384-1536-dim distance per matched row dominated latency (38 ms -> see
        // docs/PERFORMANCE.md).
        return jdbc.query("SELECT " + HIT_COLUMNS + ", 1 - (c.embedding <=> ?::vector) AS cosine, top.kw "
                        + "FROM (SELECT c.id, ts_rank_cd(c.tsv, q.query) AS kw "
                        + "      FROM chunks c, to_tsquery('english', ?) AS q(query) "
                        + "      WHERE c.active AND c.space_id = ANY(string_to_array(?, ',')) AND c.tsv @@ q.query "
                        + "      ORDER BY kw DESC LIMIT ?) top "
                        + "JOIN chunks c ON c.id = top.id ORDER BY top.kw DESC",
                JdbcKnowledgeStore::hit, toVectorLiteral(queryEmbedding), tsQuery, joinSpaces(spaces), k);
    }

    /**
     * websearch_to_tsquery/plainto_tsquery AND every term, so a natural-language question rarely
     * matches anything. We OR the content terms and let ts_rank_cd reward chunks matching more of them.
     * Terms are reduced to [a-z0-9] so user text can never inject tsquery operators.
     */
    static String toOrTsQuery(String query) {
        return TextAnalyzer.terms(query).stream()
                .map(t -> t.replaceAll("[^\\p{L}\\p{N}]", ""))
                .filter(t -> !t.isEmpty())
                .distinct()
                .collect(Collectors.joining(" | "));
    }

    private static RetrievedChunk hit(ResultSet rs, int i) throws SQLException {
        double cosine = rs.getDouble("cosine");
        return new RetrievedChunk(
                rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                new SpaceId(rs.getString("space_id")),
                rs.getString("content"),
                rs.getString("source_name"),
                (Integer) rs.getObject("page"),
                rs.getString("section"),
                cosine, rs.getDouble("kw"), 0, cosine);
    }

    @Override
    public Map<UUID, String> parentTexts(Collection<UUID> parentIds) {
        Map<UUID, String> out = new HashMap<>();
        if (parentIds.isEmpty()) {
            return out;
        }
        String ids = parentIds.stream().map(UUID::toString).collect(Collectors.joining(","));
        jdbc.query("SELECT id, content FROM parent_chunks WHERE id = ANY(string_to_array(?, ',')::uuid[])",
                rs -> {
                    out.put(rs.getObject("id", UUID.class), rs.getString("content"));
                }, ids);
        return out;
    }

    @Override
    public Map<SpaceId, Long> corpusVersions(Set<SpaceId> spaces) {
        Map<SpaceId, Long> out = new HashMap<>();
        spaces.forEach(s -> out.put(s, 0L));
        jdbc.query("SELECT space_id, version FROM corpus_versions WHERE space_id = ANY(string_to_array(?, ','))",
                rs -> {
                    out.put(new SpaceId(rs.getString("space_id")), rs.getLong("version"));
                }, joinSpaces(spaces));
        return out;
    }

    @Override
    public int failInterruptedIngestions() {
        return Optional.ofNullable(tx.execute(status -> {
            List<UUID> stuck = jdbc.queryForList(
                    "SELECT id FROM documents WHERE status IN ('QUEUED', 'INDEXING') FOR UPDATE", UUID.class);
            for (UUID id : stuck) {
                jdbc.update("DELETE FROM chunks WHERE document_id = ?", id);
                jdbc.update("DELETE FROM parent_chunks WHERE document_id = ?", id);
                jdbc.update("UPDATE documents SET status = 'FAILED', error = 'Interrupted by restart', "
                        + "updated_at = now() WHERE id = ?", id);
            }
            return stuck.size();
        })).orElse(0);
    }

    @Override
    public long countChunksWithOtherModel(String currentModel) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(chunk_count), 0) FROM documents WHERE status = 'ACTIVE' "
                        + "AND embedding_model IS NOT NULL AND embedding_model <> ?", Long.class, currentModel);
        return n == null ? 0 : n;
    }

    private static String joinSpaces(Set<SpaceId> spaces) {
        // Safe: SpaceId is validated to [a-z0-9_-], so it can never contain the separator.
        return spaces.stream().map(SpaceId::value).sorted().collect(Collectors.joining(","));
    }

    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}

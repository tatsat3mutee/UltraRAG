package io.groundedrag.store;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import io.groundedrag.Fixtures;
import io.groundedrag.chat.RagService;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.eval.EvalCase;
import io.groundedrag.eval.Evaluator;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.NoopReranker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Not part of the build (the name does not end in Test). Run explicitly:
 * <pre>./mvnw test -Dtest=LatencyBenchmark -Dbench.docs=2000</pre>
 * Indexes a synthetic corpus into Postgres and the in-memory store, then measures the query path
 * with offline models, so the numbers isolate this service's own overhead (search, fusion,
 * assembly, verification) from model latency. Writes target/benchmark.md.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("postgres")
class LatencyBenchmark {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Autowired
    KnowledgeStore jdbcStore;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    record Result(String store, int docs, long chunks, double ingestSeconds, double[] retrieveMs, double[] askMs,
            double[] vectorMs, double[] keywordMs) {
    }

    @Test
    void run() throws Exception {
        int docs = Integer.getInteger("bench.docs", 2000);
        List<String> sources = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("eval/corpus"))) {
            for (Path p : files.filter(f -> f.toString().endsWith(".md") && !f.endsWith("README.md")).sorted().toList()) {
                sources.add(Files.readString(p));
            }
        }
        List<EvalCase> cases = Evaluator.loadGoldenSet(Path.of("eval/golden-set.json"));

        List<Result> results = List.of(
                measure("postgres (pgvector HNSW + GIN)", new Fixtures(jdbcStore, Runnable::run), sources, cases, docs),
                measure("in-memory (exact scan)", new Fixtures(), sources, cases, docs));

        if (Boolean.getBoolean("bench.explain")) {
            explainIterativeScan();
        }
        StringBuilder md = new StringBuilder("| Store | Docs | Chunks | Ingest | Retrieve p50 / p95 / p99 | Full answer p50 / p95 / p99 |\n")
                .append("|---|---|---|---|---|---|\n");
        for (Result r : results) {
            md.append(String.format(Locale.ROOT, "| %s | %,d | %,d | %.0f chunks/s | %.1f / %.1f / %.1f ms | %.1f / %.1f / %.1f ms |%n",
                    r.store(), r.docs(), r.chunks(), r.chunks() / r.ingestSeconds(),
                    pct(r.retrieveMs(), 50), pct(r.retrieveMs(), 95), pct(r.retrieveMs(), 99),
                    pct(r.askMs(), 50), pct(r.askMs(), 95), pct(r.askMs(), 99)));
        }
        md.append("\n| Store | Vector search p50 / p95 | Keyword search p50 / p95 |\n|---|---|---|\n");
        for (Result r : results) {
            md.append(String.format(Locale.ROOT, "| %s | %.1f / %.1f ms | %.1f / %.1f ms |%n", r.store(),
                    pct(r.vectorMs(), 50), pct(r.vectorMs(), 95), pct(r.keywordMs(), 50), pct(r.keywordMs(), 95)));
        }
        md.append(String.format(Locale.ROOT, "%nJVM: %s, %d CPUs. Queries: %d golden questions x 5 rounds after warm-up.%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), cases.size()));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/benchmark.md"), md.toString());
        System.out.println(md);
    }

    /**
     * Shows why JdbcKnowledgeStore sets hnsw.iterative_scan: without it the index returns its
     * ef_search nearest rows, the space filter removes them, and the query returns fewer than k
     * (here: zero) rows.
     */
    private void explainIterativeScan() {
        String v = JdbcKnowledgeStore.toVectorLiteral(new Fixtures().embedder.embed("refund window annual plan"));
        String sql = "EXPLAIN (ANALYZE) SELECT id FROM chunks c WHERE c.active AND c.space_id = ANY(string_to_array('handbook,product', ',')) "
                + "ORDER BY c.embedding <=> '" + v + "'::vector LIMIT 30";
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            try (var st = con.createStatement()) {
                for (String mode : List.of("off", "relaxed_order")) {
                    st.execute("SET hnsw.iterative_scan = '" + mode + "'");
                    try (var rs = st.executeQuery(sql)) {
                        while (rs.next()) {
                            String line = rs.getString(1);
                            if (line.startsWith("Limit") || line.contains("Removed") || line.contains("Execution")) {
                                System.out.println("EXPLAIN[iterative_scan=" + mode + "] " + line.strip());
                            }
                        }
                    }
                }
            }
            return null;
        });
    }

    private Result measure(String name, Fixtures f, List<String> sources, List<EvalCase> cases, int docs) {
        String[] spaces = {"handbook", "product", "legal", "sales"};
        long start = System.nanoTime();
        for (int i = 0; i < docs; i++) {
            String text = sources.get(i % sources.size()).replace("Northwind", "Northwind" + (i % 97))
                    + "\n\nRevision note " + i + ": reviewed by team " + (i * 31 % 211) + ".";
            f.ingestion.submit(SpaceId.of(spaces[i % spaces.length]), "doc-" + i + ".md", "text/markdown",
                    text.getBytes(StandardCharsets.UTF_8));
        }
        double ingestSeconds = (System.nanoTime() - start) / 1e9;
        long chunks = Arrays.stream(spaces).flatMap(s -> f.store.listDocuments(SpaceId.of(s)).stream())
                .mapToLong(d -> d.chunkCount()).sum();

        RagService rag = f.rag();
        Set<SpaceId> all = Fixtures.spaces("handbook", "product");
        HybridRetriever parallel = new HybridRetriever(f.store, new NoopReranker(), Fixtures.RETRIEVAL,
                Fixtures.CONFIDENCE, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        // Warm-up: 3 rounds of every question through every measured path, so JIT compilation
        // and connection-pool effects are not attributed to whichever store runs first.
        for (int w = 0; w < 3; w++) {
            for (EvalCase c : cases) {
                String q = c.question() + " (warm-up " + w + ")";
                float[] e = f.embedder.embed(q);
                f.store.vectorSearch(all, e, 30);
                f.store.keywordSearch(all, q, e, 30);
                parallel.retrieve(q, e, all);
                rag.ask(new RagService.Question("bench", all, q, null));
            }
        }
        List<Double> vector = new ArrayList<>();
        List<Double> keyword = new ArrayList<>();
        for (EvalCase c : cases) {
            float[] e = f.embedder.embed(c.question());
            long a = System.nanoTime();
            f.store.vectorSearch(all, e, 30);
            vector.add((System.nanoTime() - a) / 1e6);
            long b = System.nanoTime();
            f.store.keywordSearch(all, c.question(), e, 30);
            keyword.add((System.nanoTime() - b) / 1e6);
        }
        List<Double> retrieve = new ArrayList<>();
        List<Double> ask = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            for (EvalCase c : cases) {
                String q = c.question() + " (round " + round + ")"; // defeat the answer cache
                long t0 = System.nanoTime();
                parallel.retrieve(q, f.embedder.embed(q), all);
                retrieve.add((System.nanoTime() - t0) / 1e6);
                long t1 = System.nanoTime();
                rag.ask(new RagService.Question("bench", all, q, null));
                ask.add((System.nanoTime() - t1) / 1e6);
            }
        }
        return new Result(name, docs, chunks, ingestSeconds,
                retrieve.stream().mapToDouble(Double::doubleValue).sorted().toArray(),
                ask.stream().mapToDouble(Double::doubleValue).sorted().toArray(),
                vector.stream().mapToDouble(Double::doubleValue).sorted().toArray(),
                keyword.stream().mapToDouble(Double::doubleValue).sorted().toArray());
    }

    private static double pct(double[] sorted, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
    }
}

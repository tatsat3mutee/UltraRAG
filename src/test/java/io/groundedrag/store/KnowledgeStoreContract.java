package io.groundedrag.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.groundedrag.domain.Chunk;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.ParentChunk;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.HashingEmbeddingModel;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link KnowledgeStore} adapter must have. Run against the in-memory adapter on
 * every build and against real Postgres + pgvector whenever Docker is available.
 */
public abstract class KnowledgeStoreContract {

    private final HashingEmbeddingModel model = new HashingEmbeddingModel(384);

    protected abstract KnowledgeStore store();

    /** Each test uses fresh space names so adapters with shared state (a database) don't interfere. */
    private final String run = UUID.randomUUID().toString().substring(0, 8);

    private SpaceId space(String name) {
        return SpaceId.of(name + "-" + run);
    }

    private DocumentRecord index(SpaceId space, String source, String hash, String... texts) {
        DocumentRecord doc = store().createDocument(space, source, hash, "text/plain");
        ParentChunk parent = new ParentChunk(UUID.randomUUID(), doc.id(), space, 0, String.join(" ", texts));
        List<Chunk> chunks = new java.util.ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            chunks.add(new Chunk(UUID.randomUUID(), doc.id(), parent.id(), space, i, texts[i], source, 1, "S",
                    model.embed(texts[i])));
        }
        store().saveChunks(doc.id(), List.of(parent), chunks);
        return doc;
    }

    private List<String> keyword(Set<SpaceId> spaces, String q) {
        return store().keywordSearch(spaces, q, model.embed(q), 10).stream().map(RetrievedChunk::text).toList();
    }

    @Test
    void chunksAreInvisibleUntilTheVersionIsActivated() {
        SpaceId s = space("vis");
        DocumentRecord doc = index(s, "a.md", "h1".repeat(32), "refund window is thirty days");
        assertThat(keyword(Set.of(s), "refund window")).isEmpty();
        store().activate(doc.id(), "m", 1);
        assertThat(keyword(Set.of(s), "refund window")).containsExactly("refund window is thirty days");
    }

    @Test
    void activatingANewVersionSwapsAtomicallyAndSupersedesTheOld() {
        SpaceId s = space("swap");
        DocumentRecord v1 = index(s, "a.md", "a".repeat(64), "old refund window is fourteen days");
        store().activate(v1.id(), "m", 1);
        DocumentRecord v2 = index(s, "a.md", "b".repeat(64), "new refund window is thirty days");
        assertThat(v2.version()).isEqualTo(2);

        // While v2 is indexing, v1 keeps serving.
        assertThat(keyword(Set.of(s), "refund window")).containsExactly("old refund window is fourteen days");

        store().activate(v2.id(), "m", 1);
        assertThat(keyword(Set.of(s), "refund window")).containsExactly("new refund window is thirty days");
        assertThat(store().findDocument(s, v1.id()).orElseThrow().status()).isEqualTo(DocumentStatus.SUPERSEDED);
        assertThat(store().findActiveByHash(s, "b".repeat(64))).isPresent();
    }

    @Test
    void discardingAFailedVersionLeavesThePreviousOneServing() {
        SpaceId s = space("fail");
        DocumentRecord v1 = index(s, "a.md", "c".repeat(64), "stable content about invoices");
        store().activate(v1.id(), "m", 1);
        DocumentRecord v2 = index(s, "a.md", "d".repeat(64), "half written content about invoices");
        store().discardChunks(v2.id());
        store().updateStatus(v2.id(), DocumentStatus.FAILED, "boom");
        assertThat(keyword(Set.of(s), "invoices")).containsExactly("stable content about invoices");
    }

    @Test
    void multipleSpacesMeanAnyOfNotAllOf() {
        SpaceId a = space("a");
        SpaceId b = space("b");
        store().activate(index(a, "a.md", "e".repeat(64), "invoice policy for space a").id(), "m", 1);
        store().activate(index(b, "b.md", "f".repeat(64), "invoice policy for space b").id(), "m", 1);

        assertThat(keyword(Set.of(a, b), "invoice policy")).hasSize(2);
        assertThat(store().vectorSearch(Set.of(a, b), model.embed("invoice policy"), 10)).hasSize(2);
        assertThat(keyword(Set.of(a), "invoice policy")).containsExactly("invoice policy for space a");
    }

    @Test
    void vectorSearchRanksBySimilarityAndReportsCosine() {
        SpaceId s = space("vec");
        store().activate(index(s, "a.md", "g".repeat(64),
                "parental leave is twenty six weeks", "hotel limit in tokyo is 350 usd").id(), "m", 2);
        List<RetrievedChunk> hits = store().vectorSearch(Set.of(s), model.embed("tokyo hotel limit"), 2);
        assertThat(hits.get(0).text()).contains("tokyo");
        assertThat(hits.get(0).vectorScore()).isGreaterThan(hits.get(1).vectorScore()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void keywordSearchOrsTermsAndIgnoresQuerySyntax() {
        SpaceId s = space("kw");
        store().activate(index(s, "a.md", "h".repeat(64), "error NW-4291 means rate limit exceeded").id(), "m", 1);
        assertThat(keyword(Set.of(s), "what does NW-4291 mean?")).hasSize(1);
        assertThat(keyword(Set.of(s), "rate & | ! ( limit")).hasSize(1);
        assertThat(keyword(Set.of(s), "!!! ???")).isEmpty();
    }

    @Test
    void deleteRemovesAllVersionsAndBumpsCorpusVersion() {
        SpaceId s = space("del");
        DocumentRecord v1 = index(s, "a.md", "i".repeat(64), "temporary content");
        store().activate(v1.id(), "m", 1);
        long before = store().corpusVersions(Set.of(s)).get(s);
        assertThat(store().deleteDocument(s, v1.id())).isTrue();
        assertThat(store().corpusVersions(Set.of(s)).get(s)).isGreaterThan(before);
        assertThat(store().listDocuments(s)).isEmpty();
        assertThat(keyword(Set.of(s), "temporary")).isEmpty();
        assertThat(store().deleteDocument(space("other"), v1.id())).isFalse();
    }

    @Test
    void parentTextsAreReturnedForExpansion() {
        SpaceId s = space("par");
        DocumentRecord doc = index(s, "a.md", "j".repeat(64), "child one", "child two");
        store().activate(doc.id(), "m", 2);
        RetrievedChunk hit = store().keywordSearch(Set.of(s), "child", model.embed("child"), 1).get(0);
        assertThat(store().parentTexts(List.of(hit.parentId()))).containsValue("child one child two");
    }

    @Test
    void interruptedIngestionsAreFailedOnStartup() {
        SpaceId s = space("crash");
        DocumentRecord doc = index(s, "a.md", "k".repeat(64), "never finished");
        store().updateStatus(doc.id(), DocumentStatus.INDEXING, null);
        assertThat(store().failInterruptedIngestions()).isGreaterThanOrEqualTo(1);
        assertThat(store().findDocument(s, doc.id()).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
    }

    @Test
    void detectsChunksEmbeddedWithAnotherModel() {
        SpaceId s = space("model");
        store().activate(index(s, "a.md", "l".repeat(64), "x").id(), "old-model-" + run, 1);
        assertThat(store().countChunksWithOtherModel("new-model")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void spaceIsolationOnLookups() {
        SpaceId s = space("iso");
        DocumentRecord doc = index(s, "a.md", "m".repeat(64), "secret");
        assertThat(store().findDocument(space("intruder"), doc.id())).isEmpty();
    }
}

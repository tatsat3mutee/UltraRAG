package io.groundedrag.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

import io.groundedrag.Fixtures;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.DocumentStatus;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.store.InMemoryKnowledgeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

class IngestionServiceTest {

    @Test
    void indexesAndActivatesWithModelAndChunkCount() {
        Fixtures f = new Fixtures();
        DocumentRecord doc = f.ingest("hr", "leave.md", "# Leave\nEmployees get 24 days of leave.");
        assertThat(doc.status()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(doc.embeddingModel()).isEqualTo("hashing-v1");
        assertThat(doc.chunkCount()).isEqualTo(1);
    }

    @Test
    void identicalBytesAreDetectedAsDuplicate() {
        Fixtures f = new Fixtures();
        f.ingest("hr", "leave.md", "same content");
        var again = f.ingestion.submit(SpaceId.of("hr"), "copy.md", "text/plain",
                "same content".getBytes(StandardCharsets.UTF_8));
        assertThat(again.duplicate()).isTrue();
        assertThat(f.store.listDocuments(SpaceId.of("hr"))).hasSize(1);
    }

    @Test
    void piiNeverReachesTheIndex() {
        Fixtures f = new Fixtures();
        f.ingest("hr", "contacts.md", "Escalate to jane.doe@northwind.example for payroll issues.");
        String q = "payroll escalation";
        var hits = f.store.keywordSearch(Fixtures.spaces("hr"), q, f.embedder.embed(q), 5);
        assertThat(hits.get(0).text()).contains("[REDACTED_EMAIL]").doesNotContain("jane.doe");
    }

    @Test
    void failedReindexKeepsThePreviousVersionServing() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        Fixtures ok = new Fixtures(store, Runnable::run);
        ok.ingest("hr", "leave.md", "Employees get 24 days of annual leave.");

        EmbeddingModel broken = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw new IllegalStateException("embedding provider timed out");
            }

            @Override
            public float[] embed(Document document) {
                throw new IllegalStateException("embedding provider timed out");
            }
        };
        IngestionService failing = new IngestionService(store, new DocumentParser(Set.of("md")),
                new PiiRedactor(true), new ParentChildChunker(Fixtures.TOKENS, 700, 180, 40),
                new Embedder(broken, "broken", 384, 8), Runnable::run, new SimpleMeterRegistry());
        var v2 = failing.submit(SpaceId.of("hr"), "leave.md", "text/markdown",
                "Employees get 30 days of annual leave.".getBytes(StandardCharsets.UTF_8));

        List<DocumentRecord> versions = store.listDocuments(SpaceId.of("hr"));
        assertThat(versions).extracting(DocumentRecord::status).containsExactly(DocumentStatus.ACTIVE, DocumentStatus.FAILED);
        assertThat(store.findDocument(SpaceId.of("hr"), v2.document().id()).orElseThrow().error()).contains("timed out");
        String q = "annual leave";
        assertThat(store.keywordSearch(Fixtures.spaces("hr"), q, ok.embedder.embed(q), 5))
                .singleElement().satisfies(c -> assertThat(c.text()).contains("24 days"));
    }

    @Test
    void fullQueueIsReportedAsBusyAndTheVersionMarkedFailed() {
        InMemoryKnowledgeStore store = new InMemoryKnowledgeStore();
        Fixtures f = new Fixtures(store, task -> {
            throw new RejectedExecutionException();
        });
        assertThatThrownBy(() -> f.ingestion.submit(SpaceId.of("hr"), "a.md", "text/markdown", "x".getBytes()))
                .isInstanceOf(IngestionBusyException.class);
        assertThat(store.listDocuments(SpaceId.of("hr"))).extracting(DocumentRecord::status)
                .containsExactly(DocumentStatus.FAILED);
    }

    @Test
    void rejectsUnsupportedTypesBeforeCreatingAVersion() {
        Fixtures f = new Fixtures();
        assertThatThrownBy(() -> f.ingestion.submit(SpaceId.of("hr"), "a.exe", null, new byte[] {1}))
                .isInstanceOf(UnsupportedDocumentException.class);
        assertThat(f.store.listDocuments(SpaceId.of("hr"))).isEmpty();
    }
}

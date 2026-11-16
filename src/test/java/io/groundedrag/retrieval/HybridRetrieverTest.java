package io.groundedrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import io.groundedrag.Fixtures;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HybridRetrieverTest {

    private Fixtures f;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        f.ingest("product", "api.md", """
                # Errors
                NW-4291 means the rate limit was exceeded. Wait for Retry-After seconds.
                NW-5003 means a dependent service is unavailable. Retry with backoff.
                """);
        f.ingest("product", "billing.md", """
                # Refunds
                New customers on an annual plan can get a full refund within 30 days of the first payment.
                """);
    }

    private static RetrievedChunk hit(String id) {
        return new RetrievedChunk(UUID.nameUUIDFromBytes(id.getBytes()), UUID.randomUUID(), null, SpaceId.of("s"),
                id, "f", null, null, 0.5, 0, 0, 0);
    }

    @Test
    void rrfRewardsDocumentsRankedHighInBothLists() {
        var fused = HybridRetriever.reciprocalRankFusion(
                List.of(List.of(hit("a"), hit("b"), hit("c")), List.of(hit("c"), hit("b"), hit("d"))), 60);
        // Items in both lists beat items in one; ranks 1+3 narrowly beat 2+2.
        assertThat(fused).extracting(RetrievedChunk::text).containsExactly("c", "b", "a", "d");
        assertThat(fused.get(0).fusedScore()).isEqualTo(1.0 / 61 + 1.0 / 63);
        assertThat(fused.get(1).fusedScore()).isEqualTo(2.0 / 62);
    }

    @Test
    void nearDuplicatesAreCollapsed() {
        var a = hit("refund within thirty days of payment");
        var b = hit("refund within thirty days of payment!");
        var c = hit("rate limit");
        assertThat(HybridRetriever.dropNearDuplicates(List.of(a, b, c), 0.9)).hasSize(2);
    }

    @Test
    void exactIdentifierIsFoundAndRankedFirst() {
        var r = f.retriever.retrieve("what does NW-4291 mean", f.embedder.embed("what does NW-4291 mean"),
                Fixtures.spaces("product"));
        assertThat(r.chunks().get(0).text()).contains("NW-4291");
        assertThat(r.confidence()).isNotEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void unrelatedQuestionGetsNoConfidence() {
        String q = "pizza toppings at the summer party";
        var r = f.retriever.retrieve(q, f.embedder.embed(q), Fixtures.spaces("product"));
        assertThat(r.confidence()).isEqualTo(ConfidenceLevel.NONE);
    }

    @Test
    void searchesOnlyRequestedSpaces() {
        f.ingest("handbook", "leave.md", "Employees get 24 days of annual leave per year.");
        String q = "annual leave days";
        var productOnly = f.retriever.retrieve(q, f.embedder.embed(q), Fixtures.spaces("product"));
        assertThat(productOnly.chunks()).noneMatch(c -> c.sourceName().equals("leave.md"));
    }

    @Test
    void confidenceIsEvidenceCoverageNeverTheRrfScore() {
        var r = f.retriever.retrieve("refund within 30 days", f.embedder.embed("refund within 30 days"),
                Fixtures.spaces("product"));
        assertThat(r.confidenceScore()).isEqualTo(1.0);
        assertThat(r.confidence()).isEqualTo(ConfidenceLevel.HIGH);
        assertThat(r.chunks().get(0).fusedScore()).isLessThan(0.05);
    }

    @Test
    void coverageWeightsIdentifiersAndIgnoresQuestionFillers() {
        // "how many" is filler; the missing identifier costs twice as much as a missing word.
        assertThat(NoopReranker.evidenceCoverage("how many retries for NW-4291", List.of("retries are limited")))
                .isEqualTo(1.0 / 3);
        assertThat(NoopReranker.evidenceCoverage("how many retries for NW-4291", List.of("NW-4291 retries")))
                .isEqualTo(1.0);
        assertThat(NoopReranker.evidenceCoverage("anything", List.of())).isZero();
    }
}

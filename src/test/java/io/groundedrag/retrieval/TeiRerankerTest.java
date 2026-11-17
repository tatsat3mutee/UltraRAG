package io.groundedrag.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.groundedrag.domain.RetrievedChunk;
import io.groundedrag.domain.SpaceId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Talks to a stub of the text-embeddings-inference /rerank API over real HTTP. */
class TeiRerankerTest {

    private HttpServer server;
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private String start(int status, String response, long delayMillis) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static RetrievedChunk chunk(String text, double cosine) {
        return new RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(), null, SpaceId.of("s"), text, "f.md",
                null, null, cosine, 0, 0.02, 0);
    }

    private final List<RetrievedChunk> candidates = List.of(
            chunk("monthly plans are not refundable", 0.4),
            chunk("annual plans: full refund within 30 days", 0.3),
            chunk("rate limits per token", 0.2));

    @Test
    void reordersByCrossEncoderScoreAndUsesItForConfidence() throws Exception {
        String url = start(200, "[{\"index\":1,\"score\":0.93},{\"index\":0,\"score\":0.41},{\"index\":2,\"score\":0.02}]", 0);
        TeiReranker reranker = new TeiReranker(url, Duration.ofSeconds(2));

        List<RetrievedChunk> out = reranker.rerank("refund window for annual plans", candidates, 2);

        assertThat(out).extracting(RetrievedChunk::text)
                .containsExactly("annual plans: full refund within 30 days", "monthly plans are not refundable");
        assertThat(reranker.confidence("q", out)).isEqualTo(0.93);
        assertThat(lastBody.get()).contains("\"query\":\"refund window for annual plans\"").contains("\"raw_scores\":false");
    }

    @Test
    void fallsBackToFusedOrderWhenTheServiceFails() throws Exception {
        String url = start(503, "{\"error\":\"overloaded\"}", 0);
        List<RetrievedChunk> out = new TeiReranker(url, Duration.ofSeconds(2)).rerank("q", candidates, 3);
        assertThat(out).extracting(RetrievedChunk::text).containsExactlyElementsOf(candidates.stream().map(RetrievedChunk::text).toList());
    }

    @Test
    void fallsBackWhenTheServiceIsTooSlow() throws Exception {
        String url = start(200, "[]", 1500);
        long start = System.nanoTime();
        List<RetrievedChunk> out = new TeiReranker(url, Duration.ofMillis(200)).rerank("q", candidates, 3);
        assertThat(out).hasSize(3);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1200));
    }
}

package io.groundedrag.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Streams over a real socket. MockMvc does not run the ASYNC re-dispatch through the security
 * filter chain, so it could not catch the bug where completed SSE streams were cut off with
 * ERR_INCOMPLETE_CHUNKED_ENCODING. This test does.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "rag.rate-limit.burst=1000")
class StreamingOverHttpTest {

    @LocalServerPort
    int port;

    @Test
    void streamCompletesCleanlyWithADoneEvent() throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat/stream"))
                        .header("X-API-Key", ApiIntegrationTest.USER)
                        .header("Content-Type", "application/json")
                        .header("Accept", "text/event-stream")
                        .timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"anything at all?\",\"spaces\":[\"handbook\"]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString()); // throws if the chunked body is truncated
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("event:meta").contains("event:done");
    }
}

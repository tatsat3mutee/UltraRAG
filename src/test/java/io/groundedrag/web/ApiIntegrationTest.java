package io.groundedrag.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Boots the real application (memory profile) and drives it through HTTP like a client would. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    static final String USER = "dev-key-change-me-0001";     // spaces: handbook, product
    static final String ADMIN = "dev-admin-key-change-me";   // spaces: *

    @Autowired
    MockMvc mvc;

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
    }

    private String uploadAndWait(String space, MockMultipartFile f) throws Exception {
        MvcResult r = mvc.perform(multipart("/api/v1/spaces/" + space + "/documents").file(f).header("X-API-Key", USER))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isIn(200, 202);
        String id = JsonPath.read(r.getResponse().getContentAsString(), "$.id");
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            String body = mvc.perform(get("/api/v1/spaces/" + space + "/documents/" + id).header("X-API-Key", USER))
                    .andReturn().getResponse().getContentAsString();
            String st = JsonPath.read(body, "$.status");
            if (!st.equals("QUEUED") && !st.equals("INDEXING")) {
                assertThat(st).isEqualTo("ACTIVE");
                return id;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Document did not finish indexing");
    }

    @Test
    void requestsWithoutAValidKeyAreRejected() throws Exception {
        mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("X-API-Key", "wrong-key-wrong-key")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    }

    @Test
    void spaceAccessComesFromTheKeyNotFromTheRequest() throws Exception {
        mvc.perform(multipart("/api/v1/spaces/finance/documents").file(file("a.md", "x")).header("X-API-Key", USER))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/chat").header("X-API-Key", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"budget?\",\"spaces\":[\"finance\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("No access to space 'finance'"));
        // Casing differences do not bypass or break the check.
        mvc.perform(get("/api/v1/spaces/HandBook/documents").header("X-API-Key", USER)).andExpect(status().isOk());
        // A wildcard key must name the spaces it wants to search.
        mvc.perform(post("/api/v1/chat").header("X-API-Key", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"anything\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void uploadThenAskReturnsACitedVerifiedAnswerWithATrace() throws Exception {
        uploadAndWait("product", file("webhooks.md", """
                # Webhooks
                Failed webhook deliveries are retried up to 8 times over 24 hours with exponential backoff.
                """));

        String body = mvc.perform(post("/api/v1/chat").header("X-API-Key", USER).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"How many times are failed webhook deliveries retried?\","
                                + "\"spaces\":[\"product\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[0].sourceName").value("webhooks.md"))
                .andExpect(jsonPath("$.sources[0].cited").value(true))
                .andExpect(jsonPath("$.verification.grounded").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.answer")).contains("8 times").contains("[1]");

        String answerId = JsonPath.read(body, "$.answerId");
        mvc.perform(get("/api/v1/answers/" + answerId).header("X-API-Key", USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceNames[0]").value("webhooks.md"));
        mvc.perform(post("/api/v1/answers/" + answerId + "/feedback").header("X-API-Key", USER)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":1}")).andExpect(status().isNoContent());
        // Another principal can neither read nor rate it.
        mvc.perform(get("/api/v1/answers/" + answerId).header("X-API-Key", ADMIN)).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/answers/" + answerId + "/feedback").header("X-API-Key", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":-1}")).andExpect(status().isNotFound());
    }

    @Test
    void reuploadingIdenticalBytesIsADuplicate() throws Exception {
        MockMultipartFile f = file("dup.md", "Duplicate detection works on content hashes.");
        uploadAndWait("handbook", f);
        mvc.perform(multipart("/api/v1/spaces/handbook/documents").file(f).header("X-API-Key", USER))
                .andExpect(status().isOk());
    }

    @Test
    void streamingEndpointEmitsMetaTokensAndDone() throws Exception {
        uploadAndWait("handbook", file("hours.md", "Core collaboration hours are 11:00 to 15:00 in the team time zone."));
        MvcResult r = mvc.perform(post("/api/v1/chat/stream").header("X-API-Key", USER)
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"question\":\"What are the core collaboration hours?\",\"spaces\":[\"handbook\"]}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        r.getAsyncResult(10_000);
        String sse = r.getResponse().getContentAsString();
        assertThat(sse.indexOf("event:meta")).isGreaterThanOrEqualTo(0)
                .isLessThan(sse.indexOf("event:token"));
        assertThat(sse.indexOf("event:done")).isGreaterThan(sse.indexOf("event:token"));
        assertThat(sse).contains("11:00");
    }

    @Test
    void invalidInputGetsProblemDetails() throws Exception {
        mvc.perform(multipart("/api/v1/spaces/product/documents").file(file("tool.exe", "MZ")).header("X-API-Key", USER))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/v1/chat").header("X-API-Key", USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/chat").header("X-API-Key", USER).contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"x\",\"spaces\":[\"bad,space\"]}")).andExpect(status().isBadRequest());
    }
}

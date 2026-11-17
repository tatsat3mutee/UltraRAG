package io.groundedrag.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** OIDC/JWT callers resolve to the same Caller as API keys; rate limits apply per caller. */
@SpringBootTest(properties = {
        "rag.security.jwt.hmac-secret=" + JwtAndRateLimitTest.SECRET,
        "rag.security.jwt.audience=grounded-rag",
        "rag.rate-limit.requests-per-minute=60",
        "rag.rate-limit.burst=3"})
@AutoConfigureMockMvc
class JwtAndRateLimitTest {

    static final String SECRET = "test-secret-test-secret-test-secret-0123";

    @Autowired
    MockMvc mvc;

    static String token(String subject, Object spaces, String audience, Instant expires, String secret) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer("https://idp.example").subject(subject).audience(audience)
                .issueTime(new Date()).expirationTime(Date.from(expires));
        if (spaces != null) {
            claims.claim("spaces", spaces);
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return "Bearer " + jwt.serialize();
    }

    static String valid(String subject, Object spaces) throws Exception {
        return token(subject, spaces, "grounded-rag", Instant.now().plusSeconds(300), SECRET);
    }

    @Test
    void validTokenBecomesACallerWithClaimedSpaces() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization", valid("alice", List.of("Product", "handbook"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("https://idp.example|alice"))
                .andExpect(jsonPath("$.authMethod").value("jwt"))
                .andExpect(jsonPath("$.spaces.length()").value(2));
        // Space-separated string claims (common for IdP group mappers) work too.
        mvc.perform(get("/api/v1/spaces/handbook/documents").header("Authorization", valid("bob", "handbook finance")))
                .andExpect(status().isOk());
    }

    @Test
    void tokenSpacesAreEnforcedAndMissingClaimFailsClosed() throws Exception {
        mvc.perform(get("/api/v1/spaces/finance/documents").header("Authorization", valid("carol", List.of("product"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/spaces/product/documents").header("Authorization", valid("dave", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void expiredForgedOrWrongAudienceTokensAreRejected() throws Exception {
        mvc.perform(get("/api/v1/me").header("Authorization",
                token("eve", List.of("product"), "grounded-rag", Instant.now().minusSeconds(3600), SECRET)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization",
                token("eve", List.of("product"), "grounded-rag", Instant.now().plusSeconds(300),
                        "another-secret-another-secret-another-0")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/me").header("Authorization",
                token("eve", List.of("product"), "some-other-api", Instant.now().plusSeconds(300), SECRET)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatIsRateLimitedPerCallerWithRetryAfter() throws Exception {
        String frank = valid("frank", List.of("product"));
        String body = "{\"question\":\"anything?\",\"spaces\":[\"product\"]}";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/chat").header("Authorization", frank).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/v1/chat").header("Authorization", frank).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.status").value(429));
        // Another caller has its own bucket, and cheap reads are not limited.
        mvc.perform(post("/api/v1/chat").header("Authorization", valid("grace", List.of("product")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/me").header("Authorization", frank)).andExpect(status().isOk());
    }
}

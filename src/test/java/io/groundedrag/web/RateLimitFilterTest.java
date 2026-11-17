package io.groundedrag.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import io.groundedrag.security.Caller;
import io.groundedrag.security.RateLimitFilterProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class RateLimitFilterTest {

    private final AtomicLong now = new AtomicLong();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int call(jakarta.servlet.Filter filter, String method, String path) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new Caller("k", Set.of("s"), "api-key"), null, List.of()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(method, path), res, new MockFilterChain());
        return res.getStatus();
    }

    @Test
    void refillsAtTheConfiguredRate() throws Exception {
        var filter = RateLimitFilterProbe.create(60, 2, now::get); // one token per second
        assertThat(call(filter, "POST", "/api/v1/chat")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/chat")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/chat")).isEqualTo(429);
        now.addAndGet(1_000_000_000L);
        assertThat(call(filter, "POST", "/api/v1/chat")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/chat")).isEqualTo(429);
    }

    @Test
    void onlyExpensiveEndpointsAreLimited() throws Exception {
        var filter = RateLimitFilterProbe.create(60, 1, now::get);
        assertThat(call(filter, "POST", "/api/v1/spaces/a/documents")).isEqualTo(200);
        assertThat(call(filter, "POST", "/api/v1/spaces/a/documents")).isEqualTo(429);
        assertThat(call(filter, "GET", "/api/v1/spaces/a/documents")).isEqualTo(200);
        assertThat(call(filter, "GET", "/api/v1/me")).isEqualTo(200);
    }
}

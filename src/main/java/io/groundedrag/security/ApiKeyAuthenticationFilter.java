package io.groundedrag.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.groundedrag.config.RagProperties;
import io.groundedrag.domain.SpaceId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code X-API-Key}. Keys are held only as SHA-256 digests and compared with
 * {@link MessageDigest#isEqual} (constant time). The key, not any request header or body field,
 * decides which spaces the caller can touch.
 *
 * <p>For SSO, replace this filter with Spring Security's OAuth2 resource server and map a token
 * claim (e.g. groups) to the same {@link ApiKeyPrincipal}; nothing downstream changes.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private final Map<String, ApiKeyPrincipal> principalsByDigest = new HashMap<>();

    public ApiKeyAuthenticationFilter(List<RagProperties.ApiKey> keys) {
        for (RagProperties.ApiKey k : keys) {
            if (k.key() == null || k.key().length() < 16) {
                throw new IllegalStateException("API key '" + k.name() + "' must be at least 16 characters");
            }
            Set<String> spaces = k.spaces().stream()
                    .map(s -> "*".equals(s) ? s : SpaceId.of(s).value())
                    .collect(Collectors.toUnmodifiableSet());
            principalsByDigest.put(digest(k.key()), new ApiKeyPrincipal(k.name(), spaces));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && !presented.isBlank()) {
            ApiKeyPrincipal principal = lookup(presented.strip());
            if (principal != null) {
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_API")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private ApiKeyPrincipal lookup(String presented) {
        String d = digest(presented);
        for (Map.Entry<String, ApiKeyPrincipal> e : principalsByDigest.entrySet()) {
            if (MessageDigest.isEqual(e.getKey().getBytes(StandardCharsets.US_ASCII),
                    d.getBytes(StandardCharsets.US_ASCII))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String digest(String key) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

package io.groundedrag.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>Runs alongside the OAuth2 resource server ({@link JwtCallerConverter}); both produce the same
 * {@link Caller}, so nothing downstream knows or cares how the caller authenticated.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    /** A configured key: who it identifies and which spaces it grants. */
    public record Credential(String name, String key, java.util.Collection<String> spaces) {
    }

    private final Map<String, Caller> principalsByDigest = new HashMap<>();

    public ApiKeyAuthenticationFilter(List<Credential> keys) {
        for (Credential k : keys) {
            if (k.key() == null || k.key().length() < 16) {
                throw new IllegalStateException("API key '" + k.name() + "' must be at least 16 characters");
            }
            principalsByDigest.put(digest(k.key()), new Caller(k.name(), Caller.normaliseSpaces(k.spaces()), "api-key"));
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null && !presented.isBlank()) {
            Caller principal = lookup(presented.strip());
            if (principal != null) {
                var auth = new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_API")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }

    private Caller lookup(String presented) {
        String d = digest(presented);
        for (Map.Entry<String, Caller> e : principalsByDigest.entrySet()) {
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

package io.groundedrag.security;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

import io.groundedrag.domain.SpaceId;

/**
 * Who is calling and which spaces they may read and write, however they authenticated
 * (API key or OIDC/JWT). "*" means any space, which must then be named explicitly.
 *
 * @param name   stable identity: the key name, or {@code iss|sub} for a token
 * @param spaces normalised space ids, or "*"
 * @param method how the caller authenticated, for audit logs and traces
 */
public record Caller(String name, Set<String> spaces, String method) {

    /** Normalises configured or claimed space names; "*" passes through. Invalid names fail fast. */
    public static Set<String> normaliseSpaces(Collection<String> raw) {
        return raw.stream()
                .map(s -> "*".equals(s) ? s : SpaceId.of(s).value())
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean wildcard() {
        return spaces.contains("*");
    }
}

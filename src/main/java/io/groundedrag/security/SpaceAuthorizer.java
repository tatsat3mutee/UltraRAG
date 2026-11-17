package io.groundedrag.security;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

import io.groundedrag.domain.SpaceId;
import org.springframework.security.access.AccessDeniedException;

/**
 * Fails closed: every requested space must be granted to the caller's key, otherwise 403.
 * No requested spaces means "all spaces this key can read".
 */
public final class SpaceAuthorizer {

    private SpaceAuthorizer() {
    }

    public static Set<SpaceId> resolve(Caller principal, Collection<String> requested) {
        Set<SpaceId> out = new TreeSet<>();
        if (requested == null || requested.isEmpty()) {
            if (principal.wildcard()) {
                throw new IllegalArgumentException("This key can access any space; name the spaces to search");
            }
            principal.spaces().forEach(s -> out.add(new SpaceId(s)));
            return out;
        }
        for (String raw : requested) {
            out.add(require(principal, raw));
        }
        return out;
    }

    public static SpaceId require(Caller principal, String raw) {
        SpaceId space = SpaceId.of(raw);
        if (!principal.wildcard() && !principal.spaces().contains(space.value())) {
            throw new AccessDeniedException("No access to space '" + space + "'");
        }
        return space;
    }
}

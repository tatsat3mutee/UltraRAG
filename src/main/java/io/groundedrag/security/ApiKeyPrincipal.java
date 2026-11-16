package io.groundedrag.security;

import java.util.Set;

/** Who is calling and which spaces they may read/write. "*" means any space, named explicitly. */
public record ApiKeyPrincipal(String name, Set<String> spaces) {

    public boolean wildcard() {
        return spaces.contains("*");
    }
}

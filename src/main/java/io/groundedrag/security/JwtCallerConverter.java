package io.groundedrag.security;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps a validated JWT to a {@link Caller}. Spaces come from a configurable claim, typically
 * populated by the identity provider from group membership, so access is managed where users
 * are managed. A token without the claim gets no spaces and is denied everything: fail closed.
 */
public class JwtCallerConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String spacesClaim;

    public JwtCallerConverter(String spacesClaim) {
        this.spacesClaim = spacesClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String issuer = jwt.getIssuer() == null ? "jwt" : jwt.getIssuer().toString();
        Caller caller = new Caller(issuer + "|" + jwt.getSubject(), spaces(jwt.getClaim(spacesClaim)), "jwt");
        var auth = new UsernamePasswordAuthenticationToken(caller, jwt, List.of(new SimpleGrantedAuthority("ROLE_API")));
        return auth;
    }

    static Set<String> spaces(Object claim) {
        if (claim instanceof Collection<?> c) {
            return Caller.normaliseSpaces(c.stream().map(String::valueOf).toList());
        }
        if (claim instanceof String s && !s.isBlank()) {
            return Caller.normaliseSpaces(Arrays.stream(s.split("[\\s,]+")).filter(x -> !x.isBlank()).toList());
        }
        return Set.of();
    }
}

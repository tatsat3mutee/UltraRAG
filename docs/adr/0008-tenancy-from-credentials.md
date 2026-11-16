# 0008: Tenancy derived from credentials, failing closed

## Context
Taking the tenant from a header or body field (`X-Space-ID: ...`) lets any caller read any
tenant's documents and cached answers. Optional authorisation modules that silently allow
everything when missing are a related failure.

## Decision
- **The key decides the spaces.** Each API key maps to a set of spaces. The request may narrow the
  search to some of them; asking for any other space returns 403. No list means all of the key's
  spaces.
- **Wildcard keys must be explicit.** Admin keys with `*` must name the spaces they search.
- **Space ids are normalised in one place.** `SpaceId.of` lower-cases and validates them, so
  ingest and query can never disagree on casing.
- **Key storage and comparison.** Keys are held only as SHA-256 digests and compared in constant
  time.
- **Per-principal ownership.** Traces and conversation memory belong to the principal: another key
  cannot read a trace or continue a conversation.

## Consequences
- **SSO.** Replace the filter with Spring Security's OAuth2 resource server and map a token claim
  (for example, groups) to the same principal type. Nothing downstream changes.
- **Per-document ACLs within a space are not implemented.** The store's filter clause is where an
  `acl` pre-filter would go.

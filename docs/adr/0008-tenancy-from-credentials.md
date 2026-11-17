# 0008: Tenancy derived from credentials, failing closed (API keys and OIDC/JWT)

## Context
Taking the tenant from a header or body field (`X-Space-ID: ...`) lets any caller read any
tenant's documents and cached answers. Optional authorisation modules that silently allow
everything when missing are a related failure.

## Decision
- **Two ways to authenticate, one identity.** An `X-API-Key` (services, demos) or an OIDC/JWT
  bearer token (users, through SSO: Keycloak, Okta, Entra ID) both resolve to the same `Caller`.
  For tokens, the spaces come from a configurable claim that the identity provider fills from group
  membership, so access is managed where users are managed. A token without the claim gets no
  spaces. Issuer, expiry and (optionally) audience are validated, with keys fetched from the
  issuer's JWKS.
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

- **Rate limits are per caller.** Chat and upload (the endpoints that spend LLM and embedding
  budget) use a token bucket keyed by the authenticated caller, never by IP. Behind a load balancer
  every user shares an IP, and one tenant must not exhaust another's budget. Over the limit, the
  response is `429` with `Retry-After`.

## Consequences
- **Nothing downstream knows the authentication method.** Controllers, authorisation, traces and
  rate limits see only `Caller`.
- **Rate-limit buckets are in-process,** so with N replicas the effective limit is N times the
  configured value. For a global limit, move them to Redis (for example, Bucket4j).
- **Per-document ACLs within a space are not implemented.** The store's filter clause is where an
  `acl` pre-filter would go.

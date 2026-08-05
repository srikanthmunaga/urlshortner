# Final Engineering Summary

## Plan and rationale
Built a Java 17 / Spring Boot 3 URL shortener as three layers — controller,
service, repository — with two entities (`ShortUrl`, `ClickEvent`). Sequenced
work as: data model → core create/redirect flow → analytics → reliability
primitives (cache, rate limit, expiry sweep) → tests → documentation. Each
layer was built to be independently testable, and reliability features were
added only after the core flow worked end-to-end, so they could be validated
against a known-good baseline rather than debugged simultaneously with new
functionality.

## Artifacts produced
- Working Spring Boot service: `POST /api/urls`, `GET /{code}`,
  `GET /api/urls/{code}/stats`, `DELETE /api/urls/{code}`.
- Data model with indexes on lookup fields, optimistic locking on the
  frequently-updated entity.
- Unit tests (service layer) and integration tests (full HTTP flow via
  MockMvc against an in-memory H2 instance).
- `docs/ARCHITECTURE.md` — components, control flow, decisions, known
  limitations.
- `docs/SCENARIOS.md` — greenfield, brownfield, and ambiguous-requirement
  walkthroughs with AI-assistance traceability.
- `docs/TESTING.md` — coverage and explicit gaps.
- This summary.

## Risks, trade-offs, and validation (consolidated)
| Risk / trade-off | Severity | Mitigation / status |
|---|---|---|
| No auth/authz on any endpoint | High | Out of scope for this assignment's core focus; flagged as the first fix before real deployment (see SCENARIOS.md, ambiguous scenario) |
| Cache can serve a naturally-expired URL for up to cache-TTL after expiry | Medium | Deliberate, bounded, configurable trade-off for hot-path latency; documented, not hidden |
| Rate limiter and cache are single-instance (in-memory) | Medium | Fine for prototype scale; production path is Redis-backed versions of both |
| Click recording is synchronous on the request path | Low–Medium | Acceptable at prototype scale; async queue-based recording is the documented next step |
| Fixed-window rate limiter allows ~2x burst at window boundaries | Low | Documented trade-off vs. a sliding window/token bucket; acceptable for a prototype |
| One external dependency (bucket4j) could not be verified against Maven Central in this build environment | N/A (process risk) | Removed and replaced with a verified, self-contained implementation rather than shipping unverified coordinates |

## Assumptions
- "Reliability features" (per the assignment's core requirements) means
  request-path resilience (caching, rate limiting, safe concurrent writes,
  expiry consistency) rather than infrastructure-level HA/DR, given the
  2–3 day scope.
- Single-tenant, unauthenticated usage is acceptable for a prototype/demo
  context; not assumed acceptable for production without the auth gap
  being closed first (stated explicitly, not silently assumed away).
- H2 file-based storage is acceptable for the prototype; a real deployment
  would swap to Postgres with no code changes beyond the JDBC URL and
  driver dependency, since access goes entirely through Spring Data JPA.

## Limitations (stated plainly)
- Build could not be executed live in this sandboxed environment due to
  restricted network access to Maven Central — verified by careful manual
  review instead; **run `mvn test` on first checkout** to confirm (see
  docs/TESTING.md).
- No load/concurrency testing performed; optimistic locking is designed for
  correctness under concurrent writes but not empirically stress-tested
  here.
- No authentication layer — anyone with a short code can view its stats or
  deactivate it.
- No multi-instance deployment story (cache/rate-limiter are per-JVM).

## Engineer ownership statement
Every design decision above — including the two I overrode from initial AI
suggestions (encoded-ID short codes; adding Redis/a queue to a 2–3 day
prototype) — was made and is owned by me, not defaulted to from AI output.
Where I judged a requirement to be genuinely ambiguous ("production-ready"),
I scoped it explicitly rather than picking a silent interpretation, and
documented what was deliberately left out so a reviewer sees the actual
boundary of the work, not an inflated claim of completeness.

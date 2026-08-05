# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A URL shortener prototype (Java 17 / Spring Boot 3.3.2) 
`docs/` contains the process artifacts  `ARCHITECTURE.md`
(components, control flow, key decisions), `SCENARIOS.md` (greenfield /
brownfield / ambiguous walkthroughs), `TESTING.md` (approach, coverage,
limitations), `ENGINEERING_SUMMARY.md` (plan, risks, assumptions). Read
`docs/ARCHITECTURE.md` before making non-trivial changes — it documents the
rationale and known trade-offs behind the decisions below, and explicitly
calls out what was left out of scope for a 2-3 day prototype.

## Commands

```bash
mvn spring-boot:run          # run the app (default port 8080)
mvn spring-boot:run -Dspring-boot.run.arguments=--server.port=8081   # on a different port

mvn test                                                     # full test suite
mvn test -Dtest=UrlServiceImplTest                            # one test class
mvn test -Dtest=UrlServiceImplTest#recordClick_incrementsCountAndSavesEvent  # one test method

mvn compile                  # compile only, no tests (fast sanity check)
```

Data persists to a local H2 file at `./data/urlshortener.mv.db` (gitignored),
so state survives restarts. Delete `./data/` for a clean slate.

Swagger UI is available at `/swagger-ui/index.html` and the raw OpenAPI spec
at `/v3/api-docs` once the app is running (via `springdoc-openapi-starter-webmvc-ui`).

## Architecture

### Two request paths with two different concerns

- **`POST /api/urls` (create)** is rare and write-heavy → guarded by
  `RateLimiterService`, a per-client fixed-window counter (`config/`).
- **`GET /{shortCode}` (redirect)** is frequent and read-heavy → served
  through a Caffeine cache (`@Cacheable("shortUrlCache")` on
  `UrlServiceImpl.resolveAndTrack()`).

These two mechanisms are independent — the rate limiter is a plain
`ConcurrentHashMap`, the cache is Spring's Caffeine integration — and neither
is aware of the other. Both are explicitly **in-memory, single-instance
only**; horizontally scaling this service would need a shared store (Redis)
for both, which is documented as a known limitation, not an oversight.

`UrlController` is where the two attach to HTTP: `createUrl()` checks
`rateLimiterService.tryConsume()` before calling `UrlService` at all;
`redirect()` calls `resolveAndTrack()` (cacheable) and then *unconditionally*
calls `recordClick()` — a click is always recorded whether the lookup was a
cache hit or a cache miss, because a cached method doesn't re-execute its
body on a hit.

### Dedup is hash-based, not alias-based

`UrlServiceImpl.createShortUrl()` hashes the incoming long URL (SHA-256,
`longUrlHash` column) and, if no custom alias is requested, looks up an
existing **active, non-expired** row for that hash before minting a new code.
Same long URL in → same short code out, without an extra client-side check.
This is skipped when a custom alias is supplied (line ~60), and it is a
read-then-write check, not an atomic upsert — `idx_long_url_hash` is a plain
index, not a unique constraint, so two requests for the same brand-new URL
landing at the same instant can each create their own row. Known, documented
gap, not enforced at the DB level.

### Validation matches what the redirect path actually does

`CreateUrlRequest.longUrl` is validated three ways: `@Pattern` for the
`http(s)://` prefix, and a custom `@AssertTrue` method (`isLongUrlValidUri()`)
that calls `URI.create()` — the exact same parser `UrlController.redirect()`
uses via `.location(URI.create(longUrl))`. This is deliberate: Hibernate's
built-in `@URL` constraint is backed by `java.net.URL`, which is far more
lenient than `java.net.URI` (accepts illegal characters in paths/fragments
that `URI` rejects), so it let malformed strings through that later crashed
the redirect. If you touch either the create-side validation or the
redirect-side `URI.create()` call, keep them in sync or reintroduce this bug.

### Exception handling

`GlobalExceptionHandler` (`@RestControllerAdvice`) maps domain exceptions to
status codes: `UrlNotFoundException`→404, `UrlExpiredException`→410,
`AliasAlreadyExistsException`→409, `RateLimitExceededException`→429,
`MethodArgumentNotValidException`→400. The catch-all `handleGeneric()`
handler logs via `log.error()` before returning a generic 500 — this logging
was added deliberately; without it, any exception not matched by a specific
handler (e.g. `HttpMessageNotReadableException` from malformed JSON) is
silently swallowed with no trace of the real cause anywhere.

### Other structural points worth knowing before editing

- `ShortUrl.version` (`@Version`) provides optimistic locking so concurrent
  click-count increments and deactivation don't silently clobber each other.
- Click IPs are hashed (`sha256`) before storage in `ClickEvent.ipHash` —
  never stored raw.
- `ExpiryCleanupJob` (`@Scheduled(fixedDelayString = "PT5M")`) sweeps
  expired-but-still-`active` rows in the background; this is defense in
  depth on top of the on-read `isExpired()` check, not a replacement for it.
- Short codes are random Base62 (`Base62Encoder`, `SecureRandom`) with a
  DB-uniqueness check-and-retry, not an encoded auto-increment ID —
  deliberate, to avoid leaking creation order/volume through sequential IDs.

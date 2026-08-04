# Architecture Overview

## Components

```
                     ┌─────────────────────┐
   Client ─────────▶ │   UrlController      │
                     │  POST /api/urls      │
                     │  GET  /{code}        │
                     │  GET  /api/urls/.../stats
                     │  DELETE /api/urls/...│
                     └──────────┬───────────┘
                                │
                     ┌──────────▼───────────┐
                     │  RateLimiterService   │  (fixed-window, per-client-IP)
                     └──────────┬───────────┘
                                │
                     ┌──────────▼───────────┐
                     │     UrlService        │  business logic, transactions
                     │  (UrlServiceImpl)     │
                     └───┬──────────────┬────┘
                         │              │
             ┌───────────▼───┐   ┌──────▼────────────┐
             │ ShortUrlRepo   │   │ ClickEventRepo     │
             │ (JPA)          │   │ (JPA)              │
             └───────┬────────┘   └──────┬─────────────┘
                     │                   │
                     └─────────┬─────────┘
                          ┌─────▼─────┐
                          │    H2     │  file-backed, single instance
                          └───────────┘

     Caffeine cache sits in front of ShortUrlRepo reads on the
     redirect path (shortCode -> longUrl).

     ExpiryCleanupJob (@Scheduled) sweeps expired-but-active rows
     every 5 minutes and marks them inactive.
```

## Control flow: redirect (the hot path)

1. `GET /{shortCode}` hits `UrlController.redirect`.
2. `UrlService.resolveAndTrack` is `@Cacheable` keyed on `shortCode` — cache
   hit skips the DB entirely and returns the long URL.
3. On a cache miss, the DB is read; if the code is unknown → 404, if
   expired → 410 Gone, if inactive → 404. Otherwise the long URL is
   returned and cached.
4. `UrlService.recordClick` runs **unconditionally** (cache hit or miss) —
   it increments the click counter and inserts a `ClickEvent` row. This is
   a deliberate split: resolving the destination is cacheable, but click
   analytics must be recorded on every request regardless of cache state.
5. Controller returns `302 Found` with the `Location` header.

## Key decisions and rationale

| Decision | Rationale |
|---|---|
| Random Base62 short codes, not encoded auto-increment IDs | Sequential-ID encoding leaks creation order and volume (competitors/scrapers can infer traffic by watching codes increment) and makes codes enumerable. Random generation + uniqueness retry costs almost nothing at 62^7 keyspace. |
| Idempotent creation for duplicate long URLs | Without a custom alias, re-submitting the same URL returns the existing active mapping instead of creating a new row. Keeps the table clean, gives callers a stable code for the same input, and avoids surprising duplicate-analytics-fragmentation for the same destination. |
| SHA-256 hash column for long-URL lookup | Avoids indexing/comparing a up-to-2048-char VARCHAR directly; the 64-char hash column is cheap to index and compare. |
| IP hashing before storage | Click analytics need aggregate signal (unique-ish visitor counting, abuse detection later), not raw PII. Hashing bounds privacy exposure while keeping the field useful. |
| Caffeine cache on redirect reads, explicit eviction on deactivation | Redirect is the highest-QPS, latency-sensitive path. `@CacheEvict` on `deactivate()` guarantees immediate consistency for explicit takedowns (the case that matters most operationally — e.g. abuse response). |
| Optimistic locking (`@Version`) on `ShortUrl` | Click-count increments and deactivation can race under concurrent load; optimistic locking surfaces conflicts instead of silently losing updates, without taking a pessimistic lock on the hot path. |
| Fixed-window rate limiter, self-implemented | Considered `bucket4j`, but couldn't verify its exact Maven coordinates against Maven Central in this build environment (network-restricted sandbox), so I chose not to ship an unverified dependency. A ~20-line fixed-window counter is easy to review, test, and swap for `bucket4j` or a Redis-backed limiter later without touching the controller contract. |
| Background expiry sweep (`@Scheduled`, 5 min) | Defense in depth: reads already check `isExpired()`, but the sweep converges DB/cache state even for codes nobody looks up again, and keeps `active` as a reliable single source of truth for other consumers (e.g. future admin dashboard). |

## Known limitations (explicit, not hidden)

1. **Cache staleness on natural expiry.** Explicit `deactivate()` evicts the
   cache immediately. A link that expires naturally (TTL passes) can still
   be served from cache for up to the cache TTL (10 minutes, configurable)
   before the next cache miss re-validates against the DB. This is a
   deliberate latency/consistency trade-off for the hot path, bounded and
   configurable — not an oversight. If tighter guarantees are required,
   either shorten the cache TTL or emit an active invalidation event from
   the expiry sweep job (not implemented here to keep scope tight).
2. **Single-instance rate limiting and caching.** Both `RateLimiterService`
   (in-memory map) and Caffeine are per-JVM-instance. Horizontally scaling
   this service would need a shared store (Redis) for both — noted as the
   production path, not built here, to keep the prototype's footprint
   proportional to a 2–3 day assignment.
3. **Click recording is synchronous on the request path.** Every redirect
   does a DB write (increment + insert) inline. At high QPS this becomes
   the bottleneck, not the cached lookup. Production evolution: push click
   events to a queue (SQS/Kafka) and batch-write asynchronously.
4. **No auth/authz.** Anyone can create, read stats for, or deactivate any
   short code if they know it. Out of scope per the assignment's focus on
   the shortener core, but flagged as a real gap before production use —
   see docs/SCENARIOS.md (ambiguous scenario) for how this was handled.
5. **H2 file database, single node.** Fine for a prototype; a real
   deployment would use Postgres/MySQL with connection pooling already
   provided by Spring Boot's defaults (HikariCP).

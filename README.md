# URL Shortener — AI-Assisted Engineering Assignment

A working URL shortener prototype (Java 17 / Spring Boot 3) built for the Schwab
"AI-Proficient Software Engineer" interview assignment. See `docs/` for the
process artifacts the assignment specifically asks for (decomposition, AI
traceability, scenarios, risk/validation, engineering summary).

## What's here

```
url-shortener/
├── src/main/java/...        Application code
├── src/test/java/...        Unit + integration tests
├── docs/
│   ├── ARCHITECTURE.md      Components, control flow, key decisions
│   ├── SCENARIOS.md         Greenfield / brownfield / ambiguous walkthroughs
│   ├── TESTING.md           Testing approach, coverage, limitations
│   └── ENGINEERING_SUMMARY.md   Final summary: plan, risks, assumptions
├── pom.xml
└── README.md                 (this file)
```

## Prerequisites

- Java 17+
- Maven 3.8+
- Internet access for the first build (Maven Central) — **note:** this
  prototype was built in a sandboxed environment without Maven Central
  access, so the build has been verified by careful manual review and
  local `mvn` tooling checks rather than a live `mvn compile` run. Run
  `mvn compile` on first checkout to confirm; see docs/TESTING.md for
  what to check if anything doesn't resolve cleanly.

## Run it

```bash
cd url-shortener
mvn spring-boot:run
```

The app starts on `http://localhost:8080`. Data persists to a local H2 file
at `./data/urlshortener.mv.db` (gitignored) so state survives restarts
during your review.

## API

### Create a short URL
```bash
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://www.schwab.com/some/very/long/path", "ttlSeconds": 3600}'
```
Optional fields: `customAlias` (4–16 chars, alphanumeric/-/_), `ttlSeconds`.

Response:
```json
{
  "shortCode": "aZ3kQ9x",
  "shortUrl": "http://localhost:8080/aZ3kQ9x",
  "longUrl": "https://www.schwab.com/some/very/long/path",
  "createdAt": "2026-08-04T10:15:30Z",
  "expiresAt": "2026-08-04T11:15:30Z"
}
```

### Follow a short URL
```bash
curl -i http://localhost:8080/aZ3kQ9x
# 302 Found, Location: https://www.schwab.com/some/very/long/path
```

### Get analytics
```bash
curl http://localhost:8080/api/urls/aZ3kQ9x/stats
```

### Deactivate
```bash
curl -X DELETE http://localhost:8080/api/urls/aZ3kQ9x
```

## Run tests
```bash
mvn test
```

## Design highlights (details in docs/ARCHITECTURE.md)
- Random Base62 short codes (not sequential-ID encoding) to avoid leaking
  creation order/volume.
- Duplicate-submission idempotency: re-shortening the same URL (no custom
  alias) returns the existing active mapping.
- Caffeine cache on the redirect hot path; explicit `@CacheEvict` on
  deactivation, bounded staleness (cache TTL) on natural expiry — a
  documented trade-off, not an oversight.
- IPs are hashed before storage in click analytics, never stored raw.
- Per-client fixed-window rate limiting on URL creation.
- Background job deactivates expired links so state doesn't depend solely
  on read-time checks.

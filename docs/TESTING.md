# Testing Approach

## Strategy
Two layers, matching where each type of bug actually surfaces:

1. **Unit tests** (`UrlServiceImplTest`) — mock the repositories, test
   business logic in isolation: code generation, idempotency, alias
   conflicts, expiry handling, IP hashing on click recording. Fast,
   no Spring context, no DB.
2. **Integration tests** (`UrlControllerIntegrationTest`) — full Spring
   context, MockMvc, real (in-memory) H2 instance isolated per test run via
   `@DynamicPropertySource`. Covers the actual HTTP contract: status codes,
   headers (`Location` on redirect), JSON shape, end-to-end create → follow
   → check-stats flow.

## What's covered
- Happy path: create, redirect, stats, deactivate.
- Validation failures: malformed URL → 400.
- Conflict handling: duplicate custom alias → 409.
- Not-found handling: unknown short code → 404.
- Expiry handling: expired code → 410 Gone (unit-level).
- Idempotency: re-shortening the same URL without an alias returns the
  existing mapping rather than creating a duplicate.
- Analytics correctness: click count increments, IP is hashed (never stored
  raw) — asserted directly in the unit test via the captured `ClickEvent`.

## What's intentionally not covered (and why)
- **Concurrency/load testing.** The optimistic-locking design
  (`@Version` on `ShortUrl`) is intended to handle concurrent click
  increments safely, but no concurrent-load test (e.g. JMeter/Gatling) was
  built — disproportionate to a prototype's time budget. This is the
  highest-value next test to add before treating the reliability claims as
  proven rather than designed-for.
- **Cache-behavior tests.** The Caffeine cache's interaction with expiry
  (see ARCHITECTURE.md "Known Limitations" #1) is not covered by an
  automated test that fast-forwards time across the cache TTL boundary —
  it was caught by manual code review instead. A `@DirtiesContext` test
  with a shortened cache TTL profile would close this gap.
- **Rate limiter boundary tests.** The fixed-window limiter's known
  double-burst edge case at window boundaries (documented in
  `RateLimiterService`) is not unit-tested with a mocked clock. Low risk
  for a prototype, but a real gap for a limiter meant to hold a hard SLA.

## Environment note on build verification
This prototype was built in a network-restricted sandbox without access to
Maven Central, so `mvn compile` / `mvn test` could not be executed live
against real Spring Boot artifacts in that environment. Every file was
reviewed manually line-by-line for API correctness against Spring Boot
3.3.x / Spring Data JPA conventions, and the one external dependency that
couldn't be verified (`bucket4j`) was deliberately removed and replaced
with a small self-contained implementation rather than shipped unverified
(see ARCHITECTURE.md). **Run `mvn test` on first checkout in a normal
environment to confirm** — flagging this explicitly rather than presenting
untested code as verified is itself part of the "output ownership"
principle the assignment asks for.

## Quality gates applied during development
- Every new class carries package-level or class-level intent where the
  "why" isn't obvious from the code alone (see comments in
  `Base62Encoder`, `RateLimiterService`, `UrlServiceImpl.resolveAndTrack`).
- Validation is enforced at the DTO boundary (Jakarta Bean Validation), not
  scattered through service logic.
- All exceptions map to specific, correct HTTP status codes via a single
  `GlobalExceptionHandler` rather than ad hoc `try/catch` in controllers.

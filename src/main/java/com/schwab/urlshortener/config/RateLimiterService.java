package com.schwab.urlshortener.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-client fixed-window rate limiter for the URL-creation endpoint.
 *
 * Implementation choice: a self-contained fixed-window counter rather than an
 * external library (e.g. bucket4j). This avoids taking on a dependency whose
 * exact coordinates/version couldn't be verified against Maven Central in this
 * environment's build, and a fixed window is simple enough to reason about,
 * test, and swap out later.
 *
 * Known trade-off vs. a sliding window / token bucket: a client can burst up
 * to 2x the configured limit across a window boundary (e.g. capacity requests
 * at 0:59 and another capacity at 1:00). Acceptable for this prototype;
 * documented in docs/ARCHITECTURE.md "Known Limitations".
 *
 * Scope: in-memory, single-instance only. Will NOT enforce a global limit
 * across multiple horizontally-scaled instances - production alternative is
 * a shared store (Redis INCR + EXPIRE) or API-gateway-level throttling.
 */
@Component
public class RateLimiterService {

    private final int capacity;
    private final long windowMillis;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiterService(
            @Value("${app.rate-limit.capacity}") int capacity,
            @Value("${app.rate-limit.refill-duration-seconds}") long windowSeconds) {
        this.capacity = capacity;
        this.windowMillis = windowSeconds * 1000L;
    }

    public boolean tryConsume(String clientKey) {
        long now = Instant.now().toEpochMilli();
        Window window = windows.compute(clientKey, (key, existing) -> {
            if (existing == null || now - existing.windowStartMillis >= windowMillis) {
                return new Window(now);
            }
            return existing;
        });
        return window.count.incrementAndGet() <= capacity;
    }

    private static final class Window {
        final long windowStartMillis;
        final AtomicInteger count = new AtomicInteger(0);

        Window(long windowStartMillis) {
            this.windowStartMillis = windowStartMillis;
        }
    }
}

package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.entity.ClickEvent;
import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import com.schwab.urlshortener.util.Base62Encoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UrlServiceImpl implements UrlService {

  private static final Logger log = LoggerFactory.getLogger(UrlServiceImpl.class);
  private static final int MAX_GENERATION_ATTEMPTS = 5;

  private final ShortUrlRepository shortUrlRepository;
  private final ClickEventRepository clickEventRepository;
  private final String baseUrl;
  private final int shortCodeLength;

  // Per-hash lock for the dedup check-then-insert in createShortUrl. Closes the
  // race where two concurrent requests for the same brand-new long URL both miss
  // the "existing active row" check and each insert their own row - idx_long_url_hash
  // is a plain index, not a unique constraint, so nothing at the DB level prevents
  // that duplicate. Single-instance only, same disclosed scope as RateLimiterService
  // and the Caffeine cache; a multi-instance deployment would need this to move to
  // a shared lock (e.g. a DB-level unique constraint plus conflict handling, or
  // Redis). Entries are never evicted - bounded by the number of distinct long URLs
  // ever submitted for creation, acceptable at prototype scale.
  //
  // The lock must be held across the FULL transaction, not just the method body:
  // Spring's @Transactional proxy commits after the annotated method returns, so if
  // the synchronized block were nested inside a @Transactional method, the lock
  // would release (at the end of the method body) a hair before the commit actually
  // happens - leaving a real, empirically-confirmed window where a second thread
  // can acquire the lock, query, see nothing committed yet, and insert a duplicate
  // anyway. createShortUrl below is deliberately NOT @Transactional itself; it holds
  // the lock and calls createOrReuseTransactional() through the self-injected proxy
  // (self, not this) so the transactional advice - and its commit - runs, and
  // completes, entirely inside the synchronized block.
  private final ConcurrentMap<String, Object> createLocks = new ConcurrentHashMap<>();

  // Self-injected proxy reference so createOrReuseTransactional() goes through
  // Spring's transactional advice even when called from within this same bean.
  // Calling this.createOrReuseTransactional(...) directly would bypass the proxy
  // (classic Spring self-invocation pitfall) and @Transactional would silently do
  // nothing. @Lazy defers proxy creation so this bean doesn't depend on itself
  // during construction. Package-private (not private) so unit tests that
  // construct this class directly with `new` - bypassing Spring entirely - can
  // set it to `this`, since @Autowired never runs outside a Spring context.
  @Autowired @Lazy UrlServiceImpl self;

  public UrlServiceImpl(
      ShortUrlRepository shortUrlRepository,
      ClickEventRepository clickEventRepository,
      @Value("${app.base-url}") String baseUrl,
      @Value("${app.short-code.length}") int shortCodeLength) {
    this.shortUrlRepository = shortUrlRepository;
    this.clickEventRepository = clickEventRepository;
    this.baseUrl = baseUrl;
    this.shortCodeLength = shortCodeLength;
  }

  @Override
  public CreateUrlResponse createShortUrl(CreateUrlRequest request) {
    String longUrl = request.getLongUrl().trim();
    String longUrlHash = sha256(longUrl);

    // No custom alias: the dedup check-then-insert below must be serialized per
    // hash, or two concurrent requests for the same brand-new URL can both miss
    // the "existing active row" check and each insert their own row.
    if (request.getCustomAlias() == null) {
      Object lock = createLocks.computeIfAbsent(longUrlHash, key -> new Object());
      synchronized (lock) {
        return self.createOrReuseTransactional(request, longUrl, longUrlHash);
      }
    }
    return self.createOrReuseTransactional(request, longUrl, longUrlHash);
  }

  @Transactional
  public CreateUrlResponse createOrReuseTransactional(
      CreateUrlRequest request, String longUrl, String longUrlHash) {
    // Idempotency: if this exact URL was already shortened (and is still active)
    // and no custom alias is requested, return the existing mapping rather than
    // creating a duplicate row. This keeps the table clean and gives callers a
    // stable code for the same input.
    if (request.getCustomAlias() == null) {
      var existing =
          shortUrlRepository.findFirstByLongUrlHashAndActiveTrueOrderByCreatedAtAsc(longUrlHash);
      if (existing.isPresent() && !existing.get().isExpired()) {
        return toResponse(existing.get());
      }
    }

    String shortCode;
    if (request.getCustomAlias() != null && !request.getCustomAlias().isBlank()) {
      shortCode = request.getCustomAlias();
      if (shortUrlRepository.existsByShortCode(shortCode)) {
        throw new AliasAlreadyExistsException(shortCode);
      }
    } else {
      shortCode = generateUniqueCode();
    }

    ShortUrl entity = new ShortUrl();
    entity.setShortCode(shortCode);
    entity.setLongUrl(longUrl);
    entity.setLongUrlHash(longUrlHash);
    entity.setCustomAlias(request.getCustomAlias());
    entity.setCreatedAt(Instant.now());
    if (request.getTtlSeconds() != null) {
      entity.setExpiresAt(Instant.now().plus(request.getTtlSeconds(), ChronoUnit.SECONDS));
    }
    entity.setActive(true);

    shortUrlRepository.save(entity);
    log.info("Created short URL: code={} ttlSeconds={}", shortCode, request.getTtlSeconds());
    return toResponse(entity);
  }

  @Override
  @Cacheable(value = "shortUrlCache", key = "#shortCode", unless = "#result == null")
  public String resolveAndTrack(
      String shortCode, String ipAddress, String userAgent, String referrer) {
    // Note: with @Cacheable here, click tracking on cache HITS is handled by the
    // caller (controller) explicitly recording the event, since a cached method
    // does not re-execute this body. See UrlController for the split of
    // "resolve" (cached) vs "record click" (always executed) responsibilities.
    ShortUrl entity =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));

    if (!entity.isActive()) {
      throw new UrlNotFoundException(shortCode);
    }
    if (entity.isExpired()) {
      throw new UrlExpiredException(shortCode);
    }
    return entity.getLongUrl();
  }

  @Override
  @Transactional
  public void recordClick(String shortCode, String ipAddress, String userAgent, String referrer) {
    shortUrlRepository.incrementClickCount(shortCode);

    ClickEvent event = new ClickEvent();
    event.setShortCode(shortCode);
    event.setClickedAt(Instant.now());
    event.setIpHash(ipAddress == null ? null : sha256(ipAddress));
    event.setUserAgent(truncate(userAgent, 512));
    event.setReferrer(truncate(referrer, 512));
    clickEventRepository.save(event);
  }

  @Override
  @Transactional(readOnly = true)
  public UrlStatsResponse getStats(String shortCode) {
    ShortUrl entity =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));

    long since24h =
        clickEventRepository.countSince(shortCode, Instant.now().minus(24, ChronoUnit.HOURS));
    List<ClickEvent> recent =
        clickEventRepository.findTop50ByShortCodeOrderByClickedAtDesc(shortCode);

    List<UrlStatsResponse.RecentClick> recentClicks =
        recent.stream()
            .map(
                c ->
                    new UrlStatsResponse.RecentClick(
                        c.getClickedAt(), c.getReferrer(), c.getUserAgent()))
            .toList();

    return new UrlStatsResponse(
        entity.getShortCode(),
        entity.getLongUrl(),
        entity.getClickCount(),
        since24h,
        entity.getCreatedAt(),
        entity.getExpiresAt(),
        entity.isActive() && !entity.isExpired(),
        recentClicks);
  }

  @Override
  @Transactional
  @CacheEvict(value = "shortUrlCache", key = "#shortCode")
  public void deactivate(String shortCode) {
    ShortUrl entity =
        shortUrlRepository
            .findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));
    entity.setActive(false);
    shortUrlRepository.save(entity);
  }

  private String generateUniqueCode() {
    for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
      String candidate = Base62Encoder.generate(shortCodeLength);
      if (!shortUrlRepository.existsByShortCode(candidate)) {
        return candidate;
      }
    }
    // Extremely unlikely at this keyspace size; fail loudly rather than silently
    // looping forever or returning a colliding code.
    throw new IllegalStateException(
        "Failed to generate a unique short code after " + MAX_GENERATION_ATTEMPTS + " attempts");
  }

  private CreateUrlResponse toResponse(ShortUrl entity) {
    return new CreateUrlResponse(
        entity.getShortCode(),
        baseUrl + "/" + entity.getShortCode(),
        entity.getLongUrl(),
        entity.getCreatedAt(),
        entity.getExpiresAt());
  }

  private static String truncate(String s, int max) {
    if (s == null) return null;
    return s.length() <= max ? s : s.substring(0, max);
  }

  private static String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}

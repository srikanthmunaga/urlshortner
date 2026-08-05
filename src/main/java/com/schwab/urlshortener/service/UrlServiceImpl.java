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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
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
  @Transactional
  public CreateUrlResponse createShortUrl(CreateUrlRequest request) {
    String longUrl = request.getLongUrl().trim();
    String longUrlHash = sha256(longUrl);

    // Idempotency: if this exact URL was already shortened (and is still active)
    // and no custom alias is requested, return the existing mapping rather than
    // creating a duplicate row. This keeps the table clean and gives callers a
    // stable code for the same input.
    if (request.getCustomAlias() == null) {
      var existing = shortUrlRepository.findByLongUrlHashAndActiveTrue(longUrlHash);
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

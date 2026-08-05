package com.schwab.urlshortener.controller;

import com.schwab.urlshortener.config.RateLimiterService;
import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;
import com.schwab.urlshortener.exception.RateLimitExceededException;
import com.schwab.urlshortener.service.UrlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class UrlController {

  private final UrlService urlService;
  private final RateLimiterService rateLimiterService;

  public UrlController(UrlService urlService, RateLimiterService rateLimiterService) {
    this.urlService = urlService;
    this.rateLimiterService = rateLimiterService;
  }

  @PostMapping("/api/urls")
  public ResponseEntity<CreateUrlResponse> createUrl(
      @Valid @RequestBody CreateUrlRequest request, HttpServletRequest httpRequest) {
    String clientKey = clientIp(httpRequest);
    if (!rateLimiterService.tryConsume(clientKey)) {
      throw new RateLimitExceededException("Rate limit exceeded. Try again shortly.");
    }
    CreateUrlResponse response = urlService.createShortUrl(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/{shortCode}")
  public ResponseEntity<Void> redirect(
      @PathVariable String shortCode, HttpServletRequest httpRequest) {
    String longUrl =
        urlService.resolveAndTrack(
            shortCode,
            clientIp(httpRequest),
            httpRequest.getHeader("User-Agent"),
            httpRequest.getHeader("Referer"));

    // Click is recorded unconditionally on every successful resolve, independent of
    // whether resolveAndTrack served from cache - see UrlServiceImpl for rationale.
    urlService.recordClick(
        shortCode,
        clientIp(httpRequest),
        httpRequest.getHeader("User-Agent"),
        httpRequest.getHeader("Referer"));

    return ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(longUrl))
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .build();
  }

  @GetMapping("/api/urls/{shortCode}/stats")
  public ResponseEntity<UrlStatsResponse> getStats(@PathVariable String shortCode) {
    return ResponseEntity.ok(urlService.getStats(shortCode));
  }

  @DeleteMapping("/api/urls/{shortCode}")
  public ResponseEntity<Void> deactivate(@PathVariable String shortCode) {
    urlService.deactivate(shortCode);
    return ResponseEntity.noContent().build();
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}

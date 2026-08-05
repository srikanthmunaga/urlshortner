package com.schwab.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the URL Shortener service.
 *
 * <p>Design intent (see docs/ARCHITECTURE.md for full rationale): - Caching enabled on the redirect
 * hot path (read-heavy, low-latency requirement). - Scheduling enabled for background expiry
 * cleanup (reliability requirement).
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class UrlShortenerApplication {
  public static void main(String[] args) {
    SpringApplication.run(UrlShortenerApplication.class, args);
  }
}

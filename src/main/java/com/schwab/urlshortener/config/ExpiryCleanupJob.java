package com.schwab.urlshortener.config;

import com.schwab.urlshortener.repository.ShortUrlRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Background reliability job: periodically sweeps expired-but-still-active rows and marks them
 * inactive so reads consistently reflect expiry without relying solely on the on-read isExpired()
 * check (defense in depth + keeps cache/DB state converging even for codes nobody looks up again).
 */
@Component
public class ExpiryCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(ExpiryCleanupJob.class);

  private final ShortUrlRepository shortUrlRepository;

  public ExpiryCleanupJob(ShortUrlRepository shortUrlRepository) {
    this.shortUrlRepository = shortUrlRepository;
  }

  @Scheduled(fixedDelayString = "PT5M")
  @Transactional
  public void deactivateExpired() {
    List<com.schwab.urlshortener.entity.ShortUrl> expired =
        shortUrlRepository.findExpiredActive(Instant.now());
    if (expired.isEmpty()) {
      return;
    }
    expired.forEach(u -> u.setActive(false));
    shortUrlRepository.saveAll(expired);
    log.info("Deactivated {} expired short URLs", expired.size());
  }
}

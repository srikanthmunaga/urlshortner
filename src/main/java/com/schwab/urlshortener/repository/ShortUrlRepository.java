package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.entity.ShortUrl;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

  Optional<ShortUrl> findByShortCode(String shortCode);

  // findFirst...OrderBy... (LIMIT 1) rather than a plain unique-result query: the
  // hash column has no DB-level unique constraint (see ShortUrl.longUrlHash), so
  // if more than one active row ever exists for the same hash - a leftover race,
  // or data from before the application-level lock in UrlServiceImpl existed -
  // this must not throw NonUniqueResultException on every subsequent request for
  // that URL. Oldest row wins, consistent with "first one created is canonical."
  Optional<ShortUrl> findFirstByLongUrlHashAndActiveTrueOrderByCreatedAtAsc(String longUrlHash);

  boolean existsByShortCode(String shortCode);

  @Modifying
  @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1 WHERE s.shortCode = :shortCode")
  int incrementClickCount(@Param("shortCode") String shortCode);

  @Query(
      "SELECT s FROM ShortUrl s WHERE s.active = true AND s.expiresAt IS NOT NULL AND s.expiresAt < :now")
  List<ShortUrl> findExpiredActive(@Param("now") Instant now);
}

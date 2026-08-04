package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.entity.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    Optional<ShortUrl> findByLongUrlHashAndActiveTrue(String longUrlHash);

    boolean existsByShortCode(String shortCode);

    @Modifying
    @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1 WHERE s.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode);

    @Query("SELECT s FROM ShortUrl s WHERE s.active = true AND s.expiresAt IS NOT NULL AND s.expiresAt < :now")
    List<ShortUrl> findExpiredActive(@Param("now") Instant now);
}

package com.schwab.urlshortener.repository;

import com.schwab.urlshortener.entity.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    List<ClickEvent> findTop50ByShortCodeOrderByClickedAtDesc(String shortCode);

    @Query("SELECT COUNT(c) FROM ClickEvent c WHERE c.shortCode = :shortCode AND c.clickedAt >= :since")
    long countSince(@Param("shortCode") String shortCode, @Param("since") Instant since);
}

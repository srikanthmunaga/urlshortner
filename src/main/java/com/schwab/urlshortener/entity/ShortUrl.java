package com.schwab.urlshortener.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "short_urls",
    indexes = {
      @Index(name = "idx_short_code", columnList = "shortCode", unique = true),
      @Index(name = "idx_long_url_hash", columnList = "longUrlHash")
    })
public class ShortUrl {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true, length = 16)
  private String shortCode;

  @Column(nullable = false, length = 2048)
  private String longUrl;

  // SHA-256 hash of longUrl, used to detect duplicate submissions without
  // scanning/comparing a 2048-char column.
  @Column(nullable = false, length = 64)
  private String longUrlHash;

  private String customAlias;

  @Column(nullable = false)
  private Instant createdAt;

  private Instant expiresAt;

  @Column(nullable = false)
  private boolean active = true;

  @Column(nullable = false)
  private long clickCount = 0L;

  @Version private Long version; // optimistic locking for concurrent click updates

  public ShortUrl() {}

  // Getters and setters
  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getShortCode() {
    return shortCode;
  }

  public void setShortCode(String shortCode) {
    this.shortCode = shortCode;
  }

  public String getLongUrl() {
    return longUrl;
  }

  public void setLongUrl(String longUrl) {
    this.longUrl = longUrl;
  }

  public String getLongUrlHash() {
    return longUrlHash;
  }

  public void setLongUrlHash(String longUrlHash) {
    this.longUrlHash = longUrlHash;
  }

  public String getCustomAlias() {
    return customAlias;
  }

  public void setCustomAlias(String customAlias) {
    this.customAlias = customAlias;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public long getClickCount() {
    return clickCount;
  }

  public void setClickCount(long clickCount) {
    this.clickCount = clickCount;
  }

  public boolean isExpired() {
    return expiresAt != null && Instant.now().isAfter(expiresAt);
  }
}

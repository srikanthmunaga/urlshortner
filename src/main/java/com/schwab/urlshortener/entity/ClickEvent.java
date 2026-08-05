package com.schwab.urlshortener.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(
    name = "click_events",
    indexes = {
      @Index(name = "idx_click_short_code", columnList = "shortCode"),
      @Index(name = "idx_click_clicked_at", columnList = "clickedAt")
    })
public class ClickEvent {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 16)
  private String shortCode;

  @Column(nullable = false)
  private Instant clickedAt;

  // IP is hashed (not stored raw) to limit PII exposure - see ARCHITECTURE.md security notes.
  @Column(length = 64)
  private String ipHash;

  @Column(length = 512)
  private String userAgent;

  @Column(length = 512)
  private String referrer;

  public ClickEvent() {}

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

  public Instant getClickedAt() {
    return clickedAt;
  }

  public void setClickedAt(Instant clickedAt) {
    this.clickedAt = clickedAt;
  }

  public String getIpHash() {
    return ipHash;
  }

  public void setIpHash(String ipHash) {
    this.ipHash = ipHash;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(String userAgent) {
    this.userAgent = userAgent;
  }

  public String getReferrer() {
    return referrer;
  }

  public void setReferrer(String referrer) {
    this.referrer = referrer;
  }
}

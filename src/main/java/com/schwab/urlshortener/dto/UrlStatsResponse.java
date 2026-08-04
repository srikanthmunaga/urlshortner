package com.schwab.urlshortener.dto;

import java.time.Instant;
import java.util.List;

public class UrlStatsResponse {
    private String shortCode;
    private String longUrl;
    private long totalClicks;
    private long clicksLast24h;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean active;
    private List<RecentClick> recentClicks;

    public UrlStatsResponse(String shortCode, String longUrl, long totalClicks, long clicksLast24h,
                             Instant createdAt, Instant expiresAt, boolean active, List<RecentClick> recentClicks) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.totalClicks = totalClicks;
        this.clicksLast24h = clicksLast24h;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.active = active;
        this.recentClicks = recentClicks;
    }

    public String getShortCode() { return shortCode; }
    public String getLongUrl() { return longUrl; }
    public long getTotalClicks() { return totalClicks; }
    public long getClicksLast24h() { return clicksLast24h; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
    public List<RecentClick> getRecentClicks() { return recentClicks; }

    public static class RecentClick {
        private Instant clickedAt;
        private String referrer;
        private String userAgent;

        public RecentClick(Instant clickedAt, String referrer, String userAgent) {
            this.clickedAt = clickedAt;
            this.referrer = referrer;
            this.userAgent = userAgent;
        }

        public Instant getClickedAt() { return clickedAt; }
        public String getReferrer() { return referrer; }
        public String getUserAgent() { return userAgent; }
    }
}

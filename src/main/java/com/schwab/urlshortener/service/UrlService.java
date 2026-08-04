package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.dto.UrlStatsResponse;

public interface UrlService {

    CreateUrlResponse createShortUrl(CreateUrlRequest request);

    String resolveAndTrack(String shortCode, String ipAddress, String userAgent, String referrer);

    void recordClick(String shortCode, String ipAddress, String userAgent, String referrer);

    UrlStatsResponse getStats(String shortCode);

    void deactivate(String shortCode);
}

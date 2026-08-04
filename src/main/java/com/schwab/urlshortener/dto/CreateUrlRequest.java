package com.schwab.urlshortener.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.net.URI;

public class CreateUrlRequest {

    @NotBlank(message = "longUrl is required")
    @Size(max = 2048, message = "longUrl must be at most 2048 characters")
    @Pattern(regexp = "^https?://.+", message = "longUrl must start with http:// or https://")
    private String longUrl;

    @Size(min = 4, max = 16, message = "customAlias must be 4-16 characters")
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "customAlias may only contain letters, digits, hyphens, underscores")
    private String customAlias;

    @Positive(message = "ttlSeconds must be positive if provided")
    private Long ttlSeconds;

    // Mirrors the exact parser UrlController.redirect() uses (URI.create) so nothing
    // that passes validation here can later fail at redirect time. java.net.URL
    // (Hibernate's @URL constraint) is too lenient for this - it accepts illegal
    // fragment/path characters that java.net.URI rejects.
    @AssertTrue(message = "longUrl must be a valid URI")
    private boolean isLongUrlValidUri() {
        if (longUrl == null) {
            return true; // let @NotBlank own the null/blank case
        }
        try {
            URI.create(longUrl);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String getLongUrl() { return longUrl; }
    public void setLongUrl(String longUrl) { this.longUrl = longUrl; }

    public String getCustomAlias() { return customAlias; }
    public void setCustomAlias(String customAlias) { this.customAlias = customAlias; }

    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
}

package com.schwab.urlshortener.service;

import com.schwab.urlshortener.dto.CreateUrlRequest;
import com.schwab.urlshortener.dto.CreateUrlResponse;
import com.schwab.urlshortener.entity.ShortUrl;
import com.schwab.urlshortener.exception.AliasAlreadyExistsException;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.ClickEventRepository;
import com.schwab.urlshortener.repository.ShortUrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UrlServiceImplTest {

    @Mock
    private ShortUrlRepository shortUrlRepository;
    @Mock
    private ClickEventRepository clickEventRepository;

    private UrlServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new UrlServiceImpl(shortUrlRepository, clickEventRepository,
                "http://localhost:8080", 7);
    }

    @Test
    void createShortUrl_generatesCodeAndSaves_whenNoDuplicate() {
        when(shortUrlRepository.findByLongUrlHashAndActiveTrue(anyString())).thenReturn(Optional.empty());
        when(shortUrlRepository.existsByShortCode(anyString())).thenReturn(false);

        CreateUrlRequest request = new CreateUrlRequest();
        request.setLongUrl("https://example.com/some/long/path");

        CreateUrlResponse response = service.createShortUrl(request);

        assertThat(response.getShortCode()).hasSize(7);
        assertThat(response.getShortUrl()).startsWith("http://localhost:8080/");
        verify(shortUrlRepository, times(1)).save(any(ShortUrl.class));
    }

    @Test
    void createShortUrl_returnsExistingMapping_whenDuplicateLongUrlAndNoAlias() {
        ShortUrl existing = new ShortUrl();
        existing.setShortCode("abc1234");
        existing.setLongUrl("https://example.com/dup");
        existing.setCreatedAt(Instant.now());

        when(shortUrlRepository.findByLongUrlHashAndActiveTrue(anyString())).thenReturn(Optional.of(existing));

        CreateUrlRequest request = new CreateUrlRequest();
        request.setLongUrl("https://example.com/dup");

        CreateUrlResponse response = service.createShortUrl(request);

        assertThat(response.getShortCode()).isEqualTo("abc1234");
        verify(shortUrlRepository, never()).save(any(ShortUrl.class));
    }

    @Test
    void createShortUrl_throwsConflict_whenCustomAliasTaken() {
        when(shortUrlRepository.existsByShortCode("myalias")).thenReturn(true);

        CreateUrlRequest request = new CreateUrlRequest();
        request.setLongUrl("https://example.com/x");
        request.setCustomAlias("myalias");

        assertThatThrownBy(() -> service.createShortUrl(request))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void resolveAndTrack_throwsNotFound_whenCodeUnknown() {
        when(shortUrlRepository.findByShortCode("nope000")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveAndTrack("nope000", "1.1.1.1", "ua", "ref"))
                .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveAndTrack_throwsExpired_whenPastTtl() {
        ShortUrl entity = new ShortUrl();
        entity.setShortCode("exp0001");
        entity.setLongUrl("https://example.com/expired");
        entity.setActive(true);
        entity.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        when(shortUrlRepository.findByShortCode("exp0001")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.resolveAndTrack("exp0001", "1.1.1.1", "ua", "ref"))
                .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    void recordClick_incrementsCountAndSavesEvent() {
        service.recordClick("code123", "1.2.3.4", "Mozilla/5.0", "https://google.com");

        verify(shortUrlRepository, times(1)).incrementClickCount("code123");

        ArgumentCaptor<com.schwab.urlshortener.entity.ClickEvent> captor =
                ArgumentCaptor.forClass(com.schwab.urlshortener.entity.ClickEvent.class);
        verify(clickEventRepository).save(captor.capture());
        assertThat(captor.getValue().getShortCode()).isEqualTo("code123");
        // IP should be hashed, never stored raw
        assertThat(captor.getValue().getIpHash()).isNotEqualTo("1.2.3.4");
    }

    @Test
    void deactivate_marksInactive() {
        ShortUrl entity = new ShortUrl();
        entity.setShortCode("code123");
        entity.setActive(true);
        when(shortUrlRepository.findByShortCode("code123")).thenReturn(Optional.of(entity));

        service.deactivate("code123");

        assertThat(entity.isActive()).isFalse();
        verify(shortUrlRepository).save(entity);
    }
}

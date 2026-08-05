package com.schwab.urlshortener.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class UrlControllerIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void overrideDatasource(DynamicPropertyRegistry registry) {
    // Isolated in-memory DB per test run so integration tests don't collide
    // with the file-based dev database or each other.
    registry.add("spring.datasource.url", () -> "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
  }

  @Test
  void createAndRedirect_endToEndFlow() throws Exception {
    Map<String, Object> body = Map.of("longUrl", "https://www.anthropic.com/claude");

    String response =
        mockMvc
            .perform(
                post("/api/urls")
                    .contentType("application/json")
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.shortCode").exists())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String shortCode = objectMapper.readTree(response).get("shortCode").asText();

    mockMvc
        .perform(get("/" + shortCode))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://www.anthropic.com/claude"));

    mockMvc
        .perform(get("/api/urls/" + shortCode + "/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalClicks").value(1));
  }

  @Test
  void createUrl_rejectsInvalidUrl() throws Exception {
    Map<String, Object> body = Map.of("longUrl", "not-a-valid-url");

    mockMvc
        .perform(
            post("/api/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void redirect_returns404_forUnknownCode() throws Exception {
    mockMvc.perform(get("/doesnotexist")).andExpect(status().isNotFound());
  }

  @Test
  void createUrl_conflictsOnDuplicateCustomAlias() throws Exception {
    Map<String, Object> first =
        Map.of("longUrl", "https://example.com/a", "customAlias", "uniquex1");
    mockMvc
        .perform(
            post("/api/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(first)))
        .andExpect(status().isCreated());

    Map<String, Object> second =
        Map.of("longUrl", "https://example.com/b", "customAlias", "uniquex1");
    mockMvc
        .perform(
            post("/api/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(second)))
        .andExpect(status().isConflict());
  }

  @Test
  void createUrl_rejectsUriWithIllegalCharacters() throws Exception {
    // Passes the http(s):// prefix @Pattern check but is not a well-formed URI -
    // this is the exact string that previously slipped through validation, got
    // saved, and crashed the redirect endpoint with an unhandled 500.
    Map<String, Object> body = Map.of("longUrl", "https://9gV#k\\|a/N>K-u,<9g:>");

    mockMvc
        .perform(
            post("/api/urls")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("valid URI")));
  }

  @Test
  void createUrl_rejectsMalformedJsonBody() throws Exception {
    // Deliberately broken JSON (unterminated string) - previously fell through to
    // the generic Exception handler and returned an unhelpful 500.
    String malformedJson = "{\"longUrl\": \"https://example.com/x }";

    mockMvc
        .perform(post("/api/urls").contentType("application/json").content(malformedJson))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createShortUrl_concurrentRequestsForSameUrl_produceOnlyOneShortCode() throws Exception {
    String longUrl = "https://example.com/race-condition-check";
    Map<String, Object> body = Map.of("longUrl", longUrl);
    String json = objectMapper.writeValueAsString(body);
    int threadCount = 12;

    ExecutorService pool = Executors.newFixedThreadPool(threadCount);
    try {
      Callable<String> createCall =
          () -> {
            String response =
                mockMvc
                    .perform(post("/api/urls").contentType("application/json").content(json))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            return objectMapper.readTree(response).get("shortCode").asText();
          };

      List<Future<String>> futures =
          java.util.stream.Stream.generate(() -> pool.submit(createCall))
              .limit(threadCount)
              .collect(Collectors.toList());

      List<String> shortCodes = new java.util.ArrayList<>();
      for (Future<String> f : futures) {
        shortCodes.add(f.get());
      }

      long distinctCodes = shortCodes.stream().distinct().count();
      org.assertj.core.api.Assertions.assertThat(distinctCodes)
          .as(
              "all %d concurrent requests for the same URL should return the same shortCode",
              threadCount)
          .isEqualTo(1);
    } finally {
      pool.shutdown();
    }
  }
}

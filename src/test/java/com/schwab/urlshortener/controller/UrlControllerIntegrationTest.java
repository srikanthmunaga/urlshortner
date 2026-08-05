package com.schwab.urlshortener.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
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
}

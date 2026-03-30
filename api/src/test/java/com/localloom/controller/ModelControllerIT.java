package com.localloom.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.service.OllamaService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ModelControllerIT {

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;

  @MockitoBean private OllamaService ollamaService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void listModelsReturnsModels() throws Exception {
    when(ollamaService.listModels())
        .thenReturn(
            List.of(
                new OllamaService.ModelInfo(
                    "llama4:scout", "llama4:scout", 5_000_000_000L, "2026-03-20T10:00:00Z")));

    mockMvc
        .perform(get("/api/v1/models/llm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$[0].name").value("llama4:scout"))
        .andExpect(jsonPath("$[0].size").value(5_000_000_000L));
  }

  @Test
  void listModelsReturnsEmptyArray() throws Exception {
    when(ollamaService.listModels()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/v1/models/llm"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void healthReturnsOkWhenReachable() throws Exception {
    when(ollamaService.isHealthy()).thenReturn(true);
    when(ollamaService.getBaseUrl()).thenReturn("http://localhost:11434");

    mockMvc
        .perform(get("/api/v1/models/llm/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.baseUrl").value("http://localhost:11434"));
  }

  @Test
  void healthReturns503WhenUnreachable() throws Exception {
    when(ollamaService.isHealthy()).thenReturn(false);
    when(ollamaService.getBaseUrl()).thenReturn("http://localhost:11434");

    mockMvc
        .perform(get("/api/v1/models/llm/health"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.status").value("unreachable"));
  }

  @Test
  void pullReturns501() throws Exception {
    mockMvc
        .perform(post("/api/v1/models/llm/pull"))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.message").value("Model pull not yet implemented"));
  }
}

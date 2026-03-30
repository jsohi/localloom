package com.localloom.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.SourceRepository;
import com.localloom.service.AudioService;
import com.localloom.service.EmbeddingService;
import com.localloom.service.SourceImportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SourceControllerIT {

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Autowired private SourceRepository sourceRepository;

  @MockitoBean private AudioService audioService;
  @MockitoBean private EmbeddingService embeddingService;
  @MockitoBean private SourceImportService sourceImportService;
  @MockitoBean private EmbeddingModel embeddingModel;

  @Test
  void createSourceReturnsCreated() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceType": "PODCAST",
                      "name": "Test Podcast",
                      "originUrl": "https://example.com/feed.xml"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.source_id").exists())
        .andExpect(jsonPath("$.job_id").exists());
  }

  @Test
  void createSourceWithoutNameReturnsBadRequest() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceType": "PODCAST",
                      "originUrl": "https://example.com/feed.xml"
                    }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void listSourcesReturnsAll() throws Exception {
    var source = new Source();
    source.setName("Listed Source");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/list.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    sourceRepository.save(source);

    mockMvc
        .perform(get("/api/v1/sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void getSourceReturnsSourceAndContentUnits() throws Exception {
    var source = new Source();
    source.setName("Detail Source");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/detail.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    mockMvc
        .perform(get("/api/v1/sources/" + source.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.source.name").value("Detail Source"))
        .andExpect(jsonPath("$.contentUnits").isArray());
  }

  @Test
  void getSourceReturns404ForMissing() throws Exception {
    mockMvc
        .perform(get("/api/v1/sources/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteSourceReturnsNoContent() throws Exception {
    var source = new Source();
    source.setName("Deletable Source");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/delete.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    mockMvc.perform(delete("/api/v1/sources/" + source.getId())).andExpect(status().isNoContent());

    mockMvc.perform(get("/api/v1/sources/" + source.getId())).andExpect(status().isNotFound());
  }

  @Test
  void syncSourceReturnsAccepted() throws Exception {
    var source = new Source();
    source.setName("Syncable Source");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/sync.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    mockMvc
        .perform(post("/api/v1/sources/" + source.getId() + "/sync"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.source_id").value(source.getId().toString()))
        .andExpect(jsonPath("$.job_id").exists());
  }
}

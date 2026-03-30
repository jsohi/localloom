package com.localloom.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentType;
import com.localloom.model.ContentUnit;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.AudioService;
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

/**
 * E2E tests for edge cases in the ingest pipeline: partial failure, re-sync, deletion cleanup, and
 * empty feeds.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class IngestPipelineE2ETest {

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;

  @MockitoBean private AudioService audioService;
  @MockitoBean private SourceImportService sourceImportService;
  @MockitoBean private EmbeddingModel embeddingModel;

  @Test
  void sourceWithContentUnitsDeleteCascade() throws Exception {
    // Create a source with content units directly
    var source = new Source();
    source.setName("Cascade E2E Source");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/cascade.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    var unit1 = new ContentUnit();
    unit1.setSource(source);
    unit1.setTitle("Episode A");
    unit1.setContentType(ContentType.AUDIO);
    unit1.setStatus(ContentUnitStatus.INDEXED);
    contentUnitRepository.save(unit1);

    var unit2 = new ContentUnit();
    unit2.setSource(source);
    unit2.setTitle("Episode B");
    unit2.setContentType(ContentType.AUDIO);
    unit2.setStatus(ContentUnitStatus.ERROR);
    contentUnitRepository.save(unit2);

    assertThat(contentUnitRepository.findBySourceId(source.getId())).hasSize(2);

    // Delete source via API
    mockMvc.perform(delete("/api/v1/sources/" + source.getId())).andExpect(status().isNoContent());

    // Verify all content units removed
    assertThat(sourceRepository.findById(source.getId())).isEmpty();
    assertThat(contentUnitRepository.findBySourceId(source.getId())).isEmpty();
  }

  @Test
  void reSyncCreatesNewJob() throws Exception {
    // Create source directly
    var source = new Source();
    source.setName("Re-Sync E2E Source");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/resync.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    // Trigger re-sync
    mockMvc
        .perform(post("/api/v1/sources/" + source.getId() + "/sync"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.source_id").value(source.getId().toString()))
        .andExpect(jsonPath("$.job_id").exists());
  }

  @Test
  void deleteNonExistentSourceReturns404() throws Exception {
    mockMvc
        .perform(delete("/api/v1/sources/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound());
  }

  @Test
  void createSourceWithMissingFieldsReturns400() throws Exception {
    // Missing sourceType
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "No Type",
                      "originUrl": "https://example.com/feed.xml"
                    }
                    """))
        .andExpect(status().isBadRequest());
  }

  @Test
  void syncNonExistentSourceReturns404() throws Exception {
    mockMvc
        .perform(post("/api/v1/sources/00000000-0000-0000-0000-000000000099/sync"))
        .andExpect(status().isNotFound());
  }
}

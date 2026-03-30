package com.localloom.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.model.EntityType;
import com.localloom.model.JobType;
import com.localloom.repository.JobRepository;
import com.localloom.service.AudioService;
import com.localloom.service.JobService;
import com.localloom.service.SourceImportService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
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
class JobControllerIT {

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;

  @Autowired private JobRepository jobRepository;

  @BeforeEach
  void setUp() {
    jobRepository.deleteAllInBatch();
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Autowired private JobService jobService;

  @MockitoBean private AudioService audioService;
  @MockitoBean private SourceImportService sourceImportService;
  @MockitoBean private EmbeddingModel embeddingModel;

  @Test
  void listActiveJobsReturnsArray() throws Exception {
    mockMvc
        .perform(get("/api/v1/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void getJobReturnsJob() throws Exception {
    var job = jobService.createJob(JobType.SYNC, UUID.randomUUID(), EntityType.SOURCE);

    mockMvc
        .perform(get("/api/v1/jobs/" + job.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(job.getId().toString()))
        .andExpect(jsonPath("$.type").value("SYNC"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }

  @Test
  void getJobReturns404ForMissing() throws Exception {
    mockMvc
        .perform(get("/api/v1/jobs/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound());
  }

  @Test
  void listActiveJobsExcludesCompleted() throws Exception {
    var active = jobService.createJob(JobType.SYNC, UUID.randomUUID(), EntityType.SOURCE);
    var completed = jobService.createJob(JobType.SYNC, UUID.randomUUID(), EntityType.SOURCE);
    jobService.completeJob(completed.getId());

    mockMvc
        .perform(get("/api/v1/jobs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + active.getId() + "')]").exists())
        .andExpect(jsonPath("$[?(@.id == '" + completed.getId() + "')]").doesNotExist());
  }
}

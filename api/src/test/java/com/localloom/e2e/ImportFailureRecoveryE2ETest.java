package com.localloom.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.EntityType;
import com.localloom.model.JobStatus;
import com.localloom.model.JobType;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.JobRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.AudioService;
import com.localloom.service.JobService;
import com.localloom.service.SourceImportService;
import com.localloom.service.UrlResolver;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ImportFailureRecoveryE2ETest {

  private static WireMockServer wireMock;
  private MockMvc mockMvc;

  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private JobService jobService;
  @Autowired private SourceImportService sourceImportService;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private UrlResolver urlResolver;
  @MockitoBean private AudioService audioService;

  @TempDir private Path tempDir;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @DynamicPropertySource
  static void sidecarProperties(final DynamicPropertyRegistry registry) {
    registry.add("localloom.sidecar.url", () -> "http://localhost:" + wireMock.port());
  }

  @BeforeEach
  void setUp() throws Exception {
    contentUnitRepository.deleteAllInBatch();
    jobRepository.deleteAllInBatch();
    sourceRepository.deleteAllInBatch();
    wireMock.resetAll();
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void sidecarDownFailsGracefully() throws Exception {
    var source = createSource("Sidecar Down Podcast");
    var job = jobService.createJob(JobType.SYNC, source.getId(), EntityType.SOURCE);

    stubUrlResolverSingleEpisode(source);

    var tempWav = tempDir.resolve("failure-e2e.wav");
    Files.writeString(tempWav, "fake audio");
    when(audioService.downloadAndConvert(any(), any(), anyBoolean())).thenReturn(tempWav);

    // Sidecar returns 500
    wireMock.stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/transcribe"))
            .willReturn(aResponse().withStatus(500).withBody("Service unavailable")));

    sourceImportService.importSource(source.getId(), job.getId());

    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.ERROR);

    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).hasSize(1);
    assertThat(units.getFirst().getStatus()).isEqualTo(ContentUnitStatus.ERROR);

    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.FAILED);
  }

  @Test
  void reSyncAfterFailureCreatesNewJob() throws Exception {
    // Create a source in ERROR state
    var source = createSource("Re-Sync Podcast");
    source.setSyncStatus(SyncStatus.ERROR);
    source = sourceRepository.save(source);

    // Trigger re-sync via API
    var result =
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/sources/" + source.getId() + "/sync")
                    .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.job_id").exists())
            .andReturn();

    var body = objectMapper.readTree(result.getResponse().getContentAsString());
    var jobId = body.get("job_id").asText();

    var job = jobService.getJob(java.util.UUID.fromString(jobId)).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
  }

  @Test
  void sourceDeletionAfterFailedImport() throws Exception {
    var source = createSource("Delete After Fail");
    var job = jobService.createJob(JobType.SYNC, source.getId(), EntityType.SOURCE);

    when(urlResolver.resolve(any())).thenThrow(new RuntimeException("Network error"));

    sourceImportService.importSource(source.getId(), job.getId());

    // Source should be in error state
    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.ERROR);

    // Should still be deletable via API
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/sources/" + source.getId()))
        .andExpect(status().isNoContent());

    assertThat(sourceRepository.findById(source.getId())).isEmpty();
  }

  private Source createSource(final String name) {
    var source = new Source();
    source.setName(name);
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/feed.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    return sourceRepository.save(source);
  }

  private void stubUrlResolverSingleEpisode(final Source source) {
    var episodes =
        List.of(
            new ResolvedEpisode(
                "Test Episode",
                "Description",
                "https://example.com/ep.mp3",
                Instant.now(),
                600,
                "ep-guid"));

    when(urlResolver.resolve(any()))
        .thenReturn(
            new ResolvedPodcast(
                source.getName(),
                "Author",
                "Description",
                null,
                null,
                source.getOriginUrl(),
                SourceType.PODCAST,
                episodes));

    when(urlResolver.detectType(any())).thenReturn(UrlResolver.UrlType.RSS);
  }
}

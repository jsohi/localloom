package com.localloom.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.JobStatus;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentFragmentRepository;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.JobRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SourceImportServiceIT {

  private static WireMockServer wireMock;

  @Autowired private SourceImportService sourceImportService;
  @Autowired private JobService jobService;
  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;
  @Autowired private ContentFragmentRepository contentFragmentRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private EmbeddingService embeddingService;

  @MockitoBean private UrlResolver urlResolver;
  @MockitoBean private AudioService audioService;

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
    contentFragmentRepository.deleteAllInBatch();
    contentUnitRepository.deleteAllInBatch();
    jobRepository.deleteAllInBatch();
    sourceRepository.deleteAllInBatch();
    wireMock.resetAll();

    wireMock.stubFor(
        post(urlEqualTo("/transcribe"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "segments": [
                            {"start": 0.0, "end": 5.0, "text": "Welcome to the podcast."},
                            {"start": 5.0, "end": 10.0, "text": "Today we discuss testing."}
                          ],
                          "duration": 10.0
                        }
                        """)));

    var tempWav = Files.createTempFile("import-it", ".wav");
    Files.writeString(tempWav, "fake audio");
    when(audioService.downloadAndConvert(any(), any(), anyBoolean())).thenReturn(tempWav);
  }

  @Test
  void happyPathImportCreatesContentAndCompletes() {
    var source = createSource("Happy Path Podcast");
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    stubUrlResolver(source, 2);

    // Call directly (bypasses @Async) — runs synchronously
    sourceImportService.importSource(source.getId(), job.getId());

    // Verify source metadata updated
    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.IDLE);
    assertThat(updatedSource.getDescription()).isEqualTo("A great podcast about testing");
    assertThat(updatedSource.getIconUrl()).isEqualTo("https://example.com/icon.png");
    assertThat(updatedSource.getLastSyncedAt()).isNotNull();

    // Verify content units created and indexed
    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).hasSize(2);
    assertThat(units)
        .allSatisfy(
            u -> {
              assertThat(u.getStatus()).isEqualTo(ContentUnitStatus.INDEXED);
              assertThat(u.getRawText()).isNotBlank();
            });

    // Verify fragments saved
    for (var unit : units) {
      var fragments =
          contentFragmentRepository.findByContentUnitIdOrderBySequenceIndex(unit.getId());
      assertThat(fragments).hasSize(2);
      assertThat(fragments.get(0).getSequenceIndex()).isEqualTo(0);
      assertThat(fragments.get(1).getSequenceIndex()).isEqualTo(1);
      assertThat(fragments.get(0).getText()).isEqualTo("Welcome to the podcast.");
    }

    // Verify job completed
    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(updatedJob.getProgress()).isEqualTo(1.0);

    // Verify embeddings are searchable
    var results = embeddingService.search("podcast testing", 5, List.of(source.getId()), null);
    assertThat(results).isNotEmpty();
  }

  @Test
  void partialFailureMarksErrorOnFailingEpisode() throws Exception {
    var source = createSource("Partial Failure Podcast");
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    stubUrlResolver(source, 2);

    // First call succeeds, second throws
    var tempWav = Files.createTempFile("partial-it", ".wav");
    Files.writeString(tempWav, "fake audio");
    when(audioService.downloadAndConvert(any(), any(), anyBoolean()))
        .thenReturn(tempWav)
        .thenThrow(new RuntimeException("Download failed for episode 2"));

    sourceImportService.importSource(source.getId(), job.getId());

    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.ERROR);

    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).hasSize(2);

    var indexed = units.stream().filter(u -> u.getStatus() == ContentUnitStatus.INDEXED).toList();
    var errored = units.stream().filter(u -> u.getStatus() == ContentUnitStatus.ERROR).toList();
    assertThat(indexed).hasSize(1);
    assertThat(errored).hasSize(1);

    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(updatedJob.getProgress()).isEqualTo(1.0);
    assertThat(updatedJob.getErrorMessage()).contains("1 episode(s) failed");
  }

  @Test
  void emptyFeedCompletesImmediately() {
    var source = createSource("Empty Feed Podcast");
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    when(urlResolver.resolve(any()))
        .thenReturn(
            new ResolvedPodcast(
                "Empty Podcast",
                null,
                null,
                null,
                null,
                source.getOriginUrl(),
                SourceType.PODCAST,
                List.of()));

    sourceImportService.importSource(source.getId(), job.getId());

    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);

    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).isEmpty();
  }

  @Test
  void sourceNotFoundFailsJob() {
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            UUID.randomUUID(),
            com.localloom.model.EntityType.SOURCE);

    sourceImportService.importSource(UUID.randomUUID(), job.getId());

    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(updatedJob.getErrorMessage()).contains("Source not found");
  }

  @Test
  void urlResolverFailureFailsJob() {
    var source = createSource("Resolver Failure Podcast");
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    when(urlResolver.resolve(any())).thenThrow(new RuntimeException("DNS resolution failed"));

    sourceImportService.importSource(source.getId(), job.getId());

    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.ERROR);

    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(updatedJob.getErrorMessage()).contains("DNS resolution failed");

    // No orphan content units
    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).isEmpty();
  }

  @Test
  void transcriptionFailureMarksEpisodeError() {
    var source = createSource("Transcription Failure Podcast");
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    stubUrlResolver(source, 1);

    // Override WireMock to return 500
    wireMock.resetAll();
    wireMock.stubFor(
        post(urlEqualTo("/transcribe"))
            .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

    sourceImportService.importSource(source.getId(), job.getId());

    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).hasSize(1);
    assertThat(units.getFirst().getStatus()).isEqualTo(ContentUnitStatus.ERROR);

    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.FAILED);
  }

  private Source createSource(final String name) {
    var source = new Source();
    source.setName(name);
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/feed.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    return sourceRepository.save(source);
  }

  private void stubUrlResolver(final Source source, final int episodeCount) {
    var episodes = new java.util.ArrayList<ResolvedEpisode>();
    for (var i = 1; i <= episodeCount; i++) {
      episodes.add(
          new ResolvedEpisode(
              "Episode " + i,
              "Description " + i,
              "https://example.com/ep" + i + ".mp3",
              Instant.now(),
              600,
              "ep" + i + "-guid"));
    }

    when(urlResolver.resolve(any()))
        .thenReturn(
            new ResolvedPodcast(
                source.getName(),
                "Test Author",
                "A great podcast about testing",
                "https://example.com/icon.png",
                "https://example.com/feed.xml",
                source.getOriginUrl(),
                SourceType.PODCAST,
                episodes));

    when(urlResolver.detectType(any())).thenReturn(UrlResolver.UrlType.RSS);
  }
}

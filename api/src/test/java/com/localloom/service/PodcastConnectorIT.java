package com.localloom.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
class PodcastConnectorIT {

  private static WireMockServer wireMock;

  @Autowired private SourceImportService sourceImportService;
  @Autowired private JobService jobService;
  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;
  @Autowired private ContentFragmentRepository contentFragmentRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private EmbeddingService embeddingService;

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
    contentFragmentRepository.deleteAllInBatch();
    contentUnitRepository.deleteAllInBatch();
    jobRepository.deleteAllInBatch();
    sourceRepository.deleteAllInBatch();
    wireMock.resetAll();

    // Stub sidecar /transcribe endpoint
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
                            {"start": 5.0, "end": 10.0, "text": "Today we discuss integration testing."}
                          ],
                          "duration": 10.0
                        }
                        """)));

    // Mock AudioService to return a temp WAV file
    var tempWav = tempDir.resolve("podcast-connector-it.wav");
    Files.writeString(tempWav, "fake audio");
    when(audioService.downloadAndConvert(any(), any(), anyBoolean())).thenReturn(tempWav);
  }

  @Test
  void fullPipelineFromRssToSearchableEmbeddings() throws Exception {
    // Serve RSS feed via WireMock — UrlResolver will fetch this directly
    wireMock.stubFor(
        get(urlEqualTo("/feed.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                          <channel>
                            <title>Integration Test Podcast</title>
                            <description>A podcast for connector integration tests</description>
                            <itunes:author>Test Author</itunes:author>
                            <itunes:image href="https://example.com/artwork.jpg"/>
                            <item>
                              <title>Episode 1: Getting Started</title>
                              <description>Introduction to integration testing</description>
                              <enclosure url="https://example.com/ep1.mp3" type="audio/mpeg" length="12345"/>
                              <guid>ep1-guid</guid>
                              <pubDate>Mon, 01 Jan 2024 08:00:00 GMT</pubDate>
                              <itunes:duration>01:30:00</itunes:duration>
                            </item>
                            <item>
                              <title>Episode 2: Advanced Topics</title>
                              <description>Deep dive into podcast connectors</description>
                              <enclosure url="https://example.com/ep2.mp3" type="audio/mpeg" length="67890"/>
                              <guid>ep2-guid</guid>
                              <pubDate>Mon, 08 Jan 2024 08:00:00 GMT</pubDate>
                              <itunes:duration>45:30</itunes:duration>
                            </item>
                          </channel>
                        </rss>
                        """)));

    var feedUrl = "http://localhost:" + wireMock.port() + "/feed.xml";
    var source = createSource("Full Pipeline Podcast", feedUrl);
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    // Run the import pipeline (blocks until complete via .get())
    sourceImportService.importSource(source.getId(), job.getId()).get();

    // Verify source metadata updated from RSS feed
    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.IDLE);
    assertThat(updatedSource.getDescription())
        .isEqualTo("A podcast for connector integration tests");
    assertThat(updatedSource.getIconUrl()).isEqualTo("https://example.com/artwork.jpg");
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

    // Verify fragments saved for each content unit
    for (var unit : units) {
      var fragments =
          contentFragmentRepository.findByContentUnitIdOrderBySequenceIndex(unit.getId());
      assertThat(fragments).hasSize(2);
      assertThat(fragments.get(0).getSequenceIndex()).isEqualTo(0);
      assertThat(fragments.get(1).getSequenceIndex()).isEqualTo(1);
      assertThat(fragments.get(0).getText()).isEqualTo("Welcome to the podcast.");
      assertThat(fragments.get(1).getText()).isEqualTo("Today we discuss integration testing.");
    }

    // Verify job completed
    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(updatedJob.getProgress()).isEqualTo(1.0);

    // Verify embeddings are searchable in ChromaDB
    var results = embeddingService.search("integration testing", 5, List.of(source.getId()), null);
    assertThat(results).isNotEmpty();
  }

  @Test
  void emptyRssFeedCompletesJob() throws Exception {
    // Serve empty RSS feed via WireMock
    wireMock.stubFor(
        get(urlEqualTo("/empty-feed.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <rss version="2.0">
                          <channel>
                            <title>Empty Podcast</title>
                            <description>No episodes yet</description>
                          </channel>
                        </rss>
                        """)));

    var feedUrl = "http://localhost:" + wireMock.port() + "/empty-feed.xml";
    var source = createSource("Empty Feed Podcast", feedUrl);
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    sourceImportService.importSource(source.getId(), job.getId()).get();

    // Verify job completed successfully
    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.COMPLETED);

    // Verify no content units created
    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).isEmpty();
  }

  @Test
  void malformedRssFeedFailsGracefully() throws Exception {
    // Serve malformed XML via WireMock
    wireMock.stubFor(
        get(urlEqualTo("/malformed-feed.xml"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/xml")
                    .withBody("this is not valid xml <<<")));

    var feedUrl = "http://localhost:" + wireMock.port() + "/malformed-feed.xml";
    var source = createSource("Malformed Feed Podcast", feedUrl);
    var job =
        jobService.createJob(
            com.localloom.model.JobType.SYNC,
            source.getId(),
            com.localloom.model.EntityType.SOURCE);

    sourceImportService.importSource(source.getId(), job.getId()).get();

    // Verify source marked as ERROR
    var updatedSource = sourceRepository.findById(source.getId()).orElseThrow();
    assertThat(updatedSource.getSyncStatus()).isEqualTo(SyncStatus.ERROR);

    // Verify job failed with descriptive message
    var updatedJob = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updatedJob.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(updatedJob.getErrorMessage()).contains("Failed to parse RSS feed");

    // No orphan content units
    var units = contentUnitRepository.findBySourceId(source.getId());
    assertThat(units).isEmpty();
  }

  private Source createSource(final String name, final String originUrl) {
    var source = new Source();
    source.setName(name);
    source.setSourceType(SourceType.MEDIA);
    source.setOriginUrl(originUrl);
    source.setSyncStatus(SyncStatus.IDLE);
    return sourceRepository.save(source);
  }
}

package com.localloom.e2e;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.JobRepository;
import com.localloom.repository.SourceRepository;
import com.localloom.service.AudioService;
import com.localloom.service.UrlResolver;
import com.localloom.service.dto.ResolvedEpisode;
import com.localloom.service.dto.ResolvedPodcast;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SourceImportE2ETest {

  private static WireMockServer wireMock;
  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private ObjectMapper objectMapper;

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
    contentUnitRepository.deleteAllInBatch();
    jobRepository.deleteAllInBatch();
    sourceRepository.deleteAllInBatch();
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    wireMock.resetAll();

    // Stub the sidecar /transcribe endpoint
    wireMock.stubFor(
        post(urlEqualTo("/transcribe"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "segments": [
                            {"start": 0.0, "end": 4.8, "text": "Welcome to the sample podcast episode."},
                            {"start": 4.8, "end": 10.0, "text": "This is a short clip used for testing."}
                          ],
                          "duration": 10.0
                        }
                        """)));

    // Mock UrlResolver to return 2 episodes
    var episodes =
        List.of(
            new ResolvedEpisode(
                "Episode 1",
                "Description 1",
                "https://example.com/ep1.mp3",
                Instant.now(),
                600,
                "ep1-guid"),
            new ResolvedEpisode(
                "Episode 2",
                "Description 2",
                "https://example.com/ep2.mp3",
                Instant.now(),
                900,
                "ep2-guid"));

    when(urlResolver.resolve(any()))
        .thenReturn(
            new ResolvedPodcast(
                "Test Podcast",
                "Test Author",
                "A test podcast",
                "https://example.com/icon.png",
                "https://example.com/feed.xml",
                "https://example.com/feed.xml",
                SourceType.MEDIA,
                episodes));

    // Mock AudioService to return a temp WAV file
    var tempWav = Files.createTempFile("e2e-test", ".wav");
    Files.writeString(tempWav, "fake audio");
    when(audioService.downloadAndConvert(any(), any(), anyBoolean())).thenReturn(tempWav);
  }

  @Test
  void podcastImportPipeline() throws Exception {
    var result =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "sourceType": "MEDIA",
                          "name": "E2E Test Podcast",
                          "originUrl": "https://example.com/feed.xml"
                        }
                        """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.source_id").exists())
            .andExpect(jsonPath("$.job_id").exists())
            .andReturn();

    var body = result.getResponse().getContentAsString();
    assertThat(body).contains("source_id").contains("job_id");
  }

  @Test
  void sourceDeletion() throws Exception {
    // Create a source directly (bypassing async import)
    var source = new com.localloom.model.Source();
    source.setName("Deletable E2E Source");
    source.setSourceType(SourceType.MEDIA);
    source.setOriginUrl("https://example.com/delete-e2e.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    var unit = new com.localloom.model.ContentUnit();
    unit.setSource(source);
    unit.setTitle("Deletable Episode");
    unit.setContentType(com.localloom.model.ContentType.AUDIO);
    unit.setStatus(ContentUnitStatus.INDEXED);
    contentUnitRepository.save(unit);

    // Delete via API
    mockMvc.perform(delete("/api/v1/sources/" + source.getId())).andExpect(status().isNoContent());

    // Verify cleanup
    assertThat(sourceRepository.findById(source.getId())).isEmpty();
    assertThat(contentUnitRepository.findBySourceId(source.getId())).isEmpty();
  }

  @Test
  void getJobAfterSourceCreation() throws Exception {
    var result =
        mockMvc
            .perform(
                MockMvcRequestBuilders.post("/api/v1/sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "sourceType": "MEDIA",
                          "name": "Job Tracking Podcast",
                          "originUrl": "https://example.com/job-track.xml"
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();

    // Extract job_id from response
    var responseBody = result.getResponse().getContentAsString();
    var jobIdStr = objectMapper.readTree(responseBody).get("job_id").asText();

    // Verify job is retrievable
    mockMvc
        .perform(get("/api/v1/jobs/" + jobIdStr))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("SYNC"))
        .andExpect(jsonPath("$.status").value("PENDING"));
  }
}

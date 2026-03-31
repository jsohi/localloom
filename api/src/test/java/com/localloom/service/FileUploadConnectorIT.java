package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.SourceType;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.repository.JobRepository;
import com.localloom.repository.SourceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class FileUploadConnectorIT {

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;

  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;
  @Autowired private JobRepository jobRepository;
  @Autowired private EmbeddingService embeddingService;

  @BeforeEach
  void setUp() {
    contentUnitRepository.deleteAllInBatch();
    jobRepository.deleteAllInBatch();
    sourceRepository.deleteAllInBatch();
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void uploadEndpointReturns501() throws Exception {
    var file =
        new MockMultipartFile(
            "file",
            "test-document.txt",
            MediaType.TEXT_PLAIN_VALUE,
            "Sample file content".getBytes());

    mockMvc
        .perform(multipart("/api/v1/sources/upload").file(file))
        .andExpect(status().isNotImplemented());
  }

  @Test
  void fileUploadSourceCanBeCreated() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceType": "FILE_UPLOAD",
                      "name": "Test File Upload",
                      "originUrl": "file://local/test-document.txt"
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.source_id").exists())
        .andExpect(jsonPath("$.job_id").exists());

    var sources = sourceRepository.findAll();
    assertThat(sources).hasSize(1);
    assertThat(sources.getFirst().getSourceType()).isEqualTo(SourceType.FILE_UPLOAD);
    assertThat(sources.getFirst().getName()).isEqualTo("Test File Upload");
  }

  @Test
  void fileUploadContentEmbeddableAndSearchable() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText(
        "Kubernetes container orchestration manages deployment scaling and operations "
            + "of application containers across clusters of hosts.");
    fragment.setLocation("page:1");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        "Infrastructure Guide",
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fragment));

    var results =
        embeddingService.search("Kubernetes container orchestration", 5, List.of(sourceId), null);
    assertThat(results).isNotEmpty();

    var firstResult = results.getFirst();
    assertThat(firstResult.getText()).contains("Kubernetes");
    assertThat(firstResult.getMetadata().get("source_type")).isEqualTo("FILE_UPLOAD");
    assertThat(firstResult.getMetadata().get("content_type")).isEqualTo("TEXT_FILE");
    assertThat(firstResult.getMetadata().get("source_id")).isEqualTo(sourceId.toString());

    // Verify FILE_UPLOAD source type filter works
    var filteredResults =
        embeddingService.search(
            "Kubernetes container orchestration",
            5,
            List.of(sourceId),
            List.of(SourceType.FILE_UPLOAD));
    assertThat(filteredResults).isNotEmpty();

    // Verify unrelated source type filter excludes results
    var excludedResults =
        embeddingService.search(
            "Kubernetes container orchestration",
            5,
            List.of(sourceId),
            List.of(SourceType.PODCAST));
    assertThat(excludedResults).isEmpty();
  }

  @Test
  void fileUploadContentWithMetadata() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();
    var title = "Company Policies Handbook";

    var fragment1 = new ContentFragment();
    fragment1.setText(
        "All employees must complete security awareness training within thirty days "
            + "of their start date.");
    fragment1.setLocation("section:onboarding");

    var fragment2 = new ContentFragment();
    fragment2.setText(
        "Remote work is permitted with manager approval and requires a secure VPN "
            + "connection at all times.");
    fragment2.setLocation("section:remote-work");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        title,
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fragment1, fragment2));

    // Search for first fragment content
    var results =
        embeddingService.search(
            "security awareness training onboarding", 5, List.of(sourceId), null);
    assertThat(results).isNotEmpty();

    var meta = results.getFirst().getMetadata();
    assertThat(meta.get("source_id")).isEqualTo(sourceId.toString());
    assertThat(meta.get("content_unit_id")).isEqualTo(contentUnitId.toString());
    assertThat(meta.get("content_unit_title")).isEqualTo(title);
    assertThat(meta.get("source_type")).isEqualTo("FILE_UPLOAD");
    assertThat(meta.get("content_type")).isEqualTo("TEXT_FILE");
    assertThat(meta.get("location")).isEqualTo("section:onboarding");

    // Search for second fragment and verify its distinct location metadata
    var remoteResults =
        embeddingService.search("remote work VPN connection", 5, List.of(sourceId), null);
    assertThat(remoteResults).isNotEmpty();
    assertThat(remoteResults.getFirst().getMetadata().get("location"))
        .isEqualTo("section:remote-work");
  }
}

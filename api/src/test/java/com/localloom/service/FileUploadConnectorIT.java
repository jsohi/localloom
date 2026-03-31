package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
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
  @Autowired private ObjectMapper objectMapper;

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

  @Test
  void fileUploadSourceDeletionCleansUpVectors() throws Exception {
    // Create source via API
    var result =
        mockMvc
            .perform(
                post("/api/v1/sources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "sourceType": "FILE_UPLOAD",
                          "name": "Deletable File",
                          "originUrl": "file://local/deletable.txt"
                        }
                        """))
            .andExpect(status().isCreated())
            .andReturn();

    var body = result.getResponse().getContentAsString();
    var sourceId = UUID.fromString(objectMapper.readTree(body).get("source_id").asText());

    // Embed content for this source
    var fragment = new ContentFragment();
    fragment.setText("Deletable content about microservices architecture patterns.");
    fragment.setLocation("page:1");

    embeddingService.embedContent(
        sourceId,
        UUID.randomUUID(),
        "Deletable File",
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fragment));

    // Verify searchable before deletion
    var beforeResults =
        embeddingService.search("microservices architecture", 5, List.of(sourceId), null);
    assertThat(beforeResults).isNotEmpty();

    // Delete via API
    mockMvc.perform(delete("/api/v1/sources/" + sourceId)).andExpect(status().isNoContent());

    // Verify source gone from DB
    assertThat(sourceRepository.findById(sourceId)).isEmpty();

    // Verify embeddings cleaned up from ChromaDB
    var afterResults =
        embeddingService.search("microservices architecture", 5, List.of(sourceId), null);
    assertThat(afterResults).isEmpty();
  }

  @Test
  void multipleFileFragmentsPreserveChunkOrdering() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();

    var fragment1 = new ContentFragment();
    fragment1.setText("Chapter one introduces the fundamentals of distributed systems.");
    fragment1.setLocation("page:1");

    var fragment2 = new ContentFragment();
    fragment2.setText("Chapter two covers consensus algorithms like Raft and Paxos.");
    fragment2.setLocation("page:2");

    var fragment3 = new ContentFragment();
    fragment3.setText("Chapter three discusses fault tolerance and replication strategies.");
    fragment3.setLocation("page:3");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        "Distributed Systems Book",
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fragment1, fragment2, fragment3));

    // Search broadly to get all fragments
    var results = embeddingService.search("distributed systems", 10, List.of(sourceId), null);
    assertThat(results).hasSizeGreaterThanOrEqualTo(3);

    // Verify chunk_index metadata is present and sequential
    var chunkIndices =
        results.stream()
            .map(doc -> doc.getMetadata().get("chunk_index"))
            .filter(java.util.Objects::nonNull)
            .map(Object::toString)
            .map(Integer::parseInt)
            .sorted()
            .toList();
    assertThat(chunkIndices).isNotEmpty();
    assertThat(chunkIndices.getFirst()).isEqualTo(0);

    // Verify all three locations are represented
    var locations =
        results.stream().map(doc -> (String) doc.getMetadata().get("location")).distinct().toList();
    assertThat(locations).contains("page:1", "page:2", "page:3");
  }
}

package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.SourceType;
import com.localloom.service.dto.RagQuery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RagServiceIT {

  @Autowired private RagService ragService;
  @Autowired private EmbeddingService embeddingService;

  @Test
  void answerReturnsResponseWithCitations() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText(
        "Kubernetes orchestrates containerized applications across clusters of machines,"
            + " providing automated deployment, scaling, and management.");
    fragment.setLocation("timestamp:00:10:30");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        "Cloud Native Podcast",
        SourceType.PODCAST,
        ContentType.AUDIO,
        List.of(fragment));

    var query = new RagQuery("What is Kubernetes?", null, List.of(sourceId), null, 5);
    var response = ragService.answer(query);

    assertThat(response.answer()).isNotBlank();
    assertThat(response.citations()).isNotEmpty();
    assertThat(response.citations())
        .anyMatch(c -> "PODCAST".equals(c.sourceType()) && c.contentUnitTitle() != null);
  }

  @Test
  void answerWithSourceTypeFilter() {
    var podcastSourceId = UUID.randomUUID();
    var fileSourceId = UUID.randomUUID();

    var podcastFragment = new ContentFragment();
    podcastFragment.setText("GraphQL provides a flexible query language for APIs.");
    podcastFragment.setLocation("timestamp:00:03:00");

    var fileFragment = new ContentFragment();
    fileFragment.setText("REST APIs use HTTP methods for CRUD operations.");
    fileFragment.setLocation("page:1");

    embeddingService.embedContent(
        podcastSourceId,
        UUID.randomUUID(),
        "API Design Podcast",
        SourceType.PODCAST,
        ContentType.AUDIO,
        List.of(podcastFragment));

    embeddingService.embedContent(
        fileSourceId,
        UUID.randomUUID(),
        "API Documentation",
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fileFragment));

    var query = new RagQuery("API design", null, null, List.of(SourceType.PODCAST), 5);
    var response = ragService.answer(query);

    assertThat(response.answer()).isNotBlank();
    assertThat(response.citations())
        .allSatisfy(c -> assertThat(c.sourceType()).isEqualTo("PODCAST"));
  }

  @Test
  void answerWithNoMatchingDocuments() {
    var query =
        new RagQuery(
            "quantum entanglement in photosynthesis", null, List.of(UUID.randomUUID()), null, 5);
    var response = ragService.answer(query);

    assertThat(response.answer()).isNotBlank();
    assertThat(response.citations()).isEmpty();
  }
}

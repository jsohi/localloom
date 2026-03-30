package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.SourceType;
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
class EmbeddingServiceIT {

  @Autowired private EmbeddingService embeddingService;

  @Test
  void embedAndSearch() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText(
        "Machine learning is a subset of artificial intelligence that enables systems to learn from"
            + " data.");
    fragment.setLocation("page:1");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        "ML Basics",
        SourceType.PODCAST,
        ContentType.AUDIO,
        List.of(fragment));

    var results = embeddingService.search("What is machine learning?", 5, List.of(sourceId), null);
    assertThat(results).isNotEmpty();
    assertThat(results.getFirst().getText()).containsIgnoringCase("machine learning");
  }

  @Test
  void deleteBySourceRemovesVectors() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText("This content will be deleted after embedding.");
    fragment.setLocation("page:1");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        "Deletable Content",
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fragment));

    var before = embeddingService.search("deleted after embedding", 5, List.of(sourceId), null);
    assertThat(before).isNotEmpty();

    embeddingService.deleteBySource(sourceId);

    var after = embeddingService.search("deleted after embedding", 5, List.of(sourceId), null);
    assertThat(after).isEmpty();
  }

  @Test
  void metadataFilterBySourceType() {
    var sourceId1 = UUID.randomUUID();
    var sourceId2 = UUID.randomUUID();

    var fragment1 = new ContentFragment();
    fragment1.setText("Podcast episode about distributed systems and consensus algorithms.");
    fragment1.setLocation("timestamp:00:01:00");

    var fragment2 = new ContentFragment();
    fragment2.setText("Confluence page about distributed systems architecture patterns.");
    fragment2.setLocation("page:3");

    embeddingService.embedContent(
        sourceId1,
        UUID.randomUUID(),
        "Distributed Systems Podcast",
        SourceType.PODCAST,
        ContentType.AUDIO,
        List.of(fragment1));

    embeddingService.embedContent(
        sourceId2,
        UUID.randomUUID(),
        "Architecture Patterns",
        SourceType.CONFLUENCE,
        ContentType.PAGE,
        List.of(fragment2));

    var podcastOnly =
        embeddingService.search("distributed systems", 5, null, List.of(SourceType.PODCAST));
    assertThat(podcastOnly)
        .allSatisfy(doc -> assertThat(doc.getMetadata().get("source_type")).isEqualTo("PODCAST"));
  }
}

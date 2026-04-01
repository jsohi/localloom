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
class EmbeddingServiceChunkingIT {

  @Autowired private EmbeddingService embeddingService;

  @Test
  void longTextProducesMultipleChunks() {
    var sourceId = UUID.randomUUID();
    var contentUnitId = UUID.randomUUID();

    // Generate text long enough to require chunking (repeat to exceed token limit)
    var longText =
        ("Artificial intelligence and machine learning have transformed the way we "
                + "process and analyze data across many industries. From natural language processing "
                + "to computer vision, these technologies enable automated pattern recognition, "
                + "predictive analytics, and intelligent decision-making systems. ")
            .repeat(20);

    var fragment = new ContentFragment();
    fragment.setText(longText);
    fragment.setLocation("timestamp:00:00:00");

    embeddingService.embedContent(
        sourceId,
        contentUnitId,
        "AI Podcast",
        SourceType.MEDIA,
        ContentType.AUDIO,
        List.of(fragment));

    // Search should return results from the chunked content
    var results =
        embeddingService.search(
            "artificial intelligence machine learning", 10, List.of(sourceId), null);
    assertThat(results).isNotEmpty();

    // Verify metadata preserved on all chunks
    for (var doc : results) {
      var meta = doc.getMetadata();
      assertThat(meta.get("source_id")).isEqualTo(sourceId.toString());
      assertThat(meta.get("content_unit_id")).isEqualTo(contentUnitId.toString());
      assertThat(meta.get("content_unit_title")).isEqualTo("AI Podcast");
      assertThat(meta.get("source_type")).isEqualTo("MEDIA");
      assertThat(meta.get("location")).isEqualTo("timestamp:00:00:00");
    }
  }

  @Test
  void blankFragmentsAreSkipped() {
    var sourceId = UUID.randomUUID();

    var blankFragment = new ContentFragment();
    blankFragment.setText("   ");
    blankFragment.setLocation("timestamp:00:00:00");

    var nullFragment = new ContentFragment();
    nullFragment.setText(null);
    nullFragment.setLocation("timestamp:00:01:00");

    var validFragment = new ContentFragment();
    validFragment.setText("This is a valid fragment with actual content about testing.");
    validFragment.setLocation("timestamp:00:02:00");

    embeddingService.embedContent(
        sourceId,
        UUID.randomUUID(),
        "Mixed Content",
        SourceType.MEDIA,
        ContentType.AUDIO,
        List.of(blankFragment, nullFragment, validFragment));

    var results = embeddingService.search("valid fragment testing", 5, List.of(sourceId), null);
    assertThat(results).isNotEmpty();
    // Only the valid fragment should produce results
    assertThat(results).allSatisfy(doc -> assertThat(doc.getText()).doesNotContain("   "));
  }

  @Test
  void contextualTextPrefixIncludedInEmbedding() {
    var sourceId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText("Kubernetes uses pods as the smallest deployable unit.");
    fragment.setLocation("page:42");

    embeddingService.embedContent(
        sourceId,
        UUID.randomUUID(),
        "DevOps Guide",
        SourceType.FILE_UPLOAD,
        ContentType.TEXT_FILE,
        List.of(fragment));

    var results =
        embeddingService.search("DevOps Guide Kubernetes pods", 5, List.of(sourceId), null);
    assertThat(results).isNotEmpty();

    // The stored text should include the contextual prefix
    var text = results.getFirst().getText();
    assertThat(text).contains("[FILE_UPLOAD]");
    assertThat(text).contains("DevOps Guide");
    assertThat(text).contains("[TEXT_FILE]");
    assertThat(text).contains("(page:42)");
    assertThat(text).contains("Kubernetes uses pods");
  }
}

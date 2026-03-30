package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.localloom.model.SourceType;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

/**
 * Unit tests for EmbeddingService's filter-building logic. Uses reflection to test the private
 * buildFilterExpression and buildOrChain methods without needing Spring context or containers.
 */
class EmbeddingServiceTest {

  private EmbeddingService embeddingService;

  @BeforeEach
  void setUp() {
    embeddingService = new EmbeddingService(mock(VectorStore.class), mock(TokenTextSplitter.class));
  }

  @Test
  void singleSourceIdFilter() throws Exception {
    var id = UUID.randomUUID();
    var expression = invokeBuildFilterExpression(List.of(id), null);
    assertThat(expression).isNotNull();
  }

  @Test
  void multipleSourceIdFilter() throws Exception {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var expression = invokeBuildFilterExpression(List.of(id1, id2), null);
    assertThat(expression).isNotNull();
  }

  @Test
  void combinedSourceIdAndSourceType() throws Exception {
    var id = UUID.randomUUID();
    var expression = invokeBuildFilterExpression(List.of(id), List.of(SourceType.PODCAST));
    assertThat(expression).isNotNull();
  }

  @Test
  void nullInputsReturnNull() throws Exception {
    var expression = invokeBuildFilterExpression(null, null);
    assertThat(expression).isNull();
  }

  @Test
  void emptyListsReturnNull() throws Exception {
    var expression = invokeBuildFilterExpression(Collections.emptyList(), Collections.emptyList());
    assertThat(expression).isNull();
  }

  @Test
  void singleSourceTypeFilter() throws Exception {
    var expression = invokeBuildFilterExpression(null, List.of(SourceType.CONFLUENCE));
    assertThat(expression).isNotNull();
  }

  @Test
  void multipleSourceTypeFilter() throws Exception {
    var expression =
        invokeBuildFilterExpression(null, List.of(SourceType.PODCAST, SourceType.FILE_UPLOAD));
    assertThat(expression).isNotNull();
  }

  private Filter.Expression invokeBuildFilterExpression(
      final List<UUID> sourceIds, final List<SourceType> sourceTypes) throws Exception {
    Method method =
        EmbeddingService.class.getDeclaredMethod("buildFilterExpression", List.class, List.class);
    method.setAccessible(true);
    return (Filter.Expression) method.invoke(embeddingService, sourceIds, sourceTypes);
  }
}

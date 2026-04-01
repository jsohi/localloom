package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.model.SourceType;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;

/**
 * Unit tests for EmbeddingService's filter-building logic. Now delegates to VectorStoreFilters, so
 * tests verify that shared utility directly.
 */
class EmbeddingServiceTest {

  @Test
  void singleSourceIdFilter() {
    var id = UUID.randomUUID();
    var expression = VectorStoreFilters.buildFilterExpression(List.of(id), null);
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.EQ);
    var key = (Filter.Key) expression.left();
    assertThat(key.key()).isEqualTo("source_id");
    var value = (Filter.Value) expression.right();
    assertThat(value.value()).isEqualTo(id.toString());
  }

  @Test
  void multipleSourceIdFilter() {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var expression = VectorStoreFilters.buildFilterExpression(List.of(id1, id2), null);
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.OR);
    assertThat(expression.toString()).contains(id1.toString()).contains(id2.toString());
  }

  @Test
  void combinedSourceIdAndSourceType() {
    var id = UUID.randomUUID();
    var expression =
        VectorStoreFilters.buildFilterExpression(List.of(id), List.of(SourceType.PODCAST));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.AND);
    assertThat(expression.toString()).contains("source_id").contains("source_type");
  }

  @Test
  void nullInputsReturnNull() {
    var expression = VectorStoreFilters.buildFilterExpression(null, null);
    assertThat(expression).isNull();
  }

  @Test
  void emptyListsReturnNull() {
    var expression =
        VectorStoreFilters.buildFilterExpression(Collections.emptyList(), Collections.emptyList());
    assertThat(expression).isNull();
  }

  @Test
  void singleSourceTypeFilter() {
    var expression = VectorStoreFilters.buildFilterExpression(null, List.of(SourceType.WEB_PAGE));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.EQ);
    var key = (Filter.Key) expression.left();
    assertThat(key.key()).isEqualTo("source_type");
    var value = (Filter.Value) expression.right();
    assertThat(value.value()).isEqualTo("WEB_PAGE");
  }

  @Test
  void multipleSourceTypeFilter() {
    var expression =
        VectorStoreFilters.buildFilterExpression(
            null, List.of(SourceType.PODCAST, SourceType.FILE_UPLOAD));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.OR);
    assertThat(expression.toString()).contains("PODCAST").contains("FILE_UPLOAD");
  }
}

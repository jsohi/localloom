package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.model.SourceType;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;

class VectorStoreFiltersTest {

  @Test
  void singleSourceId() {
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
  void multipleSourceIds() {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var expression = VectorStoreFilters.buildFilterExpression(List.of(id1, id2), null);
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.OR);
    assertThat(expression.toString()).contains(id1.toString()).contains(id2.toString());
  }

  @Test
  void singleSourceType() {
    var expression = VectorStoreFilters.buildFilterExpression(null, List.of(SourceType.MEDIA));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.EQ);
    var key = (Filter.Key) expression.left();
    assertThat(key.key()).isEqualTo("source_type");
    var value = (Filter.Value) expression.right();
    assertThat(value.value()).isEqualTo("MEDIA");
  }

  @Test
  void combinedFilters() {
    var id = UUID.randomUUID();
    var expression =
        VectorStoreFilters.buildFilterExpression(List.of(id), List.of(SourceType.WEB_PAGE));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.AND);
    assertThat(expression.toString()).contains("source_id").contains("source_type");
  }

  @Test
  void nullInputsReturnsNull() {
    var expression = VectorStoreFilters.buildFilterExpression(null, null);
    assertThat(expression).isNull();
  }

  @Test
  void emptyListsReturnsNull() {
    var expression =
        VectorStoreFilters.buildFilterExpression(Collections.emptyList(), Collections.emptyList());
    assertThat(expression).isNull();
  }
}

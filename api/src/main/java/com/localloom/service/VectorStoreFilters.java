package com.localloom.service;

import com.localloom.model.SourceType;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

final class VectorStoreFilters {

  private VectorStoreFilters() {}

  static Filter.Expression buildFilterExpression(
      final List<UUID> sourceIds, final List<SourceType> sourceTypes) {
    if ((sourceIds == null || sourceIds.isEmpty())
        && (sourceTypes == null || sourceTypes.isEmpty())) {
      return null;
    }

    var b = new FilterExpressionBuilder();
    var sourceIdOp = buildOrChain(b, "source_id", sourceIds, UUID::toString);
    var sourceTypeOp = buildOrChain(b, "source_type", sourceTypes, SourceType::name);

    if (sourceIdOp != null && sourceTypeOp != null) {
      return b.and(b.group(sourceIdOp), b.group(sourceTypeOp)).build();
    }
    if (sourceIdOp != null) {
      return sourceIdOp.build();
    }
    return sourceTypeOp != null ? sourceTypeOp.build() : null;
  }

  private static <T> FilterExpressionBuilder.Op buildOrChain(
      final FilterExpressionBuilder b,
      final String field,
      final List<T> values,
      final Function<T, String> mapper) {
    if (values == null || values.isEmpty()) {
      return null;
    }
    if (values.size() == 1) {
      return b.eq(field, mapper.apply(values.getFirst()));
    }
    var ops = values.stream().map(v -> b.eq(field, mapper.apply(v))).toList();
    var combined = ops.getFirst();
    for (var i = 1; i < ops.size(); i++) {
      combined = b.or(combined, ops.get(i));
    }
    return combined;
  }
}

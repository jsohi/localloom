package com.localloom.service;

import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.SourceType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@Service
public class EmbeddingService {

    private static final Logger log = LogManager.getLogger(EmbeddingService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public EmbeddingService(final VectorStore vectorStore, final TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    /**
     * Chunks each fragment's text, wraps chunks in Spring AI Documents with
     * metadata aligned to the ChromaDB schema, and stores them.
     */
    public void embedContent(final UUID sourceId, final UUID contentUnitId, final String title,
                             final SourceType sourceType, final ContentType contentType,
                             final List<ContentFragment> fragments) {

        log.debug("Embedding content for contentUnitId={} sourceId={} fragments={}",
                contentUnitId, sourceId, fragments.size());

        final var allDocuments = new ArrayList<Document>();
        var chunkIndex = 0;

        for (final var fragment : fragments) {
            if (fragment.getText() == null || fragment.getText().isBlank()) {
                continue;
            }
            final var contextualText = buildContextualText(title, sourceType, contentType, fragment);
            final var rawChunks = textSplitter.apply(List.of(new Document(contextualText)));

            for (final var chunk : rawChunks) {
                final var metadata = new HashMap<String, Object>();
                metadata.put("source_id", sourceId.toString());
                metadata.put("source_type", sourceType.name());
                metadata.put("content_unit_id", contentUnitId.toString());
                metadata.put("content_type", contentType.name());
                metadata.put("content_unit_title", title);
                metadata.put("location", fragment.getLocation());
                metadata.put("chunk_index", String.valueOf(chunkIndex));

                allDocuments.add(new Document(chunk.getText(), metadata));
                chunkIndex++;
            }
        }

        if (allDocuments.isEmpty()) {
            log.warn("No documents generated for contentUnitId={}, skipping vectorStore.add", contentUnitId);
            return;
        }

        log.info("Storing {} chunks for contentUnitId={} sourceId={}", allDocuments.size(), contentUnitId, sourceId);
        vectorStore.add(allDocuments);
    }

    /**
     * Searches the vector store with optional filters on sourceIds and sourceTypes.
     */
    public List<Document> search(final String query, final int topK,
                                 final List<UUID> sourceIds, final List<SourceType> sourceTypes) {

        log.debug("Searching query='{}' topK={} sourceIds={} sourceTypes={}", query, topK, sourceIds, sourceTypes);

        final var requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(topK);

        final var filterExpression = buildFilterExpression(sourceIds, sourceTypes);
        if (filterExpression != null) {
            requestBuilder.filterExpression(filterExpression);
        }

        final var results = vectorStore.similaritySearch(requestBuilder.build());
        log.debug("Search returned {} results", results.size());
        return results;
    }

    /**
     * Deletes all vector chunks associated with a given source.
     */
    public void deleteBySource(final UUID sourceId) {
        log.info("Deleting vectors for sourceId={}", sourceId);

        final var b = new FilterExpressionBuilder();
        final var expression = b.eq("source_id", sourceId.toString()).build();

        vectorStore.delete(expression);
        log.debug("Deletion complete for sourceId={}", sourceId);
    }

    private String buildContextualText(final String title, final SourceType sourceType,
                                       final ContentType contentType, final ContentFragment fragment) {
        final var sb = new StringBuilder();
        sb.append("[").append(sourceType.name()).append("] ");
        sb.append(title);
        sb.append(" [").append(contentType.name()).append("] ");
        if (fragment.getLocation() != null && !fragment.getLocation().isBlank()) {
            sb.append("(").append(fragment.getLocation()).append(") ");
        }
        sb.append("\n").append(fragment.getText());
        return sb.toString();
    }

    private Filter.Expression buildFilterExpression(final List<UUID> sourceIds,
                                                    final List<SourceType> sourceTypes) {
        final var b = new FilterExpressionBuilder();

        final var sourceIdOp = buildOrChain(b, "source_id", sourceIds, id -> id.toString());
        final var sourceTypeOp = buildOrChain(b, "source_type", sourceTypes, st -> st.name());

        if (sourceIdOp != null && sourceTypeOp != null) {
            return b.and(b.group(sourceIdOp), b.group(sourceTypeOp)).build();
        }
        if (sourceIdOp != null) {
            return sourceIdOp.build();
        }
        return sourceTypeOp != null ? sourceTypeOp.build() : null;
    }

    private <T> FilterExpressionBuilder.Op buildOrChain(final FilterExpressionBuilder b,
                                                         final String field,
                                                         final List<T> values,
                                                         final Function<T, String> mapper) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() == 1) {
            return b.eq(field, mapper.apply(values.getFirst()));
        }
        final var ops = values.stream()
                .map(v -> b.eq(field, mapper.apply(v)))
                .toList();
        var combined = ops.getFirst();
        for (var i = 1; i < ops.size(); i++) {
            combined = b.or(combined, ops.get(i));
        }
        return combined;
    }
}

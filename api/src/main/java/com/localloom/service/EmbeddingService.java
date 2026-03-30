package com.localloom.service;

import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.Map;
import java.util.UUID;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final VectorStore vectorStore;
    private final TokenTextSplitter textSplitter;

    public EmbeddingService(VectorStore vectorStore, TokenTextSplitter textSplitter) {
        this.vectorStore = vectorStore;
        this.textSplitter = textSplitter;
    }

    /**
     * Chunks each fragment's text, wraps chunks in Spring AI Documents with
     * metadata aligned to the ChromaDB schema (section 3.3), and stores them.
     */
    public void embedContent(UUID sourceId, UUID contentUnitId, String title,
                             SourceType sourceType, ContentType contentType,
                             List<ContentFragment> fragments) {

        log.debug("Embedding content for contentUnitId={} sourceId={} fragments={}",
                contentUnitId, sourceId, fragments.size());

        List<Document> allDocuments = new ArrayList<>();
        int chunkIndex = 0;

        for (ContentFragment fragment : fragments) {
            // Prefix the raw text with context so each chunk is self-contained
            String contextualText = buildContextualText(title, sourceType, contentType, fragment);

            // Split into overlapping token-sized chunks
            List<Document> rawChunks = textSplitter.apply(List.of(new Document(contextualText)));

            for (Document chunk : rawChunks) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("source_id", sourceId.toString());
                metadata.put("source_type", sourceType.name());
                metadata.put("content_unit_id", contentUnitId.toString());
                metadata.put("content_type", contentType.name());
                metadata.put("content_unit_title", title);
                metadata.put("location", fragment.getLocation());
                metadata.put("chunk_index", chunkIndex);

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
    public List<Document> search(String query, int topK,
                                 List<UUID> sourceIds, List<SourceType> sourceTypes) {

        log.debug("Searching query='{}' topK={} sourceIds={} sourceTypes={}", query, topK, sourceIds, sourceTypes);

        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(topK);

        Filter.Expression filterExpression = buildFilterExpression(sourceIds, sourceTypes);
        if (filterExpression != null) {
            requestBuilder.filterExpression(filterExpression);
        }

        List<Document> results = vectorStore.similaritySearch(requestBuilder.build());
        log.debug("Search returned {} results", results.size());
        return results;
    }

    /**
     * Deletes all vector chunks associated with a given source.
     */
    public void deleteBySource(UUID sourceId) {
        log.info("Deleting vectors for sourceId={}", sourceId);

        FilterExpressionBuilder b = new FilterExpressionBuilder();
        Filter.Expression expression = b.eq("source_id", sourceId.toString()).build();

        vectorStore.delete(expression);
        log.debug("Deletion complete for sourceId={}", sourceId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildContextualText(String title, SourceType sourceType,
                                       ContentType contentType, ContentFragment fragment) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(sourceType.name()).append("] ");
        sb.append(title);
        sb.append(" [").append(contentType.name()).append("] ");
        if (fragment.getLocation() != null && !fragment.getLocation().isBlank()) {
            sb.append("(").append(fragment.getLocation()).append(") ");
        }
        sb.append("\n").append(fragment.getText());
        return sb.toString();
    }

    /**
     * Builds a combined filter expression for sourceIds and sourceTypes.
     * Returns null when no filters are required so the caller can skip setting one.
     */
    private Filter.Expression buildFilterExpression(List<UUID> sourceIds, List<SourceType> sourceTypes) {
        FilterExpressionBuilder b = new FilterExpressionBuilder();

        Filter.Expression sourceIdFilter = null;
        if (sourceIds != null && !sourceIds.isEmpty()) {
            if (sourceIds.size() == 1) {
                sourceIdFilter = b.eq("source_id", sourceIds.get(0).toString()).build();
            } else {
                List<FilterExpressionBuilder.Op> ops = sourceIds.stream()
                        .map(id -> b.eq("source_id", id.toString()))
                        .toList();
                FilterExpressionBuilder.Op combined = ops.get(0);
                for (int i = 1; i < ops.size(); i++) {
                    combined = b.or(combined, ops.get(i));
                }
                sourceIdFilter = combined.build();
            }
        }

        Filter.Expression sourceTypeFilter = null;
        if (sourceTypes != null && !sourceTypes.isEmpty()) {
            if (sourceTypes.size() == 1) {
                sourceTypeFilter = b.eq("source_type", sourceTypes.get(0).name()).build();
            } else {
                List<FilterExpressionBuilder.Op> ops = sourceTypes.stream()
                        .map(st -> b.eq("source_type", st.name()))
                        .toList();
                FilterExpressionBuilder.Op combined = ops.get(0);
                for (int i = 1; i < ops.size(); i++) {
                    combined = b.or(combined, ops.get(i));
                }
                sourceTypeFilter = combined.build();
            }
        }

        if (sourceIdFilter != null && sourceTypeFilter != null) {
            return b.and(b.exp(sourceIdFilter), b.exp(sourceTypeFilter)).build();
        }
        if (sourceIdFilter != null) {
            return sourceIdFilter;
        }
        return sourceTypeFilter; // may be null — caller handles that
    }
}

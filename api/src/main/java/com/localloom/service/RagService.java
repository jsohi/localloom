package com.localloom.service;

import com.localloom.model.MessageRole;
import com.localloom.model.SourceType;
import com.localloom.repository.ConversationRepository;
import com.localloom.service.dto.Citation;
import com.localloom.service.dto.RagQuery;
import com.localloom.service.dto.RagResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class RagService {

  private static final Logger log = LogManager.getLogger(RagService.class);

  private final ChatClient chatClient;
  private final ChatModel chatModel;
  private final VectorStore vectorStore;
  private final ConversationRepository conversationRepository;
  private final int defaultTopK;

  public RagService(
      @Qualifier("ragChatClient") final ChatClient chatClient,
      final ChatModel chatModel,
      final VectorStore vectorStore,
      final ConversationRepository conversationRepository,
      @Value("${localloom.chat.top-k:5}") final int defaultTopK) {
    this.chatClient = chatClient;
    this.chatModel = chatModel;
    this.vectorStore = vectorStore;
    this.conversationRepository = conversationRepository;
    this.defaultTopK = defaultTopK;
  }

  /** Synchronous RAG query that returns the answer with source citations. */
  public RagResponse answer(final RagQuery query) {
    log.debug(
        "RAG query: question='{}' conversationId={}", query.question(), query.conversationId());

    var advisor = buildAdvisor(query);
    var promptSpec = chatClient.prompt().advisors(advisor).user(query.question());

    if (query.conversationId() != null) {
      var history = loadConversationHistory(query.conversationId());
      if (!history.isEmpty()) {
        promptSpec.messages(history);
      }
    }

    var clientResponse = promptSpec.call().chatClientResponse();
    var answer = clientResponse.chatResponse().getResult().getOutput().getText();
    var citations = extractCitations(clientResponse);

    log.debug("RAG response: citations={}", citations.size());
    return new RagResponse(answer, citations);
  }

  /** Streaming RAG query for real-time UI responses. */
  public Flux<String> streamAnswer(final RagQuery query) {
    log.debug(
        "RAG stream query: question='{}' conversationId={}",
        query.question(),
        query.conversationId());

    var advisor = buildAdvisor(query);
    var promptSpec = chatClient.prompt().advisors(advisor).user(query.question());

    if (query.conversationId() != null) {
      var history = loadConversationHistory(query.conversationId());
      if (!history.isEmpty()) {
        promptSpec.messages(history);
      }
    }

    return promptSpec.stream().content();
  }

  private RetrievalAugmentationAdvisor buildAdvisor(final RagQuery query) {
    var topK = query.topK() != null ? query.topK() : defaultTopK;

    var retrieverBuilder =
        VectorStoreDocumentRetriever.builder().vectorStore(vectorStore).topK(topK);

    var filterExpression = buildFilterExpression(query.sourceIds(), query.sourceTypes());
    if (filterExpression != null) {
      retrieverBuilder.filterExpression(filterExpression);
    }

    var queryTransformers = new ArrayList<QueryTransformer>();
    if (query.conversationId() != null) {
      queryTransformers.add(
          CompressionQueryTransformer.builder()
              .chatClientBuilder(ChatClient.builder(chatModel))
              .build());
    }

    var advisorBuilder =
        RetrievalAugmentationAdvisor.builder()
            .documentRetriever(retrieverBuilder.build())
            .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false).build());

    if (!queryTransformers.isEmpty()) {
      advisorBuilder.queryTransformers(queryTransformers);
    }

    return advisorBuilder.build();
  }

  private List<Message> loadConversationHistory(final UUID conversationId) {
    var conversation =
        conversationRepository
            .findById(conversationId)
            .orElseThrow(
                () -> new IllegalArgumentException("Conversation not found: " + conversationId));

    return conversation.getMessages().stream()
        .map(
            msg ->
                msg.getRole() == MessageRole.USER
                    ? (Message) new UserMessage(msg.getContent())
                    : (Message) new AssistantMessage(msg.getContent()))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private List<Citation> extractCitations(final ChatClientResponse clientResponse) {
    var context = clientResponse.context();
    if (context == null) {
      return List.of();
    }

    var documents = (List<Document>) context.get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
    if (documents == null || documents.isEmpty()) {
      return List.of();
    }

    return documents.stream()
        .map(
            doc -> {
              var meta = doc.getMetadata();
              return new Citation(
                  (String) meta.get("source_type"),
                  (String) meta.get("content_unit_title"),
                  (String) meta.get("location"),
                  (String) meta.get("source_id"),
                  (String) meta.get("content_unit_id"));
            })
        .distinct()
        .toList();
  }

  private Filter.Expression buildFilterExpression(
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

  private <T> FilterExpressionBuilder.Op buildOrChain(
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

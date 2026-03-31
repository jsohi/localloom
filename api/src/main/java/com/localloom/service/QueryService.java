package com.localloom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.Conversation;
import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import com.localloom.model.SourceType;
import com.localloom.repository.ConversationRepository;
import com.localloom.repository.MessageRepository;
import com.localloom.service.dto.Citation;
import com.localloom.service.dto.RagQuery;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class QueryService {

  private static final Logger log = LogManager.getLogger(QueryService.class);

  private final RagService ragService;
  private final ConversationRepository conversationRepository;
  private final MessageRepository messageRepository;
  private final ObjectMapper objectMapper;

  public QueryService(
      final RagService ragService,
      final ConversationRepository conversationRepository,
      final MessageRepository messageRepository,
      final ObjectMapper objectMapper) {
    this.ragService = ragService;
    this.conversationRepository = conversationRepository;
    this.messageRepository = messageRepository;
    this.objectMapper = objectMapper;
  }

  /**
   * Streams the RAG response as SSE events. Creates or loads a conversation, persists the user
   * message, streams tokens, then persists the full assistant response with citations.
   */
  public void streamQuery(
      final String question,
      final List<UUID> sourceIds,
      final List<SourceType> sourceTypes,
      final UUID conversationId,
      final SseEmitter emitter) {

    try {
      var conversation = resolveConversation(conversationId);
      persistUserMessage(conversation, question);

      var ragQuery = new RagQuery(question, conversation.getId(), sourceIds, sourceTypes, null);

      var tokenBuffer = new StringBuilder();

      ragService
          .streamAnswer(ragQuery)
          .doOnNext(
              token -> {
                tokenBuffer.append(token);
                sendEvent(emitter, "token", new TokenPayload(token));
              })
          .doOnComplete(
              () -> {
                var fullAnswer = tokenBuffer.toString();

                // Fetch citations via sync call for the same query
                var citations = fetchCitations(ragQuery);

                persistAssistantMessage(conversation, fullAnswer, citations);

                sendEvent(emitter, "sources", new SourcesPayload(citations));
                sendEvent(
                    emitter,
                    "done",
                    new DonePayload(conversation.getMessages().getLast().getId().toString()));

                emitter.complete();
              })
          .doOnError(
              error -> {
                log.error("Stream error for conversationId={}", conversation.getId(), error);
                emitter.completeWithError(error);
              })
          .subscribe();

    } catch (final Exception e) {
      log.error("Failed to start streaming query", e);
      emitter.completeWithError(e);
    }
  }

  /** Synchronous query fallback that returns the full response at once. */
  @Transactional
  public QueryResult query(
      final String question,
      final List<UUID> sourceIds,
      final List<SourceType> sourceTypes,
      final UUID conversationId) {

    var conversation = resolveConversation(conversationId);
    persistUserMessage(conversation, question);

    var ragQuery = new RagQuery(question, conversation.getId(), sourceIds, sourceTypes, null);
    var ragResponse = ragService.answer(ragQuery);

    persistAssistantMessage(conversation, ragResponse.answer(), ragResponse.citations());

    var messageId = conversation.getMessages().getLast().getId();
    return new QueryResult(
        ragResponse.answer(), ragResponse.citations(), conversation.getId(), messageId);
  }

  private Conversation resolveConversation(final UUID conversationId) {
    if (conversationId != null) {
      return conversationRepository
          .findById(conversationId)
          .orElseThrow(
              () -> new IllegalArgumentException("Conversation not found: " + conversationId));
    }
    var conversation = new Conversation();
    return conversationRepository.save(conversation);
  }

  private void persistUserMessage(final Conversation conversation, final String question) {
    var message = new Message();
    message.setRole(MessageRole.USER);
    message.setContent(question);
    conversation.addMessage(message);
    messageRepository.save(message);
  }

  private void persistAssistantMessage(
      final Conversation conversation, final String answer, final List<Citation> citations) {
    var message = new Message();
    message.setRole(MessageRole.ASSISTANT);
    message.setContent(answer);
    message.setSources(serializeCitations(citations));
    conversation.addMessage(message);
    messageRepository.save(message);
  }

  private List<Citation> fetchCitations(final RagQuery ragQuery) {
    try {
      var syncResponse = ragService.answer(ragQuery);
      return syncResponse.citations();
    } catch (final Exception e) {
      log.warn("Failed to fetch citations for streaming response", e);
      return List.of();
    }
  }

  private String serializeCitations(final List<Citation> citations) {
    if (citations == null || citations.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(citations);
    } catch (final Exception e) {
      log.warn("Failed to serialize citations", e);
      return null;
    }
  }

  private void sendEvent(final SseEmitter emitter, final String eventName, final Object data) {
    try {
      var json = objectMapper.writeValueAsString(data);
      emitter.send(SseEmitter.event().name(eventName).data(json));
    } catch (final Exception e) {
      log.warn("Failed to send SSE event: {}", eventName, e);
    }
  }

  public record TokenPayload(String content) {}

  public record SourcesPayload(List<Citation> sources) {}

  public record DonePayload(String messageId) {}

  public record QueryResult(
      String answer, List<Citation> citations, UUID conversationId, UUID messageId) {}
}

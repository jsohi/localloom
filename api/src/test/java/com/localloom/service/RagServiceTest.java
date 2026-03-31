package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.localloom.model.Conversation;
import com.localloom.model.MessageRole;
import com.localloom.model.SourceType;
import com.localloom.repository.ConversationRepository;
import com.localloom.service.dto.RagQuery;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.Filter.ExpressionType;

class RagServiceTest {

  private RagService ragService;
  private ConversationRepository conversationRepository;

  @BeforeEach
  void setUp() {
    var chatModel = stubChatModel();
    var chatClient = ChatClient.builder(chatModel).build();
    var vectorStore = mock(VectorStore.class);
    conversationRepository = mock(ConversationRepository.class);
    ragService = new RagService(chatClient, chatModel, vectorStore, conversationRepository, 5);
  }

  @Test
  void buildFilterExpressionWithSingleSourceId() throws Exception {
    var id = UUID.randomUUID();
    var expression = invokeBuildFilterExpression(List.of(id), null);
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.EQ);
    var key = (Filter.Key) expression.left();
    assertThat(key.key()).isEqualTo("source_id");
    var value = (Filter.Value) expression.right();
    assertThat(value.value()).isEqualTo(id.toString());
  }

  @Test
  void buildFilterExpressionWithMultipleSourceIds() throws Exception {
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var expression = invokeBuildFilterExpression(List.of(id1, id2), null);
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.OR);
    assertThat(expression.toString()).contains(id1.toString()).contains(id2.toString());
  }

  @Test
  void buildFilterExpressionWithSourceType() throws Exception {
    var expression = invokeBuildFilterExpression(null, List.of(SourceType.PODCAST));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.EQ);
    var key = (Filter.Key) expression.left();
    assertThat(key.key()).isEqualTo("source_type");
    var value = (Filter.Value) expression.right();
    assertThat(value.value()).isEqualTo("PODCAST");
  }

  @Test
  void buildFilterExpressionWithCombinedFilters() throws Exception {
    var id = UUID.randomUUID();
    var expression = invokeBuildFilterExpression(List.of(id), List.of(SourceType.CONFLUENCE));
    assertThat(expression).isNotNull();
    assertThat(expression.type()).isEqualTo(ExpressionType.AND);
    assertThat(expression.toString()).contains("source_id").contains("source_type");
  }

  @Test
  void buildFilterExpressionWithNullInputsReturnsNull() throws Exception {
    var expression = invokeBuildFilterExpression(null, null);
    assertThat(expression).isNull();
  }

  @Test
  void buildFilterExpressionWithEmptyListsReturnsNull() throws Exception {
    var expression = invokeBuildFilterExpression(Collections.emptyList(), Collections.emptyList());
    assertThat(expression).isNull();
  }

  @Test
  void extractCitationsFromDocuments() throws Exception {
    var doc =
        new Document(
            "test content",
            java.util.Map.of(
                "source_type", "PODCAST",
                "content_unit_title", "Episode 1",
                "location", "timestamp:00:05:00",
                "source_id", UUID.randomUUID().toString(),
                "content_unit_id", UUID.randomUUID().toString()));

    var context =
        java.util.Map.<String, Object>of(
            RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of(doc));
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Test response"))));
    var clientResponse = new ChatClientResponse(chatResponse, context);

    Method method =
        RagService.class.getDeclaredMethod("extractCitations", ChatClientResponse.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    var citations =
        (List<com.localloom.service.dto.Citation>) method.invoke(ragService, clientResponse);

    assertThat(citations).hasSize(1);
    assertThat(citations.getFirst().sourceType()).isEqualTo("PODCAST");
    assertThat(citations.getFirst().contentUnitTitle()).isEqualTo("Episode 1");
    assertThat(citations.getFirst().location()).isEqualTo("timestamp:00:05:00");
  }

  @Test
  void extractCitationsDeduplicates() throws Exception {
    var sourceId = UUID.randomUUID().toString();
    var contentUnitId = UUID.randomUUID().toString();
    var metadata =
        java.util.Map.<String, Object>of(
            "source_type", "PODCAST",
            "content_unit_title", "Episode 1",
            "location", "timestamp:00:05:00",
            "source_id", sourceId,
            "content_unit_id", contentUnitId);

    var doc1 = new Document("chunk 1", metadata);
    var doc2 = new Document("chunk 2", metadata);

    var context =
        java.util.Map.<String, Object>of(
            RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT, List.of(doc1, doc2));
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Test response"))));
    var clientResponse = new ChatClientResponse(chatResponse, context);

    Method method =
        RagService.class.getDeclaredMethod("extractCitations", ChatClientResponse.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    var citations =
        (List<com.localloom.service.dto.Citation>) method.invoke(ragService, clientResponse);

    assertThat(citations).hasSize(1);
  }

  @Test
  void extractCitationsWithEmptyContextReturnsEmpty() throws Exception {
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("Test response"))));
    var clientResponse = new ChatClientResponse(chatResponse, java.util.Map.of());

    Method method =
        RagService.class.getDeclaredMethod("extractCitations", ChatClientResponse.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    var citations =
        (List<com.localloom.service.dto.Citation>) method.invoke(ragService, clientResponse);

    assertThat(citations).isEmpty();
  }

  @Test
  void loadConversationHistoryThrowsWhenNotFound() {
    var id = UUID.randomUUID();
    when(conversationRepository.findById(id)).thenReturn(Optional.empty());

    var query = new RagQuery("test question", id, null, null, null);
    assertThatThrownBy(() -> ragService.answer(query))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Conversation not found");
  }

  @Test
  void loadConversationHistoryConvertsMessages() throws Exception {
    var conversationId = UUID.randomUUID();
    var conversation = new Conversation();
    conversation.setId(conversationId);

    var userMsg = new com.localloom.model.Message();
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent("What is RAG?");
    conversation.addMessage(userMsg);

    var assistantMsg = new com.localloom.model.Message();
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setContent("RAG is Retrieval Augmented Generation.");
    conversation.addMessage(assistantMsg);

    when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));

    Method method = RagService.class.getDeclaredMethod("loadConversationHistory", UUID.class);
    method.setAccessible(true);
    @SuppressWarnings("unchecked")
    var history =
        (List<org.springframework.ai.chat.messages.Message>)
            method.invoke(ragService, conversationId);

    assertThat(history).hasSize(2);
    assertThat(history.get(0)).isInstanceOf(org.springframework.ai.chat.messages.UserMessage.class);
    assertThat(history.get(1))
        .isInstanceOf(org.springframework.ai.chat.messages.AssistantMessage.class);
  }

  private Filter.Expression invokeBuildFilterExpression(
      final List<UUID> sourceIds, final List<SourceType> sourceTypes) throws Exception {
    Method method =
        RagService.class.getDeclaredMethod("buildFilterExpression", List.class, List.class);
    method.setAccessible(true);
    return (Filter.Expression) method.invoke(ragService, sourceIds, sourceTypes);
  }

  private static ChatModel stubChatModel() {
    return new ChatModel() {
      @Override
      public ChatResponse call(final Prompt prompt) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("Test response"))));
      }
    };
  }
}

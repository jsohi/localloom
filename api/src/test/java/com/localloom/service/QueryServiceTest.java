package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localloom.model.Conversation;
import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import com.localloom.repository.ConversationRepository;
import com.localloom.repository.MessageRepository;
import com.localloom.service.dto.Citation;
import com.localloom.service.dto.RagQuery;
import com.localloom.service.dto.RagResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QueryServiceTest {

  private QueryService queryService;
  private RagService ragService;
  private ConversationRepository conversationRepository;
  private MessageRepository messageRepository;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    ragService = mock(RagService.class);
    conversationRepository = mock(ConversationRepository.class);
    messageRepository = mock(MessageRepository.class);
    objectMapper = new ObjectMapper();
    queryService =
        new QueryService(ragService, conversationRepository, messageRepository, objectMapper);
  }

  @Test
  void queryCreatesNewConversationWhenNoIdProvided() {
    var conversation = createConversation();
    when(conversationRepository.save(any(Conversation.class))).thenReturn(conversation);
    when(messageRepository.save(any(Message.class)))
        .thenAnswer(
            invocation -> {
              var msg = invocation.getArgument(0, Message.class);
              msg.setId(UUID.randomUUID());
              return msg;
            });
    when(ragService.answer(any(RagQuery.class)))
        .thenReturn(new RagResponse("test answer", List.of()));

    var result = queryService.query("test question", null, null, null);

    assertThat(result.answer()).isEqualTo("test answer");
    assertThat(result.conversationId()).isEqualTo(conversation.getId());
    verify(conversationRepository).save(any(Conversation.class));
  }

  @Test
  void queryUsesExistingConversationWhenIdProvided() {
    var conversationId = UUID.randomUUID();
    var conversation = createConversation();
    conversation.setId(conversationId);
    when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversation));
    when(messageRepository.save(any(Message.class)))
        .thenAnswer(
            invocation -> {
              var msg = invocation.getArgument(0, Message.class);
              msg.setId(UUID.randomUUID());
              return msg;
            });
    when(ragService.answer(any(RagQuery.class)))
        .thenReturn(new RagResponse("test answer", List.of()));

    var result = queryService.query("test question", null, null, conversationId);

    assertThat(result.conversationId()).isEqualTo(conversationId);
    verify(conversationRepository, never()).save(any(Conversation.class));
  }

  @Test
  void queryThrowsWhenConversationNotFound() {
    var conversationId = UUID.randomUUID();
    when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> queryService.query("test question", null, null, conversationId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Conversation not found");
  }

  @Test
  void queryPersistsUserAndAssistantMessages() {
    var conversation = createConversation();
    when(conversationRepository.save(any(Conversation.class))).thenReturn(conversation);
    var messageCaptor = ArgumentCaptor.forClass(Message.class);
    when(messageRepository.save(any(Message.class)))
        .thenAnswer(
            invocation -> {
              var msg = invocation.getArgument(0, Message.class);
              msg.setId(UUID.randomUUID());
              return msg;
            });

    var citations =
        List.of(new Citation("PODCAST", "Episode 1", "timestamp:00:05:00", "s1", "cu1"));
    when(ragService.answer(any(RagQuery.class)))
        .thenReturn(new RagResponse("test answer", citations));

    queryService.query("test question", null, null, null);

    verify(messageRepository, org.mockito.Mockito.times(2)).save(messageCaptor.capture());
    var savedMessages = messageCaptor.getAllValues();

    assertThat(savedMessages.get(0).getRole()).isEqualTo(MessageRole.USER);
    assertThat(savedMessages.get(0).getContent()).isEqualTo("test question");

    assertThat(savedMessages.get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(savedMessages.get(1).getContent()).isEqualTo("test answer");
    assertThat(savedMessages.get(1).getSources()).isNotNull();
  }

  @Test
  void queryPassesSourceFiltersToRagService() {
    var conversation = createConversation();
    when(conversationRepository.save(any(Conversation.class))).thenReturn(conversation);
    when(messageRepository.save(any(Message.class)))
        .thenAnswer(
            invocation -> {
              var msg = invocation.getArgument(0, Message.class);
              msg.setId(UUID.randomUUID());
              return msg;
            });

    var queryCaptor = ArgumentCaptor.forClass(RagQuery.class);
    when(ragService.answer(queryCaptor.capture()))
        .thenReturn(new RagResponse("test answer", List.of()));

    var sourceIds = List.of(UUID.randomUUID());
    var sourceTypes = List.of(com.localloom.model.SourceType.PODCAST);
    queryService.query("test question", sourceIds, sourceTypes, null);

    var capturedQuery = queryCaptor.getValue();
    assertThat(capturedQuery.sourceIds()).isEqualTo(sourceIds);
    assertThat(capturedQuery.sourceTypes()).isEqualTo(sourceTypes);
  }

  @Test
  void queryReturnsMessageId() {
    var conversation = createConversation();
    when(conversationRepository.save(any(Conversation.class))).thenReturn(conversation);
    when(messageRepository.save(any(Message.class)))
        .thenAnswer(
            invocation -> {
              var msg = invocation.getArgument(0, Message.class);
              msg.setId(UUID.randomUUID());
              return msg;
            });
    when(ragService.answer(any(RagQuery.class)))
        .thenReturn(new RagResponse("test answer", List.of()));

    var result = queryService.query("test question", null, null, null);

    assertThat(result.messageId()).isNotNull();
  }

  private static Conversation createConversation() {
    var conversation = new Conversation();
    conversation.setId(UUID.randomUUID());
    return conversation;
  }
}

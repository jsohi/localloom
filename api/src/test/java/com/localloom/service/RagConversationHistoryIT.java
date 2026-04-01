package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.Conversation;
import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import com.localloom.model.SourceType;
import com.localloom.repository.ConversationRepository;
import com.localloom.repository.MessageRepository;
import com.localloom.service.dto.RagQuery;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RagConversationHistoryIT {

  @Autowired private RagService ragService;
  @Autowired private EmbeddingService embeddingService;
  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;

  @BeforeEach
  void cleanDatabase() {
    messageRepository.deleteAllInBatch();
    conversationRepository.deleteAllInBatch();
  }

  @Test
  void answerWithConversationHistory() {
    var sourceId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText(
        "Docker containers package applications with their dependencies for consistent"
            + " deployment across environments.");
    fragment.setLocation("timestamp:00:05:00");

    embeddingService.embedContent(
        sourceId,
        UUID.randomUUID(),
        "DevOps Podcast",
        SourceType.MEDIA,
        ContentType.AUDIO,
        List.of(fragment));

    // Create conversation with history
    var conversation = new Conversation();
    conversation.setTitle("Docker discussion");

    var userMsg = new Message();
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent("What is containerization?");
    conversation.addMessage(userMsg);

    var assistantMsg = new Message();
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setContent("Containerization is a form of virtualization.");
    conversation.addMessage(assistantMsg);

    conversation = conversationRepository.save(conversation);

    // Query with conversation context
    var query =
        new RagQuery("Tell me more about Docker", conversation.getId(), List.of(sourceId), null, 5);
    var response = ragService.answer(query);

    assertThat(response.answer()).isNotBlank();
  }

  @Test
  void answerWithEmptyConversation() {
    var sourceId = UUID.randomUUID();

    var fragment = new ContentFragment();
    fragment.setText("Microservices are small, independently deployable services.");
    fragment.setLocation("timestamp:00:02:00");

    embeddingService.embedContent(
        sourceId,
        UUID.randomUUID(),
        "Architecture Podcast",
        SourceType.MEDIA,
        ContentType.AUDIO,
        List.of(fragment));

    // Conversation with no messages
    var conversation = new Conversation();
    conversation.setTitle("Empty conversation");
    conversation = conversationRepository.save(conversation);

    var query =
        new RagQuery("What are microservices?", conversation.getId(), List.of(sourceId), null, 5);
    var response = ragService.answer(query);

    assertThat(response.answer()).isNotBlank();
  }

  @Test
  void answerWithNonExistentConversationThrows() {
    var query = new RagQuery("test", UUID.randomUUID(), null, null, null);
    assertThatThrownBy(() -> ragService.answer(query))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Conversation not found");
  }
}

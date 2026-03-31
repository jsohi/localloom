package com.localloom.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.TestcontainersConfig;
import com.localloom.model.Conversation;
import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ConversationRepositoryIT {

  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;

  @BeforeEach
  void cleanDatabase() {
    messageRepository.deleteAllInBatch();
    conversationRepository.deleteAllInBatch();
  }

  @Test
  void conversationCrud() {
    var conversation = new Conversation();
    conversation.setTitle("Test Chat");
    conversation = conversationRepository.save(conversation);

    assertThat(conversation.getId()).isNotNull();
    assertThat(conversation.getCreatedAt()).isNotNull();
    assertThat(conversation.getUpdatedAt()).isNotNull();
    assertThat(conversation.getTitle()).isEqualTo("Test Chat");

    var found = conversationRepository.findById(conversation.getId());
    assertThat(found).isPresent();
    assertThat(found.get().getTitle()).isEqualTo("Test Chat");

    conversationRepository.deleteById(conversation.getId());
    assertThat(conversationRepository.findById(conversation.getId())).isEmpty();
  }

  @Test
  void messageAddAndRetrieve() {
    var conversation = new Conversation();
    conversation.setTitle("Message Test");
    conversation = conversationRepository.save(conversation);

    var userMsg = new Message();
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent("Hello, what is RAG?");
    conversation.addMessage(userMsg);

    var assistantMsg = new Message();
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setContent("RAG is Retrieval Augmented Generation.");
    conversation.addMessage(assistantMsg);

    conversationRepository.save(conversation);
    conversationRepository.flush();

    var found = conversationRepository.findById(conversation.getId()).orElseThrow();
    assertThat(found.getMessages()).hasSize(2);
    assertThat(found.getMessages().get(0).getRole()).isEqualTo(MessageRole.USER);
    assertThat(found.getMessages().get(1).getRole()).isEqualTo(MessageRole.ASSISTANT);
  }

  @Test
  void cascadeDeleteRemovesMessages() {
    var conversation = new Conversation();
    conversation.setTitle("Cascade Test");

    var msg = new Message();
    msg.setRole(MessageRole.USER);
    msg.setContent("Will be deleted");
    conversation.addMessage(msg);

    conversation = conversationRepository.save(conversation);
    conversationRepository.flush();

    assertThat(messageRepository.count()).isEqualTo(1);

    conversationRepository.deleteById(conversation.getId());
    conversationRepository.flush();

    assertThat(messageRepository.count()).isZero();
  }

  @Test
  void messagesOrderedByCreatedAt() throws Exception {
    var conversation = new Conversation();
    conversation.setTitle("Ordering Test");
    conversation = conversationRepository.save(conversation);

    // Add messages with small delays to ensure distinct createdAt
    var msg1 = new Message();
    msg1.setRole(MessageRole.USER);
    msg1.setContent("First message");
    conversation.addMessage(msg1);
    conversationRepository.saveAndFlush(conversation);

    Thread.sleep(50);

    var msg2 = new Message();
    msg2.setRole(MessageRole.ASSISTANT);
    msg2.setContent("Second message");
    conversation.addMessage(msg2);
    conversationRepository.saveAndFlush(conversation);

    Thread.sleep(50);

    var msg3 = new Message();
    msg3.setRole(MessageRole.USER);
    msg3.setContent("Third message");
    conversation.addMessage(msg3);
    conversationRepository.saveAndFlush(conversation);

    var found = conversationRepository.findById(conversation.getId()).orElseThrow();
    assertThat(found.getMessages()).hasSize(3);
    assertThat(found.getMessages().get(0).getContent()).isEqualTo("First message");
    assertThat(found.getMessages().get(1).getContent()).isEqualTo("Second message");
    assertThat(found.getMessages().get(2).getContent()).isEqualTo("Third message");
  }

  @Test
  void messageWithJsonbSources() {
    var conversation = new Conversation();
    conversation.setTitle("JSONB Test");

    var msg = new Message();
    msg.setRole(MessageRole.ASSISTANT);
    msg.setContent("Answer with sources");
    msg.setSources("[{\"sourceType\":\"PODCAST\",\"title\":\"Episode 1\"}]");
    conversation.addMessage(msg);

    conversation = conversationRepository.save(conversation);
    conversationRepository.flush();

    var found = conversationRepository.findById(conversation.getId()).orElseThrow();
    var savedMsg = found.getMessages().getFirst();
    assertThat(savedMsg.getSources()).contains("PODCAST").contains("Episode 1");
  }

  @Test
  void orphanRemovalDeletesMessage() {
    var conversation = new Conversation();
    conversation.setTitle("Orphan Test");

    var msg = new Message();
    msg.setRole(MessageRole.USER);
    msg.setContent("Will be orphaned");
    conversation.addMessage(msg);

    conversation = conversationRepository.save(conversation);
    conversationRepository.flush();
    assertThat(messageRepository.count()).isEqualTo(1);

    conversation.removeMessage(conversation.getMessages().getFirst());
    conversationRepository.saveAndFlush(conversation);

    assertThat(messageRepository.count()).isZero();
  }
}

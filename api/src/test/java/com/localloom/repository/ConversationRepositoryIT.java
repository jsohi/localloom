package com.localloom.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.TestcontainersConfig;
import com.localloom.model.Conversation;
import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
@Transactional
class ConversationRepositoryIT {

  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;
  @Autowired private EntityManager em;

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
    em.flush();
    em.clear();
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
    em.flush();
    em.clear();

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
    var conversationId = conversation.getId();
    em.flush();
    em.clear();

    var found = conversationRepository.findByIdWithMessages(conversationId).orElseThrow();
    assertThat(found.getMessages()).hasSize(1);

    conversationRepository.deleteById(conversationId);
    em.flush();
    em.clear();

    assertThat(conversationRepository.findById(conversationId)).isEmpty();
  }

  @Test
  void messagesOrderedByCreatedAt() throws Exception {
    var conversation = new Conversation();
    conversation.setTitle("Ordering Test");
    conversation = conversationRepository.save(conversation);

    // Delays ensure distinct createdAt values for @OrderBy("createdAt ASC") verification
    var msg1 = new Message();
    msg1.setRole(MessageRole.USER);
    msg1.setContent("First message");
    conversation.addMessage(msg1);
    em.flush();

    Thread.sleep(10);

    var msg2 = new Message();
    msg2.setRole(MessageRole.ASSISTANT);
    msg2.setContent("Second message");
    conversation.addMessage(msg2);
    em.flush();

    Thread.sleep(10);

    var msg3 = new Message();
    msg3.setRole(MessageRole.USER);
    msg3.setContent("Third message");
    conversation.addMessage(msg3);
    em.flush();
    em.clear();

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
    em.flush();
    em.clear();

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
    var conversationId = conversation.getId();
    em.flush();
    em.clear();

    var found = conversationRepository.findByIdWithMessages(conversationId).orElseThrow();
    assertThat(found.getMessages()).hasSize(1);

    // Re-fetch to get managed entity
    found.removeMessage(found.getMessages().getFirst());
    em.flush();
    em.clear();

    var afterRemoval = conversationRepository.findByIdWithMessages(conversationId).orElseThrow();
    assertThat(afterRemoval.getMessages()).isEmpty();
  }
}

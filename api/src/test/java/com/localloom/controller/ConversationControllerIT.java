package com.localloom.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.model.Conversation;
import com.localloom.model.Message;
import com.localloom.model.MessageRole;
import com.localloom.repository.ConversationRepository;
import com.localloom.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ConversationControllerIT {

  private MockMvc mockMvc;
  @Autowired private WebApplicationContext webApplicationContext;
  @Autowired private ConversationRepository conversationRepository;
  @Autowired private MessageRepository messageRepository;

  @BeforeEach
  void setUp() {
    messageRepository.deleteAllInBatch();
    conversationRepository.deleteAllInBatch();
    mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
  }

  @Test
  void listConversationsEmpty() throws Exception {
    mockMvc
        .perform(get("/api/v1/conversations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void listConversationsOrderedByUpdatedAt() throws Exception {
    var older = new Conversation();
    older.setTitle("Older Chat");
    conversationRepository.save(older);

    Thread.sleep(10);

    var newer = new Conversation();
    newer.setTitle("Newer Chat");
    conversationRepository.save(newer);

    mockMvc
        .perform(get("/api/v1/conversations"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].title").value("Newer Chat"))
        .andExpect(jsonPath("$[1].title").value("Older Chat"));
  }

  @Test
  void getConversationWithMessages() throws Exception {
    var conversation = new Conversation();
    conversation.setTitle("Chat with Messages");

    var userMsg = new Message();
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent("Hello");
    conversation.addMessage(userMsg);

    var assistantMsg = new Message();
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setContent("Hi there!");
    conversation.addMessage(assistantMsg);

    conversation = conversationRepository.save(conversation);

    mockMvc
        .perform(get("/api/v1/conversations/" + conversation.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Chat with Messages"))
        .andExpect(jsonPath("$.messages.length()").value(2))
        .andExpect(jsonPath("$.messages[0].role").value("USER"))
        .andExpect(jsonPath("$.messages[0].content").value("Hello"))
        .andExpect(jsonPath("$.messages[1].role").value("ASSISTANT"))
        .andExpect(jsonPath("$.messages[1].content").value("Hi there!"));
  }

  @Test
  void getConversationReturns404ForMissing() throws Exception {
    mockMvc
        .perform(get("/api/v1/conversations/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound());
  }

  @Test
  void deleteConversation() throws Exception {
    var conversation = new Conversation();
    conversation.setTitle("To Delete");

    var msg = new Message();
    msg.setRole(MessageRole.USER);
    msg.setContent("Will be deleted");
    conversation.addMessage(msg);

    conversation = conversationRepository.save(conversation);

    mockMvc
        .perform(delete("/api/v1/conversations/" + conversation.getId()))
        .andExpect(status().isNoContent());

    assertThat(conversationRepository.findById(conversation.getId())).isEmpty();
    assertThat(messageRepository.count()).isZero();
  }

  @Test
  void deleteConversationReturns404ForMissing() throws Exception {
    mockMvc
        .perform(delete("/api/v1/conversations/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateTitle() throws Exception {
    var conversation = new Conversation();
    conversation.setTitle("Original Title");
    conversation = conversationRepository.save(conversation);

    mockMvc
        .perform(
            patch("/api/v1/conversations/" + conversation.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title": "Updated Title"}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Updated Title"));

    var updated = conversationRepository.findById(conversation.getId()).orElseThrow();
    assertThat(updated.getTitle()).isEqualTo("Updated Title");
  }
}

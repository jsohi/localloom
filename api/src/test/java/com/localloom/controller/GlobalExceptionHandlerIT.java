package com.localloom.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.localloom.TestcontainersConfig;
import com.localloom.model.Conversation;
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
class GlobalExceptionHandlerIT {

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
  void notFoundReturnsErrorResponseJson() throws Exception {
    mockMvc
        .perform(get("/api/v1/conversations/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void badRequestReturnsErrorResponseJson() throws Exception {
    var conversation = new Conversation();
    conversation.setTitle("Test");
    conversation = conversationRepository.save(conversation);

    mockMvc
        .perform(
            patch("/api/v1/conversations/" + conversation.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"title": "  "}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message").exists())
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void deleteNotFoundReturnsErrorResponseJson() throws Exception {
    mockMvc
        .perform(delete("/api/v1/conversations/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.status").value(404))
        .andExpect(
            jsonPath("$.message")
                .value("Conversation not found: 00000000-0000-0000-0000-000000000099"))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void errorResponseContainsAllRequiredFields() throws Exception {
    var result =
        mockMvc
            .perform(get("/api/v1/conversations/00000000-0000-0000-0000-000000000099"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").isNumber())
            .andExpect(jsonPath("$.message").isString())
            .andExpect(jsonPath("$.timestamp").isString())
            .andReturn();

    var contentType = result.getResponse().getContentType();
    assertThat(contentType).isNotNull().contains("application/json");
  }

  @Test
  void notFoundMessageContainsEntityInfo() throws Exception {
    mockMvc
        .perform(get("/api/v1/conversations/00000000-0000-0000-0000-000000000099"))
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.message")
                .value("Conversation not found: 00000000-0000-0000-0000-000000000099"));
  }
}

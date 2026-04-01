package com.localloom.controller;

import com.localloom.model.Conversation;
import com.localloom.repository.ConversationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {

  private static final Logger log = LogManager.getLogger(ConversationController.class);

  public record UpdateTitleRequest(String title) {}

  public record ConversationSummary(UUID id, String title, Instant createdAt, Instant updatedAt) {}

  private final ConversationRepository conversationRepository;

  public ConversationController(final ConversationRepository conversationRepository) {
    this.conversationRepository = conversationRepository;
  }

  @GetMapping
  public List<ConversationSummary> listConversations() {
    return conversationRepository.findAll(Sort.by(Sort.Direction.DESC, "updatedAt")).stream()
        .map(
            c ->
                new ConversationSummary(
                    c.getId(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt()))
        .toList();
  }

  @GetMapping("/{id}")
  public Map<String, Object> getConversation(@PathVariable final UUID id) {
    final var conversation =
        conversationRepository
            .findByIdWithMessages(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversation not found: " + id));
    return Map.of(
        "id", conversation.getId(),
        "title", conversation.getTitle() != null ? conversation.getTitle() : "",
        "createdAt", conversation.getCreatedAt(),
        "updatedAt", conversation.getUpdatedAt(),
        "messages", conversation.getMessages());
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteConversation(@PathVariable final UUID id) {
    var conversation =
        conversationRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversation not found: " + id));
    conversationRepository.delete(conversation);
    log.info("Deleted conversation: {}", id);
    return ResponseEntity.noContent().build();
  }

  @PatchMapping("/{id}")
  public Conversation updateTitle(
      @PathVariable final UUID id, @RequestBody final UpdateTitleRequest request) {
    if (request.title() == null || request.title().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Title must not be blank");
    }
    var conversation =
        conversationRepository
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Conversation not found: " + id));
    conversation.setTitle(request.title());
    return conversationRepository.save(conversation);
  }
}

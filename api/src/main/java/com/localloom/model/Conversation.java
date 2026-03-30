package com.localloom.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  private String title;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true)
  private List<Message> messages = new ArrayList<>();

  @PrePersist
  void prePersist() {
    final var now = Instant.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(final UUID id) {
    this.id = id;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(final String title) {
    this.title = title;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(final Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public List<Message> getMessages() {
    return messages;
  }

  public void setMessages(final List<Message> messages) {
    this.messages = messages;
  }

  public void addMessage(final Message message) {
    messages.add(message);
    message.setConversation(this);
  }

  public void removeMessage(final Message message) {
    messages.remove(message);
    message.setConversation(null);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof Conversation other)) return false;
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}

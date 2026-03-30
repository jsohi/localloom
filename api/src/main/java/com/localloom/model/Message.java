package com.localloom.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "messages")
public class Message {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "conversation_id", nullable = false)
  private Conversation conversation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private MessageRole role;

  @Column(name = "content", columnDefinition = "text", nullable = false)
  private String content;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private String sources;

  @Column(name = "audio_path")
  private String audioPath;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public void setId(final UUID id) {
    this.id = id;
  }

  public Conversation getConversation() {
    return conversation;
  }

  public void setConversation(final Conversation conversation) {
    this.conversation = conversation;
  }

  public MessageRole getRole() {
    return role;
  }

  public void setRole(final MessageRole role) {
    this.role = role;
  }

  public String getContent() {
    return content;
  }

  public void setContent(final String content) {
    this.content = content;
  }

  public String getSources() {
    return sources;
  }

  public void setSources(final String sources) {
    this.sources = sources;
  }

  public String getAudioPath() {
    return audioPath;
  }

  public void setAudioPath(final String audioPath) {
    this.audioPath = audioPath;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) return true;
    if (!(o instanceof Message other)) return false;
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}

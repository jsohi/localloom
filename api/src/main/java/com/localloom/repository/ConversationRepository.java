package com.localloom.repository;

import com.localloom.model.Conversation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

  @Query("SELECT c FROM Conversation c LEFT JOIN FETCH c.messages WHERE c.id = :id")
  Optional<Conversation> findByIdWithMessages(final UUID id);
}

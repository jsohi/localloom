package com.localloom.service.dto;

import com.localloom.model.SourceType;
import java.util.List;
import java.util.UUID;

public record RagQuery(
    String question,
    UUID conversationId,
    List<UUID> sourceIds,
    List<SourceType> sourceTypes,
    Integer topK) {}

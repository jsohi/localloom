package com.localloom.controller.dto;

import com.localloom.model.SourceType;
import java.util.List;
import java.util.UUID;

public record QueryRequest(
    String question, List<UUID> sourceIds, List<SourceType> sourceTypes, UUID conversationId) {}

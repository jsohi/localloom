package com.localloom.service.dto;

import java.time.Instant;

public record ResolvedEpisode(
    String title,
    String description,
    String audioUrl,
    Instant publishedAt,
    Integer durationSeconds,
    String externalId) {}

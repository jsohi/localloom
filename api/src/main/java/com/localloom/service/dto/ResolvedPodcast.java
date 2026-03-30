package com.localloom.service.dto;

import com.localloom.model.SourceType;

import java.util.List;

public record ResolvedPodcast(
        String title,
        String author,
        String description,
        String artworkUrl,
        String feedUrl,
        String sourceUrl,
        SourceType sourceType,
        List<ResolvedEpisode> episodes
) {}

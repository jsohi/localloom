package com.localloom.service.dto;

public record Citation(
    String sourceType,
    String contentUnitTitle,
    String location,
    String sourceId,
    String contentUnitId) {}

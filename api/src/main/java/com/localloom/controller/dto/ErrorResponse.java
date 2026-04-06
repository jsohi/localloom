package com.localloom.controller.dto;

import java.time.Instant;

public record ErrorResponse(int status, String message, Instant timestamp, String requestId) {}

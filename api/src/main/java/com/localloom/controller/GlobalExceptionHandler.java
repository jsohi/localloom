package com.localloom.controller;

import com.localloom.controller.dto.ErrorResponse;
import java.time.Instant;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(final ResponseStatusException ex) {
    var status = HttpStatus.valueOf(ex.getStatusCode().value());
    var body = new ErrorResponse(status.value(), ex.getReason(), Instant.now());
    return ResponseEntity.status(status).body(body);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(final IllegalArgumentException ex) {
    log.warn("Bad request: {}", ex.getMessage());
    var body = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), Instant.now());
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(final MethodArgumentNotValidException ex) {
    var message =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("Validation failed");
    log.warn("Validation error: {}", message);
    var body = new ErrorResponse(HttpStatus.BAD_REQUEST.value(), message, Instant.now());
    return ResponseEntity.badRequest().body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneric(final Exception ex) {
    log.error("Unexpected error", ex);
    var body =
        new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(), "Internal server error", Instant.now());
    return ResponseEntity.internalServerError().body(body);
  }
}

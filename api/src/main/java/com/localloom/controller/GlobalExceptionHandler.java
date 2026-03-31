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
    return ResponseEntity.status(status).body(errorResponse(status, ex.getReason()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(final IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(errorResponse(HttpStatus.BAD_REQUEST, ex.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> handleValidation(final MethodArgumentNotValidException ex) {
    var message =
        String.join(
            "; ",
            ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList());
    if (message.isBlank()) {
      message = "Validation failed";
    }
    return ResponseEntity.badRequest().body(errorResponse(HttpStatus.BAD_REQUEST, message));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneric(final Exception ex) {
    log.error("Unexpected error", ex);
    return ResponseEntity.internalServerError()
        .body(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error"));
  }

  private static ErrorResponse errorResponse(final HttpStatus status, final String message) {
    return new ErrorResponse(status.value(), message, Instant.now());
  }
}

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
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(final ResponseStatusException ex) {
    var statusCode = ex.getStatusCode();
    var reason =
        ex.getReason() != null
            ? ex.getReason()
            : (statusCode instanceof HttpStatus hs ? hs.getReasonPhrase() : "Unknown error");
    return ResponseEntity.status(statusCode).body(errorResponse(statusCode.value(), reason));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(final IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(errorResponse(400, ex.getMessage()));
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
    return ResponseEntity.badRequest().body(errorResponse(400, message));
  }

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ErrorResponse> handleNoResource(final NoResourceFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse(404, ex.getMessage()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneric(final Exception ex) {
    log.error("Unexpected error", ex);
    return ResponseEntity.internalServerError().body(errorResponse(500, "Internal server error"));
  }

  private static ErrorResponse errorResponse(final int status, final String message) {
    return new ErrorResponse(status, message, Instant.now());
  }
}

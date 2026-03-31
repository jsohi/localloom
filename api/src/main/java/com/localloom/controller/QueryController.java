package com.localloom.controller;

import com.localloom.controller.dto.QueryRequest;
import com.localloom.service.QueryService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/query")
public class QueryController {

  private static final Logger log = LogManager.getLogger(QueryController.class);
  private static final long SSE_TIMEOUT_MS = 120_000L;

  private final QueryService queryService;

  public QueryController(final QueryService queryService) {
    this.queryService = queryService;
  }

  @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter query(@RequestBody final QueryRequest request) {
    if (request.question() == null || request.question().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
    }

    log.info(
        "SSE query: question='{}' conversationId={}", request.question(), request.conversationId());

    var emitter = new SseEmitter(SSE_TIMEOUT_MS);

    emitter.onTimeout(
        () -> log.warn("SSE connection timed out for conversationId={}", request.conversationId()));
    emitter.onError(
        ex -> log.warn("SSE connection error for conversationId={}", request.conversationId(), ex));

    try {
      queryService.streamQuery(
          request.question(),
          request.sourceIds(),
          request.sourceTypes(),
          request.conversationId(),
          emitter);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
    }

    return emitter;
  }
}

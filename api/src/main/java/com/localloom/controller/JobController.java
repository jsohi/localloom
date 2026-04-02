package com.localloom.controller;

import com.localloom.model.ContentUnitStatus;
import com.localloom.model.Job;
import com.localloom.repository.ContentUnitRepository;
import com.localloom.service.JobService;
import java.util.List;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

  private static final Logger log = LogManager.getLogger(JobController.class);

  private static final List<ContentUnitStatus> ACTIVE_UNIT_STATUSES =
      List.of(
          ContentUnitStatus.FETCHING,
          ContentUnitStatus.TRANSCRIBING,
          ContentUnitStatus.EXTRACTING,
          ContentUnitStatus.EMBEDDING);

  private final JobService jobService;
  private final ContentUnitRepository contentUnitRepository;

  public JobController(
      final JobService jobService, final ContentUnitRepository contentUnitRepository) {
    this.jobService = jobService;
    this.contentUnitRepository = contentUnitRepository;
  }

  @GetMapping
  public List<Job> listActiveJobs() {
    final var active = jobService.getActiveJobs();
    if (!active.isEmpty()) {
      final var processing = contentUnitRepository.countByStatusIn(ACTIVE_UNIT_STATUSES);
      final var pending = contentUnitRepository.countByStatusIn(List.of(ContentUnitStatus.PENDING));
      log.debug("Active jobs: {}, processing: {}, pending: {}", active.size(), processing, pending);
    }
    return active;
  }

  @GetMapping("/{id}")
  public Job getJob(@PathVariable final UUID id) {
    return jobService
        .getJob(id)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + id));
  }
}

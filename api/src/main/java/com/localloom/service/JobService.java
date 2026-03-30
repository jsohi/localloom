package com.localloom.service;

import com.localloom.model.EntityType;
import com.localloom.model.Job;
import com.localloom.model.JobStatus;
import com.localloom.model.JobType;
import com.localloom.repository.JobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class JobService {

  private static final Logger log = LogManager.getLogger(JobService.class);

  private final JobRepository jobRepository;

  public JobService(final JobRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  @Transactional
  public Job createJob(final JobType type, final UUID entityId, final EntityType entityType) {
    final var job = new Job();
    job.setType(type);
    job.setEntityId(entityId);
    job.setEntityType(entityType);
    job.setStatus(JobStatus.PENDING);
    job.setProgress(0.0);

    final var saved = jobRepository.save(job);
    log.info(
        "Created job id={} type={} entityId={} entityType={}",
        saved.getId(),
        type,
        entityId,
        entityType);
    return saved;
  }

  @Transactional
  public void updateProgress(final UUID jobId, final double progress) {
    final var job = findOrThrow(jobId);
    job.setProgress(progress);
    if (job.getStatus() == JobStatus.PENDING) {
      job.setStatus(JobStatus.RUNNING);
    }
    jobRepository.save(job);
    log.debug("Job id={} progress={}", jobId, progress);
  }

  @Transactional
  public void completeJob(final UUID jobId) {
    final var job = findOrThrow(jobId);
    job.setStatus(JobStatus.COMPLETED);
    job.setProgress(1.0);
    job.setCompletedAt(Instant.now());
    jobRepository.save(job);
    log.info("Job id={} completed", jobId);
  }

  @Transactional
  public void failJob(final UUID jobId, final String errorMessage) {
    final var job = findOrThrow(jobId);
    job.setStatus(JobStatus.FAILED);
    job.setErrorMessage(errorMessage);
    job.setCompletedAt(Instant.now());
    jobRepository.save(job);
    log.warn("Job id={} failed: {}", jobId, errorMessage);
  }

  @Transactional(readOnly = true)
  public Optional<Job> getJob(final UUID jobId) {
    return jobRepository.findById(jobId);
  }

  @Transactional(readOnly = true)
  public List<Job> getActiveJobs() {
    return jobRepository.findByStatusInOrderByCreatedAtAsc(
        List.of(JobStatus.PENDING, JobStatus.RUNNING));
  }

  private Job findOrThrow(final UUID jobId) {
    return jobRepository
        .findById(jobId)
        .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
  }

  public static class JobNotFoundException extends RuntimeException {

    public JobNotFoundException(final String message) {
      super(message);
    }
  }
}

package com.localloom.service;

import com.localloom.model.EntityType;
import com.localloom.model.Job;
import com.localloom.model.JobStatus;
import com.localloom.model.JobType;
import com.localloom.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages the lifecycle of background {@link Job} records: creation, progress
 * updates, completion, and failure.
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates and persists a new {@link Job} in {@link JobStatus#PENDING} state.
     *
     * @param type       the type of work this job represents
     * @param entityId   the UUID of the entity this job is associated with
     * @param entityType whether the entity is a SOURCE or CONTENT_UNIT
     * @return the persisted Job with an assigned id
     */
    @Transactional
    public Job createJob(JobType type, UUID entityId, EntityType entityType) {
        Job job = new Job();
        job.setType(type);
        job.setEntityId(entityId);
        job.setEntityType(entityType);
        job.setStatus(JobStatus.PENDING);
        job.setProgress(0.0);

        Job saved = jobRepository.save(job);
        log.info("Created job id={} type={} entityId={} entityType={}", saved.getId(), type, entityId, entityType);
        return saved;
    }

    /**
     * Updates the progress of an existing job and transitions it to
     * {@link JobStatus#RUNNING} if it is still in PENDING state.
     *
     * @param jobId    the ID of the job to update
     * @param progress a value between 0.0 (not started) and 1.0 (complete)
     * @throws JobNotFoundException if no job exists with the given id
     */
    @Transactional
    public void updateProgress(UUID jobId, double progress) {
        Job job = findOrThrow(jobId);
        job.setProgress(progress);
        if (job.getStatus() == JobStatus.PENDING) {
            job.setStatus(JobStatus.RUNNING);
        }
        jobRepository.save(job);
        log.debug("Job id={} progress={}", jobId, progress);
    }

    /**
     * Marks a job as {@link JobStatus#COMPLETED} with progress 1.0 and records
     * the completion timestamp.
     *
     * @param jobId the ID of the job to complete
     * @throws JobNotFoundException if no job exists with the given id
     */
    @Transactional
    public void completeJob(UUID jobId) {
        Job job = findOrThrow(jobId);
        job.setStatus(JobStatus.COMPLETED);
        job.setProgress(1.0);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        log.info("Job id={} completed", jobId);
    }

    /**
     * Marks a job as {@link JobStatus#FAILED} and records the error message and
     * completion timestamp.
     *
     * @param jobId        the ID of the job to fail
     * @param errorMessage a human-readable description of the failure
     * @throws JobNotFoundException if no job exists with the given id
     */
    @Transactional
    public void failJob(UUID jobId, String errorMessage) {
        Job job = findOrThrow(jobId);
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        log.warn("Job id={} failed: {}", jobId, errorMessage);
    }

    /**
     * Returns the job with the given id, if it exists.
     *
     * @param jobId the job's UUID
     * @return an {@link Optional} containing the job, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<Job> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    /**
     * Returns all jobs that are currently in {@link JobStatus#PENDING} or
     * {@link JobStatus#RUNNING} state, ordered by creation time (oldest first).
     *
     * @return list of active jobs (may be empty)
     */
    @Transactional(readOnly = true)
    public List<Job> getActiveJobs() {
        return jobRepository.findAll().stream()
                .filter(j -> j.getStatus() == JobStatus.PENDING
                        || j.getStatus() == JobStatus.RUNNING)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Job findOrThrow(UUID jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found: " + jobId));
    }

    // -------------------------------------------------------------------------
    // Exception type
    // -------------------------------------------------------------------------

    /**
     * Thrown when a requested job does not exist in the database.
     */
    public static class JobNotFoundException extends RuntimeException {

        public JobNotFoundException(String message) {
            super(message);
        }
    }
}

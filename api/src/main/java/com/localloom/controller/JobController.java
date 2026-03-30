package com.localloom.controller;

import com.localloom.model.Job;
import com.localloom.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * REST controller exposing job status for frontend progress polling.
 *
 * <pre>
 * GET /api/v1/jobs       — list active (PENDING + RUNNING) jobs
 * GET /api/v1/jobs/{id}  — job detail
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /**
     * Returns all jobs that are currently active (PENDING or RUNNING), ordered
     * oldest-first. The frontend polls this endpoint to drive progress indicators.
     */
    @GetMapping
    public List<Job> listActiveJobs() {
        List<Job> active = jobService.getActiveJobs();
        log.debug("Active jobs: {}", active.size());
        return active;
    }

    /**
     * Returns the detail of a single job.
     *
     * @throws ResponseStatusException {@code 404} if the job does not exist
     */
    @GetMapping("/{id}")
    public Job getJob(@PathVariable UUID id) {
        return jobService.getJob(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Job not found: " + id));
    }
}

package com.localloom.controller;

import com.localloom.model.Job;
import com.localloom.service.JobService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private static final Logger log = LogManager.getLogger(JobController.class);

    private final JobService jobService;

    public JobController(final JobService jobService) {
        this.jobService = jobService;
    }

    @GetMapping
    public List<Job> listActiveJobs() {
        final var active = jobService.getActiveJobs();
        log.debug("Active jobs: {}", active.size());
        return active;
    }

    @GetMapping("/{id}")
    public Job getJob(@PathVariable final UUID id) {
        return jobService.getJob(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Job not found: " + id));
    }
}

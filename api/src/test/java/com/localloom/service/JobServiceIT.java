package com.localloom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.localloom.TestcontainersConfig;
import com.localloom.model.EntityType;
import com.localloom.model.JobStatus;
import com.localloom.model.JobType;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class JobServiceIT {

  @Autowired private JobService jobService;

  @Test
  void createJobAndRetrieve() {
    var entityId = UUID.randomUUID();
    var job = jobService.createJob(JobType.SYNC, entityId, EntityType.SOURCE);

    assertThat(job.getId()).isNotNull();
    assertThat(job.getType()).isEqualTo(JobType.SYNC);
    assertThat(job.getEntityId()).isEqualTo(entityId);
    assertThat(job.getEntityType()).isEqualTo(EntityType.SOURCE);
    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
    assertThat(job.getProgress()).isEqualTo(0.0);

    var found = jobService.getJob(job.getId());
    assertThat(found).isPresent();
  }

  @Test
  void updateProgressTransitionsPendingToRunning() {
    var job = jobService.createJob(JobType.SYNC, UUID.randomUUID(), EntityType.SOURCE);
    assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);

    jobService.updateProgress(job.getId(), 0.25);

    var updated = jobService.getJob(job.getId()).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(updated.getProgress()).isEqualTo(0.25);
  }

  @Test
  void completeJobSetsStatusAndProgress() {
    var job = jobService.createJob(JobType.SYNC, UUID.randomUUID(), EntityType.SOURCE);
    jobService.completeJob(job.getId());

    var completed = jobService.getJob(job.getId()).orElseThrow();
    assertThat(completed.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(completed.getProgress()).isEqualTo(1.0);
    assertThat(completed.getCompletedAt()).isNotNull();
  }

  @Test
  void failJobSetsErrorMessage() {
    var job = jobService.createJob(JobType.SYNC, UUID.randomUUID(), EntityType.SOURCE);
    jobService.failJob(job.getId(), "Transcription timeout");

    var failed = jobService.getJob(job.getId()).orElseThrow();
    assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(failed.getErrorMessage()).isEqualTo("Transcription timeout");
    assertThat(failed.getCompletedAt()).isNotNull();
  }

  @Test
  void getActiveJobsFiltersCompletedAndFailed() {
    var entityId = UUID.randomUUID();

    var active = jobService.createJob(JobType.SYNC, entityId, EntityType.SOURCE);
    jobService.updateProgress(active.getId(), 0.5);

    var done = jobService.createJob(JobType.SYNC, entityId, EntityType.SOURCE);
    jobService.completeJob(done.getId());

    var failed = jobService.createJob(JobType.SYNC, entityId, EntityType.SOURCE);
    jobService.failJob(failed.getId(), "error");

    var activeJobs = jobService.getActiveJobs();
    assertThat(activeJobs).extracting("id").contains(active.getId());
    assertThat(activeJobs).extracting("id").doesNotContain(done.getId(), failed.getId());
  }

  @Test
  void missingJobThrowsOnUpdate() {
    var fakeId = UUID.randomUUID();
    assertThatThrownBy(() -> jobService.updateProgress(fakeId, 0.5))
        .isInstanceOf(JobService.JobNotFoundException.class);
  }
}

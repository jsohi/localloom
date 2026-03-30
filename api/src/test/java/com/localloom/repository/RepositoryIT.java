package com.localloom.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.localloom.TestcontainersConfig;
import com.localloom.model.ContentFragment;
import com.localloom.model.ContentType;
import com.localloom.model.ContentUnit;
import com.localloom.model.ContentUnitStatus;
import com.localloom.model.EntityType;
import com.localloom.model.FragmentType;
import com.localloom.model.Job;
import com.localloom.model.JobStatus;
import com.localloom.model.JobType;
import com.localloom.model.Source;
import com.localloom.model.SourceType;
import com.localloom.model.SyncStatus;
import com.localloom.service.AudioService;
import com.localloom.service.SourceImportService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RepositoryIT {

  @MockitoBean private AudioService audioService;
  @MockitoBean private SourceImportService sourceImportService;
  @MockitoBean private EmbeddingModel embeddingModel;

  @Autowired private SourceRepository sourceRepository;
  @Autowired private ContentUnitRepository contentUnitRepository;
  @Autowired private ContentFragmentRepository contentFragmentRepository;
  @Autowired private JobRepository jobRepository;

  @BeforeEach
  void cleanDatabase() {
    contentFragmentRepository.deleteAllInBatch();
    contentUnitRepository.deleteAllInBatch();
    jobRepository.deleteAllInBatch();
    sourceRepository.deleteAllInBatch();
  }

  @Test
  void flywayMigrationsRunCleanly() {
    // If we reach this point the app started and Flyway applied all migrations
    assertThat(sourceRepository.count()).isZero();
  }

  @Test
  void sourceCrud() {
    var source = new Source();
    source.setName("Test Podcast");
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/feed.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    source = sourceRepository.save(source);

    assertThat(source.getId()).isNotNull();
    assertThat(source.getCreatedAt()).isNotNull();

    var found = sourceRepository.findById(source.getId());
    assertThat(found).isPresent();
    assertThat(found.get().getName()).isEqualTo("Test Podcast");

    sourceRepository.deleteById(source.getId());
    assertThat(sourceRepository.findById(source.getId())).isEmpty();
  }

  @Test
  void contentUnitLifecycle() {
    var source = createSource("Lifecycle Test");
    var unit = new ContentUnit();
    unit.setSource(source);
    unit.setTitle("Episode 1");
    unit.setContentType(ContentType.AUDIO);
    unit.setStatus(ContentUnitStatus.PENDING);
    unit = contentUnitRepository.save(unit);

    assertThat(unit.getId()).isNotNull();
    assertThat(unit.getStatus()).isEqualTo(ContentUnitStatus.PENDING);

    unit.setStatus(ContentUnitStatus.TRANSCRIBING);
    unit = contentUnitRepository.save(unit);
    assertThat(unit.getStatus()).isEqualTo(ContentUnitStatus.TRANSCRIBING);

    unit.setStatus(ContentUnitStatus.INDEXED);
    unit = contentUnitRepository.save(unit);
    assertThat(unit.getStatus()).isEqualTo(ContentUnitStatus.INDEXED);
  }

  @Test
  void contentFragmentOrdering() {
    var source = createSource("Fragment Test");
    var unit = new ContentUnit();
    unit.setSource(source);
    unit.setTitle("Episode Fragments");
    unit.setContentType(ContentType.AUDIO);
    unit.setStatus(ContentUnitStatus.INDEXED);
    unit = contentUnitRepository.save(unit);

    for (int i = 2; i >= 0; i--) {
      var fragment = new ContentFragment();
      fragment.setContentUnit(unit);
      fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
      fragment.setSequenceIndex(i);
      fragment.setText("Segment " + i);
      fragment.setLocation("{\"start\":" + i + ",\"end\":" + (i + 1) + "}");
      contentFragmentRepository.save(fragment);
    }

    var fragments = contentFragmentRepository.findByContentUnitIdOrderBySequenceIndex(unit.getId());
    assertThat(fragments).hasSize(3);
    assertThat(fragments.get(0).getSequenceIndex()).isEqualTo(0);
    assertThat(fragments.get(1).getSequenceIndex()).isEqualTo(1);
    assertThat(fragments.get(2).getSequenceIndex()).isEqualTo(2);
  }

  @Test
  void jobProgressTracking() {
    var job = new Job();
    job.setType(JobType.SYNC);
    job.setEntityId(java.util.UUID.randomUUID());
    job.setEntityType(EntityType.SOURCE);
    job.setStatus(JobStatus.PENDING);
    job.setProgress(0.0);
    job = jobRepository.save(job);

    assertThat(job.getId()).isNotNull();
    assertThat(job.getCreatedAt()).isNotNull();

    job.setStatus(JobStatus.RUNNING);
    job.setProgress(0.5);
    job = jobRepository.save(job);

    var found = jobRepository.findById(job.getId()).orElseThrow();
    assertThat(found.getStatus()).isEqualTo(JobStatus.RUNNING);
    assertThat(found.getProgress()).isEqualTo(0.5);
  }

  @Test
  void cascadeDelete() {
    var source = createSource("Cascade Test");
    var unit = new ContentUnit();
    unit.setSource(source);
    unit.setTitle("Will Be Deleted");
    unit.setContentType(ContentType.AUDIO);
    unit.setStatus(ContentUnitStatus.PENDING);
    unit = contentUnitRepository.save(unit);

    var fragment = new ContentFragment();
    fragment.setContentUnit(unit);
    fragment.setFragmentType(FragmentType.TIMED_SEGMENT);
    fragment.setSequenceIndex(0);
    fragment.setText("Will also be deleted");
    contentFragmentRepository.save(fragment);

    assertThat(contentUnitRepository.findBySourceId(source.getId())).hasSize(1);

    sourceRepository.deleteById(source.getId());
    sourceRepository.flush();

    assertThat(contentUnitRepository.findBySourceId(source.getId())).isEmpty();
  }

  @Test
  void findActiveJobs() {
    var entityId = java.util.UUID.randomUUID();

    var pending = new Job();
    pending.setType(JobType.SYNC);
    pending.setEntityId(entityId);
    pending.setEntityType(EntityType.SOURCE);
    pending.setStatus(JobStatus.PENDING);
    jobRepository.save(pending);

    var running = new Job();
    running.setType(JobType.SYNC);
    running.setEntityId(entityId);
    running.setEntityType(EntityType.SOURCE);
    running.setStatus(JobStatus.RUNNING);
    jobRepository.save(running);

    var completed = new Job();
    completed.setType(JobType.SYNC);
    completed.setEntityId(entityId);
    completed.setEntityType(EntityType.SOURCE);
    completed.setStatus(JobStatus.COMPLETED);
    jobRepository.save(completed);

    var active =
        jobRepository.findByStatusInOrderByCreatedAtAsc(
            List.of(JobStatus.PENDING, JobStatus.RUNNING));
    assertThat(active).hasSize(2);
    assertThat(active)
        .extracting(Job::getStatus)
        .containsOnly(JobStatus.PENDING, JobStatus.RUNNING);
  }

  private Source createSource(final String name) {
    var source = new Source();
    source.setName(name);
    source.setSourceType(SourceType.PODCAST);
    source.setOriginUrl("https://example.com/feed.xml");
    source.setSyncStatus(SyncStatus.IDLE);
    return sourceRepository.save(source);
  }
}

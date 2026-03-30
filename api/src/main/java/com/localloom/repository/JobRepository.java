package com.localloom.repository;

import com.localloom.model.EntityType;
import com.localloom.model.Job;
import com.localloom.model.JobStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, UUID> {

  List<Job> findByEntityIdAndEntityType(UUID entityId, EntityType entityType);

  List<Job> findByStatusInOrderByCreatedAtAsc(List<JobStatus> statuses);
}

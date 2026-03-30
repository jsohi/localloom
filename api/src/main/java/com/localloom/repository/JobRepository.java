package com.localloom.repository;

import com.localloom.model.EntityType;
import com.localloom.model.Job;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    List<Job> findByEntityIdAndEntityType(UUID entityId, EntityType entityType);
}

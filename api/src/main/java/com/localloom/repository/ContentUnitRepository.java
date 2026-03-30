package com.localloom.repository;

import com.localloom.model.ContentUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentUnitRepository extends JpaRepository<ContentUnit, UUID> {

    List<ContentUnit> findBySourceId(UUID sourceId);
}

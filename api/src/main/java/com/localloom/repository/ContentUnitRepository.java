package com.localloom.repository;

import com.localloom.model.ContentUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentUnitRepository extends JpaRepository<ContentUnit, UUID> {

  List<ContentUnit> findBySourceId(UUID sourceId);
}

package com.localloom.repository;

import com.localloom.model.Source;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, UUID> {

  @EntityGraph(attributePaths = "contentUnits")
  List<Source> findAllByOrderByCreatedAtDesc();

  @EntityGraph(attributePaths = "contentUnits")
  Optional<Source> findWithContentUnitsById(UUID id);
}

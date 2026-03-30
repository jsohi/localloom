package com.localloom.repository;

import com.localloom.model.Source;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SourceRepository extends JpaRepository<Source, UUID> {

  List<Source> findAllByOrderByCreatedAtDesc();
}

package com.localloom.repository;

import com.localloom.model.Source;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SourceRepository extends JpaRepository<Source, UUID> {

    List<Source> findAllByOrderByCreatedAtDesc();
}

package com.localloom.repository;

import com.localloom.model.ContentFragment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ContentFragmentRepository extends JpaRepository<ContentFragment, Long> {

    List<ContentFragment> findByContentUnitIdOrderBySequenceIndex(UUID contentUnitId);
}

package com.localloom.repository;

import com.localloom.model.ContentFragment;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentFragmentRepository extends JpaRepository<ContentFragment, Long> {

  List<ContentFragment> findByContentUnitIdOrderBySequenceIndex(UUID contentUnitId);
}

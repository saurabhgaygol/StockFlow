package com.stockmanagement.repository;

import com.stockmanagement.entity.TagLabel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagLabelRepository extends JpaRepository<TagLabel, Long> {

    List<TagLabel> findByBatchIdAndCompanyNameOrderBySeqNoAsc(Long batchId, String companyName);

    Optional<TagLabel> findByIdAndCompanyName(Long id, String companyName);
}
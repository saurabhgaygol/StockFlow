package com.stockmanagement.repository;

import com.stockmanagement.entity.TagBatchItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TagBatchItemRepository extends JpaRepository<TagBatchItem, Long> {

    List<TagBatchItem> findByBatchIdOrderByLineNoAsc(Long batchId);

    List<TagBatchItem> findByBatchIdInOrderByBatchIdAscLineNoAsc(Collection<Long> batchIds);
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailInwardLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RetailInwardLineRepository extends JpaRepository<RetailInwardLine, Long> {

    List<RetailInwardLine> findByInwardIdOrderByIdAsc(Long inwardId);

    List<RetailInwardLine> findByInwardIdInOrderByIdAsc(Collection<Long> inwardIds);

    List<RetailInwardLine> findByVariantIdIn(Collection<Long> variantIds);
}
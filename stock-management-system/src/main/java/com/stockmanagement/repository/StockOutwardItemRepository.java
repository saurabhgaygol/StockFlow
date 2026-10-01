package com.stockmanagement.repository;

import com.stockmanagement.entity.StockOutwardItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StockOutwardItemRepository extends JpaRepository<StockOutwardItem, Long> {

    List<StockOutwardItem> findByRequestId(Long requestId);

    List<StockOutwardItem> findByRequestIdIn(Collection<Long> requestIds);
}
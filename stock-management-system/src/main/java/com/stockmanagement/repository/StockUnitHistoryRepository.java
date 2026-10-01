package com.stockmanagement.repository;

import com.stockmanagement.entity.StockUnitHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockUnitHistoryRepository extends JpaRepository<StockUnitHistory, Long> {

    List<StockUnitHistory> findByStockIdOrderByCreatedAtAscIdAsc(Long stockId);
}
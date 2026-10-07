package com.stockmanagement.repository;

import com.stockmanagement.entity.StockUnitHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockUnitHistoryRepository extends JpaRepository<StockUnitHistory, Long> {

    List<StockUnitHistory> findByStockIdOrderByCreatedAtAscIdAsc(Long stockId);
    
    /** Latest event of one type for a unit (e.g. the last SOLD entry). */
    java.util.Optional<StockUnitHistory> findFirstByStockIdAndEventTypeOrderByCreatedAtDescIdDesc(
            Long stockId, String eventType);

    long countByStockIdAndEventType(Long stockId, String eventType);
}
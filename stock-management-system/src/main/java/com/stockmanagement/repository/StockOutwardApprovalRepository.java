package com.stockmanagement.repository;

import com.stockmanagement.entity.StockOutwardApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockOutwardApprovalRepository extends JpaRepository<StockOutwardApproval, Long> {

    List<StockOutwardApproval> findByRequestIdOrderByLevelNoAsc(Long requestId);

    List<StockOutwardApproval> findByRequestIdIn(Collection<Long> requestIds);

    Optional<StockOutwardApproval> findByRequestIdAndLevelNo(Long requestId, Integer levelNo);

    long countByActedByIdAndStatusAndActedAtGreaterThanEqual(Long actedById, String status, LocalDateTime since);
}
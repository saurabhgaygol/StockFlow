package com.stockmanagement.repository;

import com.stockmanagement.entity.StaffStockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffStockMovementRepository extends JpaRepository<StaffStockMovement, Long> {

    List<StaffStockMovement> findTop300ByCompanyNameOrderByCreatedAtDescIdDesc(String companyName);

    List<StaffStockMovement> findTop300ByOrderByCreatedAtDescIdDesc();

    List<StaffStockMovement> findByStockIdOrderByCreatedAtAscIdAsc(Long stockId);
}
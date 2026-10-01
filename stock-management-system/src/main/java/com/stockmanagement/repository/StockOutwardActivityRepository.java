package com.stockmanagement.repository;

import com.stockmanagement.entity.StockOutwardActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockOutwardActivityRepository extends JpaRepository<StockOutwardActivity, Long> {

    List<StockOutwardActivity> findByRequestIdOrderByCreatedAtAsc(Long requestId);
}
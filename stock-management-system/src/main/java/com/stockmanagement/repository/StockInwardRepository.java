package com.stockmanagement.repository;


import com.stockmanagement.entity.StockInward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockInwardRepository extends JpaRepository<StockInward, Long> {

    List<StockInward> findByCompanyNameOrderByCreatedAtDesc(String companyName);

    List<StockInward> findByCompanyNameAndStatus(String companyName, String status);

    boolean existsBySerialNumberAndCompanyName(String serialNumber, String companyName);

    long countByCompanyNameAndStatus(String companyName, String status);

    long countByCompanyName(String companyName);
}

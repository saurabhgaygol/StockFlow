package com.stockmanagement.repository;


import com.stockmanagement.entity.StockInward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StockInwardRepository extends JpaRepository<StockInward, Long> {

    List<StockInward> findByCompanyNameOrderByCreatedAtDesc(String companyName);

    List<StockInward> findByCompanyNameAndStatus(String companyName, String status);

    boolean existsBySerialNumberAndCompanyName(String serialNumber, String companyName);

    long countByCompanyNameAndStatus(String companyName, String status);

    long countByCompanyName(String companyName);

    // ============================================================
    // Vendor + Category + Product JOIN FETCH
    // (500 error fix — vendorName, categoryName, productName load karne ke liye)
    // ============================================================

    @Query("SELECT s FROM StockInward s " +
           "LEFT JOIN FETCH s.vendor " +
           "LEFT JOIN FETCH s.category " +
           "LEFT JOIN FETCH s.product " +
           "WHERE s.companyName = :companyName " +
           "ORDER BY s.createdAt DESC")
    List<StockInward> findByCompanyNameWithVendorAndCategory(
            @Param("companyName") String companyName);

    @Query("SELECT s FROM StockInward s " +
           "LEFT JOIN FETCH s.vendor " +
           "LEFT JOIN FETCH s.category " +
           "LEFT JOIN FETCH s.product " +
           "ORDER BY s.createdAt DESC")
    List<StockInward> findAllWithVendorAndCategory();
}
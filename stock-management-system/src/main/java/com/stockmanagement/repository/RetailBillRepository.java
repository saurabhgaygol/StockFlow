package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RetailBillRepository extends JpaRepository<RetailBill, Long> {

    Optional<RetailBill> findByIdAndCompanyName(Long id, String companyName);

    Optional<RetailBill> findByShareToken(String shareToken);

    List<RetailBill> findTop200ByCompanyNameOrderByIdDesc(String companyName);

    List<RetailBill> findByCompanyNameAndBillDateBetweenOrderByIdDesc(String companyName, LocalDateTime from, LocalDateTime to);

    List<RetailBill> findTop200ByCompanyNameAndCustomerMobileOrderByIdDesc(String companyName, String customerMobile);
}
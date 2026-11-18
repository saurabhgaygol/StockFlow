package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailPointsLedger;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetailPointsLedgerRepository extends JpaRepository<RetailPointsLedger, Long> {
    List<RetailPointsLedger> findByCompanyNameAndCustomerIdOrderByIdDesc(String companyName, Long customerId);
}
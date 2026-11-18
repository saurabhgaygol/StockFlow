package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBillLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RetailBillLineRepository extends JpaRepository<RetailBillLine, Long> {

    List<RetailBillLine> findByCompanyNameAndBillIdOrderByIdAsc(String companyName, Long billId);

    List<RetailBillLine> findByCompanyNameAndBillIdIn(String companyName, Collection<Long> billIds);
}
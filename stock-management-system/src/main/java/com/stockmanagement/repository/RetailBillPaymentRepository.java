package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBillPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RetailBillPaymentRepository extends JpaRepository<RetailBillPayment, Long> {

    List<RetailBillPayment> findByCompanyNameAndBillIdOrderByIdAsc(String companyName, Long billId);

    List<RetailBillPayment> findByCompanyNameAndBillIdIn(String companyName, Collection<Long> billIds);
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBirthdayCode;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RetailBirthdayCodeRepository extends JpaRepository<RetailBirthdayCode, Long> {
    Optional<RetailBirthdayCode> findByCompanyNameAndCode(String companyName, String code);
    java.util.List<RetailBirthdayCode> findByCompanyNameAndCustomerIdAndValidOnBetween(
            String companyName, Long customerId, LocalDate from, LocalDate to);

    Optional<RetailBirthdayCode> findFirstByCompanyNameAndCustomerIdAndValidOnAndUsedBillIdIsNull(
            String companyName, Long customerId, LocalDate validOn);
}
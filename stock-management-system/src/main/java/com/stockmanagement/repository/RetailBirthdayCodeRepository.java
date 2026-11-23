package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBirthdayCode;
import java.time.LocalDate;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RetailBirthdayCodeRepository extends JpaRepository<RetailBirthdayCode, Long> {
    Optional<RetailBirthdayCode> findByCompanyNameAndCode(String companyName, String code);
    java.util.List<RetailBirthdayCode> findByCompanyNameAndCustomerIdAndValidOnBetween(
            String companyName, Long customerId, LocalDate from, LocalDate to);

    Optional<RetailBirthdayCode> findFirstByCompanyNameAndCustomerIdAndValidOnAndUsedBillIdIsNull(
            String companyName, Long customerId, LocalDate validOn);

    /** locked until the transaction ends, so two counters can never use the same birthday code */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RetailBirthdayCode c where c.id = :id")
    Optional<RetailBirthdayCode> findLocked(@Param("id") Long id);

    /** the birthday code a bill used (to give it back when the bill is cancelled) */
    java.util.List<RetailBirthdayCode> findByCompanyNameAndUsedBillId(String companyName, Long usedBillId);
}
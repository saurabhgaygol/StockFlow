package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBillCounter;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RetailBillCounterRepository extends JpaRepository<RetailBillCounter, Long> {

    /** locked until the transaction ends, so two counters can never take the same invoice number */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RetailBillCounter c where c.companyName = :company and c.billYear = :year")
    Optional<RetailBillCounter> findLocked(@Param("company") String company, @Param("year") Integer year);
}
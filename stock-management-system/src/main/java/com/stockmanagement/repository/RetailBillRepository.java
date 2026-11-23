package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailBill;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RetailBillRepository extends JpaRepository<RetailBill, Long> {

    Optional<RetailBill> findByIdAndCompanyName(Long id, String companyName);

    /** locked until the transaction ends, so a bill can never be cancelled twice at the same time */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from RetailBill b where b.id = :id and b.companyName = :company")
    Optional<RetailBill> findLocked(@Param("id") Long id, @Param("company") String company);

    Optional<RetailBill> findByShareToken(String shareToken);

    List<RetailBill> findTop200ByCompanyNameOrderByIdDesc(String companyName);

    List<RetailBill> findByCompanyNameAndBillDateBetweenOrderByIdDesc(String companyName, LocalDateTime from, LocalDateTime to);

    List<RetailBill> findTop200ByCompanyNameAndCustomerMobileOrderByIdDesc(String companyName, String customerMobile);
}
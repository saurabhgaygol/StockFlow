package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailCustomerCoupon;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RetailCustomerCouponRepository extends JpaRepository<RetailCustomerCoupon, Long> {

    Optional<RetailCustomerCoupon> findByCompanyNameAndCode(String companyName, String code);

    /** locked while a bill is being saved, so one code can never be used by two bills */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RetailCustomerCoupon c where c.companyName = :company and c.code = :code")
    Optional<RetailCustomerCoupon> findLockedByCode(@Param("company") String company, @Param("code") String code);

    List<RetailCustomerCoupon> findByCompanyNameAndOfferIdOrderByIdAsc(String companyName, Long offerId);

    List<RetailCustomerCoupon> findByCompanyNameAndOfferIdAndMobileIn(String companyName, Long offerId, java.util.Collection<String> mobiles);

    List<RetailCustomerCoupon> findByCompanyNameAndUsedBillId(String companyName, Long usedBillId);

    long countByCompanyNameAndOfferId(String companyName, Long offerId);
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailCustomer;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RetailCustomerRepository extends JpaRepository<RetailCustomer, Long> {

    Optional<RetailCustomer> findByCompanyNameAndMobile(String companyName, String mobile);

    Optional<RetailCustomer> findByIdAndCompanyName(Long id, String companyName);

    List<RetailCustomer> findByCompanyNameOrderByIdAsc(String companyName);

    List<RetailCustomer> findByCompanyNameAndMobileIn(String companyName, java.util.Collection<String> mobiles);

    /** locked until the transaction ends, so two bills can never change the points balance at the same time */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from RetailCustomer c where c.id = :id and c.companyName = :company")
    Optional<RetailCustomer> findLocked(@Param("id") Long id, @Param("company") String company);

    /** search box: part of the mobile number or part of the name */
    @Query("select c from RetailCustomer c where c.companyName = :company "
            + "and (c.mobile like concat('%', :q, '%') or lower(c.name) like concat('%', lower(:q), '%')) "
            + "order by c.lastVisit desc")
    List<RetailCustomer> search(@Param("company") String company, @Param("q") String q, Pageable pageable);
}
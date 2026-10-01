package com.stockmanagement.repository;

import com.stockmanagement.entity.StockCustomer;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockCustomerRepository extends JpaRepository<StockCustomer, Long> {

    /** Pehchaan: ek hi naam + ek hi mobile (capital/small ka farak nahi). */
    Optional<StockCustomer> findFirstByCompanyNameAndMobileAndCustomerNameIgnoreCase(String companyName, String mobile, String customerName);

    /** Naam, mobile ya company ka koi bhi hissa; pageable se sirf pehle kuch results. */
    @Query("SELECT c FROM StockCustomer c WHERE c.companyName = :company AND ("
         + "LOWER(c.customerName) LIKE LOWER(CONCAT('%', :q, '%')) "
         + "OR c.mobile LIKE CONCAT('%', :q, '%') "
         + "OR LOWER(COALESCE(c.businessName, '')) LIKE LOWER(CONCAT('%', :q, '%'))) "
         + "ORDER BY c.customerName")
    List<StockCustomer> search(@Param("company") String company, @Param("q") String q, Pageable pageable);
}
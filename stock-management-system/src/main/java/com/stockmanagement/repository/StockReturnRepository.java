package com.stockmanagement.repository;

import com.stockmanagement.entity.StockReturn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockReturnRepository extends JpaRepository<StockReturn, Long> {

    /** Latest returns of one company (shown under the Customer Return screen). */
    List<StockReturn> findTop20ByCompanyNameOrderByIdDesc(String companyName);

    /** Super admin: latest returns across all companies. */
    List<StockReturn> findTop20ByOrderByIdDesc();

    /** Devices that are at a vendor right now (oldest first). */
    List<StockReturn> findByCompanyNameAndCaseStatusOrderBySentDateAscIdAsc(String companyName, String caseStatus);

    List<StockReturn> findByCaseStatusOrderBySentDateAscIdAsc(String caseStatus);

    /** The open vendor case of one unit (to show "this unit is with vendor X"). */
    Optional<StockReturn> findFirstByStockIdAndCaseStatusOrderByIdDesc(Long stockId, String caseStatus);
}
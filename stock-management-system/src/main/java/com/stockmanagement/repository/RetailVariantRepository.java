package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailVariant;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RetailVariantRepository extends JpaRepository<RetailVariant, Long> {

    List<RetailVariant> findByCompanyNameOrderByIdDesc(String companyName, Pageable pageable);

    Optional<RetailVariant> findByIdAndCompanyName(Long id, String companyName);

    boolean existsByCompanyNameAndBarcode(String companyName, String barcode);

    List<RetailVariant> findByCompanyNameAndProductId(String companyName, Long productId);

    /** variants for these barcodes (used to check a stock-out before it is saved) */
    List<RetailVariant> findByCompanyNameAndBarcodeIn(String companyName, Collection<String> barcodes);

    /** same, but the rows are locked until the transaction ends, so stock can never go below zero */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<RetailVariant> findLockedByCompanyNameAndBarcodeIn(String companyName, Collection<String> barcodes);

    /** every style code in use by this company (offer screen picker / check) */
    @Query("select distinct v.styleCode from RetailVariant v where v.companyName = :company "
            + "and v.styleCode is not null and v.styleCode <> '' order by v.styleCode")
    List<String> findStyleCodes(@Param("company") String company);

    /** every size label in use by this company (barcode Excel template dropdown) */
    @Query("select distinct v.sizeLabel from RetailVariant v where v.companyName = :company "
            + "and v.sizeLabel is not null and v.sizeLabel <> '' order by v.sizeLabel")
    List<String> findSizeLabels(@Param("company") String company);
}
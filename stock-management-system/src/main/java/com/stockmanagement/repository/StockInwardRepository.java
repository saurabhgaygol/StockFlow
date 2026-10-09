package com.stockmanagement.repository;


import com.stockmanagement.entity.StockInward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;

public interface StockInwardRepository extends JpaRepository<StockInward, Long> {

    List<StockInward> findByCompanyNameOrderByCreatedAtDesc(String companyName);

    List<StockInward> findByCompanyNameAndStatus(String companyName, String status);

    boolean existsBySerialNumberAndCompanyName(String serialNumber, String companyName);

    long countByCompanyNameAndStatus(String companyName, String status);

    long countByCompanyName(String companyName);

    // ============================================================
    // Vendor + Category + Product JOIN FETCH
    // (500 error fix — vendorName, categoryName, productName load karne ke liye)
    // ============================================================

    @Query("SELECT s FROM StockInward s " +
           "LEFT JOIN FETCH s.vendor " +
           "LEFT JOIN FETCH s.category " +
           "LEFT JOIN FETCH s.product " +
           "WHERE s.companyName = :companyName " +
           "ORDER BY s.createdAt DESC")
    List<StockInward> findByCompanyNameWithVendorAndCategory(
            @Param("companyName") String companyName);

    @Query("SELECT s FROM StockInward s " +
           "LEFT JOIN FETCH s.vendor " +
           "LEFT JOIN FETCH s.category " +
           "LEFT JOIN FETCH s.product " +
           "ORDER BY s.createdAt DESC")
    List<StockInward> findAllWithVendorAndCategory();
    
    
    
    // ---- IMEI unit ki pehchan hai: ek company mein unique ----
    boolean existsByImeiNumberAndCompanyName(String imeiNumber, String companyName);

    boolean existsByImeiNumberAndCompanyNameAndIdNot(String imeiNumber, String companyName, Long id);

    // ============================================================
    // NEW: Customer Return support
    // ============================================================

    /** Exact IMEI lookup (Customer Return screen). */
    java.util.Optional<StockInward> findByImeiNumberAndCompanyName(String imeiNumber, String companyName);

    java.util.Optional<StockInward> findFirstByImeiNumberOrderByIdDesc(String imeiNumber);

    /** AVAILABLE units of the same product (to pick the new device in an exchange). */
    List<StockInward> findByCompanyNameAndStatusAndProductIdInOrderByCreatedAtAscIdAsc(
            String companyName, String status, java.util.Collection<Long> productIds, Pageable pageable);

    /** All AVAILABLE units of a company (any product) for the exchange popup. */
    List<StockInward> findByCompanyNameAndStatusOrderByCreatedAtAscIdAsc(String companyName, String status);

    /** Row lock so two people cannot return / issue the same unit at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockInward s WHERE s.id = :id")
    java.util.Optional<StockInward> findByIdForUpdate(@Param("id") Long id);

    /** Device history search (adhoora IMEI). */
    List<StockInward> findTop10ByImeiNumberContainingAndCompanyNameOrderByIdDesc(String imei, String companyName);

    List<StockInward> findTop10ByImeiNumberContainingOrderByIdDesc(String imei);

    // ============================================================
    // STOCK OUTWARD support
    // ============================================================

    /** Ek outward request se issue hui units. */
    @Query("SELECT s FROM StockInward s LEFT JOIN FETCH s.vendor LEFT JOIN FETCH s.category "
         + "LEFT JOIN FETCH s.product WHERE s.outwardRequestId = :requestId ORDER BY s.id")
    List<StockInward> findByOutwardRequestId(@Param("requestId") Long outwardRequestId);

    /** Product ke hisaab se available units ki ginti ("in stock: N" hint ke liye). */
    @Query("SELECT s.productId, COUNT(s) FROM StockInward s "
         + "WHERE s.companyName = :companyName AND s.status = 'AVAILABLE' "
         + "GROUP BY s.productId")
    List<Object[]> countAvailableGroupedByProduct(@Param("companyName") String companyName);

    long countByCompanyNameAndStatusAndProductIdIn(String companyName, String status,
                                                   java.util.Collection<Long> productIds);

    /**
     * Sabse purani AVAILABLE units pehle (FIFO), FOR UPDATE lock ke saath, taaki
     * do requests ko ek hi unit kabhi na mile. PageRequest.of(0, qty) pass hota hai.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM StockInward s WHERE s.companyName = :companyName "
         + "AND s.productId IN :productIds AND s.status = 'AVAILABLE' "
         + "ORDER BY s.createdAt ASC, s.id ASC")
    List<StockInward> lockAvailableFifo(@Param("companyName") String companyName,
                                        @Param("productIds") java.util.Collection<Long> productIds,
                                        Pageable pageable);

    /**
     * Final approval popup: saari AVAILABLE units (purani pehle) taaki approver khud IMEI chun sake.
     * Lock nahi lagta, asli lock issue karte waqt findByIdForUpdate se lagta hai.
     */
    @Query("SELECT s FROM StockInward s WHERE s.companyName = :companyName "
         + "AND s.productId IN :productIds AND s.status = 'AVAILABLE' "
         + "ORDER BY s.createdAt ASC, s.id ASC")
    List<StockInward> findAvailableForPick(@Param("companyName") String companyName,
                                           @Param("productIds") java.util.Collection<Long> productIds);

    // ============================================================
    // FIELD STAFF STOCK (additive - nothing above is touched)
    // ============================================================

    /** Units physically with one staff member (status WITH_STAFF). */
    @Query("SELECT s FROM StockInward s WHERE s.companyName = :companyName AND s.status = 'WITH_STAFF' "
         + "AND s.holderUserId = :staffId ORDER BY s.holderSince ASC, s.id ASC")
    List<StockInward> findWithStaff(@Param("companyName") String companyName, @Param("staffId") Long staffId);

    /** Every unit that is with any staff member of the company. */
    @Query("SELECT s FROM StockInward s WHERE s.companyName = :companyName AND s.status = 'WITH_STAFF' "
         + "ORDER BY s.holderUserId ASC, s.holderSince ASC, s.id ASC")
    List<StockInward> findAllWithStaff(@Param("companyName") String companyName);

    /** staffId, installType (null = in hand), count. */
    @Query("SELECT s.holderUserId, s.installType, COUNT(s) FROM StockInward s "
         + "WHERE s.companyName = :companyName AND s.status = 'WITH_STAFF' "
         + "GROUP BY s.holderUserId, s.installType")
    List<Object[]> countWithStaffGrouped(@Param("companyName") String companyName);

    long countByCompanyNameAndHolderUserIdAndStatus(String companyName, Long holderUserId, String status);

}
package com.stockmanagement.repository;

import com.stockmanagement.entity.StockOutwardRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StockOutwardRequestRepository extends JpaRepository<StockOutwardRequest, Long> {

    /** Row lock: do approvers ek saath click karein to dono request ko nahi badal sakte. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StockOutwardRequest r WHERE r.id = :id")
    Optional<StockOutwardRequest> findByIdForUpdate(@Param("id") Long id);

    /** Super admin / view-all: ek company ki saari requests. */
    List<StockOutwardRequest> findByCompanyNameOrderByCreatedAtDesc(String companyName);

    /** Super admin: saari companies. */
    List<StockOutwardRequest> findAllByOrderByCreatedAtDesc();

    /** Baaki sab: jo requests unhone banayi, ya jinme wo (ya unka role) chain mein hain. */
    @Query("SELECT r FROM StockOutwardRequest r WHERE r.companyName = :company AND ("
         + "r.requestedById = :userId OR EXISTS (SELECT a.id FROM StockOutwardApproval a "
         + "WHERE a.requestId = r.id AND ((a.approverType = 'USER' AND a.approverUserId = :userId) "
         + "OR (a.approverType = 'ROLE' AND a.approverRoleId = :roleId)))) "
         + "ORDER BY r.createdAt DESC")
    List<StockOutwardRequest> findInvolving(@Param("company") String company,
                                            @Param("userId") Long userId,
                                            @Param("roleId") Long roleId);
}
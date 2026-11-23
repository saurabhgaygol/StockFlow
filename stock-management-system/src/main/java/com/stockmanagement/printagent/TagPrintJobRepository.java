package com.stockmanagement.printagent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface TagPrintJobRepository extends JpaRepository<TagPrintJob, Long> {
    Optional<TagPrintJob> findByIdAndCompanyName(Long id, String companyName);
    Optional<TagPrintJob> findByIdAndAgentId(Long id, Long agentId);
    boolean existsByAgentIdAndStatus(Long agentId, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from TagPrintJob j where j.agentId = :agentId and j.status = 'QUEUED' order by j.id asc")
    List<TagPrintJob> lockQueued(@Param("agentId") Long agentId, org.springframework.data.domain.Pageable page);
}
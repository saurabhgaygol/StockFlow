package com.stockmanagement.repository;


import com.stockmanagement.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();

    /** Sirf diye gaye users ke logs (company-scoped view ke liye). */
    List<AuditLog> findTop100ByUserIdInOrderByCreatedAtDesc(Collection<Long> userIds);
}
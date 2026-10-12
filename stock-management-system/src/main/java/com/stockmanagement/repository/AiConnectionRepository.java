package com.stockmanagement.repository;

import com.stockmanagement.entity.AiConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiConnectionRepository extends JpaRepository<AiConnection, Long> {

    List<AiConnection> findByScopeOrderByCompanyNameAscNameAsc(String scope);

    List<AiConnection> findByOwnerUserIdAndScopeOrderByNameAsc(Long ownerUserId, String scope);

    List<AiConnection> findByCompanyNameAndScopeAndStatus(String companyName, String scope, String status);
}
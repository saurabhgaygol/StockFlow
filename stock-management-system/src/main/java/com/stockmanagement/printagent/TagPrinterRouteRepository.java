package com.stockmanagement.printagent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagPrinterRouteRepository extends JpaRepository<TagPrinterRoute, Long> {
    List<TagPrinterRoute> findByCompanyName(String companyName);
    Optional<TagPrinterRoute> findByCompanyNameAndPrinterCode(String companyName, String printerCode);
    List<TagPrinterRoute> findByAgentId(Long agentId);
}
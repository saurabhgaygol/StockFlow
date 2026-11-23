package com.stockmanagement.printagent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TagAgentPrinterRepository extends JpaRepository<TagAgentPrinter, Long> {
    List<TagAgentPrinter> findByCompanyNameOrderByPrinterNameAsc(String companyName);
    List<TagAgentPrinter> findByAgentIdOrderByPrinterNameAsc(Long agentId);

    @Modifying
    @Query("delete from TagAgentPrinter p where p.agentId = :agentId")
    void deleteByAgent(@Param("agentId") Long agentId);
}
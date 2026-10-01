package com.stockmanagement.repository;

import com.stockmanagement.entity.ApprovalLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalLevelRepository extends JpaRepository<ApprovalLevel, Long> {

    List<ApprovalLevel> findByCompanyNameAndStatusOrderByLevelNoAsc(String companyName, String status);
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.CompanyBusinessType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CompanyBusinessTypeRepository extends JpaRepository<CompanyBusinessType, Long> {

    List<CompanyBusinessType> findByCompanyNameOrderByIdAsc(String companyName);

    boolean existsByCompanyName(String companyName);
}
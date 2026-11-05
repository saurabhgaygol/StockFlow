package com.stockmanagement.repository;

import com.stockmanagement.entity.CompanyFeature;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyFeatureRepository extends JpaRepository<CompanyFeature, Long> {

    List<CompanyFeature> findByCompanyName(String companyName);

    Optional<CompanyFeature> findByCompanyNameAndFeatureCode(String companyName, String featureCode);
}
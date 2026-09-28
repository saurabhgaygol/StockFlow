package com.stockmanagement.repository;

import com.stockmanagement.entity.CompanyVendor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CompanyVendorRepository extends JpaRepository<CompanyVendor, Long> {

   List<CompanyVendor> findByCompanyNameAndStatus(String companyName, String status);

   Optional<CompanyVendor> findByCompanyNameAndVendorId(String companyName, Long vendorId);

   List<CompanyVendor> findByVendorId(Long vendorId);

   boolean existsByCompanyNameAndVendorId(String companyName, Long vendorId);
}
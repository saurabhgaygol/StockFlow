package com.stockmanagement.repository;

import com.stockmanagement.entity.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
 
import java.util.List;
import java.util.Optional;
 
public interface VendorRepository extends JpaRepository<Vendor, Long> {
 
    List<Vendor> findByStatus(String status);
 
    List<Vendor> findByAddedByCompany(String addedByCompany);
 
    Optional<Vendor> findByVendorNameIgnoreCase(String vendorName);
}
 

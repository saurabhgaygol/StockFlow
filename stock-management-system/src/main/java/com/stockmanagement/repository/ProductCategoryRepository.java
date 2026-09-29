package com.stockmanagement.repository;


import com.stockmanagement.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    List<ProductCategory> findByCompanyName(String companyName);

    List<ProductCategory> findByCompanyNameAndStatus(String companyName, String status);
    
    List<ProductCategory> findByCategoryNameAndCompanyNameAndStatus(
            String categoryName, String companyName, String status);

    Optional<ProductCategory> findByCategoryNameIgnoreCaseAndCompanyName(String categoryName, String companyName);
}
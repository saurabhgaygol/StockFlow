package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailShopLogo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RetailShopLogoRepository extends JpaRepository<RetailShopLogo, Long> {

    Optional<RetailShopLogo> findByCompanyName(String companyName);

    boolean existsByCompanyName(String companyName);

    void deleteByCompanyName(String companyName);
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailShopProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RetailShopProfileRepository extends JpaRepository<RetailShopProfile, Long> {

    Optional<RetailShopProfile> findByCompanyName(String companyName);
}
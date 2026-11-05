package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RetailOfferRepository extends JpaRepository<RetailOffer, Long> {

    List<RetailOffer> findByCompanyNameOrderByIdDesc(String companyName);

    Optional<RetailOffer> findByIdAndCompanyName(Long id, String companyName);
}
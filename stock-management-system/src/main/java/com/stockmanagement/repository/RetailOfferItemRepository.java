package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailOfferItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RetailOfferItemRepository extends JpaRepository<RetailOfferItem, Long> {

    List<RetailOfferItem> findByCompanyNameAndOfferIdIn(String companyName, Collection<Long> offerIds);

    List<RetailOfferItem> findByCompanyNameAndOfferId(String companyName, Long offerId);

    void deleteByCompanyNameAndOfferId(String companyName, Long offerId);
}
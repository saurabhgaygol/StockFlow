package com.stockmanagement.repository;

import com.stockmanagement.entity.TagDesignChoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TagDesignChoiceRepository extends JpaRepository<TagDesignChoice, Long> {

    List<TagDesignChoice> findByCompanyName(String companyName);

    Optional<TagDesignChoice> findByCompanyNameAndPrinterCode(String companyName, String printerCode);
}
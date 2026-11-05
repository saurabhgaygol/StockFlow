package com.stockmanagement.repository;

import com.stockmanagement.entity.RetailInward;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RetailInwardRepository extends JpaRepository<RetailInward, Long> {

    List<RetailInward> findByCompanyNameOrderByIdDesc(String companyName, Pageable pageable);

    Optional<RetailInward> findByIdAndCompanyName(Long id, String companyName);

    List<RetailInward> findByCompanyNameAndCreatedAtBetweenOrderByIdDesc(String companyName,
                                                                         LocalDateTime from, LocalDateTime to,
                                                                         Pageable pageable);
}
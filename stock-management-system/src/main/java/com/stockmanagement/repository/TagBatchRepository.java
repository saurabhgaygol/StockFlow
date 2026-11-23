package com.stockmanagement.repository;

import com.stockmanagement.entity.TagBatch;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TagBatchRepository extends JpaRepository<TagBatch, Long> {

    List<TagBatch> findByCompanyNameOrderByIdDesc(String companyName, Pageable page);

    Optional<TagBatch> findByIdAndCompanyName(Long id, String companyName);

    /** row lock while a batch is edited, so two people saving at once cannot get the same new piece numbers */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from TagBatch b where b.id = :id and b.companyName = :company")
    Optional<TagBatch> findLocked(@Param("id") Long id, @Param("company") String company);
}
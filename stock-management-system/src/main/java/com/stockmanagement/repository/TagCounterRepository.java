package com.stockmanagement.repository;

import com.stockmanagement.entity.TagCounter;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TagCounterRepository extends JpaRepository<TagCounter, Long> {

    /** locked until the transaction ends, so two users can never take the same TAG number */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from TagCounter c where c.companyName = :company and c.tagYear = :year")
    Optional<TagCounter> findLocked(@Param("company") String company, @Param("year") Integer year);
}
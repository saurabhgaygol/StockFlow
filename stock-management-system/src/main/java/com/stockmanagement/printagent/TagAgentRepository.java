package com.stockmanagement.printagent;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TagAgentRepository extends JpaRepository<TagAgent, Long> {
    Optional<TagAgent> findByTokenHash(String tokenHash);
    Optional<TagAgent> findByPairCode(String pairCode);
    Optional<TagAgent> findByIdAndCompanyName(Long id, String companyName);
    /** paired computers of a shop, newest first */
    List<TagAgent> findByCompanyNameAndTokenHashIsNotNullOrderByIdDesc(String companyName);
    /** unused pairing codes of a shop (cleaned up when a new one is made) */
    List<TagAgent> findByCompanyNameAndTokenHashIsNull(String companyName);
    List<TagAgent> findByTokenHashIsNullAndPairExpiresAtBefore(LocalDateTime t);
}
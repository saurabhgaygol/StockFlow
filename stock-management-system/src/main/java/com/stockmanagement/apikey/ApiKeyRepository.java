package com.stockmanagement.apikey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** Request ke time: aayi hui key ke hash se dhundhna (asli key DB me nahi hoti). */
    Optional<ApiKey> findByKeyHash(String keyHash);

    /** User ki chalu (delete nahi hui) key. Rule: ek user ki ek hi. */
    List<ApiKey> findByUserIdAndDeletedAtIsNull(Long userId);

    /** Admin list: saari chalu keys. */
    List<ApiKey> findByDeletedAtIsNullOrderByCreatedAtDesc();

    /** Admin list: ek company ki chalu keys. */
    List<ApiKey> findByCompanyNameAndDeletedAtIsNullOrderByCreatedAtDesc(String companyName);
}
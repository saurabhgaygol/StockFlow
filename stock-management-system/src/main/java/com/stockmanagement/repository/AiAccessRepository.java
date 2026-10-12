package com.stockmanagement.repository;

import com.stockmanagement.entity.AiAccess;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AiAccessRepository extends JpaRepository<AiAccess, Long> {

    List<AiAccess> findByConnectionId(Long connectionId);

    List<AiAccess> findByConnectionIdIn(Collection<Long> connectionIds);

    void deleteByConnectionId(Long connectionId);
}
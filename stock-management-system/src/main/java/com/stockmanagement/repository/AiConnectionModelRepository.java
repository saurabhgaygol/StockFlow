package com.stockmanagement.repository;

import com.stockmanagement.entity.AiConnectionModel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AiConnectionModelRepository extends JpaRepository<AiConnectionModel, Long> {

    List<AiConnectionModel> findByConnectionIdOrderByIdAsc(Long connectionId);

    List<AiConnectionModel> findByConnectionIdIn(Collection<Long> connectionIds);

    void deleteByConnectionId(Long connectionId);
}
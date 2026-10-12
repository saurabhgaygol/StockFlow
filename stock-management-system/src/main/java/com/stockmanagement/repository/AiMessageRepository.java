package com.stockmanagement.repository;

import com.stockmanagement.entity.AiMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    List<AiMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Context ke liye aakhri messages (naye pehle). */
    List<AiMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    @Query("select count(m) from AiMessage m where m.userId = :uid and m.connectionId = :cid "
            + "and m.role = 'user' and m.createdAt >= :since")
    long countUserMessagesSince(@Param("uid") Long userId, @Param("cid") Long connectionId,
                                @Param("since") LocalDateTime since);

    @Transactional
    void deleteByConversationId(Long conversationId);

    /** Edit / regenerate: is message se lekar aage ke saare messages hata do. */
    @Transactional
    void deleteByConversationIdAndIdGreaterThanEqual(Long conversationId, Long id);
}
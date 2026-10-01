package com.stockmanagement.repository;

import com.stockmanagement.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop20ByTargetUserIdOrderByCreatedAtDesc(Long targetUserId);

    long countByTargetUserIdAndIsReadFalse(Long targetUserId);
    
    @Modifying
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.targetUserId = :userId AND n.url = :url AND n.isRead = false")
    int markReadByUrl(@Param("userId") Long userId, @Param("url") String url);
}
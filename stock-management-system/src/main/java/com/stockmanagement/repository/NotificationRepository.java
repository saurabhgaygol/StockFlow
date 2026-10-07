package com.stockmanagement.repository;

import com.stockmanagement.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop20ByTargetUserIdAndIsReadFalseOrderByCreatedAtDesc(Long targetUserId);

    long countByTargetUserIdAndIsReadFalse(Long targetUserId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.targetUserId = :userId AND n.url = :url AND n.isRead = false")
    int markReadByUrl(@Param("userId") Long userId, @Param("url") String url);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true WHERE n.id = :id AND n.targetUserId = :userId AND n.isRead = false")
    int markReadById(@Param("userId") Long userId, @Param("id") Long id);
}
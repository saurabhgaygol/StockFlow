package com.stockmanagement.repository;

import com.stockmanagement.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findTop20ByTargetUserIdOrderByCreatedAtDesc(Long targetUserId);

    long countByTargetUserIdAndIsReadFalse(Long targetUserId);
}
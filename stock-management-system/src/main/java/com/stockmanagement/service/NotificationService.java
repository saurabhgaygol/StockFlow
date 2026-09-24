package com.stockmanagement.service;

import com.stockmanagement.dto.NotificationDto;
import com.stockmanagement.entity.Notification;
import com.stockmanagement.repository.NotificationRepository;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(NotificationRepository notificationRepository,
                                SimpMessagingTemplate messagingTemplate) {
        this.notificationRepository = notificationRepository;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Naya notification create karta hai:
     * 1. DB mein save karta hai (permanent, page reload pe bhi dikhega) — targetUserId se
     * 2. WebSocket se turant push karta hai (agar user online hai) — targetUsername se
     *    (STOMP per-user routing Principal.getName() yaani username se hoti hai)
     *
     * Kahin se bhi (stock request wale controller/service se) aise call karo:
     *   notificationService.sendNotification(
     *       targetUserId,
     *       targetUser.getUsername(),
     *       "Stock Request",
     *       currentUser.getUsername() + " ne " + itemName + " ka stock request kiya hai",
     *       "/stock/requests/" + requestId
     *   );
     */
    public void sendNotification(Long targetUserId, String targetUsername,
                                  String title, String message, String url) {

        Notification n = new Notification();
        n.setTargetUserId(targetUserId);
        n.setTitle(title);
        n.setMessage(message);
        n.setUrl(url);
        n.setRead(false);

        n = notificationRepository.save(n);

        NotificationDto dto = new NotificationDto(
                n.getId(),
                n.getTitle(),
                n.getMessage(),
                formatTime(n.getCreatedAt()),
                n.getUrl(),
                n.isRead()
        );

        // real-time push — sirf usi user ko jo currently WebSocket se connected hai
        messagingTemplate.convertAndSendToUser(
                targetUsername,
                "/queue/notifications",
                dto
        );
    }

    /** Page load ke waqt (Thymeleaf model) use karne ke liye — last 20 notifications */
    public List<NotificationDto> getRecentNotifications(Long userId) {
        return notificationRepository.findTop20ByTargetUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(n -> new NotificationDto(
                        n.getId(), n.getTitle(), n.getMessage(),
                        formatTime(n.getCreatedAt()), n.getUrl(), n.isRead()))
                .collect(Collectors.toList());
    }

    public long getUnreadCount(Long userId) {
        return notificationRepository.countByTargetUserIdAndIsReadFalse(userId);
    }

    private String formatTime(java.time.LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("dd MMM, hh:mm a"));
    }
}

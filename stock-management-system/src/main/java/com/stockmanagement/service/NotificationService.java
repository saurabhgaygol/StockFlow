package com.stockmanagement.service;

import org.springframework.transaction.annotation.Transactional;
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

    /** Page load ke waqt: sirf unread, last 20 */
    public List<NotificationDto> getRecentNotifications(Long userId) {
        return notificationRepository.findTop20ByTargetUserIdAndIsReadFalseOrderByCreatedAtDesc(userId)
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

    /** Notification wala page kholte hi wo read ho jati hai. */
    @Transactional
    public void markReadByUrl(Long userId, String url) {
        notificationRepository.markReadByUrl(userId, url);
    }

    /** X (dismiss) dabane par ek notification read ho jati hai. */
    @Transactional
    public void markReadById(Long userId, Long id) {
        notificationRepository.markReadById(userId, id);
    }
}
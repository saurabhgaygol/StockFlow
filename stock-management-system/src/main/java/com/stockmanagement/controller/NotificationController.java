package com.stockmanagement.controller;


import com.stockmanagement.dto.NotificationDto;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.NotificationService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notificationService;

    public NotificationController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping
    public Map<String, Object> getNotifications(@AuthenticationPrincipal CustomUserDetails userDetails) {
        Long userId = userDetails.getUserId();
        List<NotificationDto> list = notificationService.getRecentNotifications(userId);
        long unread = notificationService.getUnreadCount(userId);
        return Map.of("notifications", list, "unreadCount", unread);
    }
}
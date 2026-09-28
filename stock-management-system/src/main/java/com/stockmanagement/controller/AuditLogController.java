package com.stockmanagement.controller;

import com.stockmanagement.entity.AuditLog;
import com.stockmanagement.repository.AuditLogRepository;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class AuditLogController {

    private final UserPermissionService userPermissionService;
    private final AuditLogRepository auditLogRepository;

    public AuditLogController(UserPermissionService userPermissionService, AuditLogRepository auditLogRepository) {
        this.userPermissionService = userPermissionService;
        this.auditLogRepository = auditLogRepository;
    }

    /* PAGE SHELL: logs yahan nahi, turant render hota hai */
    @GetMapping("/reports/audit-logs")
    public String auditLogsPage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Long userId = userDetails.getUserId();
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> permissionCodes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream()
                .map(p -> p.getPermissionCode())
                .collect(Collectors.toList());
        model.addAttribute("permissions", permissionCodes);

        return "reports/audit-logs";
    }

    /* DATA: DB query yahan chalti hai, htmx yahan se table load karta hai */
    @GetMapping("/reports/audit-logs/data")
    public String auditLogsData(Model model) {

        // TEST ke liye (loader check): uncomment karo, baad me hata do
        // try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        List<AuditLog> logs = auditLogRepository.findTop100ByOrderByCreatedAtDesc();
        model.addAttribute("logs", logs);

        return "reports/audit-logs :: logData";
    }
}
package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.AuditLog;
import com.stockmanagement.repository.AuditLogRepository;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;

import org.springframework.security.access.AccessDeniedException;
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
    private final UserRepository userRepository;

    public AuditLogController(UserPermissionService userPermissionService,
                              AuditLogRepository auditLogRepository,
                              UserRepository userRepository) {
        this.userPermissionService = userPermissionService;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    /* PAGE SHELL: logs yahan nahi, turant render hota hai */
    @GetMapping("/reports/audit-logs")
    @RequirePermission({"AUDIT_LOG_VIEW"})
    public String auditLogsPage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {
        require(userDetails, "AUDIT_LOG_VIEW");

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
    @RequirePermission({"AUDIT_LOG_VIEW"})
    public String auditLogsData(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        require(userDetails, "AUDIT_LOG_VIEW");

        // TEST ke liye (loader check): uncomment karo, baad me hata do
        // try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        List<AuditLog> logs;
        if (userPermissionService.hasPermission(userDetails.getUserId(), "SUPER_ADMIN")) {
            logs = auditLogRepository.findTop100ByOrderByCreatedAtDesc();
        } else {
            // Apni company ke users ke hi logs
            List<Long> companyUserIds = userRepository.findIdsByCompanyName(userDetails.getUser().getCompanyName());
            logs = companyUserIds.isEmpty()
                    ? List.of()
                    : auditLogRepository.findTop100ByUserIdInOrderByCreatedAtDesc(companyUserIds);
        }
        model.addAttribute("logs", logs);

        return "reports/audit-logs :: logData";
    }

    private void require(CustomUserDetails user, String permission) {
        Long id = user.getUserId();
        if (!userPermissionService.hasPermission(id, permission)
                && !userPermissionService.hasPermission(id, "SUPER_ADMIN")) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }
}
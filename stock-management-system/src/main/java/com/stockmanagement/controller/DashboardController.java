package com.stockmanagement.controller;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanagement.dto.PermissionResponseDTO;
import com.stockmanagement.dto.MenuModuleView;
import com.stockmanagement.dto.NotificationDto;
import com.stockmanagement.entity.Permission;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.SidebarMenuService;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.DashboardExportService;
import com.stockmanagement.service.DashboardService;
import com.stockmanagement.service.NotificationService;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class DashboardController {

    private final UserPermissionService userPermissionService;
    private final ObjectMapper objectMapper;
    private final SidebarMenuService sidebarMenuService;
    private final NotificationService notificationService;
    private final DashboardService dashboardService;
    private final DashboardExportService exportService;
    private final AuditLogService auditLogService;

    public DashboardController(
            UserPermissionService userPermissionService,
            ObjectMapper objectMapper,
            SidebarMenuService sidebarMenuService,
            NotificationService notificationService,
            DashboardService dashboardService,
            DashboardExportService exportService,
            AuditLogService auditLogService) {

        this.userPermissionService = userPermissionService;
        this.objectMapper = objectMapper;
        this.sidebarMenuService = sidebarMenuService;
        this.notificationService = notificationService;
        this.dashboardService = dashboardService;
        this.exportService = exportService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/dashboard")
    public String dashboard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model,
            HttpServletRequest request,
            @RequestParam(name = "range", required = false) String range,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) {

        Long userId = userDetails.getUserId();
        String username = userDetails.getUsername();

        model.addAttribute("userId", userId);
        model.addAttribute("username", username);

        List<Permission> permissions =
                userPermissionService.getAllowedPermissionDetails(userId);

        System.out.println("========== USER PERMISSION DETAILS ==========");

        for (Permission permission : permissions) {

            System.out.println(
                    "ID: " + permission.getId()
                    + " | Code: " + permission.getPermissionCode()
                    + " | Name: " + permission.getPermissionName()
                    + " | Module: " + permission.getModule()
                    + " | SubModule: " + permission.getSubModule()
                    + " | Action: " + permission.getAction()
            );
        }

        System.out.println("Total Permissions: " + permissions.size());
        System.out.println("=============================================");

        List<String> permissionCodes = permissions.stream()
                .map(Permission::getPermissionCode)
                .collect(Collectors.toList());

        model.addAttribute("permissions", permissionCodes);

        PermissionResponseDTO permissionResponse =
                userPermissionService.getPermissionResponse(userId);

        try {

            String json =
                    objectMapper
                            .writerWithDefaultPrettyPrinter()
                            .writeValueAsString(permissionResponse);

            System.out.println("========== PERMISSION JSON ==========");
            System.out.println(json);
            System.out.println("=====================================");

        } catch (Exception e) {

            e.printStackTrace();
        }

        List<MenuModuleView> menu = sidebarMenuService.buildMenu(permissionResponse);
        model.addAttribute("menu", menu);
        model.addAttribute("activePath", request.getRequestURI());

        boolean canViewUsers =
                userPermissionService.hasPermission(
                        userId,
                        "USER_VIEW"
                );

        boolean canAddUsers =
                userPermissionService.hasPermission(
                        userId,
                        "USER_ADD"
                );

        boolean canDeleteUsers =
                userPermissionService.hasPermission(
                        userId,
                        "USER_DELETE"
                );

        System.out.println("========== PERMISSION CHECK ==========");
        System.out.println("USER_VIEW   : " + canViewUsers);
        System.out.println("USER_ADD    : " + canAddUsers);
        System.out.println("USER_DELETE : " + canDeleteUsers);
        System.out.println("======================================");

        // ===== Notifications: topbar bell ke liye =====
        List<NotificationDto> notifications = notificationService.getRecentNotifications(userId);
        long notifUnreadCount = notificationService.getUnreadCount(userId);
        model.addAttribute("notifications", notifications);
        model.addAttribute("notifUnreadCount", notifUnreadCount);

        // ===== Dashboard widgets =====
        model.addAttribute("dash", dashboardService.build(userDetails, range, from, to));

        return "dashbords/dashboard";
    }

    // ===== Excel export (same filter as the page) =====
    @GetMapping("/dashboard/export")
    public ResponseEntity<byte[]> export(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletRequest request,
            @RequestParam(name = "range", required = false) String range,
            @RequestParam(name = "from", required = false) String from,
            @RequestParam(name = "to", required = false) String to) throws IOException {

        // Page jaisi hi permission: DASHBOARD_VIEW
        if (!userPermissionService.hasPermission(userDetails.getUserId(), "DASHBOARD_VIEW")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        DashboardService.DashboardView view = dashboardService.build(userDetails, range, from, to);
        byte[] bytes = exportService.toXlsx(view);

        try {
            auditLogService.log(userDetails.getUserId(), userDetails.getUsername(),
                    "EXPORT", "Reports", "Dashboard",
                    "Exported dashboard report (" + view.rangeLabel() + ")", request);
        } catch (Exception ignored) {
            // audit fail hone se download nahi rukna chahiye
        }

        String filename = "StockFlow-report-" + view.fromIso() + "_to_" + view.toIso() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
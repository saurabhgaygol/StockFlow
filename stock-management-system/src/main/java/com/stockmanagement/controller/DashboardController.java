package com.stockmanagement.controller;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockmanagement.dto.PermissionResponseDTO;
import com.stockmanagement.dto.MenuModuleView;
import com.stockmanagement.dto.NotificationDto;
import com.stockmanagement.entity.Permission;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.SidebarMenuService;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.NotificationService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class DashboardController {

    private final UserPermissionService userPermissionService;
    private final ObjectMapper objectMapper;
    private final SidebarMenuService sidebarMenuService;
    private final NotificationService notificationService;

    public DashboardController(
            UserPermissionService userPermissionService,
            ObjectMapper objectMapper,
            SidebarMenuService sidebarMenuService,
            NotificationService notificationService) {

        this.userPermissionService = userPermissionService;
        this.objectMapper = objectMapper;
        this.sidebarMenuService = sidebarMenuService;
        this.notificationService = notificationService;
    }

    @GetMapping("/dashboard")
    public String dashboard(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model,
            HttpServletRequest request) {

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

        return "dashbords/dashboard";
    }
}
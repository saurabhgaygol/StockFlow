package com.stockmanagement.controller;

import com.stockmanagement.dto.MenuModuleView;
import com.stockmanagement.dto.PermissionResponseDTO;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.NotificationService;
import com.stockmanagement.service.SidebarMenuService;
import com.stockmanagement.service.UserPermissionService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

@ControllerAdvice(annotations = Controller.class)
public class MenuAdvice {

    private final UserPermissionService userPermissionService;
    private final SidebarMenuService sidebarMenuService;
    private final NotificationService notificationService;

    public MenuAdvice(UserPermissionService userPermissionService,
                      SidebarMenuService sidebarMenuService,
                      NotificationService notificationService) {
        this.userPermissionService = userPermissionService;
        this.sidebarMenuService = sidebarMenuService;
        this.notificationService = notificationService;
    }

    @ModelAttribute
    public void addMenuToModel(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model,
            HttpServletRequest request) {

        if (userDetails == null) {
            return;
        }

        Long userId = userDetails.getUserId();
        PermissionResponseDTO permissionResponse = userPermissionService.getPermissionResponse(userId);

        List<MenuModuleView> menu = sidebarMenuService.buildMenu(permissionResponse);
        model.addAttribute("menu", menu);
        model.addAttribute("activePath", request.getRequestURI());

        // topbar ke bell aur avatar ko ye har page pe chahiye, sirf /dashboard pe nahi
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());
        model.addAttribute("notifications", notificationService.getRecentNotifications(userId));
        model.addAttribute("notifUnreadCount", notificationService.getUnreadCount(userId));
    }
}
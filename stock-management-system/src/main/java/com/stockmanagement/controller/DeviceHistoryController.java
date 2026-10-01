package com.stockmanagement.controller;

import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.StockUnitHistoryService;
import com.stockmanagement.service.UserPermissionService;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** IMEI search karo aur device ki poori zindagi dekho: inward, kisko becha, kis price pe. */
@Controller
@RequestMapping("/reports/device-history")
public class DeviceHistoryController {

    private final StockUnitHistoryService historyService;
    private final UserPermissionService permissionService;

    public DeviceHistoryController(StockUnitHistoryService historyService,
                                   UserPermissionService permissionService) {
        this.historyService = historyService;
        this.permissionService = permissionService;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(value = "imei", required = false) String imei,
                       Model model) {
        Long userId = user.getUserId();
        if (!permissionService.hasPermission(userId, "DEVICE_HISTORY_VIEW")
                && !permissionService.hasPermission(userId, "SUPER_ADMIN")) {
            throw new AccessDeniedException("Device history is not enabled for your role.");
        }

        model.addAttribute("query", imei == null ? "" : imei.trim());
        if (imei != null && !imei.isBlank()) {
            try {
                model.addAttribute("devices", historyService.search(imei, user));
            } catch (IllegalArgumentException e) {
                model.addAttribute("errorMessage", e.getMessage());
            }
        }
        return "reports/device-history.html";
    }
}
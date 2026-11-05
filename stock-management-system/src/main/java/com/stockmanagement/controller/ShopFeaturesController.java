package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CompanyFeatureService;
import com.stockmanagement.service.CompanyService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;

/** Super Admin only: switch retail features ON/OFF for a company. */
@Controller
public class ShopFeaturesController {

    private final CompanyFeatureService featureService;
    private final CompanyService companyService;
    private final UserPermissionService userPermissionService;
    private final AuditLogService auditLogService;

    public ShopFeaturesController(CompanyFeatureService featureService,
                                  CompanyService companyService,
                                  UserPermissionService userPermissionService,
                                  AuditLogService auditLogService) {
        this.featureService = featureService;
        this.companyService = companyService;
        this.userPermissionService = userPermissionService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/settings/shop-features")
    @RequirePermission({"SHOP_FEATURES_MANAGE"})
    public String page(@AuthenticationPrincipal CustomUserDetails userDetails,
                       @RequestParam(value = "company", required = false) String company,
                       Model model) {
        requireSuperAdmin(userDetails);

        List<String> companies = companyService.activeNames();
        String selected = (company != null && companies.contains(company)) ? company
                : (companies.isEmpty() ? null : companies.get(0));
        Set<String> enabled = selected == null ? Set.of() : featureService.enabledCodes(selected);

        model.addAttribute("companies", companies);
        model.addAttribute("selected", selected);
        model.addAttribute("features", featureService.catalog());
        model.addAttribute("enabled", enabled);
        return "settings/shop-features";
    }

    @PostMapping("/settings/shop-features/save")
    @RequirePermission({"SHOP_FEATURES_MANAGE"})
    public String save(@AuthenticationPrincipal CustomUserDetails userDetails,
                       @RequestParam("company") String company,
                       @RequestParam("featureCode") String featureCode,
                       @RequestParam("enabled") boolean enabled,
                       HttpServletRequest request,
                       RedirectAttributes ra) {
        requireSuperAdmin(userDetails);
        try {
            featureService.setFeature(company, featureCode, enabled, userDetails.getUserId(), userDetails.getUsername());
            auditLogService.log(userDetails.getUserId(), userDetails.getUsername(),
                    "SHOP_FEATURE_" + (enabled ? "ON" : "OFF"), "Settings", "Shop Features",
                    featureCode + " turned " + (enabled ? "ON" : "OFF") + " for " + company, request);
            ra.addFlashAttribute("successMessage", "Saved. " + featureCode + " is now " + (enabled ? "ON" : "OFF") + " for " + company + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        ra.addAttribute("company", company);
        return "redirect:/settings/shop-features";
    }

    /** SHOP_FEATURES_MANAGE alone is not enough: a company admin must never switch features for themselves. */
    private void requireSuperAdmin(CustomUserDetails userDetails) {
        if (userDetails == null || !userPermissionService.hasPermission(userDetails.getUserId(), "SUPER_ADMIN")) {
            throw new AccessDeniedException("Only Super Admin can manage shop features.");
        }
    }
}
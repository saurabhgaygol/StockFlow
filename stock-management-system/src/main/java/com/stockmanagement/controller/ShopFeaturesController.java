package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CompanyFeatureService;
import com.stockmanagement.service.CompanyService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.RetailBillingService;
import com.stockmanagement.service.RetailLogoService;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Super Admin only: switch retail features ON/OFF for a company. */
@Controller
public class ShopFeaturesController {

    private final CompanyFeatureService featureService;
    private final CompanyService companyService;
    private final UserPermissionService userPermissionService;
    private final AuditLogService auditLogService;
    private final RetailBillingService billingService;
    private final RetailLogoService logoService;

    public ShopFeaturesController(CompanyFeatureService featureService,
                                  CompanyService companyService,
                                  UserPermissionService userPermissionService,
                                  AuditLogService auditLogService,
                                  RetailBillingService billingService,
                                  RetailLogoService logoService) {
        this.billingService = billingService;
        this.logoService = logoService;
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
        model.addAttribute("profile", selected == null ? null : billingService.profile(selected));
        model.addAttribute("hasLogo", selected != null && logoService.has(selected));
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
            // Billing just switched ON: open the shop profile panel so the shop details are filled in at once
            if (enabled && CompanyFeatureService.RETAIL_BILLING.equals(featureCode)) {
                ra.addFlashAttribute("openProfile", true);
            }
            ra.addFlashAttribute("successMessage", "Saved. " + featureCode + " is now " + (enabled ? "ON" : "OFF") + " for " + company + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        ra.addAttribute("company", company);
        return "redirect:/settings/shop-features";
    }

    // ------------------------------------------------------------------ shop profile of the selected company

    @PostMapping("/settings/shop-features/shop-profile")
    @ResponseBody
    @RequirePermission({"SHOP_FEATURES_MANAGE"})
    public ResponseEntity<Map<String, Object>> saveProfile(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                           @RequestParam("company") String company,
                                                           @RequestBody RetailBillingService.ProfileIn in,
                                                           HttpServletRequest request) {
        requireSuperAdmin(userDetails);
        requireCompany(company);
        try {
            billingService.saveProfile(company, userDetails.getUserId(), userDetails.getUsername(), in, request);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @GetMapping("/settings/shop-features/shop-profile/logo")
    @ResponseBody
    @RequirePermission({"SHOP_FEATURES_MANAGE"})
    public ResponseEntity<byte[]> logo(@AuthenticationPrincipal CustomUserDetails userDetails,
                                       @RequestParam("company") String company) {
        requireSuperAdmin(userDetails);
        requireCompany(company);
        return logoService.get(company)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(l.getContentType()))
                        .cacheControl(CacheControl.noCache())
                        .body(l.getImageData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/settings/shop-features/shop-profile/logo")
    @ResponseBody
    @RequirePermission({"SHOP_FEATURES_MANAGE"})
    public ResponseEntity<Map<String, Object>> saveLogo(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                        @RequestParam("company") String company,
                                                        @RequestParam("file") MultipartFile file) {
        requireSuperAdmin(userDetails);
        requireCompany(company);
        try {
            logoService.save(company, file.getBytes());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (java.io.IOException e) {
            return bad("Could not read the file. Please try again.");
        }
    }

    @PostMapping("/settings/shop-features/shop-profile/logo/remove")
    @ResponseBody
    @RequirePermission({"SHOP_FEATURES_MANAGE"})
    public ResponseEntity<Map<String, Object>> removeLogo(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                          @RequestParam("company") String company) {
        requireSuperAdmin(userDetails);
        requireCompany(company);
        logoService.remove(company);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private void requireCompany(String company) {
        if (company == null || !companyService.activeNames().contains(company)) {
            throw new AccessDeniedException("Unknown company.");
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return ResponseEntity.badRequest().body(m);
    }

    /** SHOP_FEATURES_MANAGE alone is not enough: a company admin must never switch features for themselves. */
    private void requireSuperAdmin(CustomUserDetails userDetails) {
        if (userDetails == null || !userPermissionService.hasPermission(userDetails.getUserId(), "SUPER_ADMIN")) {
            throw new AccessDeniedException("Only Super Admin can manage shop features.");
        }
    }
}
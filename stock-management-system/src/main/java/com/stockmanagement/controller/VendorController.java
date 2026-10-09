package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.Vendor;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.VendorService;
import com.stockmanagement.service.VendorService.AddVendorResult;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class VendorController {

    private final VendorService vendorService;
    private final UserPermissionService userPermissionService;

    public VendorController(VendorService vendorService, UserPermissionService userPermissionService) {
        this.vendorService = vendorService;
        this.userPermissionService = userPermissionService;
    }

    @GetMapping("/settings/vendors")
    @RequirePermission({"VENDOR_VIEW", "VENDOR_ADD", "VENDOR_EDIT", "VENDOR_DELETE"})
    public String listVendors(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {
        // Module ki koi bhi ek permission ho to page khulta hai; list sirf VENDOR_VIEW walon ko dikhti hai
        requireAny(userDetails, "VENDOR_VIEW", "VENDOR_ADD", "VENDOR_EDIT", "VENDOR_DELETE");
        boolean canView = has(userDetails, "VENDOR_VIEW");

        Long userId = userDetails.getUserId();

        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> permissionCodes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream()
                .map(p -> p.getPermissionCode())
                .collect(Collectors.toList());

        model.addAttribute("permissions", permissionCodes);
        model.addAttribute("canView", canView);
        model.addAttribute("vendors", canView ? vendorService.getVendorsForUser(userDetails) : List.<Vendor>of());
        model.addAttribute("newVendor", new Vendor());

        return "settings/vendor-list";
    }

    @PostMapping("/settings/vendors/add")
    @RequirePermission({"VENDOR_ADD"})
    public String addVendor(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @ModelAttribute("newVendor") Vendor vendor,
            RedirectAttributes redirectAttributes) {
        require(userDetails, "VENDOR_ADD");

        String companyName = userDetails.getUser().getCompanyName();
        String createdBy = userDetails.getUsername();

        AddVendorResult result = vendorService.addVendorForCompany(vendor, companyName, createdBy);

        if (result.alreadyInYourList()) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "\"" + result.vendor().getVendorName() + "\" is already in your vendor list.");
        } else if (result.vendorWasNew()) {
            redirectAttributes.addFlashAttribute("successMessage",
                    "Vendor added.");
        } else {
            redirectAttributes.addFlashAttribute("successMessage",
                    "\"" + result.vendor().getVendorName() + "\" already existed — added to your vendor list.");
        }

        return "redirect:/settings/vendors";
    }

    @PostMapping("/settings/vendors/edit/{id}")
    @RequirePermission({"VENDOR_EDIT"})
    public String editVendor(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @ModelAttribute("newVendor") Vendor vendor,
            RedirectAttributes redirectAttributes) {
        require(userDetails, "VENDOR_EDIT");

        String companyName = userDetails.getUser().getCompanyName();
        boolean superAdmin = userPermissionService.hasPermission(userDetails.getUserId(), "SUPER_ADMIN");

        try {
            vendorService.updateVendor(id, vendor, companyName, superAdmin);
            redirectAttributes.addFlashAttribute("successMessage", "Vendor updated.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        // AccessDeniedException (dusri company ka vendor) catch nahi hota -> AccessDeniedAdvice handle karta hai

        return "redirect:/settings/vendors";
    }

    @PostMapping("/settings/vendors/delete/{id}")
    @RequirePermission({"VENDOR_DELETE"})
    public String deleteVendor(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            RedirectAttributes redirectAttributes) {
        require(userDetails, "VENDOR_DELETE");

        String companyName = userDetails.getUser().getCompanyName();

        try {
            vendorService.removeVendorForCompany(id, companyName);
            redirectAttributes.addFlashAttribute("successMessage", "Vendor removed from your list.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/settings/vendors";
    }

    private boolean has(CustomUserDetails user, String permission) {
        Long id = user.getUserId();
        return userPermissionService.hasPermission(id, permission)
                || userPermissionService.hasPermission(id, "SUPER_ADMIN");
    }

    private void requireAny(CustomUserDetails user, String... permissions) {
        for (String p : permissions) {
            if (has(user, p)) return;
        }
        throw new AccessDeniedException("You do not have permission for this page.");
    }

    private void require(CustomUserDetails user, String permission) {
        Long id = user.getUserId();
        if (!userPermissionService.hasPermission(id, permission)
                && !userPermissionService.hasPermission(id, "SUPER_ADMIN")) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }
}
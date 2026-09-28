package com.stockmanagement.controller;

import com.stockmanagement.entity.Vendor;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.VendorService;
import com.stockmanagement.service.VendorService.AddVendorResult;

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
    public String listVendors(
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
        model.addAttribute("vendors", vendorService.getVendorsForUser(userDetails));
        model.addAttribute("newVendor", new Vendor());

        return "settings/vendor-list";
    }

    @PostMapping("/settings/vendors/add")
    public String addVendor(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @ModelAttribute("newVendor") Vendor vendor,
            RedirectAttributes redirectAttributes) {

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
    public String editVendor(
            @PathVariable Long id,
            @ModelAttribute("newVendor") Vendor vendor,
            RedirectAttributes redirectAttributes) {

        try {
            vendorService.updateVendor(id, vendor);
            redirectAttributes.addFlashAttribute("successMessage", "Vendor updated.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/settings/vendors";
    }

    @PostMapping("/settings/vendors/delete/{id}")
    public String deleteVendor(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            RedirectAttributes redirectAttributes) {

        String companyName = userDetails.getUser().getCompanyName();
        vendorService.removeVendorForCompany(id, companyName);
        redirectAttributes.addFlashAttribute("successMessage", "Vendor removed from your list.");

        return "redirect:/settings/vendors";
    }
}
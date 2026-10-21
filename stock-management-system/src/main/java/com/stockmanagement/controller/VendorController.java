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

        // GST ke alawa sab zaruri
        String missing = missingVendorField(vendor);
        if (missing != null) {
            redirectAttributes.addFlashAttribute("errorMessage", missing + " is required.");
            return "redirect:/settings/vendors";
        }

        String companyName = userDetails.getUser().getCompanyName();
        String createdBy = userDetails.getUsername();

        AddVendorResult result;
        try {
            result = vendorService.addVendorForCompany(vendor, companyName, createdBy);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/settings/vendors";
        }

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
            // Edit: naam zaruri; jo field pehle se bhara hai use khali nahi kar sakte
            Vendor current = vendorService.getVendorById(id);
            String cleared = clearedVendorField(vendor, current);
            if (cleared != null) {
                redirectAttributes.addFlashAttribute("errorMessage", cleared + " is required.");
                return "redirect:/settings/vendors";
            }
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

    /** Vendor add: GST ke alawa sab zaruri. Kuch kam ho to uska naam, warna null. */
    private String missingVendorField(Vendor v) {
        if (isBlank(v.getVendorName())) return "Vendor name";
        if (isBlank(v.getContactPerson())) return "Contact person";
        if (isBlank(v.getPhone())) return "Phone";
        if (isBlank(v.getEmail())) return "Email";
        if (isBlank(v.getAddress())) return "Address";
        return null;
    }

    /** Vendor edit: naam zaruri; pehle se bhara field khali ho gaya ho to uska naam, warna null. */
    private String clearedVendorField(Vendor in, Vendor cur) {
        if (isBlank(in.getVendorName())) return "Vendor name";
        if (isBlank(in.getContactPerson()) && !isBlank(cur.getContactPerson())) return "Contact person";
        if (isBlank(in.getPhone()) && !isBlank(cur.getPhone())) return "Phone";
        if (isBlank(in.getEmail()) && !isBlank(cur.getEmail())) return "Email";
        if (isBlank(in.getAddress()) && !isBlank(cur.getAddress())) return "Address";
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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
package com.stockmanagement.controller;

import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.StaffStockService;
import com.stockmanagement.service.StaffStockService.Result;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/**
 * Field staff stock:
 *   - "Issue to Staff" popup on the Stock Outward panel posts here (give / take back / staff to staff)
 *   - /reports/staff-stock : which staff holds which device, temporary fits, movement history
 */
@Controller
public class StaffStockController {

    private final StaffStockService staffService;
    private final AuditLogService auditLogService;

    public StaffStockController(StaffStockService staffService, AuditLogService auditLogService) {
        this.staffService = staffService;
        this.auditLogService = auditLogService;
    }

    /* ===================== JSON for the popups ===================== */

    @GetMapping("/settings/staff-stock/staff")
    @ResponseBody
    public ResponseEntity<?> staff(@AuthenticationPrincipal CustomUserDetails user) {
        try {
            if (!staffService.canIssue(user)) throw new AccessDeniedException("no");
            return ResponseEntity.ok(staffService.staffList(user));
        } catch (AccessDeniedException e) {
            return forbidden();
        }
    }

    @GetMapping("/settings/staff-stock/office-units")
    @ResponseBody
    public ResponseEntity<?> officeUnits(@AuthenticationPrincipal CustomUserDetails user) {
        try {
            return ResponseEntity.ok(staffService.officeUnits(user));
        } catch (AccessDeniedException e) {
            return forbidden();
        }
    }

    @GetMapping("/settings/staff-stock/units")
    @ResponseBody
    public ResponseEntity<?> units(@AuthenticationPrincipal CustomUserDetails user,
                                   @RequestParam("staffId") Long staffId) {
        try {
            if (!staffService.canIssue(user)) throw new AccessDeniedException("no");
            return ResponseEntity.ok(staffService.staffUnits(user, staffId));
        } catch (AccessDeniedException e) {
            return forbidden();
        }
    }

    /** Last staff stock movements (History tab of the Issue to Staff popup). */
    @GetMapping("/settings/staff-stock/history")
    @ResponseBody
    public ResponseEntity<?> history(@AuthenticationPrincipal CustomUserDetails user) {
        try {
            java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            for (com.stockmanagement.entity.StaffStockMovement m : staffService.recentMovements(user)) {
                if (rows.size() >= 100) break;
                Map<String, Object> r = new java.util.LinkedHashMap<>();
                r.put("when", m.getCreatedAt() == null ? "" : m.getCreatedAt().format(f));
                r.put("type", m.getTypeLabel());
                r.put("batch", m.getBatchNo());
                r.put("imei", m.getImeiNumber());
                r.put("product", m.getProductName());
                r.put("from", m.getFromName());
                r.put("to", m.getToName());
                r.put("by", m.getCreatedBy());
                r.put("remarks", m.getRemarks());
                rows.add(r);
            }
            return ResponseEntity.ok(rows);
        } catch (AccessDeniedException e) {
            return forbidden();
        }
    }

    /* ===================== actions (forms post here) ===================== */

    @PostMapping("/settings/staff-stock/issue")
    public String issue(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                        @RequestParam("staffId") Long staffId,
                        @RequestParam(value = "stockIds", required = false) List<Long> stockIds,
                        @RequestParam(value = "remarks", required = false) String remarks,
                        RedirectAttributes ra) {
        try {
            Result r = staffService.issue(user, ip(request), staffId, stockIds, remarks);
            ra.addFlashAttribute("successMessage", r.count() + " device(s) given to "
                    + staffService.nameOf(staffId) + ". Batch " + r.batchNo() + ".");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward";
    }

    @PostMapping("/settings/staff-stock/take-back")
    public String takeBack(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                           @RequestParam(value = "stockIds", required = false) List<Long> stockIds,
                           @RequestParam(value = "backTo", required = false) String backTo,
                           @RequestParam(value = "remarks", required = false) String remarks,
                           RedirectAttributes ra) {
        try {
            Result r = staffService.takeBack(user, ip(request), stockIds, backTo, remarks);
            ra.addFlashAttribute("successMessage", r.count() + " device(s) taken back to the office. Batch "
                    + r.batchNo() + ".");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward";
    }

    @PostMapping("/settings/staff-stock/transfer")
    public String transfer(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                           @RequestParam("toStaffId") Long toStaffId,
                           @RequestParam(value = "stockIds", required = false) List<Long> stockIds,
                           @RequestParam(value = "remarks", required = false) String remarks,
                           RedirectAttributes ra) {
        try {
            Result r = staffService.transfer(user, ip(request), toStaffId, stockIds, remarks);
            ra.addFlashAttribute("successMessage", r.count() + " device(s) moved to "
                    + staffService.nameOf(toStaffId) + ". Batch " + r.batchNo() + ".");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward";
    }

    @PostMapping("/settings/staff-stock/temp-permanent")
    public String tempPermanent(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                                @RequestParam("stockId") Long stockId, RedirectAttributes ra) {
        try {
            staffService.makePermanent(user, ip(request), stockId);
            ra.addFlashAttribute("successMessage", "Device is now permanent at the customer.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/reports/staff-stock";
    }

    @PostMapping("/settings/staff-stock/temp-back")
    public String tempBack(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                           @RequestParam("stockId") Long stockId,
                           @RequestParam(value = "remarks", required = false) String remarks,
                           RedirectAttributes ra) {
        try {
            staffService.tempTakenBack(user, ip(request), stockId, remarks);
            ra.addFlashAttribute("successMessage", "Temporary device is back with the staff member.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/reports/staff-stock";
    }

    /* ===================== report page ===================== */

    @GetMapping("/reports/staff-stock")
    public String report(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        staffService.requireView(user);
        List<StaffStockService.StaffBlock> blocks = staffService.report(user);
        model.addAttribute("blocks", blocks);
        model.addAttribute("totalInHand", blocks.stream().mapToLong(b -> b.staff().inHand()).sum());
        model.addAttribute("totalTemp", blocks.stream().mapToLong(b -> b.staff().temporary()).sum());
        model.addAttribute("movements", staffService.recentMovements(user));
        model.addAttribute("canManage", staffService.canIssue(user));
        return "reports/staff-stock";
    }

    /* ===================== helpers ===================== */

    private String ip(HttpServletRequest request) {
        return auditLogService.getClientIp(request);
    }

    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You are not allowed to do this."));
    }

    private void flashError(RedirectAttributes ra, RuntimeException e) {
        if (e instanceof AccessDeniedException) {
            ra.addFlashAttribute("errorMessage", "You are not allowed to do this.");
        } else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } else {
            throw e;
        }
    }
}
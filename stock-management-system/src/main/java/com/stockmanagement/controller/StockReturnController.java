package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.StockReturn;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.StaffStockService;
import com.stockmanagement.service.StockReturnService;
import com.stockmanagement.service.StockReturnService.ReceiveForm;
import com.stockmanagement.service.StockReturnService.ReturnForm;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Customer Return screen (return cases + vendor repair + exchange):
 *  - type the IMEI, see who bought it / warranty
 *  - then: put it back into stock, mark it damaged, or send it to a vendor for repair
 *  - optionally also give any AVAILABLE device to the customer (exchange, chosen from a popup)
 *  - "Currently with vendor" list: receive the device back (repaired / replaced / not repairable)
 */
@Controller
@RequestMapping("/settings/stock-return")
public class StockReturnController {

    private final StockReturnService returnService;
    private final UserPermissionService permissionService;
    private final AuditLogService auditLogService;
    private final StaffStockService staffStockService;

    public StockReturnController(StockReturnService returnService,
                                 UserPermissionService permissionService,
                                 AuditLogService auditLogService,
                                 StaffStockService staffStockService) {
        this.staffStockService = staffStockService;
        this.returnService = returnService;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    @RequirePermission({"STOCK_RETURN"})
    public String page(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(value = "imei", required = false) String imei,
                       Model model) {

        List<String> permissionCodes = permissionService.getAllowedPermissionDetails(user.getUserId())
                .stream().map(p -> p.getPermissionCode()).collect(Collectors.toList());
        model.addAttribute("permissions", permissionCodes);
        // "Give from staff stock" option in the Exchange section
        model.addAttribute("canStaffExchange", staffStockService.canView(user));

        // these also check the STOCK_RETURN permission (403 if the user does not have it)
        model.addAttribute("recent", returnService.recent(user));
        model.addAttribute("withVendor", returnService.withVendor(user));
        model.addAttribute("query", imei == null ? "" : imei.trim());
        model.addAttribute("today", LocalDate.now().toString());

        if (imei != null && !imei.isBlank()) {
            try {
                model.addAttribute("result", returnService.lookup(imei, user));
            } catch (IllegalArgumentException ex) {
                model.addAttribute("errorMessage", ex.getMessage());
            }
        }
        return "settings/stock-return";
    }

    /** All AVAILABLE devices (category, product, IMEI) for the exchange popup. */
    @GetMapping("/available-stock")
    @ResponseBody
    @RequirePermission({"STOCK_RETURN"})
    public List<StockReturnService.StockOption> availableStock(@AuthenticationPrincipal CustomUserDetails user) {
        return returnService.availableStock(user);
    }

    /** Field staff (name, city, how many devices in hand) for the exchange "staff stock" option.
     *  NOTE: annotation nahi, kyunki STOCK_RETURN aur STOCK_EXCHANGE dono chahiye (requireStaffExchange check karta hai). */
    @GetMapping("/staff-list")
    @ResponseBody
    public List<StaffStockService.StaffOption> staffList(@AuthenticationPrincipal CustomUserDetails user) {
        requireStaffExchange(user);
        return staffStockService.staffList(user);
    }

    /** Devices one staff member has in hand (category, product, IMEI) for the exchange popup. */
    @GetMapping("/staff-stock")
    @ResponseBody
    public List<StaffStockService.UnitOption> staffStock(@AuthenticationPrincipal CustomUserDetails user,
                                                         @RequestParam("staffId") Long staffId) {
        requireStaffExchange(user);
        return staffStockService.staffUnits(user, staffId);
    }

    private void requireStaffExchange(CustomUserDetails user) {
        boolean sup = permissionService.hasPermission(user.getUserId(), "SUPER_ADMIN");
        if (!sup && !(permissionService.hasPermission(user.getUserId(), StockReturnService.PERMISSION)
                && permissionService.hasPermission(user.getUserId(), StockReturnService.EXCHANGE_PERMISSION))) {
            throw new org.springframework.security.access.AccessDeniedException("Exchange is not enabled for your role.");
        }
        if (!staffStockService.canView(user)) {
            throw new org.springframework.security.access.AccessDeniedException("Staff stock is not enabled for your role.");
        }
    }

    /** The customer's device came back. */
    @PostMapping("/save")
    @RequirePermission({"STOCK_RETURN"})
    public String save(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam("stockId") Long stockId,
                       @RequestParam("imei") String imei,
                       @RequestParam("resolution") String resolution,
                       @RequestParam(value = "conditionAfter", required = false) String conditionAfter,
                       @RequestParam(value = "returnDate", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate returnDate,
                       @RequestParam("reason") String reason,
                       @RequestParam(value = "vendorId", required = false) Long vendorId,
                       @RequestParam(value = "challanNo", required = false) String challanNo,
                       @RequestParam(value = "vendorIssue", required = false) String vendorIssue,
                       @RequestParam(value = "sentDate", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sentDate,
                       @RequestParam(value = "expectedBackDate", required = false)
                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expectedBackDate,
                       @RequestParam(value = "exchangeImei", required = false) String exchangeImei,
                       @RequestParam(value = "exchangeSource", required = false) String exchangeSource,
                       @RequestParam(value = "exchangeStaffId", required = false) Long exchangeStaffId,
                       @RequestParam(value = "exchangeType", required = false) String exchangeType,
                       @RequestParam(value = "exchangeVehicle", required = false) String exchangeVehicle,
                       HttpServletRequest request,
                       RedirectAttributes redirectAttributes) {
        try {
            ReturnForm form = new ReturnForm(stockId, resolution, conditionAfter, returnDate, reason,
                    vendorId, challanNo, vendorIssue, sentDate, expectedBackDate, exchangeImei);
            StaffStockService.StaffExchange sx = null;
            if ("STAFF".equals(exchangeSource) && exchangeImei != null && !exchangeImei.isBlank()) {
                sx = new StaffStockService.StaffExchange(exchangeStaffId,
                        exchangeType == null ? null : exchangeType.trim().toUpperCase(), exchangeVehicle);
            }
            StockReturn r = returnService.doReturn(form, sx, user, auditLogService.getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Case " + r.getCaseNo() + " saved. IMEI " + r.getImeiNumber() + ": " + r.getOutcome() + ".");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        redirectAttributes.addAttribute("imei", imei);
        return "redirect:/settings/stock-return";
    }

    /** The vendor gave the device back. */
    @PostMapping("/receive")
    @RequirePermission({"STOCK_RETURN"})
    public String receive(@AuthenticationPrincipal CustomUserDetails user,
                          @RequestParam("caseId") Long caseId,
                          @RequestParam("result") String result,
                          @RequestParam(value = "receivedDate", required = false)
                          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receivedDate,
                          @RequestParam(value = "conditionAfter", required = false) String conditionAfter,
                          @RequestParam(value = "replacementImei", required = false) String replacementImei,
                          @RequestParam(value = "repairCost", required = false) BigDecimal repairCost,
                          @RequestParam(value = "notes", required = false) String notes,
                          HttpServletRequest request,
                          RedirectAttributes redirectAttributes) {
        try {
            ReceiveForm form = new ReceiveForm(caseId, result, receivedDate, conditionAfter,
                    replacementImei, repairCost, notes);
            StockReturn r = returnService.receiveFromVendor(form, user, auditLogService.getClientIp(request));
            redirectAttributes.addFlashAttribute("successMessage",
                    "Case " + r.getCaseNo() + " closed. IMEI " + r.getImeiNumber() + ": " + r.getOutcome() + ".");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/settings/stock-return";
    }
}
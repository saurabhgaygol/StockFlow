package com.stockmanagement.controller;

import com.stockmanagement.entity.StockOutwardRequest;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.NotificationService;
import com.stockmanagement.service.StockCustomerService;
import com.stockmanagement.service.StockOutwardService;
import com.stockmanagement.service.StockOutwardService.Detail;
import com.stockmanagement.service.StockOutwardService.ItemInput;
import com.stockmanagement.service.StockOutwardService.ListResult;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/settings/outward")
public class StockOutwardController {

    private final StockOutwardService outwardService;
    private final UserPermissionService permissionService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final StockCustomerService customerService;

    public StockOutwardController(StockOutwardService outwardService,
                                  UserPermissionService permissionService,
                                  NotificationService notificationService,
                                  AuditLogService auditLogService,
                                  StockCustomerService customerService) {
        this.outwardService = outwardService;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.customerService = customerService;
    }

    /* ============================================================
       PANEL (page + data, Stock Inward jaisa hi)
       ============================================================ */

    @GetMapping
    public String page(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        Set<String> codes = codes(user);
        requireView(codes);

        model.addAttribute("canRequest", codes.contains("STOCK_OUT_REQUEST") || codes.contains("SUPER_ADMIN"));
        model.addAttribute("productOptions", outwardService.productOptions(user.getUser().getCompanyName()));
        return "settings/outward-list";
    }

    @GetMapping("/data")
    public String data(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        requireView(codes(user));

        ListResult result = outwardService.list(user);
        model.addAttribute("rows", result.rows());
        model.addAttribute("stats", result.stats());
        return "settings/outward-list :: outwardData";
    }

    /* ============================================================
       NAYI REQUEST
       ============================================================ */

    @PostMapping("/create")
    public String create(@AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request,
                         @RequestParam(value = "customerId", required = false) Long customerId,
                         @RequestParam(value = "customerName", required = false) String customerName,
                         @RequestParam(value = "customerMobile", required = false) String customerMobile,
                         @RequestParam(value = "customerCompany", required = false) String customerCompany,
                         @RequestParam(value = "customerGst", required = false) String customerGst,
                         @RequestParam(value = "customerEmail", required = false) String customerEmail,
                         @RequestParam(value = "customerAddress", required = false) String customerAddress,
                         @RequestParam(value = "customerCity", required = false) String customerCity,
                         @RequestParam(value = "customerState", required = false) String customerState,
                         @RequestParam(value = "customerPincode", required = false) String customerPincode,
                         @RequestParam(value = "dealName", required = false) String dealName,
                         @RequestParam(value = "remarks", required = false) String remarks,
                         @RequestParam(value = "productId", required = false) List<String> productIds,
                         @RequestParam(value = "quantity", required = false) List<String> quantities,
                         @RequestParam(value = "unitPrice", required = false) List<String> unitPrices,
                         RedirectAttributes ra) {
        try {
            List<ItemInput> items = parseItems(productIds, quantities, unitPrices);
            StockCustomerService.CustomerInput customer = new StockCustomerService.CustomerInput(
                    customerId, customerName, customerMobile, customerCompany, customerGst, customerEmail,
                    customerAddress, customerCity, customerState, customerPincode);
            StockOutwardRequest r = outwardService.create(user, ip(request), customer, dealName, remarks, items);
            ra.addFlashAttribute("successMessage",
                    "Request " + r.getRequestNo() + " raised. It is now waiting for Level 1 approval.");
            return "redirect:/settings/outward/" + r.getId();
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/settings/outward";
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("errorMessage", "You are not allowed to raise stock outward requests.");
            return "redirect:/settings/outward";
        }
    }

    /* ============================================================
       CUSTOMER SUGGESTIONS (naam / mobile / company likhte hi)
       ============================================================ */

    @GetMapping("/customers")
    @ResponseBody
    public List<StockCustomerService.Suggestion> customers(@AuthenticationPrincipal CustomUserDetails user,
                                                           @RequestParam("q") String q) {
        Set<String> codes = codes(user);
        if (!codes.contains("STOCK_OUT_REQUEST") && !codes.contains("SUPER_ADMIN")) {
            throw new AccessDeniedException("Not allowed.");
        }
        return customerService.search(user.getUser().getCompanyName(), q);
    }

    /* ============================================================
       DETAIL PAGE (har notification yahin kholta hai)
       ============================================================ */

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @AuthenticationPrincipal CustomUserDetails user,
                         Model model,
                         RedirectAttributes ra) {
        try {
            Detail d = outwardService.getDetail(id, user);
            model.addAttribute("d", d);

            // page khulte hi is request ke saare notifications "read" ho jaate hain
            notificationService.markReadByUrl(user.getUserId(), StockOutwardService.detailUrl(id));
            model.addAttribute("notifications", notificationService.getRecentNotifications(user.getUserId()));
            model.addAttribute("notifUnreadCount", notificationService.getUnreadCount(user.getUserId()));
            return "settings/outward-detail";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/settings/outward";
        } catch (AccessDeniedException e) {
            ra.addFlashAttribute("errorMessage", "You do not have access to this request.");
            return "redirect:/settings/outward";
        }
    }

    /* ============================================================
       FINAL APPROVAL POPUP: har product ki available IMEI list (JSON)
       ============================================================ */

    @GetMapping("/{id}/available-units")
    @ResponseBody
    public ResponseEntity<?> availableUnits(@PathVariable Long id,
                                            @AuthenticationPrincipal CustomUserDetails user) {
        try {
            return ResponseEntity.ok(outwardService.availableUnits(id, user));
        } catch (AccessDeniedException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "You are not allowed to do this."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /* ============================================================
       BUTTONS: approve / hold / reject / reply / resubmit / cancel
       ============================================================ */

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                          HttpServletRequest request,
                          @RequestParam(value = "level", required = false) Integer level,
                          @RequestParam(value = "message", required = false) String message,
                          @RequestParam(value = "stockIds", required = false) List<Long> stockIds,
                          RedirectAttributes ra) {
        try {
            StockOutwardRequest r = outwardService.approve(id, user, ip(request), level, message, stockIds);
            ra.addFlashAttribute("successMessage", StockOutwardService.ISSUED.equals(r.getStatus())
                    ? "Final approval done. Stock has been issued."
                    : "Approved. The request has moved to Level " + r.getCurrentLevel() + ".");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward/" + id;
    }

    @PostMapping("/{id}/hold")
    public String hold(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                       HttpServletRequest request,
                       @RequestParam(value = "level", required = false) Integer level,
                       @RequestParam(value = "message", required = false) String message,
                       RedirectAttributes ra) {
        try {
            outwardService.hold(id, user, ip(request), level, message);
            ra.addFlashAttribute("successMessage", "Request put on hold. The requester has been notified.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward/" + id;
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request,
                         @RequestParam(value = "level", required = false) Integer level,
                         @RequestParam(value = "message", required = false) String message,
                         RedirectAttributes ra) {
        try {
            outwardService.reject(id, user, ip(request), level, message);
            ra.addFlashAttribute("successMessage", "Request rejected. The requester has been notified.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward/" + id;
    }

    @PostMapping("/{id}/comment")
    public String comment(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                          HttpServletRequest request,
                          @RequestParam(value = "message", required = false) String message,
                          RedirectAttributes ra) {
        try {
            outwardService.comment(id, user, ip(request), message);
            ra.addFlashAttribute("successMessage", "Reply sent.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward/" + id;
    }

    @PostMapping("/{id}/resubmit")
    public String resubmit(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                           HttpServletRequest request,
                           @RequestParam(value = "message", required = false) String message,
                           RedirectAttributes ra) {
        try {
            outwardService.resubmit(id, user, ip(request), message);
            ra.addFlashAttribute("successMessage", "Request resubmitted to the approver.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward/" + id;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request,
                         @RequestParam(value = "message", required = false) String message,
                         RedirectAttributes ra) {
        try {
            outwardService.cancel(id, user, ip(request), message);
            ra.addFlashAttribute("successMessage", "Request cancelled.");
        } catch (RuntimeException e) {
            flashError(ra, e);
        }
        return "redirect:/settings/outward/" + id;
    }

    /* ============================================================
       helpers
       ============================================================ */

    private Set<String> codes(CustomUserDetails user) {
        return permissionService.getAllowedPermissionDetails(user.getUserId()).stream()
                .map(p -> p.getPermissionCode()).collect(Collectors.toSet());
    }

    private void requireView(Set<String> codes) {
        if (!codes.contains("STOCK_OUT_VIEW") && !codes.contains("SUPER_ADMIN")) {
            throw new AccessDeniedException("Stock Outward is not enabled for your role.");
        }
    }

    private String ip(HttpServletRequest request) {
        return auditLogService.getClientIp(request);
    }

    private void flashError(RedirectAttributes ra, RuntimeException e) {
        if (e instanceof AccessDeniedException) {
            ra.addFlashAttribute("errorMessage", "You are not allowed to do this.");
        } else if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } else {
            throw e;   // asli bug ho to log/error page mein dikhne do
        }
    }

    private List<ItemInput> parseItems(List<String> productIds, List<String> quantities, List<String> unitPrices) {
        List<ItemInput> items = new ArrayList<>();
        if (productIds == null) return items;
        try {
            for (int i = 0; i < productIds.size(); i++) {
                String pid = productIds.get(i);
                if (pid == null || pid.isBlank()) continue;
                String q = (quantities != null && i < quantities.size()) ? quantities.get(i) : "1";
                String pr = (unitPrices != null && i < unitPrices.size()) ? unitPrices.get(i) : "";
                BigDecimal price = (pr == null || pr.isBlank()) ? null : new BigDecimal(pr.trim());
                items.add(new ItemInput(Long.valueOf(pid.trim()), Integer.valueOf(q.trim()), price));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid product, quantity or price.");
        }
        return items;
    }
}
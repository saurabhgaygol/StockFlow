package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.RetailBill;
import com.stockmanagement.entity.RetailOffer;
import com.stockmanagement.service.CompanyFeatureService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.RetailBillingService;
import com.stockmanagement.service.RetailLogoService;
import com.stockmanagement.service.RetailOfferService;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

/**
 * Billing (POS) and Bills. The company always comes from the logged-in user; works only when the company's
 * RETAIL_BILLING feature is ON (Super Admin switches it) and Products &amp; Variants is ON too.
 */
@Controller
public class RetailBillingController {

    private final RetailBillingService billing;
    private final RetailOfferService offerService;
    private final CompanyFeatureService featureService;
    private final UserPermissionService userPermissionService;
    private final RetailLogoService logoService;

    public RetailBillingController(RetailBillingService billing, RetailOfferService offerService,
                                   CompanyFeatureService featureService, UserPermissionService userPermissionService,
                                   RetailLogoService logoService) {
        this.logoService = logoService;
        this.billing = billing;
        this.offerService = offerService;
        this.featureService = featureService;
        this.userPermissionService = userPermissionService;
    }

    // ------------------------------------------------------------------ billing screen

    @GetMapping("/retail/billing")
    @RequirePermission({"RETAIL_BILL_CREATE"})
    public String billingPage(@AuthenticationPrincipal CustomUserDetails u, Model model) {
        String company = company(u);
        requireFeature(company);
        var profile = billing.profile(company);
        List<RetailOffer> billOffers = billing.liveBillOffers(company);
        model.addAttribute("profile", profile);
        model.addAttribute("billOffers", billOffers.stream()
                .map(o -> Map.of("id", o.getId(), "name", o.getName(), "summary", RetailOfferService.summary(o)))
                .collect(Collectors.toList()));
        model.addAttribute("canOverride", canOverride(u));
        model.addAttribute("canSettings", has(u, "RETAIL_SHOP_SETTINGS"));
        model.addAttribute("profileReady", profile.getShopName() != null && !profile.getShopName().isBlank());
        return "retail/billing";
    }

    /** prices the cart (nothing is saved) */
    @PostMapping("/retail/billing/quote")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_CREATE"})
    public ResponseEntity<Map<String, Object>> quote(@AuthenticationPrincipal CustomUserDetails u,
                                                     @RequestBody RetailBillingService.BillIn in) {
        String company = company(u);
        requireFeature(company);
        try {
            return ok("quote", billing.quote(company, in, canOverride(u)));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** saves the bill. A manager's approval is taken from the logged-in user's own permission. */
    @PostMapping("/retail/billing/save")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_CREATE"})
    public ResponseEntity<Map<String, Object>> save(@AuthenticationPrincipal CustomUserDetails u,
                                                    @RequestBody RetailBillingService.BillIn in,
                                                    HttpServletRequest request) {
        String company = company(u);
        requireFeature(company);
        try {
            boolean override = canOverride(u);
            RetailBillingService.Saved s = billing.save(company, u.getUserId(), u.getUsername(), in, override,
                    override ? u.getUsername() : null, request);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("billId", s.billId());
            m.put("billNo", s.billNo());
            m.put("token", s.token());
            m.put("grandTotal", s.grandTotal());
            m.put("pointsEarned", s.pointsEarned());
            m.put("pointsBalance", s.pointsBalance());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** type-ahead for an item typed by name / style code instead of a scanned barcode */
    @GetMapping("/retail/billing/item-search")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_CREATE"})
    public List<Map<String, Object>> itemSearch(@AuthenticationPrincipal CustomUserDetails u, @RequestParam("q") String q) {
        String company = company(u);
        requireFeature(company);
        return offerService.searchVariants(company, q).stream()
                .filter(h -> h.barcode() != null && !h.barcode().isBlank())
                .map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("label", h.label());
                    m.put("barcode", h.barcode());
                    m.put("price", h.price() == null ? "" : h.price().toPlainString());
                    return m;
                }).collect(Collectors.toList());
    }

    /** all sellable items for the product grid (category, product, size, colour, price, stock) */
    @GetMapping("/retail/billing/catalog")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_CREATE"})
    public List<RetailOfferService.CatalogItem> catalog(@AuthenticationPrincipal CustomUserDetails u) {
        String company = company(u);
        requireFeature(company);
        return offerService.catalog(company);
    }

    @GetMapping("/retail/billing/customers")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_CREATE"})
    public List<RetailBillingService.CustomerView> customers(@AuthenticationPrincipal CustomUserDetails u,
                                                             @RequestParam("q") String q) {
        String company = company(u);
        requireFeature(company);
        return billing.searchCustomers(company, q);
    }

    // ------------------------------------------------------------------ bills list, print, cancel

    @GetMapping("/retail/bills")
    @RequirePermission({"RETAIL_BILL_VIEW"})
    public String billsPage(@AuthenticationPrincipal CustomUserDetails u,
                            @RequestParam(value = "from", required = false) String from,
                            @RequestParam(value = "to", required = false) String to,
                            @RequestParam(value = "q", required = false) String q,
                            @RequestParam(value = "status", required = false) String status,
                            Model model) {
        String company = company(u);
        requireFeature(company);
        LocalDate f = date(from), t = date(to);
        if (f == null) f = LocalDate.now();
        if (t == null) t = f;
        List<RetailBill> bills = billing.list(company, f, t, q, status);
        model.addAttribute("bills", bills);
        model.addAttribute("from", f.toString());
        model.addAttribute("to", t.toString());
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("status", status == null ? "" : status);
        model.addAttribute("total", bills.stream().filter(b -> RetailBill.COMPLETED.equals(b.getStatus()))
                .map(RetailBill::getGrandTotal).reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add));
        model.addAttribute("sum", billing.summary(company, f, t));
        LocalDate today = LocalDate.now();
        model.addAttribute("sumTitle", f.equals(today) && t.equals(today) ? "Today's sales"
                : "Sales");
        model.addAttribute("canCancel", has(u, "RETAIL_BILL_CANCEL"));
        model.addAttribute("canSettings", has(u, "RETAIL_SHOP_SETTINGS"));
        model.addAttribute("profile", billing.profile(company));
        model.addAttribute("hasLogo", logoService.has(company));
        model.addAttribute("canLogo", has(u, "RETAIL_SHOP_LOGO"));
        return "retail/bills";
    }

    /** printable bill: size = a4, 80 or 58 */
    @GetMapping("/retail/bills/{id}/print")
    @RequirePermission({"RETAIL_BILL_VIEW", "RETAIL_BILL_CREATE"})
    public String print(@AuthenticationPrincipal CustomUserDetails u, @PathVariable("id") Long id,
                        @RequestParam(value = "size", defaultValue = "80") String size, Model model) {
        String company = company(u);
        requireFeature(company);
        RetailBillingService.BillDetail d = billing.detail(company, id);
        model.addAttribute("d", d);
        model.addAttribute("bill", d.bill());
        model.addAttribute("lines", d.lines());
        model.addAttribute("payments", d.payments());
        model.addAttribute("rates", d.rateRows());
        model.addAttribute("profile", d.profile());
        model.addAttribute("pointsBalance", d.pointsBalance());
        model.addAttribute("hasLogo", logoService.has(company));
        model.addAttribute("saved", d.bill().getItemDiscount().add(d.bill().getManualDiscount()).add(d.bill().getBillDiscount()));
        return switch (size) {
            case "a4" -> "retail/bill-print-a4";
            case "58" -> "retail/bill-print-58";
            default -> "retail/bill-print-80";
        };
    }

    @PostMapping("/retail/bills/{id}/cancel")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_CANCEL"})
    public ResponseEntity<Map<String, Object>> cancel(@AuthenticationPrincipal CustomUserDetails u,
                                                      @PathVariable("id") Long id,
                                                      @RequestParam("reason") String reason,
                                                      HttpServletRequest request) {
        String company = company(u);
        requireFeature(company);
        try {
            billing.cancel(company, u.getUserId(), u.getUsername(), id, reason, request);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/retail/billing/shop-details")
    @ResponseBody
    @RequirePermission({"RETAIL_SHOP_SETTINGS"})
    public ResponseEntity<Map<String, Object>> saveShop(@AuthenticationPrincipal CustomUserDetails u,
                                                        @RequestBody RetailBillingService.ProfileIn in,
                                                        HttpServletRequest request) {
        String company = company(u);
        requireFeature(company);
        try {
            billing.saveProfile(company, u.getUserId(), u.getUsername(), in, request);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ shop logo

    /** the logo image (shown on printed bills and in Shop details) */
    @GetMapping("/retail/billing/logo")
    @ResponseBody
    @RequirePermission({"RETAIL_BILL_VIEW", "RETAIL_BILL_CREATE"})
    public ResponseEntity<byte[]> logo(@AuthenticationPrincipal CustomUserDetails u) {
        String company = company(u);
        requireFeature(company);
        return logoService.get(company)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(l.getContentType()))
                        .cacheControl(CacheControl.noCache())
                        .body(l.getImageData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/retail/billing/logo")
    @ResponseBody
    @RequirePermission({"RETAIL_SHOP_LOGO"})
    public ResponseEntity<Map<String, Object>> saveLogo(@AuthenticationPrincipal CustomUserDetails u,
                                                        @RequestParam("file") MultipartFile file) {
        String company = company(u);
        requireFeature(company);
        try {
            logoService.save(company, file.getBytes());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (java.io.IOException e) {
            return bad("Could not read the file. Please try again.");
        }
    }

    @PostMapping("/retail/billing/logo/remove")
    @ResponseBody
    @RequirePermission({"RETAIL_SHOP_LOGO"})
    public ResponseEntity<Map<String, Object>> removeLogo(@AuthenticationPrincipal CustomUserDetails u) {
        String company = company(u);
        requireFeature(company);
        logoService.remove(company);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseEntity<Map<String, Object>> ok(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put(key, value);
        return ResponseEntity.ok(m);
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return ResponseEntity.badRequest().body(m);
    }

    private static LocalDate date(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean has(CustomUserDetails u, String code) {
        return userPermissionService.hasPermission(u.getUserId(), code)
                || userPermissionService.hasPermission(u.getUserId(), "SUPER_ADMIN");
    }

    private boolean canOverride(CustomUserDetails u) {
        return has(u, "RETAIL_BILL_OVERRIDE");
    }

    private String company(CustomUserDetails userDetails) {
        String c = userDetails == null || userDetails.getUser() == null ? null : userDetails.getUser().getCompanyName();
        if (c == null || c.isBlank()) throw new AccessDeniedException("No company is linked to your account.");
        return c.trim();
    }

    private void requireFeature(String company) {
        if (!featureService.isEnabled(company, CompanyFeatureService.RETAIL_BILLING)
                || !featureService.isEnabled(company, CompanyFeatureService.RETAIL_VARIANT_STOCK)) {
            throw new AccessDeniedException("Billing is not enabled for your company.");
        }
    }
}
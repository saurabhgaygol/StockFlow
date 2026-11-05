package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.service.CompanyFeatureService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.RetailOfferService;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Offers & Discounts. Company always comes from the logged-in user; works only when the company's
 * RETAIL_OFFERS feature is ON (Super Admin switches it) and the Products & Variants feature is ON too.
 */
@Controller
public class RetailOfferController {

    private final RetailOfferService offerService;
    private final CompanyFeatureService featureService;
    private final UserPermissionService userPermissionService;

    public RetailOfferController(RetailOfferService offerService,
                                 CompanyFeatureService featureService,
                                 UserPermissionService userPermissionService) {
        this.offerService = offerService;
        this.featureService = featureService;
        this.userPermissionService = userPermissionService;
    }

    @GetMapping("/retail/offers")
    @RequirePermission({"RETAIL_OFFER_VIEW", "RETAIL_OFFER_MANAGE"})
    public String page(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        String company = company(userDetails);
        requireFeature(company);

        boolean manage = canManage(userDetails);
        List<RetailOfferService.OfferView> offers = offerService.list(company);

        List<ProductCategory> products = manage ? offerService.products(company) : List.<ProductCategory>of();
        Set<String> categories = new LinkedHashSet<>();
        for (ProductCategory p : products) categories.add(p.getCategoryName());

        model.addAttribute("offers", offers);
        model.addAttribute("canManage", manage);
        model.addAttribute("products", products);
        model.addAttribute("categories", categories);
        model.addAttribute("styles", manage ? offerService.styleCodes(company) : List.<String>of());
        model.addAttribute("liveCount", offers.stream().filter(o -> "LIVE".equals(o.status())).count());
        model.addAttribute("scheduledCount", offers.stream().filter(o -> "SCHEDULED".equals(o.status())).count());
        model.addAttribute("offCount", offers.stream().filter(o -> "OFF".equals(o.status()) || "EXPIRED".equals(o.status())).count());
        return "retail/offers";
    }

    /** one offer with its items, for the edit window */
    @GetMapping("/retail/offers/{id}")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<Map<String, Object>> one(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                    @PathVariable("id") Long id) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            RetailOfferService.OfferView v = offerService.get(company, id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.o().getId());
            m.put("name", v.o().getName());
            m.put("type", v.o().getOfferType());
            m.put("value", v.o().getValue() == null ? "" : v.o().getValue().stripTrailingZeros().toPlainString());
            m.put("buyQty", v.o().getBuyQty() == null ? "" : v.o().getBuyQty());
            m.put("getQty", v.o().getGetQty() == null ? "" : v.o().getGetQty());
            m.put("startDate", v.o().getStartDate() == null ? "" : v.o().getStartDate().toString());
            m.put("endDate", v.o().getEndDate() == null ? "" : v.o().getEndDate().toString());
            m.put("enabled", Boolean.TRUE.equals(v.o().getEnabled()));
            m.put("minAgeMonths", v.o().getMinAgeMonths() == null ? "" : v.o().getMinAgeMonths());
            m.put("freePick", v.o().getFreePick() == null ? "CHEAPEST" : v.o().getFreePick());
            m.put("groupMode", v.o().getGroupMode() == null ? "ANY" : v.o().getGroupMode());
            m.put("combinable", Boolean.TRUE.equals(v.o().getCombinable()));
            m.put("priority", v.o().getPriority() == null ? "" : v.o().getPriority());
            m.put("minBill", v.o().getMinBill() == null ? "" : v.o().getMinBill().stripTrailingZeros().toPlainString());
            m.put("cashierCanRemove", !Boolean.TRUE.equals(v.o().getLockedForCashier()));
            m.put("items", v.items().stream().map(i -> Map.of("scope", i.scope(), "ref", i.ref(), "label", i.label()))
                    .collect(Collectors.toList()));
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** type-ahead for the "specific barcodes / variants" picker */
    @GetMapping("/retail/offers/variant-search")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public List<Map<String, Object>> variantSearch(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                   @RequestParam("q") String q) {
        String company = company(userDetails);
        requireFeature(company);
        return offerService.searchVariants(company, q).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.id());
            m.put("label", h.label());
            m.put("barcode", h.barcode() == null ? "" : h.barcode());
            m.put("price", h.price() == null ? "" : h.price().toPlainString());
            return m;
        }).collect(Collectors.toList());
    }

    /** blank Excel to fill with barcodes */
    @GetMapping("/retail/offers/barcode-template")
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<byte[]> barcodeTemplate(@AuthenticationPrincipal CustomUserDetails userDetails) {
        String company = company(userDetails);
        requireFeature(company);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"offer_barcodes_template.xlsx\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(offerService.barcodeTemplate(company));
    }

    /** reads barcodes from an Excel file and returns the matching items; nothing is saved here */
    @PostMapping("/retail/offers/barcode-upload")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<Map<String, Object>> barcodeUpload(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                             @RequestParam("file") MultipartFile file) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            String name = file == null || file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please choose an Excel file.");
            if (file.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("File is too large (max 10 MB).");
            if (!(name.endsWith(".xlsx") || name.endsWith(".xlsm") || name.endsWith(".xls")))
                throw new IllegalArgumentException("Only .xlsx, .xlsm or .xls files are allowed.");
            List<RetailOfferService.BarcodeRow> codes;
            try (java.io.InputStream in = file.getInputStream()) {
                codes = offerService.readBarcodes(in);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Could not read the file.");
            }
            RetailOfferService.BarcodeResult r = offerService.variantsByBarcodes(company, codes);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("total", codes.size());
            m.put("found", r.found().stream().map(h -> Map.of("id", h.id(), "label", h.label())).collect(Collectors.toList()));
            m.put("skippedCount", r.skipped().size());
            byte[] nf = offerService.notFoundExcel(r.skipped());
            if (nf != null) {
                m.put("notFoundFile", java.util.Base64.getEncoder().encodeToString(nf));
                m.put("notFoundName", "barcodes_skipped.xlsx");
            }
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/retail/offers/save")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<Map<String, Object>> save(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                    @RequestBody RetailOfferService.OfferIn in,
                                                    HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            Long id = offerService.save(company, userDetails.getUserId(), userDetails.getUsername(), in, request);
            return ResponseEntity.ok(Map.of("ok", true, "id", id));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/retail/offers/{id}/toggle")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<Map<String, Object>> toggle(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                      @PathVariable("id") Long id,
                                                      @RequestParam("on") boolean on,
                                                      HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            offerService.setEnabled(company, userDetails.getUserId(), userDetails.getUsername(), id, on, request);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/retail/offers/{id}/extend")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<Map<String, Object>> extend(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                      @PathVariable("id") Long id,
                                                      @RequestParam(value = "endDate", required = false) String endDate,
                                                      HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            offerService.extend(company, userDetails.getUserId(), userDetails.getUsername(), id, endDate, request);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    @PostMapping("/retail/offers/{id}/delete")
    @ResponseBody
    @RequirePermission({"RETAIL_OFFER_MANAGE"})
    public ResponseEntity<Map<String, Object>> delete(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                      @PathVariable("id") Long id,
                                                      HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            offerService.delete(company, userDetails.getUserId(), userDetails.getUsername(), id, request);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return ResponseEntity.badRequest().body(m);
    }

    private boolean canManage(CustomUserDetails u) {
        return userPermissionService.hasPermission(u.getUserId(), "RETAIL_OFFER_MANAGE")
                || userPermissionService.hasPermission(u.getUserId(), "SUPER_ADMIN");
    }

    private String company(CustomUserDetails userDetails) {
        String c = userDetails == null || userDetails.getUser() == null ? null : userDetails.getUser().getCompanyName();
        if (c == null || c.isBlank()) throw new AccessDeniedException("No company is linked to your account.");
        return c.trim();
    }

    private void requireFeature(String company) {
        if (!featureService.isEnabled(company, CompanyFeatureService.RETAIL_OFFERS)
                || !featureService.isEnabled(company, CompanyFeatureService.RETAIL_VARIANT_STOCK)) {
            throw new AccessDeniedException("Offers & Discounts is not enabled for your company.");
        }
    }
}
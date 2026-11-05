package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.service.CompanyFeatureService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.RetailInwardService;
import com.stockmanagement.service.RetailOfferService;
import com.stockmanagement.service.RetailVariantService;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Retail "Products & Variants" screen.
 * Company is always the logged-in user's company; the page works only when the company's
 * RETAIL_VARIANT_STOCK feature is ON (Super Admin switches it).
 */
@Controller
public class RetailVariantController {

    private static final Logger log = LoggerFactory.getLogger(RetailVariantController.class);

    private final RetailVariantService retailService;
    private final RetailInwardService inwardService;
    private final RetailOfferService offerService;
    private final CompanyFeatureService featureService;
    private final UserPermissionService userPermissionService;

    public RetailVariantController(RetailVariantService retailService,
                                   RetailInwardService inwardService,
                                   RetailOfferService offerService,
                                   CompanyFeatureService featureService,
                                   UserPermissionService userPermissionService) {
        this.retailService = retailService;
        this.inwardService = inwardService;
        this.offerService = offerService;
        this.featureService = featureService;
        this.userPermissionService = userPermissionService;
    }

    @GetMapping("/retail/variants")
    @RequirePermission({"RETAIL_VARIANT_VIEW", "RETAIL_VARIANT_ADD", "RETAIL_VARIANT_EDIT", "RETAIL_VARIANT_ADJUST"})
    public String page(@AuthenticationPrincipal CustomUserDetails userDetails,
                       Model model) {
        String company = company(userDetails);
        requireFeature(company);

        List<String> codes = userPermissionService.getAllowedPermissionDetails(userDetails.getUserId())
                .stream().map(p -> p.getPermissionCode()).collect(Collectors.toList());
        boolean canView = codes.contains("RETAIL_VARIANT_VIEW") || isSuper(userDetails);

        model.addAttribute("permissions", codes);
        model.addAttribute("canView", canView);
        model.addAttribute("units", List.of("pcs", "kg", "g", "litre", "ml"));
        model.addAttribute("products", canView || codes.contains("RETAIL_VARIANT_ADD")
                ? retailService.activeProducts(company) : List.<ProductCategory>of());
        List<RetailVariantService.Line> rows = canView ? retailService.rows(company, null) : List.of();
        model.addAttribute("rows", rows);
        model.addAttribute("inwardRefs", inwardService.latestInwardByVariant(company,
                rows.stream().map(r -> r.v().getId()).collect(Collectors.toList())));
        // offer price on the stock list (only when the company has Offers & Discounts ON)
        model.addAttribute("offerMap", featureService.isEnabled(company, CompanyFeatureService.RETAIL_OFFERS)
                ? offerService.appliedFor(company, rows) : Map.<Long, RetailOfferService.Applied>of());
        model.addAttribute("totalCount", rows.size());
        model.addAttribute("inStockCount", rows.stream()
                .filter(r -> "ACTIVE".equals(r.v().getStatus()) && r.v().getQty().signum() > 0).count());
        model.addAttribute("outCount", rows.stream()
                .filter(r -> "ACTIVE".equals(r.v().getStatus()) && r.v().getQty().signum() == 0).count());
        model.addAttribute("productCount", rows.stream().map(r -> r.v().getProductId()).distinct().count());
        return "retail/variants";
    }

    @PostMapping("/retail/products/add")
    @RequirePermission({"RETAIL_VARIANT_ADD"})
    public String addProduct(@AuthenticationPrincipal CustomUserDetails userDetails,
                             @RequestParam("category") String category,
                             @RequestParam("product") String product,
                             RedirectAttributes ra) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            retailService.addProduct(company, category, product, userDetails.getUsername());
            ra.addFlashAttribute("successMessage", "Product saved.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/retail/variants";
    }

    @PostMapping("/retail/variants/add")
    @RequirePermission({"RETAIL_VARIANT_ADD"})
    public String addVariant(@AuthenticationPrincipal CustomUserDetails userDetails,
                             @RequestParam(value = "productId", required = false) String productId,
                             @RequestParam(value = "newCategory", required = false) String newCategory,
                             @RequestParam(value = "newProduct", required = false) String newProduct,
                             @RequestParam(value = "bulk", required = false) String bulk,
                             @RequestParam(value = "sizeLabel", required = false) String sizeLabel,
                             @RequestParam(value = "colour", required = false) String colour,
                             @RequestParam(value = "barcode", required = false) String barcode,
                             @RequestParam("unit") String unit,
                             @RequestParam("qty") String qty,
                             @RequestParam(value = "mrp", required = false) String mrp,
                             @RequestParam(value = "salePrice", required = false) String salePrice,
                             HttpServletRequest request,
                             RedirectAttributes ra) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            Long pid;
            if ("__new".equals(productId)) {
                pid = retailService.addProduct(company, newCategory, newProduct, userDetails.getUsername());
            } else {
                try {
                    pid = (productId == null || productId.isBlank()) ? null : Long.valueOf(productId.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Please select a product.");
                }
            }
            if (bulk != null) {
                RetailVariantService.BulkResult r = retailService.bulkAdd(company, pid, sizeLabel, colour, unit, qty,
                        mrp, salePrice, userDetails.getUserId(), userDetails.getUsername(), request);
                bulkMessage(ra, r, "variant(s)");
            } else {
                retailService.addVariant(company, pid, sizeLabel, colour, barcode, unit, qty, mrp, salePrice,
                        userDetails.getUserId(), userDetails.getUsername(), request);
                ra.addFlashAttribute("successMessage", "Variant saved.");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/retail/variants";
    }

    /** One card = one product with many variant rows; the whole batch is saved together (all or nothing). */
    @PostMapping(value = "/retail/variants/batch", consumes = "application/json")
    @RequirePermission({"RETAIL_VARIANT_ADD"})
    @ResponseBody
    public ResponseEntity<Map<String, Object>> batch(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                     @RequestBody RetailVariantService.BatchRequest body,
                                                     HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            RetailInwardService.Saved saved = retailService.addBatch(company, body, userDetails.getUserId(),
                    userDetails.getUsername(), request);
            int n = saved.variants();
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", n + " variant" + (n == 1 ? "" : "s") + " saved.",
                    "inwardId", saved.id(),
                    "inwardNo", saved.inwardNo()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Retail batch save failed", e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", "Could not save. Please try again."));
        }
    }

    // ------------------------------------------------------------ Inward (PDF + history)

    /** Inward PDF - made from the saved snapshot, so it can be downloaded again any time. */
    @GetMapping("/retail/inwards/{id}/pdf")
    @RequirePermission({"RETAIL_VARIANT_VIEW", "RETAIL_VARIANT_ADD"})
    public String inwardPdf(@AuthenticationPrincipal CustomUserDetails userDetails,
                            @PathVariable("id") Long id,
                            HttpServletResponse response,
                            RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        String no;
        try {
            no = inwardService.find(company, id).map(e -> e.inward().getInwardNo()).orElse(null);
            if (no == null) throw new IllegalArgumentException("Inward not found.");
            data = inwardService.pdf(company, id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/retail/inwards";
        } catch (IOException | RuntimeException e) {
            log.error("Inward PDF failed", e);
            ra.addFlashAttribute("errorMessage", "Could not create the PDF. Please try again.");
            return "redirect:/retail/inwards";
        }
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=" + no + ".pdf");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
        return null;
    }

    @GetMapping("/retail/inwards")
    @RequirePermission({"RETAIL_VARIANT_VIEW"})
    public String inwards(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        String company = company(userDetails);
        requireFeature(company);
        List<RetailInwardService.HistoryItem> items = inwardService.historyAll(company);
        java.time.LocalDate today = java.time.LocalDate.now();
        long inCount = 0, outCount = 0, thisMonth = 0;
        java.math.BigDecimal qtyIn = java.math.BigDecimal.ZERO, qtyOut = java.math.BigDecimal.ZERO;
        for (RetailInwardService.HistoryItem h : items) {
            if ("IN".equals(h.type())) { inCount++; qtyIn = qtyIn.add(h.qty()); }
            else { outCount++; qtyOut = qtyOut.add(h.qty()); }
            java.time.LocalDateTime at = h.date();
            if (at != null && at.getYear() == today.getYear() && at.getMonth() == today.getMonth()) thisMonth++;
        }
        model.addAttribute("items", items);
        model.addAttribute("inCount", inCount);
        model.addAttribute("outCount", outCount);
        model.addAttribute("monthCount", thisMonth);
        model.addAttribute("qtyIn", qtyIn);
        model.addAttribute("qtyOut", qtyOut);
        return "retail/inwards";
    }

    /** Product-wise stock movement report: inwards (IN) and outwards (OUT) with date and document number. */
    @GetMapping("/retail/inwards/report")
    @RequirePermission({"RETAIL_VARIANT_VIEW"})
    public String inwardReport(@AuthenticationPrincipal CustomUserDetails userDetails,
                               @RequestParam(value = "from", required = false) String from,
                               @RequestParam(value = "to", required = false) String to,
                               @RequestParam(value = "type", required = false) String type,
                               Model model) {
        String company = company(userDetails);
        requireFeature(company);
        java.time.LocalDate[] range = reportRange(from, to);
        String t = reportType(type);
        List<RetailInwardService.ReportRow> rows = inwardService.report(company, range[0], range[1], t);
        java.math.BigDecimal qtyIn = java.math.BigDecimal.ZERO, qtyOut = java.math.BigDecimal.ZERO;
        java.util.Set<String> inwards = new java.util.HashSet<>();
        java.util.Set<String> outwards = new java.util.HashSet<>();
        for (RetailInwardService.ReportRow r : rows) {
            if ("IN".equals(r.type())) { qtyIn = qtyIn.add(r.qty()); inwards.add(r.docKey()); }
            else { qtyOut = qtyOut.add(r.qty()); outwards.add(r.docKey()); }
        }
        model.addAttribute("rows", rows);
        model.addAttribute("from", range[0].toString());
        model.addAttribute("to", range[1].toString());
        model.addAttribute("type", t);
        model.addAttribute("inwardCount", inwards.size());
        model.addAttribute("outwardCount", outwards.size());
        model.addAttribute("qtyIn", qtyIn);
        model.addAttribute("qtyOut", qtyOut);
        return "retail/inward-report";
    }

    @GetMapping("/retail/inwards/report/export")
    @RequirePermission({"RETAIL_VARIANT_VIEW"})
    public String inwardReportExport(@AuthenticationPrincipal CustomUserDetails userDetails,
                                     @RequestParam(value = "from", required = false) String from,
                                     @RequestParam(value = "to", required = false) String to,
                                     @RequestParam(value = "type", required = false) String type,
                                     @RequestParam(value = "ids", required = false) String ids,
                                     HttpServletResponse response,
                                     RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        java.time.LocalDate[] range = reportRange(from, to);
        java.util.Set<String> wanted = new java.util.HashSet<>();
        if (ids != null) {
            for (String part : ids.split(",")) {
                String k = part.trim();
                if (k.matches("[IO][0-9]{1,18}")) wanted.add(k);
                if (wanted.size() >= 2000) break;
            }
        }
        byte[] data;
        try {
            data = inwardService.reportExcel(company, range[0], range[1], reportType(type), wanted);
        } catch (IOException | RuntimeException e) {
            log.error("Stock movement export failed", e);
            ra.addFlashAttribute("errorMessage", "Could not export the report. Please try again.");
            return "redirect:/retail/inwards/report";
        }
        writeXlsx(response, data, "stock-movement-" + range[0] + "-to-" + range[1] + ".xlsx");
        return null;
    }

    private static String reportType(String t) {
        return "IN".equalsIgnoreCase(t) ? "IN" : ("OUT".equalsIgnoreCase(t) ? "OUT" : "ALL");
    }

    /** Excel of ONE inward (its items), from the Inward History page. */
    @GetMapping("/retail/inwards/{id}/excel")
    @RequirePermission({"RETAIL_VARIANT_VIEW"})
    public String inwardExcel(@AuthenticationPrincipal CustomUserDetails userDetails,
                              @PathVariable("id") Long id,
                              HttpServletResponse response,
                              RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        String no;
        try {
            no = inwardService.find(company, id).map(e -> e.inward().getInwardNo()).orElse(null);
            if (no == null) throw new IllegalArgumentException("Inward not found.");
            data = inwardService.inwardExcel(company, id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/retail/inwards";
        } catch (IOException | RuntimeException e) {
            log.error("Inward Excel failed", e);
            ra.addFlashAttribute("errorMessage", "Could not create the Excel. Please try again.");
            return "redirect:/retail/inwards";
        }
        writeXlsx(response, data, no + ".xlsx");
        return null;
    }

    /** Excel of ONE outward, from the history page. */
    @GetMapping("/retail/outwards/{id}/excel")
    @RequirePermission({"RETAIL_VARIANT_VIEW"})
    public String outwardExcel(@AuthenticationPrincipal CustomUserDetails userDetails,
                               @PathVariable("id") Long id,
                               HttpServletResponse response,
                               RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        String no;
        try {
            no = inwardService.outwardNo(company, id);
            if (no == null) throw new IllegalArgumentException("Outward not found.");
            data = inwardService.outwardExcel(company, id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/retail/inwards";
        } catch (IOException | RuntimeException e) {
            log.error("Outward Excel failed", e);
            ra.addFlashAttribute("errorMessage", "Could not create the Excel. Please try again.");
            return "redirect:/retail/inwards";
        }
        writeXlsx(response, data, no + ".xlsx");
        return null;
    }

    /** from / to as yyyy-MM-dd; default = last 30 days; a swapped range is put right; max 1 year. */
    private static java.time.LocalDate[] reportRange(String from, String to) {
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate t = parseDate(to, today);
        java.time.LocalDate f = parseDate(from, t.minusDays(29));
        if (f.isAfter(t)) { java.time.LocalDate x = f; f = t; t = x; }
        if (f.isBefore(t.minusDays(365))) f = t.minusDays(365);
        return new java.time.LocalDate[]{f, t};
    }

    private static java.time.LocalDate parseDate(String s, java.time.LocalDate fallback) {
        try {
            return s == null || s.isBlank() ? fallback : java.time.LocalDate.parse(s.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ Excel

    @PostMapping("/retail/variants/import")
    @RequirePermission({"RETAIL_VARIANT_ADD"})
    public String importExcel(@AuthenticationPrincipal CustomUserDetails userDetails,
                              @RequestParam("file") MultipartFile file,
                              HttpServletRequest request,
                              RedirectAttributes ra) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
            if (file.isEmpty()) throw new IllegalArgumentException("Please choose an Excel file.");
            if (file.getSize() > 2L * 1024 * 1024) throw new IllegalArgumentException("File is too large (max 2 MB).");
            if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
                throw new IllegalArgumentException("Only .xlsx or .xls files are allowed.");
            }
            RetailVariantService.BulkResult r;
            try (java.io.InputStream in = file.getInputStream()) {
                r = retailService.importExcel(company, in, userDetails.getUserId(), userDetails.getUsername(), request);
            }
            bulkMessage(ra, r, "row(s)");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        } catch (IOException | RuntimeException e) {
            ra.addFlashAttribute("errorMessage", "Could not read this file. Please use the template.");
        }
        return "redirect:/retail/variants";
    }

    @GetMapping("/retail/variants/template")
    @RequirePermission({"RETAIL_VARIANT_ADD"})
    public String template(@AuthenticationPrincipal CustomUserDetails userDetails,
                           HttpServletResponse response,
                           RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        try {
            data = retailService.templateExcel(company);
        } catch (IOException | RuntimeException e) {
            log.error("Retail template download failed", e);
            ra.addFlashAttribute("errorMessage", "Could not create the template. Please try again.");
            return "redirect:/retail/variants";
        }
        writeXlsx(response, data, "retail-variants-template.xlsx");
        return null;
    }

    @GetMapping("/retail/variants/export")
    @RequirePermission({"RETAIL_VARIANT_VIEW"})
    public String export(@AuthenticationPrincipal CustomUserDetails userDetails,
                         HttpServletResponse response,
                         RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        try {
            data = retailService.exportExcel(company);
        } catch (IOException | RuntimeException e) {
            log.error("Retail stock export failed", e);
            ra.addFlashAttribute("errorMessage", "Could not export the stock list. Please try again.");
            return "redirect:/retail/variants";
        }
        writeXlsx(response, data, "retail-stock.xlsx");
        return null;
    }

    /** Same way the Stock Inward template download writes its file: straight to the response. */
    private void writeXlsx(HttpServletResponse response, byte[] data, String filename) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=" + filename);
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    private void bulkMessage(RedirectAttributes ra, RetailVariantService.BulkResult r, String what) {
        String msg = r.added() + " " + what + " added" + (r.skipped() > 0 ? ", " + r.skipped() + " skipped" : "") + ".";
        if (r.added() > 0 && r.errors().isEmpty()) {
            ra.addFlashAttribute("successMessage", msg + (r.skipped() > 0 ? " (already existing combinations are skipped)" : ""));
        } else if (r.added() > 0) {
            ra.addFlashAttribute("successMessage", msg + " " + String.join(" | ", r.errors()));
        } else {
            ra.addFlashAttribute("errorMessage", msg + " " + String.join(" | ", r.errors()));
        }
    }

    @PostMapping("/retail/variants/{id}/edit")
    @RequirePermission({"RETAIL_VARIANT_EDIT"})
    public String editVariant(@AuthenticationPrincipal CustomUserDetails userDetails,
                              @PathVariable("id") Long id,
                              @RequestParam(value = "styleCode", required = false) String styleCode,
                              @RequestParam(value = "sizeLabel", required = false) String sizeLabel,
                              @RequestParam(value = "colour", required = false) String colour,
                              @RequestParam(value = "barcode", required = false) String barcode,
                              @RequestParam("unit") String unit,
                              @RequestParam(value = "mrp", required = false) String mrp,
                              @RequestParam(value = "salePrice", required = false) String salePrice,
                              @RequestParam(value = "status", required = false) String status,
                              HttpServletRequest request,
                              RedirectAttributes ra) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            retailService.editVariant(company, id, styleCode, sizeLabel, colour, barcode, unit, mrp, salePrice, status,
                    userDetails.getUserId(), userDetails.getUsername(), request);
            ra.addFlashAttribute("successMessage", "Variant updated.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/retail/variants";
    }

    @PostMapping("/retail/variants/{id}/adjust")
    @RequirePermission({"RETAIL_VARIANT_ADJUST"})
    public String adjust(@AuthenticationPrincipal CustomUserDetails userDetails,
                         @PathVariable("id") Long id,
                         @RequestParam("delta") String delta,
                         @RequestParam("reason") String reason,
                         HttpServletRequest request,
                         RedirectAttributes ra) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            retailService.adjustStock(company, id, delta, reason,
                    userDetails.getUserId(), userDetails.getUsername(), request);
            ra.addFlashAttribute("successMessage", "Stock updated.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/retail/variants";
    }

    // ------------------------------------------------------------ Stock OUT (barcode + qty)

    /** Out screen: every row must be valid, otherwise nothing goes out. One outward (one OUT number) for all rows. */
    @PostMapping(value = "/retail/variants/out", consumes = "application/json")
    @RequirePermission({"RETAIL_VARIANT_ADJUST"})
    @ResponseBody
    public ResponseEntity<Map<String, Object>> stockOut(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                        @RequestBody RetailVariantService.OutRequest body,
                                                        HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            RetailInwardService.Saved saved = retailService.stockOut(company, body, userDetails.getUserId(),
                    userDetails.getUsername(), request);
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", saved.variants() + " item" + (saved.variants() == 1 ? "" : "s") + " taken out.",
                    "outId", saved.id(),
                    "outNo", saved.inwardNo()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (RuntimeException e) {
            log.error("Retail stock out failed", e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", "Could not save. Please try again."));
        }
    }

    @GetMapping("/retail/variants/out-template")
    @RequirePermission({"RETAIL_VARIANT_ADJUST"})
    public String outTemplate(@AuthenticationPrincipal CustomUserDetails userDetails,
                              HttpServletResponse response,
                              RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        try {
            data = retailService.outTemplateExcel();
        } catch (IOException | RuntimeException e) {
            log.error("Stock out template failed", e);
            ra.addFlashAttribute("errorMessage", "Could not create the template. Please try again.");
            return "redirect:/retail/variants";
        }
        writeXlsx(response, data, "stock-out-template.xlsx");
        return null;
    }

    private static void requireExcel(MultipartFile file) {
        String name = file == null || file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Please choose an Excel file.");
        if (file.getSize() > 2L * 1024 * 1024) throw new IllegalArgumentException("File is too large (max 2 MB).");
        if (!(name.endsWith(".xlsx") || name.endsWith(".xls"))) {
            throw new IllegalArgumentException("Only .xlsx or .xls files are allowed.");
        }
    }

    /**
     * Step 1 of a bulk Out: checks the file and tells what WOULD go out. Nothing is saved.
     * Valid rows are listed; invalid rows come back as an Excel (base64) with the reason for each.
     */
    @PostMapping("/retail/variants/out/preview")
    @RequirePermission({"RETAIL_VARIANT_ADJUST"})
    @ResponseBody
    public ResponseEntity<Map<String, Object>> outPreview(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                          @RequestParam("file") MultipartFile file) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            requireExcel(file);
            RetailVariantService.OutCheck c;
            try (java.io.InputStream in = file.getInputStream()) {
                c = retailService.previewOutExcel(company, in);
            }
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("ok", true);
            out.put("validCount", c.ok().size());
            out.put("validQty", c.okQty().stripTrailingZeros().toPlainString());
            out.put("invalidCount", c.bad().size());
            List<Map<String, Object>> items = new java.util.ArrayList<>();
            for (RetailVariantService.OutOk r : c.ok()) {
                if (items.size() == 200) break;
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("row", r.row());
                m.put("barcode", r.line().barcode());
                m.put("label", r.label());
                m.put("qty", r.line().qty().stripTrailingZeros().toPlainString() + " " + r.line().unit());
                m.put("available", r.available().stripTrailingZeros().toPlainString() + " " + r.line().unit());
                items.add(m);
            }
            out.put("items", items);
            List<Map<String, Object>> bad = new java.util.ArrayList<>();
            for (RetailVariantService.OutBad b : c.bad()) {
                if (bad.size() == 20) break;
                java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("row", b.row());
                m.put("barcode", b.barcode());
                m.put("qty", b.qty());
                m.put("error", b.error());
                bad.add(m);
            }
            out.put("invalid", bad);
            out.put("invalidXlsx", c.bad().isEmpty() ? null
                    : java.util.Base64.getEncoder().encodeToString(retailService.invalidOutExcel(c.bad())));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (IOException | RuntimeException e) {
            log.error("Stock out preview failed", e);
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "Could not read this file. Please use the template."));
        }
    }

    /** Step 2 of a bulk Out, only after the user pressed Confirm: takes out the valid rows, one outward for all. */
    @PostMapping("/retail/variants/out/confirm")
    @RequirePermission({"RETAIL_VARIANT_ADJUST"})
    @ResponseBody
    public ResponseEntity<Map<String, Object>> outConfirm(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                          @RequestParam("file") MultipartFile file,
                                                          @RequestParam("expectCount") int expectCount,
                                                          @RequestParam("expectQty") String expectQty,
                                                          HttpServletRequest request) {
        String company = company(userDetails);
        requireFeature(company);
        try {
            requireExcel(file);
            RetailInwardService.Saved saved;
            try (java.io.InputStream in = file.getInputStream()) {
                saved = retailService.confirmOutExcel(company, in, expectCount, new java.math.BigDecimal(expectQty.trim()),
                        userDetails.getUserId(), userDetails.getUsername(), request);
            }
            return ResponseEntity.ok(Map.of(
                    "ok", true,
                    "message", saved.variants() + " item" + (saved.variants() == 1 ? "" : "s") + " taken out.",
                    "outId", saved.id(),
                    "outNo", saved.inwardNo()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", e.getMessage()));
        } catch (IOException | RuntimeException e) {
            log.error("Stock out confirm failed", e);
            return ResponseEntity.status(500).body(Map.of("ok", false, "message", "Could not save. Please try again."));
        }
    }

    /** Outward PDF - made from the saved rows, so it can be downloaded again any time. */
    @GetMapping("/retail/outwards/{id}/pdf")
    @RequirePermission({"RETAIL_VARIANT_VIEW", "RETAIL_VARIANT_ADJUST"})
    public String outwardPdf(@AuthenticationPrincipal CustomUserDetails userDetails,
                             @PathVariable("id") Long id,
                             HttpServletResponse response,
                             RedirectAttributes ra) throws IOException {
        String company = company(userDetails);
        requireFeature(company);
        byte[] data;
        String no;
        try {
            no = inwardService.outwardNo(company, id);
            if (no == null) throw new IllegalArgumentException("Outward not found.");
            data = inwardService.outwardPdf(company, id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/retail/inwards";
        } catch (IOException | RuntimeException e) {
            log.error("Outward PDF failed", e);
            ra.addFlashAttribute("errorMessage", "Could not create the PDF. Please try again.");
            return "redirect:/retail/inwards";
        }
        response.setContentType("application/pdf");
        response.setHeader("Content-Disposition", "attachment; filename=" + no + ".pdf");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
        return null;
    }

    // ------------------------------------------------------------------

    private String company(CustomUserDetails userDetails) {
        String c = userDetails == null || userDetails.getUser() == null ? null : userDetails.getUser().getCompanyName();
        if (c == null || c.isBlank()) throw new AccessDeniedException("No company is linked to your account.");
        return c.trim();
    }

    private void requireFeature(String company) {
        if (!featureService.isEnabled(company, CompanyFeatureService.RETAIL_VARIANT_STOCK)) {
            throw new AccessDeniedException("Products & Variants is not enabled for your company.");
        }
    }

    private boolean isSuper(CustomUserDetails u) {
        return userPermissionService.hasPermission(u.getUserId(), "SUPER_ADMIN");
    }
}
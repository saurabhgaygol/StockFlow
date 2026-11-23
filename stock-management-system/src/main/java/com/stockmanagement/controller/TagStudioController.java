package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.TagBatchService;
import com.stockmanagement.service.TagDesigns;
import com.stockmanagement.service.TagPdfService;
import com.stockmanagement.service.TagStudioService;
import com.stockmanagement.printagent.TagAgentHub;
import com.stockmanagement.printagent.TagAgentService;
import com.stockmanagement.printagent.TagPrintJob;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Tag Studio for a shop (Retail > Tag Studio).
 *   generate (default tab) - "Create tags" form + list of TAG batches (TAG-2026-000001 ...)
 *   history / designs      - next steps
 * Opens only when Super Admin has turned TAG_STUDIO ON for the user's company AND saved at least one business type.
 * Company always comes from the logged-in user; a company only ever sees its own batches and tags.
 * Every user who can open Tag Studio (TAG_STUDIO_VIEW) can create, edit and download - there is no separate
 * permission and no delete. An edit never changes a barcode.
 */
@Controller
public class TagStudioController {

    private static final Logger log = LoggerFactory.getLogger(TagStudioController.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH);

    private final TagStudioService tagStudioService;
    private final TagBatchService batchService;
    private final TagPdfService pdfService;
    private final AuditLogService auditLogService;
    private final TagAgentService agentService;
    private final TagAgentHub agentHub;

    public TagStudioController(TagStudioService tagStudioService, TagBatchService batchService, TagPdfService pdfService,
                               AuditLogService auditLogService, TagAgentService agentService, TagAgentHub agentHub) {
        this.agentService = agentService;
        this.agentHub = agentHub;
        this.tagStudioService = tagStudioService;
        this.batchService = batchService;
        this.pdfService = pdfService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------ page

    @GetMapping("/retail/tag-studio")
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public String home(@AuthenticationPrincipal CustomUserDetails userDetails,
                       @RequestParam(value = "tab", required = false) String tab,
                       Model model) {
        String company = company(userDetails);
        requireReady(company);
        String activeTab = "history".equals(tab) || "designs".equals(tab) ? tab : "generate";

        List<String> types = tagStudioService.businessTypes(company);
        model.addAttribute("company", company);
        model.addAttribute("businessTypeLabels", tagStudioService.businessTypeLabels(company));
        model.addAttribute("tab", activeTab);
        TagPdfService.Setting setting = pdfService.setting(company);
        model.addAttribute("printerCode", setting.printer().code());
        model.addAttribute("printerName", setting.printer().name());
        model.addAttribute("designCode", setting.design().code());
        model.addAttribute("designName", setting.design().name());
        model.addAttribute("printers", printerList());
        model.addAttribute("designs", TagDesigns.DESIGNS);
        model.addAttribute("designList", designList());
        // printer -> chosen design, printer -> chosen colour ("" = black), read by the page script
        Map<String, String> choiceDesign = new LinkedHashMap<>(), choiceColour = new LinkedHashMap<>();
        pdfService.choices(company).forEach((printer, c) -> {
            choiceDesign.put(printer, c.design().code());
            choiceColour.put(printer, c.hex() == null ? "" : c.hex());
        });
        model.addAttribute("choices", choiceDesign);
        model.addAttribute("colours", choiceColour);
        model.addAttribute("inks", TagDesigns.INKS);
        model.addAttribute("hasLogo", pdfService.hasLogo(company));
        model.addAttribute("previewKey", pdfService.previewKey(company));

        if ("generate".equals(activeTab)) {
            List<TagBatchService.BatchRow> rows = batchService.list(company);
            model.addAttribute("batches", rows);
            model.addAttribute("stats", batchService.stats(rows));
            // what the "Create tags" form needs, per business type of this shop (read by the page script)
            List<Map<String, Object>> forms = new ArrayList<>();
            for (String code : types) forms.add(form(code));
            model.addAttribute("tagForms", forms);
            model.addAttribute("maxTags", TagBatchService.MAX_TAGS_PER_BATCH);
        }
        return "retail/tag-studio";
    }

    // ------------------------------------------------------------------ create a batch

    @PostMapping("/retail/tag-studio/batches")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> create(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                      @RequestBody TagBatchService.BatchIn in,
                                                      HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        try {
            TagBatchService.Created c = batchService.create(company, in, userDetails.getUserId(), userDetails.getUsername());
            auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_BATCH_CREATE", "Retail", "Tag Studio",
                    c.batchNo() + " created with " + c.tags() + " tags", auditLogService.getClientIp(request), c.id(), "TAG_BATCH");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("id", c.id());
            m.put("no", c.batchNo());
            m.put("tags", c.tags());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tag batch save failed for {}", company, e);
            return bad("Could not save the tags. Please try again.");
        }
    }

    // ------------------------------------------------------------------ edit a batch (barcodes never change, nothing is deleted)

    @PostMapping("/retail/tag-studio/batches/{id}")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> update(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                      @PathVariable("id") Long id,
                                                      @RequestBody TagBatchService.BatchIn in,
                                                      HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        try {
            TagBatchService.Updated u = batchService.update(company, id, in, userDetails.getUserId(), userDetails.getUsername());
            if (u.changedRows() > 0) {
                auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_BATCH_EDIT", "Retail", "Tag Studio",
                        u.batchNo() + " edited (" + u.changedRows() + " rows changed, " + u.added() + " new tags)",
                        auditLogService.getClientIp(request), u.id(), "TAG_BATCH");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("id", u.id());
            m.put("no", u.batchNo());
            m.put("tags", u.tags());
            m.put("added", u.added());
            m.put("changed", u.changedRows());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tag batch edit failed for {} batch {}", company, id, e);
            return bad("Could not save the changes. Please try again.");
        }
    }

    // ------------------------------------------------------------------ edit one tag (its barcode stays the same)

    @PostMapping("/retail/tag-studio/tags/{id}")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> updateTag(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                         @PathVariable("id") Long id,
                                                         @RequestBody TagBatchService.ItemIn in,
                                                         HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        try {
            TagBatchService.TagView t = batchService.editTag(company, id, in, userDetails.getUserId(), userDetails.getUsername());
            auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_EDIT", "Retail", "Tag Studio",
                    "Tag " + t.barcode() + " edited", auditLogService.getClientIp(request), t.id(), "TAG_LABEL");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("id", t.id());
            m.put("barcode", t.barcode());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tag edit failed for {} tag {}", company, id, e);
            return bad("Could not save the tag. Please try again.");
        }
    }

    // ------------------------------------------------------------------ printer + tag design of the shop

    /**
     * {"printer": "50x38"}                       -> the shop now prints with this printer
     * {"printer": "50x38", "design": "premium"}  -> this design is used for every tag printed on that printer
     * {"printer": "50x38", "color": "#7A1F2B"}   -> tags of that printer print in this colour ("#000000" = black)
     */
    @PostMapping("/retail/tag-studio/print-setting")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> saveSetting(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                           @RequestBody Map<String, String> body,
                                                           HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        try {
            String printer = body == null ? null : blankToNull(body.get("printer"));
            String design = body == null ? null : blankToNull(body.get("design"));
            String colour = body == null ? null : blankToNull(body.get("color"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            if (design != null || colour != null) {
                String forPrinter = printer != null ? printer : pdfService.setting(company).printer().code();
                TagPdfService.Choice c = pdfService.saveChoice(company, forPrinter, design, colour,
                        userDetails.getUserId(), userDetails.getUsername());
                TagDesigns.Printer p = TagDesigns.printer(forPrinter);
                auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_DESIGN", "Retail", "Tag Studio",
                        "Tag design for " + p.name() + ": " + c.design().name() + (c.hex() == null ? "" : " in " + c.hex()),
                        auditLogService.getClientIp(request), null, "TAG_DESIGN");
                m.put("printer", p.code());
                m.put("printerName", p.name());
                m.put("design", c.design().code());
                m.put("designName", c.design().name());
                m.put("color", c.hex() == null ? "" : c.hex());
            } else if (printer != null) {
                TagPdfService.Setting st = pdfService.savePrinter(company, printer, userDetails.getUserId(), userDetails.getUsername());
                auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_PRINTER", "Retail", "Tag Studio",
                        "Tag printer: " + st.printer().name(), auditLogService.getClientIp(request), null, "TAG_PRINT_SETTING");
                m.put("printer", st.printer().code());
                m.put("printerName", st.printer().name());
                m.put("design", st.design().code());
                m.put("designName", st.design().name());
            } else {
                return bad("Nothing to save.");
            }
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tag print setting failed for {}", company, e);
            return bad("Could not save. Please try again.");
        }
    }

    // ------------------------------------------------------------------ shop logo (from Shop profile; printed instead of the monogram)

    @GetMapping("/retail/tag-studio/logo.png")
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<byte[]> logo(@AuthenticationPrincipal CustomUserDetails userDetails) {
        String company = company(userDetails);
        requireReady(company);
        return pdfService.logoPng(company)
                .map(png -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                        .cacheControl(CacheControl.maxAge(0, TimeUnit.SECONDS).cachePrivate()).body(png))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** picture of a sample tag of this shop in one design, at the given printer size (Designs page) */
    @GetMapping("/retail/tag-studio/designs/{code}/preview.png")
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<byte[]> designPreview(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                @PathVariable("code") String code,
                                                @RequestParam(value = "printer", required = false) String printer,
                                                @RequestParam(value = "color", required = false) String colour) {
        String company = company(userDetails);
        requireReady(company);
        try {
            String p = printer == null || printer.isBlank() ? pdfService.setting(company).printer().code() : printer;
            byte[] png = pdfService.preview(company, p, code, colour);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate())
                    .body(png);
        } catch (IOException | RuntimeException e) {
            log.error("Tag design preview failed for {} design {}", company, code, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private static List<Map<String, Object>> printerList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TagDesigns.Printer p : TagDesigns.PRINTERS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", p.code());
            m.put("name", p.name());
            m.put("hint", p.hint());
            m.put("w", p.w());
            m.put("h", p.h());
            m.put("sheet", p.sheet());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> designList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (TagDesigns.Design d : TagDesigns.DESIGNS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", d.code());
            m.put("name", d.name());
            m.put("group", d.group());
            out.add(m);
        }
        return out;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    // ------------------------------------------------------------------ one batch (popup)

    @GetMapping("/retail/tag-studio/batches/{id}")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> detail(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                      @PathVariable("id") Long id) {
        String company = company(userDetails);
        requireReady(company);
        return batchService.detail(company, id).<ResponseEntity<Map<String, Object>>>map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("id", d.batch().getId());
            m.put("no", d.batch().getBatchNo());
            m.put("date", d.batch().getCreatedAt() == null ? "" : d.batch().getCreatedAt().format(WHEN));
            m.put("by", d.batch().getCreatedByName());
            m.put("typeLabel", d.typeLabel());
            m.put("sizeLabel", d.spec().sizeLabel());
            m.put("colourLabel", d.spec().colourLabel());
            m.put("styleLabel", d.spec().styleLabel());
            m.put("editedAt", d.batch().getUpdatedAt() == null ? "" : d.batch().getUpdatedAt().format(WHEN));
            m.put("editedBy", d.batch().getUpdatedByName());
            m.put("form", form(d.batch().getBusinessType()));
            List<Map<String, Object>> items = new ArrayList<>();
            for (TagBatchService.ItemView it : d.items()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", it.id());
                r.put("barcode", it.barcode());
                r.put("category", it.category());
                r.put("size", it.size());
                r.put("colour", it.colour());
                r.put("styleCode", it.styleCode());
                r.put("mrp", it.mrp());
                r.put("qty", it.qty());
                items.add(r);
            }
            m.put("items", items);
            List<Map<String, Object>> tags = new ArrayList<>();
            for (TagBatchService.TagView t : d.tags()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", t.id());
                r.put("seq", t.seqNo());
                r.put("barcode", t.barcode());
                r.put("category", t.category());
                r.put("size", t.size());
                r.put("colour", t.colour());
                r.put("style", t.styleCode());
                r.put("mrp", t.mrp());
                r.put("qty", t.qty());
                tags.add(r);
            }
            m.put("tags", tags);
            return ResponseEntity.ok(m);
        }).orElseGet(() -> bad("Tag batch not found."));
    }

    // ------------------------------------------------------------------ PDF: whole batch, or only the chosen products
    // tags=12,15&copies=4,1 -> 4 tags of product 12 and 1 tag of product 15 (same barcode on every copy).
    // No tags = every product, each printed Qty times.

    @GetMapping("/retail/tag-studio/batches/{id}/pdf")
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal CustomUserDetails userDetails,
                                      @PathVariable("id") Long id,
                                      @RequestParam(value = "layout", required = false) String layout,
                                      @RequestParam(value = "tags", required = false) List<Long> tagIds,
                                      @RequestParam(value = "copies", required = false) List<Integer> copies,
                                      @RequestParam(value = "sheet", required = false) String sheet) {
        String company = company(userDetails);
        requireReady(company);
        TagBatchService.BatchDetail d = batchService.detail(company, id)
                .orElseThrow(() -> new AccessDeniedException("Tag batch not found."));
        List<TagBatchService.TagView> chosen = batchService.pick(d, tagIds, copies);
        if (chosen.isEmpty()) return ResponseEntity.badRequest().build();
        TagDesigns.Printer printer = layout == null || layout.isBlank() ? pdfService.setting(company).printer() : TagDesigns.printer(layout);
        try {
            boolean a4 = "a4".equalsIgnoreCase(sheet) && !printer.sheet();     // same tag size, laid out on A4 pages
            byte[] data = pdfService.pdf(company, d, chosen, printer.code(), a4);
            boolean all = tagIds == null || tagIds.isEmpty();
            boolean one = !all && chosen.stream().map(TagBatchService.TagView::id).distinct().count() == 1;
            String name = d.batch().getBatchNo()
                    + (all ? "" : one ? "-" + chosen.get(0).barcode() : "-" + chosen.size() + "-tags")
                    + "-" + printer.code().toUpperCase(Locale.ROOT) + (a4 ? "-A4" : "") + ".pdf";
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                    .body(data);
        } catch (IOException | RuntimeException e) {
            log.error("Tag PDF failed for {} batch {}", company, id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ------------------------------------------------------------------ direct printing (Printer Agent on the shop computer)

    /** new one-time code to connect a computer; the agent asks for it once, then connects by itself every time */
    @PostMapping("/retail/tag-studio/agent/pair")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> agentPair(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                         HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        String code = agentService.newPairCode(company, userDetails.getUserId(), userDetails.getUsername());
        String base = request.getScheme() + "://" + request.getServerName()
                + (("http".equals(request.getScheme()) && request.getServerPort() == 80)
                   || ("https".equals(request.getScheme()) && request.getServerPort() == 443) ? "" : ":" + request.getServerPort())
                + request.getContextPath();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("code", code);
        m.put("server", base);
        m.put("line", base + " " + code);
        m.put("minutes", 15);
        return ResponseEntity.ok(m);
    }

    /** connected computers with their printers + which printer prints which tag size */
    @GetMapping("/retail/tag-studio/agent")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> agentState(@AuthenticationPrincipal CustomUserDetails userDetails) {
        String company = company(userDetails);
        requireReady(company);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("agents", agentService.agentViews(company));
        m.put("routes", agentService.routeViews(company));
        return ResponseEntity.ok(m);
    }

    /** the Windows agent program (put at src/main/resources/agent/stockflow-printer-agent.exe) */
    @GetMapping("/retail/tag-studio/agent/download")
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<byte[]> agentDownload(@AuthenticationPrincipal CustomUserDetails userDetails) {
        requireReady(company(userDetails));
        try (java.io.InputStream in = getClass().getResourceAsStream("/agent/stockflow-printer-agent.exe")) {
            if (in == null) return ResponseEntity.notFound().build();
            byte[] data = in.readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("StockFlow-Printer-Agent.exe").build().toString())
                    .body(data);
        } catch (IOException e) {
            log.error("Agent download failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** {"printer": "50x38", "agentId": 3, "name": "TSC TTP-244"}; empty name = no direct printing for that size */
    @PostMapping("/retail/tag-studio/agent/route")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> agentRoute(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                          @RequestBody Map<String, Object> body, HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        try {
            TagDesigns.Printer size = TagDesigns.printer(String.valueOf(body.get("printer")));
            if (size.sheet()) return bad("A4 sheets are printed from the PDF.");
            Object aid = body.get("agentId");
            Long agentId = aid == null || String.valueOf(aid).isBlank() ? null : Long.valueOf(String.valueOf(aid));
            String name = body.get("name") == null ? null : String.valueOf(body.get("name"));
            agentService.setRoute(company, size.code(), agentId, name, userDetails.getUsername());
            auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_PRINTER_ROUTE", "Retail", "Tag Studio",
                    "Direct printer for " + size.name() + ": " + (name == null || name.isBlank() ? "none" : name),
                    auditLogService.getClientIp(request), null, "TAG_PRINT_SETTING");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("routes", agentService.routeViews(company));
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {
            return bad(e.getMessage());
        }
    }

    /** removes a connected computer */
    @PostMapping("/retail/tag-studio/agent/{id}/disconnect")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> agentDisconnect(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                               @PathVariable("id") Long id, HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        if (!agentService.disconnect(company, id)) return bad("Computer not found.");
        agentHub.kick(id);                                   // its open connection ends at once
        auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_AGENT_REMOVED", "Retail", "Tag Studio",
                "Printer agent computer removed", auditLogService.getClientIp(request), null, "TAG_PRINT_SETTING");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return ResponseEntity.ok(m);
    }

    /** {"layout": "50x38", "tags": [..], "copies": [..]} -> queued at once, the agent prints it in about a second */
    @PostMapping("/retail/tag-studio/batches/{id}/print")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> printNow(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                        @PathVariable("id") Long id,
                                                        @RequestBody(required = false) Map<String, Object> body,
                                                        HttpServletRequest request) {
        String company = company(userDetails);
        requireReady(company);
        TagBatchService.BatchDetail d = batchService.detail(company, id)
                .orElseThrow(() -> new AccessDeniedException("Tag batch not found."));
        try {
            String layout = body == null || body.get("layout") == null ? null : String.valueOf(body.get("layout"));
            TagDesigns.Printer size = layout == null || layout.isBlank() ? pdfService.setting(company).printer() : TagDesigns.printer(layout);
            List<Long> tagIds = new ArrayList<>();
            List<Integer> copies = new ArrayList<>();
            if (body != null && body.get("tags") instanceof List<?> l) for (Object o : l) tagIds.add(Long.valueOf(String.valueOf(o)));
            if (body != null && body.get("copies") instanceof List<?> l) for (Object o : l) copies.add(Integer.valueOf(String.valueOf(o)));
            TagPrintJob job = agentService.createJob(company, d, tagIds, copies, size, userDetails.getUserId(), userDetails.getUsername());
            agentHub.wake(job.getAgentId());
            auditLogService.log(userDetails.getUserId(), userDetails.getUsername(), "TAG_PRINT", "Retail", "Tag Studio",
                    "Printed " + job.getTotalTags() + " tag(s) of " + d.batch().getBatchNo() + " on " + job.getPrinterName(),
                    auditLogService.getClientIp(request), null, "TAG_PRINT");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("jobId", job.getId());
            m.put("printer", job.getPrinterName());
            m.put("total", job.getTotalTags());
            return ResponseEntity.ok(m);
        } catch (IllegalArgumentException e) {      // includes NumberFormatException (a bad number in the request)
            return bad(e instanceof NumberFormatException ? "Invalid request." : e.getMessage());
        } catch (RuntimeException e) {
            log.error("Tag print job failed for {} batch {}", company, id, e);
            return bad("Could not start printing. Please try again.");
        }
    }

    /** the page asks this about every second after Print until DONE or FAILED */
    @GetMapping("/retail/tag-studio/print-jobs/{id}")
    @ResponseBody
    @RequirePermission({"TAG_STUDIO_VIEW"})
    public ResponseEntity<Map<String, Object>> printJob(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                        @PathVariable("id") Long id) {
        String company = company(userDetails);
        requireReady(company);
        TagPrintJob j = agentService.job(company, id).orElse(null);
        if (j == null) return bad("Print job not found.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("status", j.getStatus());
        m.put("message", j.getMessage() == null ? "" : j.getMessage());
        m.put("printer", j.getPrinterName());
        m.put("total", j.getTotalTags());
        return ResponseEntity.ok(m);
    }

    // ------------------------------------------------------------------ helpers

    /** labels and suggestions the form uses for one business type (read by the page script) */
    private Map<String, Object> form(String code) {
        TagStudioService.TagFieldSpec s = tagStudioService.fieldSpec(code);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", tagStudioService.label(code));
        m.put("sizeLabel", s.sizeLabel());
        m.put("colourLabel", s.colourLabel());
        m.put("styleLabel", s.styleLabel());
        m.put("sizeRequired", s.sizeRequired());
        m.put("categories", s.categories());
        m.put("sizes", s.sizes());
        m.put("colours", s.colours());
        return m;
    }

    private void requireReady(String company) {
        if (!tagStudioService.isReady(company)) {
            throw new AccessDeniedException("Tag Studio is not enabled for your company.");
        }
    }

    private static ResponseEntity<Map<String, Object>> bad(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return ResponseEntity.badRequest().body(m);
    }

    private String company(CustomUserDetails userDetails) {
        String c = userDetails == null || userDetails.getUser() == null ? null : userDetails.getUser().getCompanyName();
        if (c == null || c.isBlank()) throw new AccessDeniedException("No company is linked to your account.");
        return c.trim();
    }
}
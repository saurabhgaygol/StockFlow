package com.stockmanagement.service;

import com.stockmanagement.entity.RetailInward;
import com.stockmanagement.entity.RetailInwardLine;
import com.stockmanagement.entity.RetailStockOut;
import com.stockmanagement.repository.RetailInwardLineRepository;
import com.stockmanagement.repository.RetailInwardRepository;
import com.stockmanagement.repository.RetailStockOutRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Inward = one "Save" of new retail stock. Keeps a snapshot so the PDF can be made now or later. */
@Service
public class RetailInwardService {

    private static final int LIST_LIMIT = 200;

    /** one received variant, as saved */
    public record LineData(Long variantId, String productLabel, String styleCode, String size, String colour, String barcode,
                           String unit, BigDecimal qty, BigDecimal mrp, BigDecimal salePrice) {}

    /** result of creating an inward */
    public record Saved(Long id, String inwardNo, int variants, BigDecimal totalQty) {}

    /** the inward a variant last came in with, ready to show ("INW-2026-000012 · 02 Nov 2026") */
    public record InwardRef(Long inwardId, String no, String date, String label) {}

    /** an inward with its lines (for the history page) */
    public record Entry(RetailInward inward, List<RetailInwardLine> lines) {
        /** short list for the history table, e.g. "T-Shirts / Round neck (4), Jeans / Slim (2)" */
        public String productSummary() {
            Map<String, Integer> count = new LinkedHashMap<>();
            for (RetailInwardLine l : lines) {
                String name = (l.getStyleCode() == null || l.getStyleCode().isBlank() ? "" : "[" + l.getStyleCode() + "] ")
                        + (l.getProductLabel() == null ? "" : l.getProductLabel());
                count.merge(name, 1, Integer::sum);
            }
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Map.Entry<String, Integer> e : count.entrySet()) {
                if (shown == 2) break;
                if (shown > 0) sb.append(", ");
                sb.append(e.getKey()).append(" (").append(e.getValue()).append(')');
                shown++;
            }
            if (count.size() > 2) sb.append("  +").append(count.size() - 2).append(" more");
            return sb.toString();
        }

        /** lower-case text the history page searches in (inward no, user, product, style, barcode) */
        public String searchText() {
            StringBuilder sb = new StringBuilder();
            sb.append(inward.getInwardNo() == null ? "" : inward.getInwardNo()).append(' ')
              .append(inward.getCreatedByName() == null ? "" : inward.getCreatedByName());
            for (RetailInwardLine l : lines) {
                sb.append(' ').append(l.getProductLabel() == null ? "" : l.getProductLabel())
                  .append(' ').append(l.getStyleCode() == null ? "" : l.getStyleCode())
                  .append(' ').append(l.getSizeLabel() == null ? "" : l.getSizeLabel())
                  .append(' ').append(l.getColour() == null ? "" : l.getColour())
                  .append(' ').append(l.getBarcode() == null ? "" : l.getBarcode());
            }
            return sb.toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final RetailInwardRepository inwardRepo;
    private final RetailInwardLineRepository lineRepo;

    private final RetailStockOutRepository outRepo;

    public RetailInwardService(RetailInwardRepository inwardRepo, RetailInwardLineRepository lineRepo,
                               RetailStockOutRepository outRepo) {
        this.inwardRepo = inwardRepo;
        this.lineRepo = lineRepo;
        this.outRepo = outRepo;
    }

    private static RetailStockOut newOut(String company, Long userId, String userName, LineData l, String reason) {
        RetailStockOut o = new RetailStockOut();
        o.setCompanyName(company);
        o.setVariantId(l.variantId());
        o.setProductLabel(l.productLabel());
        o.setStyleCode(l.styleCode());
        o.setSizeLabel(l.size());
        o.setColour(l.colour());
        o.setBarcode(l.barcode());
        o.setUnit(l.unit());
        o.setQty(l.qty());
        o.setReason(reason);
        o.setCreatedByUserId(userId);
        o.setCreatedByName(userName);
        return o;
    }

    /** Joins the caller's transaction: records stock that went out (an outward) with its own OUT number. */
    @Transactional
    public Saved recordOut(String company, Long userId, String userName, LineData l, String reason) {
        return recordOutBatch(company, userId, userName, List.of(l), List.of(reason == null ? "" : reason));
    }

    /**
     * Joins the caller's transaction: ONE outward (one OUT number) that carries many items.
     * reasons.get(i) belongs to lines.get(i).
     */
    @Transactional
    public Saved recordOutBatch(String company, Long userId, String userName, List<LineData> lines, List<String> reasons) {
        RetailStockOut first = null;
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            LineData l = lines.get(i);
            String why = reasons != null && i < reasons.size() && reasons.get(i) != null && !reasons.get(i).isBlank()
                    ? reasons.get(i) : null;
            RetailStockOut o = newOut(company, userId, userName, l, why);
            if (first == null) {
                o = outRepo.saveAndFlush(o);
                o.setOutNo(String.format("OUT-%d-%06d", java.time.Year.now().getValue(), o.getId()));
                o.setOutGroupId(o.getId());
                first = outRepo.save(o);
            } else {
                o.setOutNo(first.getOutNo());
                o.setOutGroupId(first.getId());
                outRepo.save(o);
            }
            total = total.add(l.qty());
        }
        if (first == null) throw new IllegalArgumentException("Nothing to stock out.");
        return new Saved(first.getId(), first.getOutNo(), lines.size(), total);
    }

    /** Joins the caller's transaction, so inward and stock are saved together or not at all. */
    @Transactional
    public Saved create(String company, Long userId, String userName, List<LineData> lines) {
        BigDecimal totalQty = BigDecimal.ZERO;
        for (LineData l : lines) totalQty = totalQty.add(l.qty());

        RetailInward in = new RetailInward();
        in.setCompanyName(company);
        in.setTotalVariants(lines.size());
        in.setTotalQty(totalQty);
        in.setCreatedByUserId(userId);
        in.setCreatedByName(userName);
        in = inwardRepo.saveAndFlush(in);
        in.setInwardNo(String.format("INW-%d-%06d", java.time.Year.now().getValue(), in.getId()));
        in = inwardRepo.save(in);

        List<RetailInwardLine> rows = new ArrayList<>();
        for (LineData l : lines) {
            RetailInwardLine r = new RetailInwardLine();
            r.setInwardId(in.getId());
            r.setVariantId(l.variantId());
            r.setProductLabel(l.productLabel());
            r.setStyleCode(l.styleCode());
            r.setSizeLabel(l.size());
            r.setColour(l.colour());
            r.setBarcode(l.barcode());
            r.setUnit(l.unit());
            r.setQty(l.qty());
            r.setMrp(l.mrp());
            r.setSalePrice(l.salePrice());
            rows.add(r);
        }
        lineRepo.saveAll(rows);
        return new Saved(in.getId(), in.getInwardNo(), lines.size(), totalQty);
    }

    @Transactional(readOnly = true)
    public List<Entry> history(String company) {
        List<RetailInward> list = inwardRepo.findByCompanyNameOrderByIdDesc(company, PageRequest.of(0, LIST_LIMIT));
        Map<Long, List<RetailInwardLine>> byInward = new LinkedHashMap<>();
        for (RetailInward i : list) byInward.put(i.getId(), new ArrayList<>());
        if (!list.isEmpty()) {
            for (RetailInwardLine l : lineRepo.findByInwardIdInOrderByIdAsc(byInward.keySet())) {
                byInward.get(l.getInwardId()).add(l);
            }
        }
        List<Entry> out = new ArrayList<>();
        for (RetailInward i : list) out.add(new Entry(i, byInward.get(i.getId())));
        return out;
    }

    /** variant id -> latest inward it was received in (variants saved before inwards existed have none). */
    @Transactional(readOnly = true)
    public Map<Long, InwardRef> latestInwardByVariant(String company, java.util.Collection<Long> variantIds) {
        Map<Long, InwardRef> out = new LinkedHashMap<>();
        if (variantIds == null || variantIds.isEmpty()) return out;
        List<RetailInwardLine> lines = lineRepo.findByVariantIdIn(variantIds);
        if (lines.isEmpty()) return out;
        java.util.Set<Long> inwardIds = new java.util.HashSet<>();
        for (RetailInwardLine l : lines) inwardIds.add(l.getInwardId());
        Map<Long, RetailInward> inwards = new LinkedHashMap<>();
        for (RetailInward i : inwardRepo.findAllById(inwardIds)) {
            if (company.equalsIgnoreCase(i.getCompanyName())) inwards.put(i.getId(), i);
        }
        Map<Long, Long> best = new LinkedHashMap<>();       // variant -> highest inward id
        for (RetailInwardLine l : lines) {
            if (l.getVariantId() == null || !inwards.containsKey(l.getInwardId())) continue;
            Long cur = best.get(l.getVariantId());
            if (cur == null || l.getInwardId() > cur) best.put(l.getVariantId(), l.getInwardId());
        }
        DateTimeFormatter day = DateTimeFormatter.ofPattern("dd MMM yyyy");
        for (Map.Entry<Long, Long> b : best.entrySet()) {
            RetailInward i = inwards.get(b.getValue());
            String d = i.getCreatedAt() == null ? "" : i.getCreatedAt().format(day);
            out.put(b.getKey(), new InwardRef(i.getId(), i.getInwardNo(), d,
                    d.isEmpty() ? i.getInwardNo() : i.getInwardNo() + " \u00b7 " + d));
        }
        return out;
    }

    /** variant id -> when it was last received (latest inward date); variants with no inward are absent. */
    @Transactional(readOnly = true)
    public Map<Long, java.time.LocalDate> latestInwardDates(String company, java.util.Collection<Long> variantIds) {
        Map<Long, java.time.LocalDate> out = new LinkedHashMap<>();
        if (variantIds == null || variantIds.isEmpty()) return out;
        List<RetailInwardLine> lines = lineRepo.findByVariantIdIn(variantIds);
        if (lines.isEmpty()) return out;
        java.util.Set<Long> inwardIds = new java.util.HashSet<>();
        for (RetailInwardLine l : lines) inwardIds.add(l.getInwardId());
        Map<Long, RetailInward> inwards = new LinkedHashMap<>();
        for (RetailInward i : inwardRepo.findAllById(inwardIds)) {
            if (company.equalsIgnoreCase(i.getCompanyName()) && i.getCreatedAt() != null) inwards.put(i.getId(), i);
        }
        for (RetailInwardLine l : lines) {
            RetailInward i = inwards.get(l.getInwardId());
            if (l.getVariantId() == null || i == null) continue;
            java.time.LocalDate d = i.getCreatedAt().toLocalDate();
            java.time.LocalDate cur = out.get(l.getVariantId());
            if (cur == null || d.isAfter(cur)) out.put(l.getVariantId(), d);
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Optional<Entry> find(String company, Long id) {
        return inwardRepo.findByIdAndCompanyName(id, company)
                .map(i -> new Entry(i, lineRepo.findByInwardIdOrderByIdAsc(i.getId())));
    }

    // ------------------------------------------------------- product-wise report

    private static final int REPORT_LIMIT = 1000;      // max inwards in one report
    private static final int OUT_ROW_LIMIT = 5000;     // max outward item rows in one report / history

    /** one line of the report: stock IN (an inward) or stock OUT (an outward) */
    public record ReportRow(Long docId, String docNo, LocalDateTime date, String user, String product,
                            String styleCode, String size, String colour, String barcode, String unit,
                            BigDecimal qty, BigDecimal mrp, BigDecimal salePrice, String type, String reason,
                            Long variantId, String inwardNo, String inwardDate) {
        /** unique key of the document: I12 = inward 12, O5 = outward 5 */
        public String docKey() { return ("IN".equals(type) ? "I" : "O") + docId; }

        /** same row with the inward this item came in with */
        public ReportRow withInward(String no, String date) {
            return new ReportRow(docId, docNo, date(), user, product, styleCode, size, colour, barcode, unit,
                    qty, mrp, salePrice, type, reason, variantId, no, date);
        }
    }

    private static String normType(String type) {
        return "IN".equalsIgnoreCase(type) ? "IN" : ("OUT".equalsIgnoreCase(type) ? "OUT" : "ALL");
    }

    /**
     * Every stock movement between two dates (inclusive): inwards (IN) and outwards (OUT).
     * type = ALL / IN / OUT. Newest first; the rows of one document stay together.
     */
    @Transactional(readOnly = true)
    public List<ReportRow> report(String company, LocalDate from, LocalDate to, String type) {
        String t = normType(type);
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay().minusNanos(1);
        List<ReportRow> out = new ArrayList<>();

        if (!"OUT".equals(t)) {
            List<RetailInward> inwards = inwardRepo.findByCompanyNameAndCreatedAtBetweenOrderByIdDesc(
                    company, start, end, PageRequest.of(0, REPORT_LIMIT));
            if (!inwards.isEmpty()) {
                Map<Long, RetailInward> byId = new LinkedHashMap<>();
                for (RetailInward i : inwards) byId.put(i.getId(), i);
                Map<Long, List<RetailInwardLine>> lines = new LinkedHashMap<>();
                for (Long id : byId.keySet()) lines.put(id, new ArrayList<>());
                for (RetailInwardLine l : lineRepo.findByInwardIdInOrderByIdAsc(byId.keySet())) {
                    lines.get(l.getInwardId()).add(l);
                }
                for (RetailInward i : inwards) {
                    for (RetailInwardLine l : lines.get(i.getId())) out.add(inRow(i, l));
                }
            }
        }
        if (!"IN".equals(t)) {
            for (RetailStockOut o : outRepo.findByCompanyNameAndCreatedAtBetweenOrderByIdDesc(
                    company, start, end, PageRequest.of(0, OUT_ROW_LIMIT))) {
                out.add(outRow(o));
            }
        }
        // outward rows: show the inward the item came in with (its latest inward)
        java.util.Set<Long> outVariants = new java.util.HashSet<>();
        for (ReportRow r : out) if ("OUT".equals(r.type()) && r.variantId() != null) outVariants.add(r.variantId());
        if (!outVariants.isEmpty()) {
            Map<Long, InwardRef> refs = latestInwardByVariant(company, outVariants);
            for (int k = 0; k < out.size(); k++) {
                ReportRow r = out.get(k);
                InwardRef ref = "OUT".equals(r.type()) && r.variantId() != null ? refs.get(r.variantId()) : null;
                if (ref != null) out.set(k, r.withInward(ref.no(), ref.date()));
            }
        }
        if ("ALL".equals(t)) {      // newest document first; stable sort keeps a document's rows together
            out.sort((a, b) -> {
                if (a.date() == null || b.date() == null) return 0;
                return b.date().compareTo(a.date());
            });
        }
        return out;
    }

    private static ReportRow inRow(RetailInward i, RetailInwardLine l) {
        return new ReportRow(i.getId(), i.getInwardNo(), i.getCreatedAt(), i.getCreatedByName(),
                l.getProductLabel(), l.getStyleCode(), l.getSizeLabel(), l.getColour(), l.getBarcode(),
                l.getUnit(), l.getQty(), l.getMrp(), l.getSalePrice(), "IN", null,
                l.getVariantId(), i.getInwardNo(),
                i.getCreatedAt() == null ? "" : i.getCreatedAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy")));
    }

    /** id of the outward this row belongs to (older single-item rows are their own outward) */
    private static Long grp(RetailStockOut o) {
        return o.getOutGroupId() != null ? o.getOutGroupId() : o.getId();
    }

    private static ReportRow outRow(RetailStockOut o) {
        return new ReportRow(grp(o), o.getOutNo(), o.getCreatedAt(), o.getCreatedByName(),
                o.getProductLabel(), o.getStyleCode(), o.getSizeLabel(), o.getColour(), o.getBarcode(),
                o.getUnit(), o.getQty(), null, null, "OUT", o.getReason(), o.getVariantId(), null, null);
    }

    /** Excel of the date range; when docKeys (I12 / O5) are given, only those documents are included. */
    @Transactional(readOnly = true)
    public byte[] reportExcel(String company, LocalDate from, LocalDate to, String type,
                              java.util.Set<String> docKeys) throws IOException {
        List<ReportRow> rows = report(company, from, to, type);
        if (docKeys != null && !docKeys.isEmpty()) {
            rows.removeIf(r -> !docKeys.contains(r.docKey()));
        }
        return excel(rows);
    }

    /** Excel of one inward (all its items). */
    @Transactional(readOnly = true)
    public byte[] inwardExcel(String company, Long id) throws IOException {
        Entry e = find(company, id).orElseThrow(() -> new IllegalArgumentException("Inward not found."));
        List<ReportRow> rows = new ArrayList<>();
        for (RetailInwardLine l : e.lines()) rows.add(inRow(e.inward(), l));
        return excel(rows);
    }

    /** Excel of one outward. */
    @Transactional(readOnly = true)
    public byte[] outwardExcel(String company, Long id) throws IOException {
        List<RetailStockOut> items = outRepo.findGroup(company, id);
        if (items.isEmpty()) throw new IllegalArgumentException("Outward not found.");
        List<ReportRow> rows = new ArrayList<>();
        for (RetailStockOut o : items) rows.add(outRow(o));
        return excel(rows);
    }

    @Transactional(readOnly = true)
    public String outwardNo(String company, Long id) {
        List<RetailStockOut> items = outRepo.findGroup(company, id);
        return items.isEmpty() ? null : items.get(0).getOutNo();
    }

    // ------------------------------------------------ history (inward + outward)

    /** one line of the history page: an inward (with all its items) or an outward (one item) */
    public record HistoryItem(String type, Long id, String no, LocalDateTime date, String user, String summary,
                              int variants, BigDecimal qty, String reason, List<ReportRow> lines, String search) {}

    @Transactional(readOnly = true)
    public List<HistoryItem> historyAll(String company) {
        List<HistoryItem> out = new ArrayList<>();
        for (Entry e : history(company)) {
            RetailInward i = e.inward();
            List<ReportRow> lines = new ArrayList<>();
            for (RetailInwardLine l : e.lines()) lines.add(inRow(i, l));
            out.add(new HistoryItem("IN", i.getId(), i.getInwardNo(), i.getCreatedAt(), i.getCreatedByName(),
                    e.productSummary(), i.getTotalVariants(), i.getTotalQty(), null, lines, e.searchText()));
        }
        Map<Long, List<RetailStockOut>> groups = new LinkedHashMap<>();
        for (RetailStockOut o : outRepo.findByCompanyNameOrderByIdDesc(company, PageRequest.of(0, OUT_ROW_LIMIT))) {
            groups.computeIfAbsent(grp(o), k -> new ArrayList<>()).add(o);
        }
        for (Map.Entry<Long, List<RetailStockOut>> g : groups.entrySet()) {
            List<RetailStockOut> items = new ArrayList<>(g.getValue());
            java.util.Collections.reverse(items);                 // oldest first, as entered
            RetailStockOut first = items.get(0);
            List<ReportRow> lines = new ArrayList<>();
            Map<String, Integer> names = new LinkedHashMap<>();
            java.util.Set<String> reasons = new java.util.LinkedHashSet<>();
            BigDecimal total = BigDecimal.ZERO;
            StringBuilder search = new StringBuilder(nz(first.getOutNo()) + " " + nz(first.getCreatedByName()));
            for (RetailStockOut o : items) {
                lines.add(outRow(o));
                total = total.add(o.getQty());
                names.merge((o.getStyleCode() == null || o.getStyleCode().isBlank() ? "" : "[" + o.getStyleCode() + "] ")
                        + o.getProductLabel(), 1, Integer::sum);
                if (o.getReason() != null && !o.getReason().isBlank()) reasons.add(o.getReason());
                search.append(' ').append(nz(o.getProductLabel())).append(' ').append(nz(o.getStyleCode())).append(' ')
                      .append(nz(o.getSizeLabel())).append(' ').append(nz(o.getColour())).append(' ')
                      .append(nz(o.getBarcode())).append(' ').append(nz(o.getReason()));
            }
            String summary;
            if (items.size() == 1) {
                RetailStockOut o = first;
                summary = (o.getStyleCode() == null || o.getStyleCode().isBlank() ? "" : "[" + o.getStyleCode() + "] ")
                        + o.getProductLabel()
                        + ((o.getSizeLabel() == null && o.getColour() == null) ? ""
                           : " (" + nz(o.getSizeLabel()) + (o.getSizeLabel() != null && o.getColour() != null ? " / " : "")
                             + nz(o.getColour()) + ")");
            } else {
                StringBuilder sb = new StringBuilder();
                int shown = 0;
                for (Map.Entry<String, Integer> e : names.entrySet()) {
                    if (shown == 2) break;
                    if (shown > 0) sb.append(", ");
                    sb.append(e.getKey()).append(" (").append(e.getValue()).append(')');
                    shown++;
                }
                if (names.size() > 2) sb.append("  +").append(names.size() - 2).append(" more");
                summary = sb.toString();
            }
            out.add(new HistoryItem("OUT", g.getKey(), first.getOutNo(), first.getCreatedAt(), first.getCreatedByName(),
                    summary, items.size(), total, String.join(" / ", reasons), lines,
                    search.toString().toLowerCase(java.util.Locale.ROOT)));
        }
        out.sort((a, b) -> {
            if (a.date() == null || b.date() == null) return 0;
            return b.date().compareTo(a.date());
        });
        return out;
    }

    private byte[] excel(List<ReportRow> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Stock movement");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            String[] head = {"Date", "Type", "Document No", "Inward No", "Inward Date", "Entered by", "Product",
                    "Style Code", "Size", "Colour", "Barcode", "Unit", "Qty", "MRP", "Sale Price", "Reason"};
            Row h = sh.createRow(0);
            for (int i = 0; i < head.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
                sh.setColumnWidth(i, (i == 6 ? 34 : 16) * 256);
            }
            int n = 1;
            for (ReportRow r : rows) {
                Row row = sh.createRow(n++);
                row.createCell(0).setCellValue(r.date() == null ? "" : r.date().format(FMT));
                row.createCell(1).setCellValue("IN".equals(r.type()) ? "Inward" : "Outward");
                row.createCell(2).setCellValue(safe(r.docNo()));
                row.createCell(3).setCellValue(safe(r.inwardNo()));
                row.createCell(4).setCellValue(safe(r.inwardDate()));
                row.createCell(5).setCellValue(safe(r.user()));
                row.createCell(6).setCellValue(safe(r.product()));
                row.createCell(7).setCellValue(safe(r.styleCode()));
                row.createCell(8).setCellValue(safe(r.size()));
                row.createCell(9).setCellValue(safe(r.colour()));
                row.createCell(10).setCellValue(safe(r.barcode()));
                row.createCell(11).setCellValue(safe(r.unit()));
                row.createCell(12).setCellValue(r.qty() == null ? 0 : r.qty().doubleValue());
                if (r.mrp() != null) row.createCell(13).setCellValue(r.mrp().doubleValue());
                if (r.salePrice() != null) row.createCell(14).setCellValue(r.salePrice().doubleValue());
                row.createCell(15).setCellValue(safe(r.reason()));
            }
            sh.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Stops Excel from running a text cell as a formula (=, +, -, @ at the start). */
    private static String safe(String s) {
        if (s == null) return "";
        return !s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0 ? "'" + s : s;
    }

    // ------------------------------------------------------------------ PDF

    private static final float M = 36f;                 // page margin
    private static final float TOP = SimplePdf.PAGE_H - 40f;
    private static final float BOTTOM = 50f;
    private static final float ROW_H = 18f;
    // column left edges: #, product, size, colour, barcode, unit, qty, mrp, sale
    private static final float[] COL = {M, M + 22, M + 190, M + 232, M + 282, M + 352, M + 382, M + 430, M + 480};
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    public byte[] pdf(String company, Long id) throws IOException {
        Entry e = find(company, id).orElseThrow(() -> new IllegalArgumentException("Inward not found."));
        RetailInward in = e.inward();
        SimplePdf pdf = new SimplePdf();
        int page = pdf.newPage();
        float y = header(pdf, in, company);
        y = tableHead(pdf, y);

        int n = 0;
        for (RetailInwardLine l : e.lines()) {
            if (y - ROW_H < BOTTOM) {
                footer(pdf, page);
                page = pdf.newPage();
                y = tableHead(pdf, TOP);
            }
            n++;
            if (n % 2 == 0) pdf.fillRect(M, y - ROW_H + 4, SimplePdf.PAGE_W - 2 * M, ROW_H, 0.96f);
            float base = y - ROW_H + 9;
            pdf.text(COL[0] + 2, base, 8.5f, false, String.valueOf(n));
            pdf.text(COL[1], base, 8.5f, false, SimplePdf.fit((l.getStyleCode() == null || l.getStyleCode().isBlank() ? "" : "[" + l.getStyleCode() + "] ") + l.getProductLabel(), 8.5f, COL[2] - COL[1] - 4));
            pdf.text(COL[2], base, 8.5f, false, SimplePdf.fit(nz(l.getSizeLabel()), 8.5f, COL[3] - COL[2] - 4));
            pdf.text(COL[3], base, 8.5f, false, SimplePdf.fit(nz(l.getColour()), 8.5f, COL[4] - COL[3] - 4));
            pdf.text(COL[4], base, 8.5f, false, SimplePdf.fit(nz(l.getBarcode()), 8.5f, COL[5] - COL[4] - 4));
            pdf.text(COL[5], base, 8.5f, false, nz(l.getUnit()));
            pdf.text(COL[6], base, 8.5f, true, qty(l.getQty()));
            pdf.text(COL[7], base, 8.5f, false, money(l.getMrp()));
            pdf.text(COL[8], base, 8.5f, false, money(l.getSalePrice()));
            y -= ROW_H;
        }
        pdf.line(M, y + 4, SimplePdf.PAGE_W - M, y + 4, 0.6f, 0.5f);
        if (y - 40 < BOTTOM) {
            footer(pdf, page);
            page = pdf.newPage();
            y = TOP + 20;
        }
        pdf.text(M, y - 14, 10, true, "Total variants: " + in.getTotalVariants() + "     Total quantity: " + qty(in.getTotalQty()));
        pdf.text(M, y - 40, 8, false, "Received by: ______________________          Checked by: ______________________", 0.35f);
        footer(pdf, page);
        return pdf.build();
    }

    private float header(SimplePdf pdf, RetailInward in, String company) {
        return header(pdf, "INWARD NOTE", in.getInwardNo(), company, in.getCreatedAt(), in.getCreatedByName(),
                in.getTotalVariants() == null ? 0 : in.getTotalVariants());
    }

    private float header(SimplePdf pdf, String title, String no, String company, LocalDateTime at, String by, int items) {
        pdf.fillRect(0, SimplePdf.PAGE_H - 70, SimplePdf.PAGE_W, 70, 0.12f);
        pdf.text(M, SimplePdf.PAGE_H - 36, 17, true, title, 1f);
        pdf.text(M, SimplePdf.PAGE_H - 54, 9.5f, false, SimplePdf.fit(company, 9.5f, 300), 0.85f);
        pdf.text(SimplePdf.PAGE_W - M - 150, SimplePdf.PAGE_H - 36, 12, true, no == null ? "" : no, 1f);
        float y = SimplePdf.PAGE_H - 96;
        String when = at == null ? "" : at.format(FMT);
        pdf.text(M, y, 9, false, "Date: " + when);
        pdf.text(M + 220, y, 9, false, "Entered by: " + SimplePdf.fit(nz(by), 9, 160));
        pdf.text(M + 420, y, 9, false, "Items: " + items);
        return y - 18;
    }

    // ---- outward PDF (one OUT number, many items)

    private float outTableHead(SimplePdf pdf, float y) {
        pdf.fillRect(M, y - ROW_H + 4, SimplePdf.PAGE_W - 2 * M, ROW_H, 0.88f);
        float base = y - ROW_H + 9;
        String[] h = {"#", "Product", "Size", "Colour", "Barcode", "Unit", "Qty", "Reason"};
        for (int i = 0; i < h.length; i++) pdf.text(COL[i] + (i == 0 ? 2 : 0), base, 8.5f, true, h[i]);
        return y - ROW_H;
    }

    /** Outward note - made from the saved rows, so it can be downloaded again any time. */
    @Transactional(readOnly = true)
    public byte[] outwardPdf(String company, Long id) throws IOException {
        List<RetailStockOut> items = outRepo.findGroup(company, id);
        if (items.isEmpty()) throw new IllegalArgumentException("Outward not found.");
        RetailStockOut first = items.get(0);
        BigDecimal total = BigDecimal.ZERO;
        for (RetailStockOut o : items) total = total.add(o.getQty());

        SimplePdf pdf = new SimplePdf();
        int page = pdf.newPage();
        float y = header(pdf, "OUTWARD NOTE", first.getOutNo(), company, first.getCreatedAt(), first.getCreatedByName(), items.size());
        y = outTableHead(pdf, y);

        int n = 0;
        for (RetailStockOut o : items) {
            if (y - ROW_H < BOTTOM) {
                footer(pdf, page);
                page = pdf.newPage();
                y = outTableHead(pdf, TOP);
            }
            n++;
            if (n % 2 == 0) pdf.fillRect(M, y - ROW_H + 4, SimplePdf.PAGE_W - 2 * M, ROW_H, 0.96f);
            float base = y - ROW_H + 9;
            pdf.text(COL[0] + 2, base, 8.5f, false, String.valueOf(n));
            pdf.text(COL[1], base, 8.5f, false, SimplePdf.fit((o.getStyleCode() == null || o.getStyleCode().isBlank() ? "" : "[" + o.getStyleCode() + "] ") + nz(o.getProductLabel()), 8.5f, COL[2] - COL[1] - 4));
            pdf.text(COL[2], base, 8.5f, false, SimplePdf.fit(nz(o.getSizeLabel()), 8.5f, COL[3] - COL[2] - 4));
            pdf.text(COL[3], base, 8.5f, false, SimplePdf.fit(nz(o.getColour()), 8.5f, COL[4] - COL[3] - 4));
            pdf.text(COL[4], base, 8.5f, false, SimplePdf.fit(nz(o.getBarcode()), 8.5f, COL[5] - COL[4] - 4));
            pdf.text(COL[5], base, 8.5f, false, nz(o.getUnit()));
            pdf.text(COL[6], base, 8.5f, true, qty(o.getQty()));
            pdf.text(COL[7], base, 8.5f, false, SimplePdf.fit(nz(o.getReason()), 8.5f, SimplePdf.PAGE_W - M - COL[7] - 2));
            y -= ROW_H;
        }
        pdf.line(M, y + 4, SimplePdf.PAGE_W - M, y + 4, 0.6f, 0.5f);
        if (y - 40 < BOTTOM) {
            footer(pdf, page);
            page = pdf.newPage();
            y = TOP + 20;
        }
        pdf.text(M, y - 14, 10, true, "Total items: " + items.size() + "     Total quantity: " + qty(total));
        pdf.text(M, y - 40, 8, false, "Issued by: ______________________          Received by: ______________________", 0.35f);
        footer(pdf, page);
        return pdf.build();
    }

    private float tableHead(SimplePdf pdf, float y) {
        pdf.fillRect(M, y - ROW_H + 4, SimplePdf.PAGE_W - 2 * M, ROW_H, 0.88f);
        float base = y - ROW_H + 9;
        String[] h = {"#", "Product", "Size", "Colour", "Barcode", "Unit", "Qty", "MRP", "Sale"};
        for (int i = 0; i < h.length; i++) pdf.text(COL[i] + (i == 0 ? 2 : 0), base, 8.5f, true, h[i]);
        return y - ROW_H;
    }

    private void footer(SimplePdf pdf, int page) {
        pdf.text(M, 28, 7.5f, false, "Generated by StockFlow on " + LocalDateTime.now().format(FMT), 0.5f);
        pdf.text(SimplePdf.PAGE_W - M - 40, 28, 7.5f, false, "Page " + page, 0.5f);
    }

    private static String qty(BigDecimal q) {
        if (q == null) return "0";
        BigDecimal s = q.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static String money(BigDecimal m) {
        return m == null ? "-" : "Rs " + m.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
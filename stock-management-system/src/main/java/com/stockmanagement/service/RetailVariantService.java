package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.RetailVariant;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.RetailVariantRepository;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Retail stock (barcode + quantity). The company is ALWAYS passed in from the logged-in user,
 * never from a form. Every lookup is filtered by that company.
 */
@Service
public class RetailVariantService {

    public static final Set<String> UNITS = Set.of("pcs", "kg", "g", "litre", "ml");
    private static final Pattern BARCODE = Pattern.compile("^[A-Za-z0-9._/-]{1,64}$");
    private static final BigDecimal MAX_QTY = new BigDecimal("999999999");
    private static final BigDecimal MAX_PRICE = new BigDecimal("9999999999");
    private static final int LIST_LIMIT = 1000;

    /** one table row for the screen */
    public record Line(RetailVariant v, String category, String product) {}

    private final RetailVariantRepository variantRepo;
    private final ProductCategoryRepository productRepo;
    private final AuditLogService auditLogService;
    private final RetailInwardService inwardService;

    public RetailVariantService(RetailVariantRepository variantRepo,
                                ProductCategoryRepository productRepo,
                                AuditLogService auditLogService,
                                RetailInwardService inwardService) {
        this.variantRepo = variantRepo;
        this.productRepo = productRepo;
        this.auditLogService = auditLogService;
        this.inwardService = inwardService;
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<ProductCategory> activeProducts(String company) {
        List<ProductCategory> list = new ArrayList<>(productRepo.findByCompanyNameAndStatus(company, "ACTIVE"));
        list.sort((a, b) -> (a.getCategoryName() + " " + a.getProductName())
                .compareToIgnoreCase(b.getCategoryName() + " " + b.getProductName()));
        return list;
    }

    @Transactional(readOnly = true)
    public List<Line> rows(String company, String search) {
        Map<Long, ProductCategory> products = new HashMap<>();
        for (ProductCategory p : productRepo.findByCompanyName(company)) products.put(p.getId(), p);

        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Line> out = new ArrayList<>();
        for (RetailVariant v : variantRepo.findByCompanyNameOrderByIdDesc(company, PageRequest.of(0, LIST_LIMIT))) {
            ProductCategory p = products.get(v.getProductId());
            String cat = p == null ? "-" : p.getCategoryName();
            String prod = p == null ? "-" : p.getProductName();
            if (!q.isEmpty()) {
                String hay = (cat + " " + prod + " " + nz(v.getSizeLabel()) + " " + nz(v.getColour()) + " " + nz(v.getBarcode()) + " " + nz(v.getStyleCode()))
                        .toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            out.add(new Line(v, cat, prod));
        }
        return out;
    }

    // --------------------------------------------------------------- write

    /** Add a category + product pair (or reuse it if it already exists). Returns the product id. */
    @Transactional
    public Long addProduct(String company, String category, String product, String byName) {
        String cat = clean(category, 100, "Category");
        String prod = clean(product, 100, "Product name");

        for (ProductCategory p : productRepo.findByCompanyName(company)) {
            if (p.getCategoryName().equalsIgnoreCase(cat) && p.getProductName().equalsIgnoreCase(prod)) {
                if (!"ACTIVE".equals(p.getStatus())) {
                    p.setStatus("ACTIVE");
                    productRepo.save(p);
                }
                return p.getId();
            }
        }
        ProductCategory p = new ProductCategory();
        p.setCategoryName(cat);
        p.setProductName(prod);
        p.setCompanyName(company);
        p.setStatus("ACTIVE");
        p.setCreatedBy(byName);
        return productRepo.save(p).getId();
    }

    @Transactional
    public RetailVariant addVariant(String company, Long productId, String size, String colour, String barcode,
                                    String unit, String qty, String mrp, String salePrice,
                                    Long userId, String userName, HttpServletRequest request) {
        return addVariant(company, productId, null, size, colour, barcode, unit, qty, mrp, salePrice,
                userId, userName, request);
    }

    @Transactional
    public RetailVariant addVariant(String company, Long productId, String styleCode, String size, String colour,
                                    String barcode, String unit, String qty, String mrp, String salePrice,
                                    Long userId, String userName, HttpServletRequest request) {
        String style = optional(styleCode, 50, "Style code");
        ProductCategory product = productOf(company, productId);
        String u = unit(unit);
        String sz = optional(size, 50, "Size");
        String col = optional(colour, 50, "Colour");
        String bc = barcode(barcode);
        BigDecimal q = qty(qty, u);
        BigDecimal m = price(mrp, "MRP");
        BigDecimal sp = price(salePrice, "Sale price");
        if (m != null && sp != null && sp.compareTo(m) > 0) {
            throw new IllegalArgumentException("Sale price cannot be more than MRP.");
        }
        if (bc != null && variantRepo.existsByCompanyNameAndBarcode(company, bc)) {
            throw new IllegalArgumentException("This barcode is already used.");
        }
        if (sameVariantExists(company, productId, sz, col, null)) {
            throw new IllegalArgumentException("This size / colour already exists for the product.");
        }

        RetailVariant v = new RetailVariant();
        v.setCompanyName(company);
        v.setProductId(product.getId());
        v.setStyleCode(style);
        v.setSizeLabel(sz);
        v.setColour(col);
        v.setBarcode(bc);
        v.setUnit(u);
        v.setQty(q);
        v.setMrp(m);
        v.setSalePrice(sp);
        v.setStatus("ACTIVE");
        v.setCreatedByUserId(userId);
        v.setCreatedByName(userName);
        try {
            v = variantRepo.saveAndFlush(v);
            if (bc == null) {
                v.setBarcode(autoBarcode(company, v.getId()));
                v = variantRepo.save(v);
            }
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("This barcode is already used.");
        }
        audit(userId, userName, "RETAIL_VARIANT_ADD", "Added variant " + label(product, v)
                + " barcode " + v.getBarcode() + " qty " + q.toPlainString() + " " + u, request, v.getId());
        return v;
    }

    @Transactional
    public RetailVariant editVariant(String company, Long id, String styleCode, String size, String colour, String barcode,
                                     String unit, String mrp, String salePrice, String status,
                                     Long userId, String userName, HttpServletRequest request) {
        RetailVariant v = variantOf(company, id);
        String style = optional(styleCode, 50, "Style code");
        ProductCategory product = productOf(company, v.getProductId());
        String u = unit(unit);
        String sz = optional(size, 50, "Size");
        String col = optional(colour, 50, "Colour");
        String bc = barcode(barcode);
        if (bc == null) bc = v.getBarcode();           // blank = keep the existing barcode
        BigDecimal m = price(mrp, "MRP");
        BigDecimal sp = price(salePrice, "Sale price");
        if (m != null && sp != null && sp.compareTo(m) > 0) {
            throw new IllegalArgumentException("Sale price cannot be more than MRP.");
        }
        String st = "INACTIVE".equalsIgnoreCase(status) ? "INACTIVE" : "ACTIVE";
        if ("pcs".equals(u) && v.getQty().stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Quantity is not a whole number, so the unit cannot be pcs.");
        }
        if (bc != null && !bc.equals(v.getBarcode()) && variantRepo.existsByCompanyNameAndBarcode(company, bc)) {
            throw new IllegalArgumentException("This barcode is already used.");
        }
        if (sameVariantExists(company, v.getProductId(), sz, col, v.getId())) {
            throw new IllegalArgumentException("This size / colour already exists for the product.");
        }

        v.setStyleCode(style);
        v.setSizeLabel(sz);
        v.setColour(col);
        v.setBarcode(bc);
        v.setUnit(u);
        v.setMrp(m);
        v.setSalePrice(sp);
        v.setStatus(st);
        try {
            v = variantRepo.saveAndFlush(v);
        } catch (DataIntegrityViolationException e) {
            throw new IllegalArgumentException("This barcode is already used.");
        }
        audit(userId, userName, "RETAIL_VARIANT_EDIT", "Edited variant " + label(product, v), request, v.getId());
        return v;
    }

    /** delta can be negative (stock out). Result may never go below zero. */
    @Transactional
    public RetailVariant adjustStock(String company, Long id, String delta, String reason,
                                     Long userId, String userName, HttpServletRequest request) {
        RetailVariant v = variantOf(company, id);
        ProductCategory product = productOf(company, v.getProductId());

        BigDecimal d;
        try {
            d = new BigDecimal(delta == null ? "" : delta.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid quantity (use - for stock out).");
        }
        if (d.signum() == 0) throw new IllegalArgumentException("Quantity change cannot be zero.");
        if (d.abs().compareTo(MAX_QTY) > 0) throw new IllegalArgumentException("Quantity is too large.");
        if (d.scale() > 3) throw new IllegalArgumentException("Quantity can have at most 3 decimals.");
        if ("pcs".equals(v.getUnit()) && d.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Unit is pcs, so use a whole number.");
        }
        String why = clean(reason, 150, "Reason");

        BigDecimal before = v.getQty();
        BigDecimal after = before.add(d);
        if (after.signum() < 0) {
            throw new IllegalArgumentException("Stock cannot go below zero (available: " + before.stripTrailingZeros().toPlainString() + ").");
        }
        if (after.compareTo(MAX_QTY) > 0) throw new IllegalArgumentException("Resulting quantity is too large.");
        v.setQty(after);
        v = variantRepo.save(v);
        // every stock change gets a record: more stock = an inward, less stock = an outward
        RetailInwardService.LineData moved = new RetailInwardService.LineData(v.getId(),
                product.getCategoryName() + " / " + product.getProductName(), v.getStyleCode(), v.getSizeLabel(),
                v.getColour(), v.getBarcode(), v.getUnit(), d.abs(), v.getMrp(), v.getSalePrice());
        String movedNo;
        if (d.signum() > 0) {
            movedNo = inwardService.create(company, userId, userName, List.of(moved)).inwardNo();
        } else {
            movedNo = inwardService.recordOut(company, userId, userName, moved, why).inwardNo();
        }
        audit(userId, userName, "RETAIL_STOCK_ADJUST", "Stock " + label(product, v) + ": "
                + before.stripTrailingZeros().toPlainString() + " -> " + after.stripTrailingZeros().toPlainString()
                + " " + v.getUnit() + " (" + (d.signum() > 0 ? "+" : "") + d.stripTrailingZeros().toPlainString()
                + "). Reason: " + why + ". " + movedNo, request, v.getId());
        return v;
    }


    // ------------------------------------------- stock OUT (barcode + qty)

    /** what the Out screen sends: one barcode and how much goes out */
    public record OutEntry(String barcode, String qty) {}
    public record OutRequest(String reason, List<OutEntry> rows) {}

    /** one row to check (row = row number shown to the user) */
    public record OutInput(int row, String barcode, String qty, String reason) {}
    /** a row that can go out */
    public record OutOk(int row, RetailInwardService.LineData line, String label, BigDecimal available, String reason) {}
    /** a row that cannot go out, with the reason */
    public record OutBad(int row, String barcode, String qty, String reason, String error) {}
    /** result of checking many rows: ok ones, bad ones, total quantity of the ok ones */
    public record OutCheck(List<OutOk> ok, List<OutBad> bad, BigDecimal okQty) {}

    private static String plain(BigDecimal q) { return q.stripTrailingZeros().toPlainString(); }

    private static BigDecimal outQty(String raw, String unit) {
        BigDecimal q = qty(raw, unit);
        if (q.signum() == 0) throw new IllegalArgumentException("Quantity must be more than zero.");
        return q;
    }

    /**
     * Checks every row against the stock. A row is OK only when the barcode exists in this company's stock, the
     * variant is active and the quantity is available (rows with the same barcode share the same stock, in order).
     * lock = true locks the variants until the transaction ends, so stock can never go below zero.
     */
    private OutCheck checkOut(String company, List<OutInput> inputs, boolean lock) {
        java.util.Set<String> codes = new LinkedHashSet<>();
        for (OutInput in : inputs) {
            String b = nz(in.barcode()).trim();
            if (!b.isEmpty()) codes.add(b);
        }
        Map<String, RetailVariant> byCode = new HashMap<>();
        if (!codes.isEmpty()) {
            List<RetailVariant> found = lock
                    ? variantRepo.findLockedByCompanyNameAndBarcodeIn(company, codes)
                    : variantRepo.findByCompanyNameAndBarcodeIn(company, codes);
            for (RetailVariant v : found) {
                if (v.getBarcode() != null) byCode.put(v.getBarcode().toLowerCase(Locale.ROOT), v);
            }
        }
        Map<Long, BigDecimal> left = new HashMap<>();
        Map<Long, ProductCategory> products = new HashMap<>();
        List<OutOk> ok = new ArrayList<>();
        List<OutBad> bad = new ArrayList<>();
        BigDecimal okQty = BigDecimal.ZERO;
        for (OutInput in : inputs) {
            String code = nz(in.barcode()).trim();
            try {
                if (code.isEmpty()) throw new IllegalArgumentException("Barcode is required.");
                RetailVariant v = byCode.get(code.toLowerCase(Locale.ROOT));
                if (v == null) throw new IllegalArgumentException("Barcode not found in your stock.");
                if (!"ACTIVE".equals(v.getStatus())) throw new IllegalArgumentException("This variant is inactive.");
                BigDecimal q = outQty(in.qty(), v.getUnit());
                BigDecimal avail = left.containsKey(v.getId()) ? left.get(v.getId()) : v.getQty();
                if (q.compareTo(avail) > 0) {
                    throw new IllegalArgumentException("Only " + plain(avail) + " " + v.getUnit()
                            + " available (asked for " + plain(q) + ").");
                }
                ProductCategory p = products.get(v.getProductId());
                if (p == null) {
                    p = productOf(company, v.getProductId());
                    products.put(v.getProductId(), p);
                }
                String why = optional(in.reason(), 150, "Reason");
                left.put(v.getId(), avail.subtract(q));
                ok.add(new OutOk(in.row(), new RetailInwardService.LineData(v.getId(),
                        p.getCategoryName() + " / " + p.getProductName(), v.getStyleCode(), v.getSizeLabel(),
                        v.getColour(), v.getBarcode(), v.getUnit(), q, v.getMrp(), v.getSalePrice()),
                        label(p, v), avail, why));
                okQty = okQty.add(q);
            } catch (IllegalArgumentException e) {
                bad.add(new OutBad(in.row(), code, nz(in.qty()), nz(in.reason()), e.getMessage()));
            }
        }
        return new OutCheck(ok, bad, okQty);
    }

    /** Takes the stock out for rows that were already checked, and writes ONE outward for all of them. */
    private RetailInwardService.Saved applyOut(String company, List<OutOk> rows, String summaryReason,
                                               Long userId, String userName, HttpServletRequest request) {
        List<RetailInwardService.LineData> lines = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (OutOk r : rows) {
            RetailVariant v = variantRepo.findByIdAndCompanyName(r.line().variantId(), company)
                    .orElseThrow(() -> new IllegalArgumentException("Variant not found."));
            BigDecimal after = v.getQty().subtract(r.line().qty());
            if (after.signum() < 0) {      // cannot happen after checkOut(lock=true); stops a negative stock for sure
                throw new IllegalArgumentException("Stock cannot go below zero for barcode " + nz(v.getBarcode()) + ".");
            }
            v.setQty(after);
            variantRepo.save(v);
            lines.add(r.line());
            reasons.add(r.reason());
            total = total.add(r.line().qty());
        }
        RetailInwardService.Saved saved = inwardService.recordOutBatch(company, userId, userName, lines, reasons);
        audit(userId, userName, "RETAIL_STOCK_OUT", "Stock OUT " + saved.inwardNo() + ": " + rows.size() + " item(s), qty "
                + plain(total) + (summaryReason == null || summaryReason.isBlank() ? "" : ". Reason: " + summaryReason),
                request, saved.id());
        return saved;
    }

    /** Out screen: every row must be valid, otherwise nothing goes out. */
    @Transactional
    public RetailInwardService.Saved stockOut(String company, OutRequest req, Long userId, String userName,
                                              HttpServletRequest request) {
        if (req == null || req.rows() == null || req.rows().isEmpty()) {
            throw new IllegalArgumentException("Add at least one barcode.");
        }
        if (req.rows().size() > MAX_IMPORT_ROWS) {
            throw new IllegalArgumentException("Too many rows (max " + MAX_IMPORT_ROWS + ").");
        }
        String why = clean(req.reason(), 150, "Reason");
        List<OutInput> inputs = new ArrayList<>();
        int n = 0;
        for (OutEntry e : req.rows()) {
            n++;
            if (e == null || (nz(e.barcode()).isBlank() && nz(e.qty()).isBlank())) continue;
            inputs.add(new OutInput(n, e.barcode(), e.qty(), why));
        }
        if (inputs.isEmpty()) throw new IllegalArgumentException("Add at least one barcode.");
        OutCheck c = checkOut(company, inputs, true);
        if (!c.bad().isEmpty()) {
            List<String> msg = new ArrayList<>();
            for (OutBad b : c.bad()) {
                if (msg.size() == 5) break;
                msg.add("Row " + b.row() + ": " + b.error());
            }
            throw new IllegalArgumentException(String.join(" | ", msg)
                    + (c.bad().size() > 5 ? " | and " + (c.bad().size() - 5) + " more" : ""));
        }
        return applyOut(company, c.ok(), why, userId, userName, request);
    }

    // ---- bulk upload (Out): Barcode | Qty | Reason

    private static final String[] OUT_HEAD = {"Barcode", "Qty", "Reason"};

    private List<OutInput> readOutExcel(InputStream in) throws IOException {
        DataFormatter fmt = new DataFormatter();
        List<OutInput> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            Row head = sheet.getRow(0);
            String h0 = head == null || head.getCell(0) == null ? "" : fmt.formatCellValue(head.getCell(0)).trim();
            if (!h0.toLowerCase(Locale.ROOT).startsWith("barcode")) {
                throw new IllegalArgumentException("This is not the Stock Out template. Please download it again.");
            }
            int last = sheet.getLastRowNum();
            if (last > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException("Too many rows (max " + MAX_IMPORT_ROWS + " per file).");
            }
            for (int r = 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String[] c = new String[OUT_HEAD.length];
                boolean empty = true;
                for (int i = 0; i < OUT_HEAD.length; i++) {
                    Cell cell = row.getCell(i);
                    c[i] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!c[i].isEmpty()) empty = false;
                }
                if (empty) continue;
                rows.add(new OutInput(r + 1, c[0], c[1], c[2].isEmpty() ? "Bulk stock out" : c[2]));
            }
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("The file has no rows. Please fill the template.");
        return rows;
    }

    /** Checks the uploaded Out file. Nothing is saved. */
    @Transactional(readOnly = true)
    public OutCheck previewOutExcel(String company, InputStream in) throws IOException {
        return checkOut(company, readOutExcel(in), false);
    }

    /**
     * After the user pressed Confirm: reads and checks the same file again (stock may have changed), and takes out
     * the valid rows only. If they differ from what the user confirmed, nothing is saved.
     */
    @Transactional
    public RetailInwardService.Saved confirmOutExcel(String company, InputStream in, int expectCount, BigDecimal expectQty,
                                                     Long userId, String userName, HttpServletRequest request)
            throws IOException {
        OutCheck c = checkOut(company, readOutExcel(in), true);
        if (c.ok().isEmpty()) throw new IllegalArgumentException("There is nothing valid to stock out.");
        if (c.ok().size() != expectCount || c.okQty().compareTo(expectQty) != 0) {
            throw new IllegalArgumentException("Stock changed after the check. Nothing was taken out - please upload the file again.");
        }
        return applyOut(company, c.ok(), "Bulk upload", userId, userName, request);
    }

    /** Excel of the invalid rows (with the reason), to fix and upload again. */
    public byte[] invalidOutExcel(List<OutBad> bad) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Invalid rows");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            String[] head = {"Row in your file", "Barcode", "Qty", "Reason", "Why it is invalid"};
            Row h = sh.createRow(0);
            for (int i = 0; i < head.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
                sh.setColumnWidth(i, (i == 4 ? 48 : 18) * 256);
            }
            int n = 1;
            for (OutBad b : bad) {
                Row r = sh.createRow(n++);
                r.createCell(0).setCellValue(b.row());
                r.createCell(1).setCellValue(safe(b.barcode()));
                r.createCell(2).setCellValue(safe(b.qty()));
                r.createCell(3).setCellValue(safe(b.reason()));
                r.createCell(4).setCellValue(safe(b.error()));
            }
            sh.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Empty Stock Out template: Barcode (text), Qty, Reason (optional). */
    public byte[] outTemplateExcel() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Stock Out");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));     // keep long barcodes as typed
            sh.setDefaultColumnStyle(0, text);
            Row h = sh.createRow(0);
            for (int i = 0; i < OUT_HEAD.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(OUT_HEAD[i]);
                c.setCellStyle(bold);
                sh.setColumnWidth(i, (i == 2 ? 34 : 24) * 256);
            }
            sh.createFreezePane(0, 1);
            wb.write(out);
            return out.toByteArray();
        }
    }


    // ------------------------------------------- one card = one product

    public record VariantInput(String size, String colour, String barcode, String qty, String mrp, String salePrice) {}
    public record ProductBlock(String productId, String newCategory, String newProduct, String unit, String styleCode, List<VariantInput> rows) {}
    public record BatchRequest(List<ProductBlock> products) {}

    private static final int MAX_BATCH_PRODUCTS = 20;
    private static final int MAX_BATCH_ROWS = 500;

    /**
     * Saves several products and their variants in ONE transaction: if any row is wrong nothing is saved,
     * and the message says which product and row to fix. Every successful save also gets a unique
     * inward number (the "load" this stock came in with); it is returned so a PDF can be made.
     */
    @Transactional
    public RetailInwardService.Saved addBatch(String company, BatchRequest req, Long userId, String userName, HttpServletRequest request) {
        if (req == null || req.products() == null || req.products().isEmpty()) {
            throw new IllegalArgumentException("Add at least one product.");
        }
        if (req.products().size() > MAX_BATCH_PRODUCTS) {
            throw new IllegalArgumentException("Too many products at once (max " + MAX_BATCH_PRODUCTS + ").");
        }
        int total = 0;
        int idx = 0;
        List<RetailInwardService.LineData> received = new ArrayList<>();
        for (ProductBlock b : req.products()) {
            idx++;
            if (b == null) continue;
            List<VariantInput> rows = new ArrayList<>();
            if (b.rows() != null) {
                for (VariantInput r : b.rows()) {
                    if (r != null && !blankRow(r)) rows.add(r);
                }
            }
            boolean noProduct = b.productId() == null || b.productId().isBlank();
            if (noProduct && rows.isEmpty()) continue;            // untouched card
            if (total + rows.size() > MAX_BATCH_ROWS) {
                throw new IllegalArgumentException("Too many variants at once (max " + MAX_BATCH_ROWS + ").");
            }
            try {
                Long pid;
                if ("__new".equals(b.productId())) {
                    pid = addProduct(company, b.newCategory(), b.newProduct(), userName);
                } else {
                    try {
                        pid = Long.valueOf(noProduct ? "" : b.productId().trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("please select a product.");
                    }
                }
                if (rows.isEmpty()) throw new IllegalArgumentException("add at least one variant.");
                ProductCategory pc = productOf(company, pid);
                String productLabel = pc.getCategoryName() + " / " + pc.getProductName();
                int rn = 0;
                for (VariantInput r : rows) {
                    rn++;
                    try {
                        String qty = r.qty() == null || r.qty().isBlank() ? "0" : r.qty();
                        RetailVariant sv = addVariant(company, pid, b.styleCode(), r.size(), r.colour(), r.barcode(), b.unit(), qty,
                                r.mrp(), r.salePrice(), userId, userName, request);
                        received.add(new RetailInwardService.LineData(sv.getId(), productLabel, sv.getStyleCode(), sv.getSizeLabel(),
                                sv.getColour(), sv.getBarcode(), sv.getUnit(), sv.getQty(), sv.getMrp(), sv.getSalePrice()));
                        total++;
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("row " + rn + ": " + e.getMessage());
                    }
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Product " + idx + ": " + e.getMessage());
            }
        }
        if (total == 0) throw new IllegalArgumentException("Nothing to save. Fill at least one variant.");
        RetailInwardService.Saved saved = inwardService.create(company, userId, userName, received);
        audit(userId, userName, "RETAIL_INWARD_ADD", "Inward " + saved.inwardNo() + ": " + saved.variants()
                + " variant(s), total qty " + saved.totalQty().toPlainString(), request, saved.id());
        return saved;
    }

    private static boolean blankRow(VariantInput r) {
        return isBlank(r.size()) && isBlank(r.colour()) && isBlank(r.barcode())
                && isBlank(r.qty()) && isBlank(r.mrp()) && isBlank(r.salePrice());
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    // ------------------------------------------------- bulk (size x colour)

    /** Result of a bulk action: how many added / skipped and the first few problems. */
    public record BulkResult(int added, int skipped, List<String> errors) {}

    private static final int MAX_BULK = 200;
    private static final int MAX_IMPORT_ROWS = 2000;

    /** "S, M, L" x "Red, Blue" -> up to 200 variants with the same qty/price. Existing combinations are skipped. */
    public BulkResult bulkAdd(String company, Long productId, String sizesCsv, String coloursCsv, String unit,
                              String qty, String mrp, String salePrice,
                              Long userId, String userName, HttpServletRequest request) {
        List<String> sizes = splitCsv(sizesCsv, 50, "Size");
        List<String> colours = splitCsv(coloursCsv, 50, "Colour");
        if (sizes.isEmpty() && colours.isEmpty()) {
            throw new IllegalArgumentException("Enter at least one size or one colour.");
        }
        if (sizes.isEmpty()) sizes.add(null);
        if (colours.isEmpty()) colours.add(null);
        if (sizes.size() * colours.size() > MAX_BULK) {
            throw new IllegalArgumentException("Too many combinations (max " + MAX_BULK + " at a time).");
        }
        int added = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        for (String sz : sizes) {
            for (String col : colours) {
                if (sameVariantExists(company, productId, sz, col, null)) { skipped++; continue; }
                try {
                    addVariant(company, productId, sz, col, null, unit, qty, mrp, salePrice, userId, userName, request);
                    added++;
                } catch (IllegalArgumentException e) {
                    if (errors.isEmpty()) errors.add(e.getMessage());
                    skipped++;
                }
            }
        }
        return new BulkResult(added, skipped, errors);
    }

    private static List<String> splitCsv(String raw, int max, String label) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,;\\n]")) {
            String t = part.trim().replaceAll("\\s+", " ");
            if (t.isEmpty()) continue;
            if (t.length() > max) throw new IllegalArgumentException(label + " is too long (max " + max + ").");
            if (seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------- Excel import

    private static final String[] HEAD = {"Category", "Product", "Size", "Colour", "Barcode", "Unit", "Qty", "MRP", "Sale Price"};

    private static String key(String category, String product) {
        return (category == null ? "" : category.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                + "|" + (product == null ? "" : product.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
    }

    /** Reads .xlsx/.xls. Row 1 = header. Each row is saved on its own; bad rows are reported, good rows still go in. */
    public BulkResult importExcel(String company, InputStream in, Long userId, String userName, HttpServletRequest request)
            throws IOException {
        int added = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();
        Map<String, Long> existing = new HashMap<>();
        for (ProductCategory p : productRepo.findByCompanyNameAndStatus(company, "ACTIVE")) {
            existing.put(key(p.getCategoryName(), p.getProductName()), p.getId());
        }
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            int last = sheet.getLastRowNum();
            if (last > MAX_IMPORT_ROWS) {
                throw new IllegalArgumentException("Too many rows (max " + MAX_IMPORT_ROWS + " per file).");
            }
            for (int r = 1; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                String[] c = new String[HEAD.length];
                boolean empty = true;
                for (int i = 0; i < HEAD.length; i++) {
                    Cell cell = row.getCell(i);
                    c[i] = cell == null ? "" : fmt.formatCellValue(cell).trim();
                    if (!c[i].isEmpty()) empty = false;
                }
                if (empty) continue;
                try {
                    Long productId = existing.get(key(c[0], c[1]));
                    if (productId == null) {
                        throw new IllegalArgumentException("Product \"" + c[0] + " / " + c[1]
                                + "\" does not exist. Add it first using Add Variant > New product.");
                    }
                    String unit = c[5].isEmpty() ? "pcs" : c[5];
                    String qty = c[6].isEmpty() ? "0" : c[6];
                    addVariant(company, productId, c[2], c[3], c[4], unit, qty, c[7], c[8], userId, userName, request);
                    added++;
                } catch (IllegalArgumentException e) {
                    skipped++;
                    if (errors.size() < 10) errors.add("Row " + (r + 1) + ": " + e.getMessage());
                }
            }
        }
        return new BulkResult(added, skipped, errors);
    }

    /**
     * Empty sheet with the right headers. Category, Product (depends on Category) and Unit are fixed dropdowns
     * built from this company's existing products, so nothing new can be typed in.
     */
    public byte[] templateExcel(String company) throws IOException {
        // category -> products (existing, active)
        Map<String, List<String>> tree = new LinkedHashMap<>();
        for (ProductCategory p : activeProducts(company)) {
            tree.computeIfAbsent(p.getCategoryName(), k -> new ArrayList<>()).add(p.getProductName());
        }
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Variants");
            Sheet help = wb.createSheet("Help");
            Sheet lists = wb.createSheet("Lists");

            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Row h = sh.createRow(0);
            for (int i = 0; i < HEAD.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEAD[i]);
                c.setCellStyle(bold);
                sh.setColumnWidth(i, 20 * 256);
            }
            // Barcode column as Text so long numbers are not turned into 8.9E+12
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            sh.setDefaultColumnStyle(4, text);
            sh.createFreezePane(0, 1);

            // ---- hidden Lists sheet: A = category, B = safe name, D.. = products of each category
            int n = 0;
            lists.createRow(0).createCell(0).setCellValue("Category");
            lists.getRow(0).createCell(1).setCellValue("Name");
            for (Map.Entry<String, List<String>> e : tree.entrySet()) {
                n++;
                Row r = lists.getRow(n) != null ? lists.getRow(n) : lists.createRow(n);
                r.createCell(0).setCellValue(e.getKey());
                String safeName = "cat_" + n;
                r.createCell(1).setCellValue(safeName);

                int col = 2 + n;   // D, E, F ...
                lists.getRow(0).createCell(col).setCellValue(e.getKey());
                List<String> prods = e.getValue();
                for (int k = 0; k < prods.size(); k++) {
                    Row pr = lists.getRow(k + 1) != null ? lists.getRow(k + 1) : lists.createRow(k + 1);
                    pr.createCell(col).setCellValue(prods.get(k));
                }
                String L = CellReference.convertNumToColString(col);
                Name nm = wb.createName();
                nm.setNameName(safeName);
                nm.setRefersToFormula("Lists!$" + L + "$2:$" + L + "$" + (prods.size() + 1));
            }

            DataValidationHelper dvh = sh.getDataValidationHelper();
            if (n > 0) {
                addList(sh, dvh.createFormulaListConstraint("Lists!$A$2:$A$" + (n + 1)), dvh, 0,
                        "Choose a category from the list. New products are added from Add Variant > New product.");
                addList(sh, dvh.createFormulaListConstraint(
                        "INDIRECT(VLOOKUP($A2,Lists!$A$2:$B$" + (n + 1) + ",2,FALSE))"), dvh, 1,
                        "Choose a product from the list (pick the Category first).");
            }
            addList(sh, dvh.createExplicitListConstraint(UNITS_ORDERED), dvh, 5,
                    "Choose pcs, kg, g, litre or ml.");
            wb.setSheetVisibility(wb.getSheetIndex(lists), SheetVisibility.HIDDEN);

            String[] lines = {
                "1. Category and Product are dropdowns: only products that already exist can be used.",
                n == 0 ? "   No products exist yet. First create them from Add Variant > \"+ New product...\", then download this template again."
                       : "   To use a new product, create it first from Add Variant > \"+ New product...\", then download this template again.",
                "2. Size, Colour, MRP and Sale Price are optional. Unit is a dropdown (blank = pcs). Qty is a number (blank = 0).",
                "3. Barcode blank = auto-generated. Same product + size + colour is skipped if it already exists.",
                "4. Max " + MAX_IMPORT_ROWS + " rows per file."};
            for (int i = 0; i < lines.length; i++) help.createRow(i).createCell(0).setCellValue(lines[i]);
            help.setColumnWidth(0, 120 * 256);

            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static final String[] UNITS_ORDERED = {"pcs", "kg", "g", "litre", "ml"};

    private static void addList(Sheet sh, DataValidationConstraint c, DataValidationHelper dvh, int col, String msg) {
        CellRangeAddressList range = new CellRangeAddressList(1, MAX_IMPORT_ROWS, col, col);
        DataValidation dv = dvh.createValidation(c, range);
        dv.setShowErrorBox(true);
        dv.setErrorStyle(DataValidation.ErrorStyle.STOP);
        dv.createErrorBox("Not allowed", msg);
        dv.setSuppressDropDownArrow(true);   // true = show the arrow (POI naming)
        sh.addValidationData(dv);
    }

    /** Current stock list as .xlsx (same columns as the import file + Status). */
    public byte[] exportExcel(String company) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Stock");
            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            String[] head = {"Category", "Product", "Size", "Colour", "Barcode", "Unit", "Qty", "MRP", "Sale Price", "Status", "Style Code"};
            Row h = sh.createRow(0);
            for (int i = 0; i < head.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(head[i]);
                c.setCellStyle(bold);
                sh.setColumnWidth(i, 18 * 256);
            }
            int n = 1;
            for (Line r : rows(company, null)) {
                RetailVariant v = r.v();
                Row row = sh.createRow(n++);
                row.createCell(0).setCellValue(safe(r.category()));
                row.createCell(1).setCellValue(safe(r.product()));
                row.createCell(2).setCellValue(safe(v.getSizeLabel()));
                row.createCell(3).setCellValue(safe(v.getColour()));
                row.createCell(4).setCellValue(safe(v.getBarcode()));
                row.createCell(5).setCellValue(v.getUnit());
                row.createCell(6).setCellValue(v.getQty().doubleValue());
                if (v.getMrp() != null) row.createCell(7).setCellValue(v.getMrp().doubleValue());
                if (v.getSalePrice() != null) row.createCell(8).setCellValue(v.getSalePrice().doubleValue());
                row.createCell(9).setCellValue(v.getStatus());
                row.createCell(10).setCellValue(safe(v.getStyleCode()));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** Stops Excel from running a text cell as a formula (=, +, -, @ at the start). */
    private static String safe(String s) {
        if (s == null) return "";
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) return "'" + s;
        return s;
    }

    // ------------------------------------------------------------- helpers

    private RetailVariant variantOf(String company, Long id) {
        if (id == null) throw new IllegalArgumentException("Variant not found.");
        return variantRepo.findByIdAndCompanyName(id, company)
                .orElseThrow(() -> new IllegalArgumentException("Variant not found."));
    }

    private ProductCategory productOf(String company, Long productId) {
        if (productId == null) throw new IllegalArgumentException("Please select a product.");
        return productRepo.findById(productId)
                .filter(p -> company.equalsIgnoreCase(p.getCompanyName()))
                .orElseThrow(() -> new IllegalArgumentException("Product not found."));
    }

    private boolean sameVariantExists(String company, Long productId, String size, String colour, Long exceptId) {
        for (RetailVariant o : variantRepo.findByCompanyNameAndProductId(company, productId)) {
            if (exceptId != null && exceptId.equals(o.getId())) continue;
            if (nz(o.getSizeLabel()).equalsIgnoreCase(nz(size)) && nz(o.getColour()).equalsIgnoreCase(nz(colour))) {
                return true;
            }
        }
        return false;
    }

    private String autoBarcode(String company, Long id) {
        String base = String.format("V%08d", id);
        String candidate = base;
        int n = 0;
        while (variantRepo.existsByCompanyNameAndBarcode(company, candidate)) {
            candidate = base + "-" + (++n);
        }
        return candidate;
    }

    private static String unit(String unit) {
        String u = unit == null ? "" : unit.trim().toLowerCase(Locale.ROOT);
        if (!UNITS.contains(u)) throw new IllegalArgumentException("Please select a valid unit.");
        return u;
    }

    private static String barcode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String b = raw.trim();
        if (!BARCODE.matcher(b).matches()) {
            throw new IllegalArgumentException("Barcode can have letters, numbers and . _ / - only (max 64).");
        }
        return b;
    }

    private static BigDecimal qty(String raw, String unit) {
        BigDecimal q;
        try {
            q = new BigDecimal(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid quantity.");
        }
        if (q.signum() < 0) throw new IllegalArgumentException("Quantity cannot be negative.");
        if (q.compareTo(MAX_QTY) > 0) throw new IllegalArgumentException("Quantity is too large.");
        if (q.scale() > 3 && q.stripTrailingZeros().scale() > 3) {
            throw new IllegalArgumentException("Quantity can have at most 3 decimals.");
        }
        if ("pcs".equals(unit) && q.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("Unit is pcs, so use a whole number.");
        }
        return q.setScale(3, java.math.RoundingMode.UNNECESSARY);
    }

    private static BigDecimal price(String raw, String label) {
        if (raw == null || raw.isBlank()) return null;
        BigDecimal p;
        try {
            p = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " is not a valid amount.");
        }
        if (p.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
        if (p.compareTo(MAX_PRICE) > 0) throw new IllegalArgumentException(label + " is too large.");
        if (p.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException(label + " can have at most 2 decimals.");
        return p.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    private static String clean(String raw, int max, String label) {
        String s = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) throw new IllegalArgumentException(label + " is required.");
        if (s.length() > max) throw new IllegalArgumentException(label + " is too long (max " + max + ").");
        return s;
    }

    private static String optional(String raw, int max, String label) {
        String s = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        if (s.length() > max) throw new IllegalArgumentException(label + " is too long (max " + max + ").");
        return s;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String label(ProductCategory p, RetailVariant v) {
        StringBuilder sb = new StringBuilder(p.getCategoryName()).append(" / ").append(p.getProductName());
        if (v.getSizeLabel() != null) sb.append(" / ").append(v.getSizeLabel());
        if (v.getColour() != null) sb.append(" / ").append(v.getColour());
        return sb.toString();
    }

    private void audit(Long userId, String userName, String action, String text, HttpServletRequest request, Long refId) {
        try {
            auditLogService.log(userId, userName, action, "Retail", "Products & Variants", text, request);
        } catch (RuntimeException ignored) {
            // audit failure must not block stock work
        }
    }
}
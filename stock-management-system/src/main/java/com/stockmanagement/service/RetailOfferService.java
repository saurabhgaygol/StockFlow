package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.RetailOffer;
import com.stockmanagement.entity.RetailOfferItem;
import com.stockmanagement.entity.RetailVariant;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.RetailOfferItemRepository;
import com.stockmanagement.repository.RetailOfferRepository;
import com.stockmanagement.repository.RetailVariantRepository;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Offers & Discounts. An offer is live when it is switched ON and today is inside its optional start/end dates.
 * {@link #appliedFor} is the one place that decides which offer a variant gets, so a future Billing screen
 * can call it when a barcode is scanned (and simply skip it for "void all offers").
 */
@Service
public class RetailOfferService {

    public static final int ITEM_LIMIT = 20000;
    /** items are written to the database this many at a time (all inside one transaction) */
    private static final int SAVE_BATCH = 500;

    /** input from the screen (JSON) */
    public record ItemIn(String scope, String ref) {}
    public record OfferIn(Long id, String name, String type, String value, String buyQty, String getQty,
                          String startDate, String endDate, Boolean enabled, String minAgeMonths,
                          String freePick, String groupMode, Boolean combinable, String priority,
                          String minBill, Boolean cashierCanRemove, List<ItemIn> items) {}

    /** one chosen item with a readable label */
    public record ItemView(String scope, String ref, String label) {}

    /** one offer row for the list: status is LIVE / SCHEDULED / EXPIRED / OFF */
    public record OfferView(RetailOffer o, String status, String summary, List<ItemView> items) {
        public String age() { return RetailOfferService.ageText(o); }
    }

    /** the offer a variant gets right now (offerPrice is null for Buy X Get Y) */
    public record Applied(Long offerId, String name, String type, String label,
                          BigDecimal basePrice, BigDecimal offerPrice) {}

    public record VariantHit(Long id, String label, String barcode, BigDecimal price) {}

    private final RetailOfferRepository offerRepo;
    private final RetailOfferItemRepository itemRepo;
    private final RetailVariantRepository variantRepo;
    private final ProductCategoryRepository productRepo;
    private final AuditLogService auditLogService;
    private final RetailVariantService variantService;
    private final RetailInwardService inwardService;

    public RetailOfferService(RetailOfferRepository offerRepo,
                              RetailOfferItemRepository itemRepo,
                              RetailVariantRepository variantRepo,
                              ProductCategoryRepository productRepo,
                              AuditLogService auditLogService,
                              RetailVariantService variantService,
                              RetailInwardService inwardService) {
        this.offerRepo = offerRepo;
        this.itemRepo = itemRepo;
        this.variantRepo = variantRepo;
        this.productRepo = productRepo;
        this.auditLogService = auditLogService;
        this.variantService = variantService;
        this.inwardService = inwardService;
    }

    // ---------------------------------------------------------------- list / read

    @Transactional(readOnly = true)
    public List<OfferView> list(String company) {
        List<RetailOffer> offers = offerRepo.findByCompanyNameOrderByIdDesc(company);
        if (offers.isEmpty()) return List.of();
        Map<Long, List<RetailOfferItem>> byOffer = itemRepo
                .findByCompanyNameAndOfferIdIn(company, offers.stream().map(RetailOffer::getId).toList())
                .stream().collect(Collectors.groupingBy(RetailOfferItem::getOfferId));
        Labels labels = new Labels(company);
        List<OfferView> out = new ArrayList<>();
        for (RetailOffer o : offers) {
            List<ItemView> items = new ArrayList<>();
            for (RetailOfferItem i : byOffer.getOrDefault(o.getId(), List.of())) {
                items.add(new ItemView(i.getScopeType(), i.getRefValue(), labels.of(i)));
            }
            out.add(new OfferView(o, status(o), summary(o), items));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public OfferView get(String company, Long id) {
        RetailOffer o = offerRepo.findByIdAndCompanyName(id, company)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        Labels labels = new Labels(company);
        List<ItemView> items = itemRepo.findByCompanyNameAndOfferId(company, id).stream()
                .map(i -> new ItemView(i.getScopeType(), i.getRefValue(), labels.of(i))).toList();
        return new OfferView(o, status(o), summary(o), items);
    }

    /** categories, products and style codes for the pickers */
    @Transactional(readOnly = true)
    public List<ProductCategory> products(String company) { return variantService.activeProducts(company); }

    @Transactional(readOnly = true)
    public List<String> styleCodes(String company) { return variantRepo.findStyleCodes(company); }

    /** variants that match a typed search (barcode, product, size, colour, style) */
    @Transactional(readOnly = true)
    public List<VariantHit> searchVariants(String company, String q) {
        if (q == null || q.isBlank()) return List.of();
        List<VariantHit> out = new ArrayList<>();
        for (RetailVariantService.Line l : variantService.rows(company, q)) {
            RetailVariant v = l.v();
            if (!"ACTIVE".equals(v.getStatus())) continue;
            out.add(new VariantHit(v.getId(), variantLabel(l.category(), l.product(), v), v.getBarcode(), price(v)));
            if (out.size() >= 30) break;
        }
        return out;
    }

    // ---------------------------------------------------------------- barcode Excel

    /** one Excel row: the barcode (sizes is always empty now: only barcodes are read) */
    public record BarcodeRow(String barcode, Set<String> sizes) {}

    public record Skipped(String barcode, String reason) {}

    public record BarcodeResult(List<VariantHit> found, List<Skipped> skipped) {}

    private static final int MAX_BARCODE_ROWS = 20000;
    private static final String[] STANDARD_SIZES = {"XS", "S", "M", "L", "XL", "XXL", "3XL", "4XL"};

    /**
     * Only column A is read: one barcode per row (the "Barcode" heading row is skipped). Any other column is ignored.
     * Repeated barcodes are counted once.
     */
    public List<BarcodeRow> readBarcodes(InputStream in) {
        List<String> raw = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getNumberOfSheets() == 0 ? null : wb.getSheetAt(0);
            if (sheet != null) {
                DataFormatter fmt = new DataFormatter();
                for (Row row : sheet) {
                    Cell cell = row.getCell(0);
                    raw.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                    if (raw.size() > MAX_BARCODE_ROWS + 1000) break;
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Could not read the Excel file. Please use a normal .xlsx or .xls file with barcodes in the first column.");
        }
        if (raw.isEmpty()) throw new IllegalArgumentException("The Excel file is empty.");

        String h0 = raw.get(0).replace("*", "").trim();
        boolean hasHeader = h0.equalsIgnoreCase("barcode") || h0.equalsIgnoreCase("barcodes");
        Set<String> codes = new LinkedHashSet<>();
        for (int i = hasHeader ? 1 : 0; i < raw.size(); i++) {
            if (!raw.get(i).isEmpty()) codes.add(raw.get(i));
        }
        if (codes.isEmpty()) throw new IllegalArgumentException("No barcodes found in the first column.");
        if (codes.size() > MAX_BARCODE_ROWS)
            throw new IllegalArgumentException("Too many barcodes. Please keep it within " + MAX_BARCODE_ROWS + " per file.");
        List<BarcodeRow> out = new ArrayList<>();
        for (String c : codes) out.add(new BarcodeRow(c, Set.of()));
        return out;
    }

    /** matches barcodes to this company's variants; a filled Size must match the item's size. Nothing is saved */
    public BarcodeResult variantsByBarcodes(String company, List<BarcodeRow> rows) {
        List<String> codes = rows.stream().map(BarcodeRow::barcode).collect(Collectors.toList());
        Map<String, RetailVariant> byCode = new HashMap<>();
        for (int i = 0; i < codes.size(); i += 500) {
            List<String> part = codes.subList(i, Math.min(codes.size(), i + 500));
            for (RetailVariant v : variantRepo.findByCompanyNameAndBarcodeIn(company, part)) {
                if (v.getBarcode() != null) byCode.put(v.getBarcode(), v);
            }
        }
        Map<Long, ProductCategory> products = new HashMap<>();
        for (ProductCategory p : productRepo.findByCompanyName(company)) products.put(p.getId(), p);

        List<VariantHit> found = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (BarcodeRow row : rows) {
            RetailVariant v = byCode.get(row.barcode());
            if (v == null) { skipped.add(new Skipped(row.barcode(), "Not in the system")); continue; }
            if (!"ACTIVE".equals(v.getStatus())) { skipped.add(new Skipped(row.barcode(), "Item is inactive")); continue; }
            if (!row.sizes().isEmpty()) {
                String have = v.getSizeLabel() == null ? "" : v.getSizeLabel().trim().toLowerCase(Locale.ROOT);
                if (!row.sizes().contains(have)) {
                    skipped.add(new Skipped(row.barcode(), "Size does not match (item size: "
                            + (have.isEmpty() ? "none" : v.getSizeLabel().trim()) + ")"));
                    continue;
                }
            }
            ProductCategory p = products.get(v.getProductId());
            found.add(new VariantHit(v.getId(), variantLabel(p == null ? "" : p.getCategoryName(),
                    p == null ? "" : p.getProductName(), v), v.getBarcode(), price(v)));
        }
        return new BarcodeResult(found, skipped);
    }

    /** Template for the barcode upload: one required column, Barcode */
    public byte[] barcodeTemplate(String company) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Barcodes");
            Sheet help = wb.createSheet("How to use");

            CellStyle bold = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(true);
            bold.setFont(f);
            Cell h0 = sh.createRow(0).createCell(0);
            h0.setCellValue("Barcode *");
            h0.setCellStyle(bold);
            sh.setColumnWidth(0, 30 * 256);

            String[] lines = {
                "1. Barcode * is required: one barcode per row in column A. Keep the heading row.",
                "2. Barcodes that are not in the system or are inactive are skipped and listed in a downloadable Excel.",
                "3. Max " + MAX_BARCODE_ROWS + " barcodes per file.",
                "4. Save the filled file as .xlsx (Excel Workbook) before uploading it."};
            for (int i = 0; i < lines.length; i++) help.createRow(i).createCell(0).setCellValue(lines[i]);
            help.setColumnWidth(0, 110 * 256);

            wb.setActiveSheet(0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the template.");
        }
    }

    /** Excel listing the barcodes that did not get the offer, with the reason; null when there are none */
    public byte[] notFoundExcel(List<Skipped> skipped) {
        if (skipped.isEmpty()) return null;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Skipped");
            Row head = sh.createRow(0);
            head.createCell(0).setCellValue("Barcode");
            head.createCell(1).setCellValue("Reason");
            int r = 1;
            for (Skipped s : skipped) {
                Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(s.barcode());
                row.createCell(1).setCellValue(s.reason());
            }
            sh.setColumnWidth(0, 24 * 256);
            sh.setColumnWidth(1, 44 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- save

    @Transactional
    public Long save(String company, Long userId, String userName, OfferIn in, HttpServletRequest request) {
        if (in == null) throw new IllegalArgumentException("Nothing to save.");
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Offer name is required.");
        if (name.length() > 120) throw new IllegalArgumentException("Offer name is too long (max 120 characters).");

        String type = in.type() == null ? "" : in.type().trim().toUpperCase(Locale.ROOT);
        BigDecimal value = null;
        Integer buy = null, get = null;
        BigDecimal minBill = null;
        String freePick = null, groupMode = null;
        boolean billType = RetailOffer.BILLPCT.equals(type) || RetailOffer.BILLFLAT.equals(type);
        switch (type) {
            case RetailOffer.PERCENT -> {
                value = money(in.value(), "Percentage");
                if (value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0)
                    throw new IllegalArgumentException("Percentage must be more than 0 and at most 100.");
            }
            case RetailOffer.FLAT -> {
                value = money(in.value(), "Flat amount");
                if (value.signum() <= 0) throw new IllegalArgumentException("Flat amount must be more than 0.");
            }
            case RetailOffer.FIXED -> {
                value = money(in.value(), "Offer price");
                if (value.signum() < 0) throw new IllegalArgumentException("Offer price cannot be negative.");
            }
            case RetailOffer.BXGY -> {
                buy = whole(in.buyQty(), "Buy quantity");
                get = whole(in.getQty(), "Free quantity");
                freePick = "COSTLIEST".equalsIgnoreCase(in.freePick()) ? "COSTLIEST" : "CHEAPEST";
                groupMode = "SAME".equalsIgnoreCase(in.groupMode()) ? "SAME" : "ANY";
            }
            case RetailOffer.BILLPCT -> {
                value = money(in.value(), "Percentage");
                if (value.signum() <= 0 || value.compareTo(new BigDecimal("100")) > 0)
                    throw new IllegalArgumentException("Percentage must be more than 0 and at most 100.");
                minBill = money(in.minBill(), "Minimum bill");
                if (minBill.signum() <= 0) throw new IllegalArgumentException("Minimum bill must be more than 0.");
            }
            case RetailOffer.BILLFLAT -> {
                value = money(in.value(), "Amount off");
                if (value.signum() <= 0) throw new IllegalArgumentException("Amount off must be more than 0.");
                minBill = money(in.minBill(), "Minimum bill");
                if (minBill.signum() <= 0) throw new IllegalArgumentException("Minimum bill must be more than 0.");
                if (value.compareTo(minBill) >= 0)
                    throw new IllegalArgumentException("Amount off must be less than the minimum bill.");
            }
            default -> throw new IllegalArgumentException("Choose an offer type.");
        }

        LocalDate start = date(in.startDate(), "Start date");
        LocalDate end = date(in.endDate(), "End date");
        if (start != null && end != null && end.isBefore(start))
            throw new IllegalArgumentException("End date cannot be before the start date.");

        Integer priority = null;
        if (in.priority() != null && !in.priority().isBlank()) {
            try {
                priority = Integer.parseInt(in.priority().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Priority must be a whole number.");
            }
            if (priority < 1 || priority > 999) throw new IllegalArgumentException("Priority must be from 1 to 999.");
        }

        Integer minAge = null;
        if (!billType && in.minAgeMonths() != null && !in.minAgeMonths().isBlank()) {
            try {
                minAge = Integer.parseInt(in.minAgeMonths().trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Stock age must be a whole number of months.");
            }
            if (minAge < 1 || minAge > 120) throw new IllegalArgumentException("Stock age must be from 1 to 120 months.");
        }

        // items may be empty only when a stock age is set (then the offer covers every product of that age)
        // bill offers cover the whole bill, so they have no items
        List<RetailOfferItem> items = (billType || (minAge != null && (in.items() == null || in.items().isEmpty())))
                ? new ArrayList<>() : cleanItems(company, in.items());

        RetailOffer o;
        boolean isNew = in.id() == null;
        if (isNew) {
            o = new RetailOffer();
            o.setCompanyName(company);
            o.setCreatedBy(userName);
        } else {
            o = offerRepo.findByIdAndCompanyName(in.id(), company)
                    .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        }
        o.setName(name);
        o.setOfferType(type);
        o.setValue(value);
        o.setBuyQty(buy);
        o.setGetQty(get);
        o.setStartDate(start);
        o.setEndDate(end);
        o.setMinAgeMonths(minAge);
        o.setFreePick(freePick);
        o.setGroupMode(groupMode);
        o.setCombinable(Boolean.TRUE.equals(in.combinable()));
        o.setPriority(priority);
        o.setMinBill(minBill);
        o.setLockedForCashier(Boolean.FALSE.equals(in.cashierCanRemove()));
        o.setEnabled(in.enabled() == null || in.enabled());
        o = offerRepo.save(o);

        if (!isNew) itemRepo.deleteByCompanyNameAndOfferId(company, o.getId());
        for (RetailOfferItem i : items) i.setOfferId(o.getId());
        // saved 500 at a time; this whole method is one transaction, so if anything fails part-way
        // nothing is kept (not the offer, not the batches already written)
        for (int i = 0; i < items.size(); i += SAVE_BATCH) {
            itemRepo.saveAll(items.subList(i, Math.min(items.size(), i + SAVE_BATCH)));
            itemRepo.flush();
        }

        audit(userId, userName, isNew ? "RETAIL_OFFER_ADD" : "RETAIL_OFFER_EDIT",
                (isNew ? "Created offer \"" : "Edited offer \"") + name + "\" (" + summary(o) + ", "
                        + (billType ? "whole bill" : items.isEmpty() ? "all products" : items.size() + " item" + (items.size() == 1 ? "" : "s"))
                        + (minAge == null ? "" : ", stock older than " + minAge + " months") + ")", request);
        return o.getId();
    }

    @Transactional
    public void setEnabled(String company, Long userId, String userName, Long id, boolean on, HttpServletRequest request) {
        RetailOffer o = offerRepo.findByIdAndCompanyName(id, company)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        o.setEnabled(on);
        offerRepo.save(o);
        audit(userId, userName, on ? "RETAIL_OFFER_ON" : "RETAIL_OFFER_OFF",
                "Offer \"" + o.getName() + "\" switched " + (on ? "ON" : "OFF"), request);
    }

    /** change only the end date (blank = no end date) */
    @Transactional
    public void extend(String company, Long userId, String userName, Long id, String endDate, HttpServletRequest request) {
        RetailOffer o = offerRepo.findByIdAndCompanyName(id, company)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        LocalDate end = date(endDate, "End date");
        if (end != null) {
            if (o.getStartDate() != null && end.isBefore(o.getStartDate()))
                throw new IllegalArgumentException("End date cannot be before the start date.");
            if (end.isBefore(LocalDate.now()))
                throw new IllegalArgumentException("Choose today or a later date.");
        }
        o.setEndDate(end);
        offerRepo.save(o);
        audit(userId, userName, "RETAIL_OFFER_EXTEND",
                "Offer \"" + o.getName() + "\" end date set to " + (end == null ? "no end date" : end), request);
    }

    @Transactional
    public void delete(String company, Long userId, String userName, Long id, HttpServletRequest request) {
        RetailOffer o = offerRepo.findByIdAndCompanyName(id, company)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        itemRepo.deleteByCompanyNameAndOfferId(company, id);
        offerRepo.delete(o);
        audit(userId, userName, "RETAIL_OFFER_DELETE", "Deleted offer \"" + o.getName() + "\"", request);
    }

    // ---------------------------------------------------------------- resolver

    /**
     * Which live offer each of these variants gets (variants without an offer are absent from the map).
     * A price offer (percent / flat / fixed) wins over Buy X Get Y; between price offers the lowest price wins.
     */
    @Transactional(readOnly = true)
    public Map<Long, Applied> appliedFor(String company, Collection<RetailVariantService.Line> rows) {
        Map<Long, Applied> result = new HashMap<>();
        if (rows == null || rows.isEmpty()) return result;
        LocalDate today = LocalDate.now();
        // bill offers work on the whole bill (billing), never on a single item price
        List<RetailOffer> live = offerRepo.findByCompanyNameOrderByIdDesc(company).stream()
                .filter(o -> isLive(o, today) && !isBill(o)).toList();
        if (live.isEmpty()) return result;

        Map<Long, List<RetailOfferItem>> items = itemRepo
                .findByCompanyNameAndOfferIdIn(company, live.stream().map(RetailOffer::getId).toList())
                .stream().collect(Collectors.groupingBy(RetailOfferItem::getOfferId));

        // stock age is only worked out when some live offer needs it
        Map<Long, LocalDate> received = Map.of();
        if (live.stream().anyMatch(o -> o.getMinAgeMonths() != null)) {
            received = inwardService.latestInwardDates(company,
                    rows.stream().map(l -> l.v().getId()).collect(Collectors.toList()));
        }

        for (RetailVariantService.Line l : rows) {
            RetailVariant v = l.v();
            if (!"ACTIVE".equals(v.getStatus())) continue;
            BigDecimal base = price(v);
            Applied best = null;
            RetailOffer bestOffer = null;
            for (RetailOffer o : live) {
                List<RetailOfferItem> its = items.getOrDefault(o.getId(), List.of());
                if (!its.isEmpty() && !matches(its, v, l.category())) continue;
                if (o.getMinAgeMonths() != null && !oldEnough(v, received.get(v.getId()), o.getMinAgeMonths(), today)) continue;
                Applied a = apply(o, v, base);
                if (a == null) continue;
                if (best == null || beats(o, a, bestOffer, best)) { best = a; bestOffer = o; }
            }
            if (best != null) result.put(v.getId(), best);
        }
        return result;
    }

    /** stock age = when the variant was last received (its latest inward), else when it was added */
    private static boolean oldEnough(RetailVariant v, LocalDate lastInward, int months, LocalDate today) {
        LocalDate since = lastInward != null ? lastInward
                : (v.getCreatedAt() == null ? null : v.getCreatedAt().toLocalDate());
        if (since == null) return false;
        return !since.isAfter(today.minusMonths(months));
    }

    public static String ageText(RetailOffer o) {
        return o.getMinAgeMonths() == null ? "" : "Stock older than " + o.getMinAgeMonths()
                + (o.getMinAgeMonths() == 1 ? " month" : " months");
    }

    static boolean isBill(RetailOffer o) {
        return RetailOffer.BILLPCT.equals(o.getOfferType()) || RetailOffer.BILLFLAT.equals(o.getOfferType());
    }

    /** a smaller priority number wins first; with equal (or no) priority the better price wins */
    private static boolean beats(RetailOffer o, Applied a, RetailOffer cur, Applied best) {
        int po = o.getPriority() == null ? Integer.MAX_VALUE : o.getPriority();
        int pc = cur.getPriority() == null ? Integer.MAX_VALUE : cur.getPriority();
        if (po != pc) return po < pc;
        return better(a, best);
    }

    private static boolean better(Applied a, Applied than) {
        if (a.offerPrice() != null && than.offerPrice() == null) return true;
        if (a.offerPrice() == null) return false;
        return a.offerPrice().compareTo(than.offerPrice()) < 0;
    }

    private static Applied apply(RetailOffer o, RetailVariant v, BigDecimal base) {
        String type = o.getOfferType();
        if (RetailOffer.BXGY.equals(type)) {
            return new Applied(o.getId(), o.getName(), type, summary(o), base, null);
        }
        if (base == null) return null;
        BigDecimal p;
        switch (type) {
            case RetailOffer.PERCENT -> p = base.subtract(base.multiply(o.getValue()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            case RetailOffer.FLAT -> p = base.subtract(o.getValue());
            case RetailOffer.FIXED -> p = o.getValue();
            default -> { return null; }
        }
        if (p.signum() < 0) p = BigDecimal.ZERO;
        p = p.setScale(2, RoundingMode.HALF_UP);
        if (p.compareTo(base) >= 0) return null; // not a real discount
        return new Applied(o.getId(), o.getName(), type, summary(o), base, p);
    }

    private static boolean matches(List<RetailOfferItem> items, RetailVariant v, String category) {
        for (RetailOfferItem i : items) {
            String ref = i.getRefValue();
            switch (i.getScopeType()) {
                case RetailOfferItem.VARIANT -> { if (v.getId() != null && String.valueOf(v.getId()).equals(ref)) return true; }
                case RetailOfferItem.PRODUCT -> { if (v.getProductId() != null && String.valueOf(v.getProductId()).equals(ref)) return true; }
                case RetailOfferItem.CATEGORY -> { if (category != null && category.equalsIgnoreCase(ref)) return true; }
                case RetailOfferItem.STYLE -> { if (v.getStyleCode() != null && v.getStyleCode().equalsIgnoreCase(ref)) return true; }
                default -> { }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    static boolean isLive(RetailOffer o, LocalDate today) {
        return "LIVE".equals(status(o, today));
    }

    public static String status(RetailOffer o) { return status(o, LocalDate.now()); }

    static String status(RetailOffer o, LocalDate today) {
        if (!Boolean.TRUE.equals(o.getEnabled())) return "OFF";
        if (o.getStartDate() != null && o.getStartDate().isAfter(today)) return "SCHEDULED";
        if (o.getEndDate() != null && o.getEndDate().isBefore(today)) return "EXPIRED";
        return "LIVE";
    }

    public static String summary(RetailOffer o) {
        String t = o.getOfferType();
        if (t == null) return "";
        return switch (t) {
            case RetailOffer.PERCENT -> o.getValue().stripTrailingZeros().toPlainString() + "% off";
            case RetailOffer.FLAT -> "₹" + o.getValue().stripTrailingZeros().toPlainString() + " off";
            case RetailOffer.FIXED -> "Only ₹" + o.getValue().stripTrailingZeros().toPlainString();
            case RetailOffer.BXGY -> "Buy " + o.getBuyQty() + " Get " + o.getGetQty() + " Free";
            case RetailOffer.BILLPCT -> o.getValue().stripTrailingZeros().toPlainString() + "% off on bills of ₹"
                    + o.getMinBill().stripTrailingZeros().toPlainString() + " or more";
            case RetailOffer.BILLFLAT -> "₹" + o.getValue().stripTrailingZeros().toPlainString() + " off on bills of ₹"
                    + o.getMinBill().stripTrailingZeros().toPlainString() + " or more";
            default -> t;
        };
    }

    private static BigDecimal price(RetailVariant v) {
        return v.getSalePrice() != null ? v.getSalePrice() : v.getMrp();
    }

    private static String variantLabel(String category, String product, RetailVariant v) {
        StringBuilder sb = new StringBuilder(product == null ? "" : product);
        if (v.getSizeLabel() != null && !v.getSizeLabel().isBlank()) sb.append(" / ").append(v.getSizeLabel());
        if (v.getColour() != null && !v.getColour().isBlank()) sb.append(" / ").append(v.getColour());
        if (v.getBarcode() != null && !v.getBarcode().isBlank()) sb.append(" (").append(v.getBarcode()).append(")");
        return sb.toString();
    }

    private static BigDecimal money(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(field + " is required.");
        try {
            BigDecimal b = new BigDecimal(s.trim());
            if (b.scale() > 2 && b.stripTrailingZeros().scale() > 2)
                throw new IllegalArgumentException(field + " can have at most 2 decimals.");
            if (b.compareTo(new BigDecimal("9999999999")) > 0) throw new IllegalArgumentException(field + " is too large.");
            return b.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a number.");
        }
    }

    private static Integer whole(String s, String field) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(field + " is required.");
        try {
            int n = Integer.parseInt(s.trim());
            if (n < 1 || n > 1000) throw new IllegalArgumentException(field + " must be a whole number from 1 to 1000.");
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole number from 1 to 1000.");
        }
    }

    private static LocalDate date(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not a valid date.");
        }
    }

    /** checks every chosen item belongs to this company and removes duplicates */
    private List<RetailOfferItem> cleanItems(String company, List<ItemIn> raw) {
        if (raw == null || raw.isEmpty()) throw new IllegalArgumentException("Choose at least one item for the offer.");
        if (raw.size() > ITEM_LIMIT) throw new IllegalArgumentException("Too many items (max " + ITEM_LIMIT + ").");

        Map<Long, ProductCategory> products = new HashMap<>();
        for (ProductCategory p : productRepo.findByCompanyName(company)) products.put(p.getId(), p);
        Map<String, String> categories = new HashMap<>();
        for (ProductCategory p : products.values()) categories.putIfAbsent(p.getCategoryName().toLowerCase(Locale.ROOT), p.getCategoryName());
        Map<String, String> styles = new HashMap<>();
        for (String s : variantRepo.findStyleCodes(company)) styles.put(s.toLowerCase(Locale.ROOT), s);

        List<Long> variantIds = new ArrayList<>();
        for (ItemIn i : raw) {
            if (i != null && RetailOfferItem.VARIANT.equals(upper(i.scope()))) {
                try { variantIds.add(Long.valueOf(i.ref().trim())); } catch (RuntimeException ignored) { /* checked below */ }
            }
        }
        Set<Long> okVariants = new HashSet<>();
        for (int i = 0; i < variantIds.size(); i += SAVE_BATCH) {
            List<Long> part = variantIds.subList(i, Math.min(variantIds.size(), i + SAVE_BATCH));
            for (RetailVariant v : variantRepo.findAllById(part)) {
                if (company.equals(v.getCompanyName())) okVariants.add(v.getId());
            }
        }

        Set<String> seen = new LinkedHashSet<>();
        List<RetailOfferItem> out = new ArrayList<>();
        for (ItemIn i : raw) {
            if (i == null || i.ref() == null || i.ref().isBlank()) throw new IllegalArgumentException("An item is empty.");
            String scope = upper(i.scope());
            String ref = i.ref().trim();
            switch (scope) {
                case RetailOfferItem.VARIANT -> {
                    Long id;
                    try { id = Long.valueOf(ref); } catch (NumberFormatException e) { throw new IllegalArgumentException("An item is not valid."); }
                    if (!okVariants.contains(id)) throw new IllegalArgumentException("A chosen variant was not found.");
                    ref = String.valueOf(id);
                }
                case RetailOfferItem.PRODUCT -> {
                    Long id;
                    try { id = Long.valueOf(ref); } catch (NumberFormatException e) { throw new IllegalArgumentException("An item is not valid."); }
                    if (!products.containsKey(id)) throw new IllegalArgumentException("A chosen product was not found.");
                    ref = String.valueOf(id);
                }
                case RetailOfferItem.CATEGORY -> {
                    String c = categories.get(ref.toLowerCase(Locale.ROOT));
                    if (c == null) throw new IllegalArgumentException("Category \"" + ref + "\" was not found.");
                    ref = c;
                }
                case RetailOfferItem.STYLE -> {
                    String s = styles.get(ref.toLowerCase(Locale.ROOT));
                    if (s == null) throw new IllegalArgumentException("Style code \"" + ref + "\" was not found.");
                    ref = s;
                }
                default -> throw new IllegalArgumentException("An item has an unknown type.");
            }
            if (!seen.add(scope + "|" + ref.toLowerCase(Locale.ROOT))) continue;
            RetailOfferItem it = new RetailOfferItem();
            it.setCompanyName(company);
            it.setScopeType(scope);
            it.setRefValue(ref);
            out.add(it);
        }
        return out;
    }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }

    private void audit(Long userId, String userName, String action, String text, HttpServletRequest request) {
        try {
            auditLogService.log(userId, userName, action, "Retail", "Offers & Discounts", text, request);
        } catch (RuntimeException ignored) {
            // audit failure must not block offer work
        }
    }

    /** turns stored item refs into readable text */
    private class Labels {
        private final Map<Long, ProductCategory> products = new HashMap<>();
        private final Map<Long, RetailVariant> variants = new HashMap<>();

        Labels(String company) {
            for (ProductCategory p : productRepo.findByCompanyName(company)) products.put(p.getId(), p);
        }

        String of(RetailOfferItem i) {
            String ref = i.getRefValue();
            switch (i.getScopeType()) {
                case RetailOfferItem.VARIANT -> {
                    try {
                        Long id = Long.valueOf(ref);
                        RetailVariant v = variants.computeIfAbsent(id, k -> variantRepo.findById(k).orElse(null));
                        if (v == null || !i.getCompanyName().equals(v.getCompanyName())) return "Variant (removed)";
                        ProductCategory p = products.get(v.getProductId());
                        return variantLabel(p == null ? "" : p.getCategoryName(), p == null ? "" : p.getProductName(), v);
                    } catch (NumberFormatException e) {
                        return ref;
                    }
                }
                case RetailOfferItem.PRODUCT -> {
                    try {
                        ProductCategory p = products.get(Long.valueOf(ref));
                        return p == null ? "Product (removed)" : p.getCategoryName() + " / " + p.getProductName() + " (all sizes)";
                    } catch (NumberFormatException e) {
                        return ref;
                    }
                }
                case RetailOfferItem.CATEGORY -> { return "Category: " + ref; }
                case RetailOfferItem.STYLE -> { return "Style: " + ref; }
                default -> { return ref; }
            }
        }
    }
}
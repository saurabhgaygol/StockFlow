package com.stockmanagement.service;


import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.StockInwardRepository;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class StockInwardService {

    private final StockInwardRepository stockRepository;
    private final UserPermissionService userPermissionService;
    private final ProductCategoryRepository categoryRepository;
    private final StockUnitHistoryService historyService;

    /** Jo status haath se set ho sakte hain. ISSUED, AT_VENDOR wagairah sirf apne workflow se set honge. */
    private static final Set<String> MANUAL_STATUSES = Set.of("AVAILABLE", "DAMAGED");

    public StockInwardService(
            StockInwardRepository stockRepository,
            UserPermissionService userPermissionService,
            ProductCategoryRepository categoryRepository,
            StockUnitHistoryService historyService) {
        this.stockRepository = stockRepository;
        this.userPermissionService = userPermissionService;
        this.categoryRepository = categoryRepository;
        this.historyService = historyService;
    }

    /* ============================================================
       LIST — screen ke liye
       ============================================================ */

    /**
     * view = "instock" (default: jo abhi humare paas hai), "issued" (approved Stock Outward se
     * bahar gaya) ya "all". Issued units delete nahi hote - row history ban ke rehti hai.
     */
    @Transactional(readOnly = true)
    public List<StockInward> getStockForUser(CustomUserDetails userDetails, String view) {
        Long userId = userDetails.getUserId();
        UserTable user = userDetails.getUser();

        List<StockInward> all;
        if (userPermissionService.hasPermission(userId, "SUPER_ADMIN")) {
            all = stockRepository.findAllWithVendorAndCategory();
        } else {
            all = stockRepository.findByCompanyNameWithVendorAndCategory(user.getCompanyName());
        }

        if ("issued".equalsIgnoreCase(view)) {
            return all.stream().filter(s -> "ISSUED".equals(s.getStatus())).collect(java.util.stream.Collectors.toList());
        }
        if ("all".equalsIgnoreCase(view)) {
            return all;
        }
        return all.stream().filter(s -> !"ISSUED".equals(s.getStatus()) && !"REPLACED".equals(s.getStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    /* ============================================================
       CRUD — Add / Update / Delete
       ============================================================ */

    /** Add a stock item. */
    @Transactional
    public StockInward addStock(StockInward stock, String companyName, String createdBy) {
        // IMEI unit ki pehchan hai - zaroori aur company mein unique
        String imei = stock.getImeiNumber() == null ? "" : stock.getImeiNumber().trim();
        if (imei.isEmpty()) {
            throw new IllegalArgumentException("IMEI number is required.");
        }
        if (stockRepository.existsByImeiNumberAndCompanyName(imei, companyName)) {
            throw new IllegalArgumentException("A unit with IMEI \"" + imei + "\" already exists.");
        }
        stock.setImeiNumber(imei);

        // Null-safe serial check (serial optional hai)
        if (stock.getSerialNumber() != null && !stock.getSerialNumber().isBlank()) {
            stock.setSerialNumber(stock.getSerialNumber().trim());
            if (stockRepository.existsBySerialNumberAndCompanyName(
                    stock.getSerialNumber(), companyName)) {
                throw new IllegalArgumentException(
                        "A unit with serial number \"" + stock.getSerialNumber() + "\" already exists.");
            }
        } else {
            stock.setSerialNumber(null);
        }

        if (stock.getStatus() == null || stock.getStatus().isBlank()) {
            stock.setStatus("AVAILABLE");
        }
        if (!MANUAL_STATUSES.contains(stock.getStatus())) {
            throw new IllegalArgumentException("A new unit can only be AVAILABLE or DAMAGED.");
        }
        if ("DAMAGED".equals(stock.getStatus()) && isBlank(stock.getRemarks())) {
            throw new IllegalArgumentException("Write the damage details in Remarks.");
        }

        stock.setCompanyName(companyName);
        stock.setCreatedBy(createdBy);
        stock.setQuantity(1);   // ek row = ek asli unit (IMEI se track); outward allocation isi pe chalta hai
        applyCalculations(stock);
        StockInward saved = stockRepository.save(stock);

        historyService.recordInward(saved, createdBy);
        if ("DAMAGED".equals(saved.getStatus())) {
            historyService.record(saved, StockUnitHistoryService.STATUS_CHANGED, "AVAILABLE", "DAMAGED",
                    null, null, null, null, saved.getRemarks(), null, createdBy);
        }
        return saved;
    }

    public StockInward getStockById(Long id) {
        return stockRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Stock item not found: " + id));
    }

    /** Update an existing stock item. */
    @Transactional
    public void updateStock(Long id, StockInward incoming, String actorName) {
        StockInward stock = getStockById(id);
        if ("ISSUED".equals(stock.getStatus()) || "AT_VENDOR".equals(stock.getStatus())
                || "REPLACED".equals(stock.getStatus())) {
            throw new IllegalArgumentException("This unit is issued, with a vendor or replaced - it can no longer be edited here.");
        }

        String imei = incoming.getImeiNumber() == null ? "" : incoming.getImeiNumber().trim();
        if (imei.isEmpty()) {
            throw new IllegalArgumentException("IMEI number is required.");
        }
        if (stockRepository.existsByImeiNumberAndCompanyNameAndIdNot(imei, stock.getCompanyName(), id)) {
            throw new IllegalArgumentException("Another unit with IMEI \"" + imei + "\" already exists.");
        }
        incoming.setImeiNumber(imei);

        String oldStatus = stock.getStatus();
        String newStatus = incoming.getStatus() == null || incoming.getStatus().isBlank() ? oldStatus : incoming.getStatus();
        boolean statusChanged = !newStatus.equals(oldStatus);
        if (statusChanged && !MANUAL_STATUSES.contains(newStatus)) {
            throw new IllegalArgumentException("Status can only be changed to AVAILABLE or DAMAGED here.");
        }
        if (statusChanged && "DAMAGED".equals(newStatus) && isBlank(incoming.getRemarks())) {
            throw new IllegalArgumentException("Write the damage details in Remarks.");
        }

        stock.setVendorId(incoming.getVendorId());
        stock.setCategoryId(incoming.getCategoryId());
        stock.setProductId(incoming.getProductId());
        stock.setImeiNumber(incoming.getImeiNumber());
        stock.setCondition(incoming.getCondition());
        stock.setQuantity(1);
        stock.setUnitPrice(incoming.getUnitPrice());
        stock.setTaxPercent(incoming.getTaxPercent());
        stock.setPurchaseDate(incoming.getPurchaseDate());
        stock.setWarrantyPeriodMonths(incoming.getWarrantyPeriodMonths());
        stock.setWarrantyStartDate(incoming.getWarrantyStartDate());
        stock.setInvoiceNumber(incoming.getInvoiceNumber());
        stock.setPoNumber(incoming.getPoNumber());
        stock.setBatchNumber(incoming.getBatchNumber());
        stock.setWarehouse(incoming.getWarehouse());
        stock.setStatus(newStatus);
        stock.setRemarks(incoming.getRemarks());

        applyCalculations(stock);
        stockRepository.save(stock);

        if (statusChanged) {
            historyService.record(stock, StockUnitHistoryService.STATUS_CHANGED, oldStatus, newStatus,
                    null, null, null, null, incoming.getRemarks(), null, actorName);
        }
    }

    @Transactional
    public void deleteStock(Long id) {
        StockInward stock = getStockById(id);
        if ("ISSUED".equals(stock.getStatus()) || "RESERVED".equals(stock.getStatus())
                || "AT_VENDOR".equals(stock.getStatus()) || "REPLACED".equals(stock.getStatus())) {
            throw new IllegalArgumentException("Issued / reserved units cannot be deleted - they are linked to a Stock Outward request.");
        }
        stockRepository.deleteById(id);
    }

    /* ============================================================
       SUMMARY COUNTS — dashboard cards ke liye
       ============================================================ */

    public long countTotal(String companyName)     { return stockRepository.countByCompanyName(companyName); }
    public long countAvailable(String companyName) { return stockRepository.countByCompanyNameAndStatus(companyName, "AVAILABLE"); }
    public long countReserved(String companyName)  { return stockRepository.countByCompanyNameAndStatus(companyName, "RESERVED"); }
    public long countIssued(String companyName)    { return stockRepository.countByCompanyNameAndStatus(companyName, "ISSUED"); }

    /* ============================================================
       INTERNAL — total, tax, grand total, warranty end date
       ============================================================ */

    private void applyCalculations(StockInward stock) {
        int qty = stock.getQuantity() != null ? stock.getQuantity() : 1;

        if (stock.getUnitPrice() != null) {
            BigDecimal total = stock.getUnitPrice().multiply(BigDecimal.valueOf(qty));
            stock.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));

            BigDecimal grand = total;
            if (stock.getTaxPercent() != null) {
                BigDecimal tax = total.multiply(stock.getTaxPercent())
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                grand = total.add(tax);
            }
            stock.setGrandTotal(grand.setScale(2, RoundingMode.HALF_UP));
        }

        if (stock.getWarrantyStartDate() != null && stock.getWarrantyPeriodMonths() != null) {
            stock.setWarrantyEndDate(stock.getWarrantyStartDate().plusMonths(stock.getWarrantyPeriodMonths()));
        }
    }

    /* ============================================================
       BULK UPLOAD — Excel (.xlsx/.xls) aur CSV dono support
       Template columns: Product, IMEI*, Category, Condition, Unit Price, Tax %
       ============================================================ */

    @Transactional
    public int bulkUpload(MultipartFile file,
                          Long vendorId,
                          String invoiceNumber,
                          String warehouse,
                          LocalDate purchaseDate,
                          Integer warrantyMonths,
                          String condition,
                          String companyName,
                          String createdBy) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }

        String originalName = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase();

        List<Map<String, String>> rows;

        if (originalName.endsWith(".csv")) {
            rows = parseCsv(file.getInputStream());
        } else if (originalName.endsWith(".xlsx") || originalName.endsWith(".xls")) {
            rows = parseExcel(file.getInputStream());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported file type. Use .xlsx, .xls or .csv");
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("No rows found in the file.");
        }

        // Category name -> id map (company specific)
        Map<String, Long> categoryMap = new HashMap<>();
        // Product name -> id map (company specific)
        Map<String, Long> productMap = new HashMap<>();

        for (ProductCategory c : categoryRepository.findByCompanyName(companyName)) {
            if (c.getCategoryName() != null) {
                categoryMap.put(c.getCategoryName().toLowerCase().trim(), c.getId());
            }
            if (c.getProductName() != null) {
                productMap.put(c.getProductName().toLowerCase().trim(), c.getId());
            }
        }

        List<StockInward> items = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> seenImeis = new HashSet<>();
        int rowNum = 1;

        for (Map<String, String> row : rows) {
            rowNum++;

            String productName  = get(row, "product", "product name");
            String imei         = get(row, "imei", "imei number");
            String categoryName = get(row, "category", "category name");

            if (isBlank(productName) || isBlank(imei)) {
                errors.add("Row " + rowNum + ": product/IMEI required");
                continue;
            }
            if (productMap.get(productName.toLowerCase().trim()) == null) {
                errors.add("Row " + rowNum + ": product \"" + productName.trim() + "\" not found");
                continue;
            }
            if (!seenImeis.add(imei.trim()) || stockRepository.existsByImeiNumberAndCompanyName(imei.trim(), companyName)) {
                errors.add("Row " + rowNum + ": IMEI " + imei.trim() + " already exists");
                continue;
            }

            StockInward s = new StockInward();
            s.setVendorId(vendorId);
            s.setInvoiceNumber(invoiceNumber);
            s.setWarehouse(warehouse);
            s.setPurchaseDate(purchaseDate);
            s.setWarrantyPeriodMonths(warrantyMonths);
            s.setCondition(condition != null && !condition.isBlank() ? condition : "NEW");

            s.setImeiNumber(imei.trim());

            // Product lookup — product name -> productId
            Long productId = productMap.get(productName.toLowerCase().trim());
            s.setProductId(productId);

            // Per-row condition override
            String rowCondition = get(row, "condition");
            if (!isBlank(rowCondition)) {
                s.setCondition(rowCondition.trim().toUpperCase());
            }

            // Category lookup
            if (!isBlank(categoryName)) {
                Long catId = categoryMap.get(categoryName.toLowerCase().trim());
                s.setCategoryId(catId);
            }

            // Quantity hamesha 1
            s.setQuantity(1);

            // Unit price
            String unitPriceStr = get(row, "unit price", "price");
            if (!isBlank(unitPriceStr)) {
                try {
                    s.setUnitPrice(new BigDecimal(unitPriceStr.trim()));
                } catch (NumberFormatException ignored) { }
            }

            // Tax %
            String taxStr = get(row, "tax", "tax %", "taxpercent");
            if (!isBlank(taxStr)) {
                try {
                    s.setTaxPercent(new BigDecimal(taxStr.trim()));
                } catch (NumberFormatException ignored) { }
            }

            // Bulk rows hamesha AVAILABLE aate hain; damaged unit stock screen se reason ke saath mark hota hai
            s.setStatus("AVAILABLE");

            s.setCompanyName(companyName);
            s.setCreatedBy(createdBy);

            applyCalculations(s);
            items.add(s);
        }

        if (items.isEmpty()) {
            String msg = errors.isEmpty()
                    ? "No valid rows found."
                    : "No valid rows. Errors: " + String.join("; ", errors);
            throw new IllegalArgumentException(msg);
        }

        List<StockInward> saved = stockRepository.saveAll(items);
        for (StockInward u : saved) {
            historyService.recordInward(u, createdBy);
        }

        if (!errors.isEmpty()) {
            System.out.println("Bulk upload partial: " + errors.size() + " rows skipped");
            errors.forEach(System.out::println);
        }

        return items.size();
    }

    /* ============================================================
       PARSERS — Excel aur CSV
       ============================================================ */

    // ---- Excel (.xlsx) parser using Apache POI ----
    private List<Map<String, String>> parseExcel(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return rows;

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) return rows;

            Map<Integer, String> headers = new HashMap<>();
            for (Cell cell : headerRow) {
                headers.put(cell.getColumnIndex(), getCellString(cell).toLowerCase().trim());
            }

            int lastRow = sheet.getLastRowNum();
            for (int r = 1; r <= lastRow; r++) {
                Row dataRow = sheet.getRow(r);
                if (dataRow == null) continue;

                Map<String, String> rowMap = new HashMap<>();
                boolean hasAnyValue = false;

                for (Map.Entry<Integer, String> h : headers.entrySet()) {
                    Cell cell = dataRow.getCell(h.getKey());
                    String val = getCellString(cell);
                    if (!val.isBlank()) hasAnyValue = true;
                    rowMap.put(h.getValue(), val);
                }

                if (hasAnyValue) rows.add(rowMap);
            }
        }
        return rows;
    }

    private String getCellString(Cell cell) {
        if (cell == null) return "";
        CellType type = cell.getCellType();

        if (type == CellType.STRING) return cell.getStringCellValue().trim();
        if (type == CellType.NUMERIC) {
            double d = cell.getNumericCellValue();
            if (d == Math.floor(d)) {
                return String.valueOf((long) d);
            }
            return String.valueOf(d);
        }
        if (type == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        if (type == CellType.FORMULA) {
            try {
                return cell.getStringCellValue().trim();
            } catch (Exception e) {
                return String.valueOf(cell.getNumericCellValue());
            }
        }
        return "";
    }

    // ---- CSV parser ----
    private List<Map<String, String>> parseCsv(InputStream in) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String headerLine = br.readLine();
            if (headerLine == null) return rows;

            String[] headers = headerLine.split(",");
            for (int i = 0; i < headers.length; i++) {
                headers[i] = headers[i].trim().toLowerCase();
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;

                String[] cols = line.split(",", -1);
                Map<String, String> rowMap = new HashMap<>();
                boolean hasAnyValue = false;

                for (int i = 0; i < headers.length; i++) {
                    String val = i < cols.length ? cols[i].trim() : "";
                    if (!val.isEmpty()) hasAnyValue = true;
                    rowMap.put(headers[i], val);
                }

                if (hasAnyValue) rows.add(rowMap);
            }
        }
        return rows;
    }

    /* ============================================================
       HELPERS
       ============================================================ */

    private String get(Map<String, String> row, String... keys) {
        for (String k : keys) {
            String v = row.get(k.toLowerCase());
            if (v != null) return v;
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
package com.stockmanagement.controller;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.ProductCategoryService;
import com.stockmanagement.service.StaffStockService;
import com.stockmanagement.service.StockInwardService;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.VendorService;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class StockInwardController {

    private final StockInwardService stockService;
    private final VendorService vendorService;
    private final ProductCategoryService categoryService;
    private final UserPermissionService userPermissionService;
    private final StaffStockService staffStockService;

    public StockInwardController(
            StockInwardService stockService,
            VendorService vendorService,
            ProductCategoryService categoryService,
            UserPermissionService userPermissionService,
            StaffStockService staffStockService) {
        this.stockService = stockService;
        this.vendorService = vendorService;
        this.categoryService = categoryService;
        this.userPermissionService = userPermissionService;
        this.staffStockService = staffStockService;
    }

    /* ============================================================
       PAGE SHELL
       ============================================================ */
    @GetMapping("/settings/stock-inward")
    public String listStock(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Long userId = userDetails.getUserId();
        String companyName = userDetails.getUser().getCompanyName();

        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> permissionCodes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream()
                .map(p -> p.getPermissionCode())
                .collect(Collectors.toList());
        model.addAttribute("permissions", permissionCodes);

        // Dropdown data
        model.addAttribute("vendors", vendorService.getVendorsForUser(userDetails));
        model.addAttribute("categories", categoryService.getActiveCategoriesForCompany(companyName));
        model.addAttribute("products", categoryService.getActiveProductForCompany(companyName));
        model.addAttribute("newStock", new StockInward());

        return "settings/stock-inward-list";
    }

    /* ============================================================
       DEPENDENT DROPDOWN (UI) — Category select hone pe products laao
       ============================================================ */
    @GetMapping("/settings/stock-inward/products-by-category")
    public String getProductsByCategory(
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        if (categoryId == null) {
            model.addAttribute("products", java.util.List.of());
            return "settings/stock-inward-list :: productOptions";
        }

        String companyName = userDetails.getUser().getCompanyName();

        List<ProductCategory> products =
                categoryService.getProductsByCategory(categoryId, companyName);

        model.addAttribute("products", products);

        return "settings/stock-inward-list :: productOptions";
    }

    /* ============================================================
       DATA — heavy DB query
       view = instock (default) / issued / all
       ============================================================ */
    @GetMapping("/settings/stock-inward/data")
    public String stockData(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(value = "view", defaultValue = "instock") String view,
            Model model) {

        String companyName = userDetails.getUser().getCompanyName();

        List<StockInward> stockList = stockService.getStockForUser(userDetails, view);
        staffStockService.fillHolderNames(stockList);   // "with Pavan" for units held by field staff
        model.addAttribute("stockList", stockList);
        model.addAttribute("totalCount", stockService.countTotal(companyName));
        model.addAttribute("availableCount", stockService.countAvailable(companyName));
        model.addAttribute("reservedCount", stockService.countReserved(companyName));
        model.addAttribute("issuedCount", stockService.countIssued(companyName));

        return "settings/stock-inward-list :: stockData";
    }

    /* ============================================================
       ADD STOCK
       ============================================================ */
    @PostMapping("/settings/stock-inward/add")
    public String addStock(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @ModelAttribute("newStock") StockInward stock,
            RedirectAttributes redirectAttributes) {

        String companyName = userDetails.getUser().getCompanyName();
        String createdBy = userDetails.getUsername();

        try {
            stockService.addStock(stock, companyName, createdBy);
            redirectAttributes.addFlashAttribute("successMessage", "Stock item added.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/settings/stock-inward";
    }

    /* ============================================================
       EDIT STOCK
       ============================================================ */
    @PostMapping("/settings/stock-inward/edit/{id}")
    public String editStock(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @ModelAttribute("newStock") StockInward stock,
            RedirectAttributes redirectAttributes) {

        try {
            stockService.updateStock(id, stock, userDetails.getUsername());
            redirectAttributes.addFlashAttribute("successMessage", "Stock item updated.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/settings/stock-inward";
    }

    /* ============================================================
       DELETE STOCK
       ============================================================ */
    @PostMapping("/settings/stock-inward/delete/{id}")
    public String deleteStock(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            stockService.deleteStock(id);
            redirectAttributes.addFlashAttribute("successMessage", "Stock item deleted.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/settings/stock-inward";
    }

    /* ============================================================
       BULK UPLOAD
       ============================================================ */
    @PostMapping("/settings/stock-inward/bulk-upload")
    public String bulkUpload(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam("vendorId") Long vendorId,
            @RequestParam(value = "invoiceNumber", required = false) String invoiceNumber,
            @RequestParam(value = "warehouse", required = false) String warehouse,
            @RequestParam(value = "purchaseDate", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate purchaseDate,
            @RequestParam(value = "warrantyPeriodMonths", required = false) Integer warrantyMonths,
            @RequestParam(value = "condition", required = false) String condition,
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes) {

        String companyName = userDetails.getUser().getCompanyName();
        String createdBy = userDetails.getUsername();

        if (file == null || file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please upload a file.");
            return "redirect:/settings/stock-inward";
        }

        try {
            int count = stockService.bulkUpload(
                    file,
                    vendorId,
                    invoiceNumber,
                    warehouse,
                    purchaseDate,
                    warrantyMonths,
                    condition,
                    companyName,
                    createdBy);

            redirectAttributes.addFlashAttribute("successMessage",
                    count + " stock items imported successfully.");

        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Bulk upload failed: " + ex.getMessage());
        }

        return "redirect:/settings/stock-inward";
    }

    /* ============================================================
       DOWNLOAD TEMPLATE
       Columns: Product, IMEI*, Category, Condition, Unit Price, Tax %
       Product dropdown Category pe DEPENDENT hai.
       ============================================================ */
    @GetMapping("/settings/stock-inward/template")
    public void downloadTemplate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response) throws IOException {

        String companyName = userDetails.getUser().getCompanyName();

        // ============================================================
        // 1) Category -> Products (Product names) map banao
        // ============================================================
        List<ProductCategory> categories = categoryService
                .getActiveCategoriesForCompany(companyName);

        Map<String, List<String>> categoryProductsMap = new LinkedHashMap<>();
        Map<String, String> categoryToSafeName = new LinkedHashMap<>();

        int suffix = 0;
        for (ProductCategory cat : categories) {
            String catName = cat.getCategoryName();

            List<ProductCategory> products =
                    categoryService.getProductsByCategory(cat.getId(), companyName);

            // ⚠️ Yahan apna actual getter use karo
            // ProductCategory me product name ka getter (getProductName / getName / etc.)
            List<String> productNames = products.stream()
                    .map(ProductCategory::getProductName)
                    .filter(n -> n != null && !n.isBlank())
                    .distinct()
                    .collect(Collectors.toList());

            categoryProductsMap.put(catName, productNames);

            // Named range ke liye safe unique name
            String safe = catName.replaceAll("[^A-Za-z0-9]", "_");
            if (safe.isEmpty() || !Character.isLetter(safe.charAt(0))) {
                safe = "C_" + safe;
            }
            String base = safe;
            while (categoryToSafeName.containsValue(safe)) {
                safe = base + "_" + (++suffix);
            }
            categoryToSafeName.put(catName, safe);
        }

        List<String> categoryNames = new ArrayList<>(categoryProductsMap.keySet());

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=stock-inward-template.xlsx");

        try (Workbook wb = new XSSFWorkbook()) {

            Sheet sheet = wb.createSheet("Stock Inward");
            Sheet listSheet = wb.createSheet("Lists");

            // ============ Header Row ============
            Row header = sheet.createRow(0);
            String[] cols = {
                    "Product", "IMEI*",
                    "Category", "Condition", "Unit Price", "Tax %"
            };

            CellStyle headerStyle = wb.createCellStyle();
            Font boldFont = wb.createFont();
            boldFont.setBold(true);
            headerStyle.setFont(boldFont);

            for (int i = 0; i < cols.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(cols[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 22 * 256);
            }

            // ============================================================
            // 2) Lists sheet bharo
            //    Column A       : Category names (dropdown ke liye)
            //    Column B onwards: har category ke products (alag column)
            //    Last-1 column  : Condition values
            //    Last column    : sanitized category names (helper)
            // ============================================================

            // --- Column A: category names ---
            for (int i = 0; i < categoryNames.size(); i++) {
                Row r = listSheet.getRow(i);
                if (r == null) r = listSheet.createRow(i);
                r.createCell(0).setCellValue(categoryNames.get(i));
            }

            // --- Product columns (B onwards) ---
            int productColStart = 1; // B
            int colIdx = productColStart;
            Map<String, Integer> categoryColumnMap = new LinkedHashMap<>();

            for (Map.Entry<String, List<String>> entry : categoryProductsMap.entrySet()) {
                List<String> products = entry.getValue();
                for (int i = 0; i < products.size(); i++) {
                    Row r = listSheet.getRow(i);
                    if (r == null) r = listSheet.createRow(i);
                    r.createCell(colIdx).setCellValue(products.get(i));
                }
                categoryColumnMap.put(entry.getKey(), colIdx);
                colIdx++;
            }

            // --- Condition column ---
            int condCol = colIdx;
            String[] conditions = {"NEW", "REFURBISHED"};
            for (int i = 0; i < conditions.length; i++) {
                Row r = listSheet.getRow(i);
                if (r == null) r = listSheet.createRow(i);
                r.createCell(condCol).setCellValue(conditions[i]);
            }

            // --- Helper column: sanitized category names ---
            int safeCol = condCol + 1;
            for (int i = 0; i < categoryNames.size(); i++) {
                Row r = listSheet.getRow(i);
                if (r == null) r = listSheet.createRow(i);
                r.createCell(safeCol).setCellValue(
                        categoryToSafeName.get(categoryNames.get(i)));
            }

            // ============================================================
            // 3) Named ranges — har category ke products ke liye
            // ============================================================
            for (Map.Entry<String, List<String>> entry : categoryProductsMap.entrySet()) {
                String catName = entry.getKey();
                List<String> products = entry.getValue();
                if (products.isEmpty()) continue;

                int col = categoryColumnMap.get(catName);
                int size = products.size();

                String colLetter = CellReference.convertNumToColString(col);
                String rangeRef = "Lists!$" + colLetter + "$1:$" + colLetter + "$" + size;

                String safeName = categoryToSafeName.get(catName);

                Name name = wb.createName();
                name.setNameName(safeName);
                name.setRefersToFormula(rangeRef);
            }

            // Hide Lists sheet
            wb.setSheetHidden(wb.getSheetIndex(listSheet), true);

            // ============================================================
            // 4) Data validations
            // ============================================================
            DataValidationHelper helper = sheet.getDataValidationHelper();

            // --- Category dropdown (column C — index 2) ---
            if (!categoryNames.isEmpty()) {
                DataValidationConstraint catConstraint =
                        helper.createFormulaListConstraint(
                                "Lists!$A$1:$A$" + categoryNames.size());

                CellRangeAddressList catRange =
                        new CellRangeAddressList(1, 500, 2, 2); // C2:C501

                DataValidation catValidation =
                        helper.createValidation(catConstraint, catRange);
                catValidation.setShowErrorBox(true);
                catValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
                catValidation.createErrorBox("Invalid Category",
                        "Please select a category from the dropdown.");
                catValidation.setShowPromptBox(true);
                catValidation.createPromptBox("Category",
                        "Select a category from the dropdown.");
                sheet.addValidationData(catValidation);
            }

            // --- Product dropdown (column A — index 0) — DEPENDENT on Category ---
            // Category (C) me jo selected hai uske sanitized naam se
            // INDIRECT karke named range pick karega.
            //
            // Formula: INDIRECT(VLOOKUP($C2, Lists!$A$1:$X$N, safeColIndex, FALSE))
            if (!categoryNames.isEmpty()) {
                String safeColLetter = CellReference.convertNumToColString(safeCol);

                int safeColIndex = safeCol + 1; // 1-based
                String lookupRange =
                        "Lists!$A$1:$" + safeColLetter + "$" + categoryNames.size();

                String formula =
                        "INDIRECT(VLOOKUP($C2," + lookupRange + ","
                                + safeColIndex + ",FALSE))";

                DataValidationConstraint productConstraint =
                        helper.createFormulaListConstraint(formula);

                CellRangeAddressList productRange =
                        new CellRangeAddressList(1, 500, 0, 0); // A2:A501

                DataValidation productValidation =
                        helper.createValidation(productConstraint, productRange);
                productValidation.setShowErrorBox(true);
                productValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
                productValidation.createErrorBox("Invalid Product",
                        "Pehle Category select karo (column C), phir Product dropdown se choose karo.");
                productValidation.setShowPromptBox(true);
                productValidation.createPromptBox("Product",
                        "Pehle Category select karo (column C), phir yahan Product select karo.");
                sheet.addValidationData(productValidation);
            }

            // --- Condition dropdown (column D — index 3) ---
            String condColLetter = CellReference.convertNumToColString(condCol);
            DataValidationConstraint condConstraint =
                    helper.createFormulaListConstraint(
                            "Lists!$" + condColLetter + "$1:$" + condColLetter + "$2");

            CellRangeAddressList condRange =
                    new CellRangeAddressList(1, 500, 3, 3); // D2:D501

            DataValidation condValidation =
                    helper.createValidation(condConstraint, condRange);
            condValidation.setShowErrorBox(true);
            condValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            condValidation.createErrorBox("Invalid Condition",
                    "Choose NEW or REFURBISHED.");
            sheet.addValidationData(condValidation);

            wb.write(response.getOutputStream());
        }
    }
}
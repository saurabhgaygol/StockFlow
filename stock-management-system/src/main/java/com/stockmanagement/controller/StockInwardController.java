package com.stockmanagement.controller;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.ProductCategoryService;
import com.stockmanagement.service.StockInwardService;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.VendorService;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
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
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class StockInwardController {

    private final StockInwardService stockService;
    private final VendorService vendorService;
    private final ProductCategoryService categoryService;
    private final UserPermissionService userPermissionService;

    public StockInwardController(
            StockInwardService stockService,
            VendorService vendorService,
            ProductCategoryService categoryService,
            UserPermissionService userPermissionService) {
        this.stockService = stockService;
        this.vendorService = vendorService;
        this.categoryService = categoryService;
        this.userPermissionService = userPermissionService;
    }

    /* ============================================================
       PAGE SHELL — halka data (dropdowns). Stock list yahan nahi.
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

        model.addAttribute("vendors", vendorService.getVendorsForUser(userDetails));
        model.addAttribute("categories", categoryService.getActiveCategoriesForCompany(companyName));
        model.addAttribute("newStock", new StockInward());

        return "settings/stock-inward-list";
    }

    /* ============================================================
       DATA — heavy DB query (htmx yahan se cards + table load karta hai)
       ============================================================ */
    @GetMapping("/settings/stock-inward/data")
    public String stockData(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        String companyName = userDetails.getUser().getCompanyName();

        // TEST ke liye (loader check): neeche wali line uncomment karo, baad me hata do
        // try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        model.addAttribute("stockList", stockService.getStockForUser(userDetails));
        model.addAttribute("totalCount", stockService.countTotal(companyName));
        model.addAttribute("availableCount", stockService.countAvailable(companyName));
        model.addAttribute("reservedCount", stockService.countReserved(companyName));
        model.addAttribute("issuedCount", stockService.countIssued(companyName));

        // Fragment ab stock-inward-list.html ke andar hi hai
        return "settings/stock-inward-list :: stockData";
    }

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

    @PostMapping("/settings/stock-inward/edit/{id}")
    public String editStock(
            @PathVariable Long id,
            @ModelAttribute("newStock") StockInward stock,
            RedirectAttributes redirectAttributes) {

        try {
            stockService.updateStock(id, stock);
            redirectAttributes.addFlashAttribute("successMessage", "Stock item updated.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/settings/stock-inward";
    }

    @PostMapping("/settings/stock-inward/delete/{id}")
    public String deleteStock(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        stockService.deleteStock(id);
        redirectAttributes.addFlashAttribute("successMessage", "Stock item deleted.");

        return "redirect:/settings/stock-inward";
    }

    /* ============================================================
       BULK UPLOAD — Excel/CSV import
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
        String createdBy   = userDetails.getUsername();

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
       DOWNLOAD TEMPLATE — Excel with dropdowns
       ============================================================ */
    @GetMapping("/settings/stock-inward/template")
    public void downloadTemplate(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            HttpServletResponse response) throws IOException {

        String companyName = userDetails.getUser().getCompanyName();

        List<String> categoryNames = categoryService
                .getActiveCategoriesForCompany(companyName)
                .stream()
                .map(ProductCategory::getCategoryName)
                .toList();

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=stock-inward-template.xlsx");

        try (Workbook wb = new XSSFWorkbook()) {

            // ============ Sheet 1: Stock Inward ============
            Sheet sheet = wb.createSheet("Stock Inward");

            Row header = sheet.createRow(0);
            String[] cols = {
                    "Model*", "Serial Number*", "IMEI*",
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

            Row sample = sheet.createRow(1);
            sample.createCell(0).setCellValue("70mai A800S");
            sample.createCell(1).setCellValue("SN001");
            sample.createCell(2).setCellValue("123456789");
            sample.createCell(3).setCellValue(
                    categoryNames.isEmpty() ? "Dashcam" : categoryNames.get(0));
            sample.createCell(4).setCellValue("NEW");
            sample.createCell(5).setCellValue(5000);
            sample.createCell(6).setCellValue(18);

            // ============ Sheet 2: Lists (hidden) ============
            Sheet listSheet = wb.createSheet("Lists");

            for (int i = 0; i < categoryNames.size(); i++) {
                Row r = listSheet.getRow(i);
                if (r == null) r = listSheet.createRow(i);
                r.createCell(0).setCellValue(categoryNames.get(i));
            }

            String[] conditions = {"NEW", "REFURBISHED"};
            for (int i = 0; i < conditions.length; i++) {
                Row r = listSheet.getRow(i);
                if (r == null) r = listSheet.createRow(i);
                r.createCell(1).setCellValue(conditions[i]);
            }

            wb.setSheetHidden(wb.getSheetIndex(listSheet), true);

            // ============ Category dropdown (column D) ============
            if (!categoryNames.isEmpty()) {
                DataValidationHelper helper = sheet.getDataValidationHelper();
                DataValidationConstraint catConstraint =
                        helper.createFormulaListConstraint(
                                "Lists!$A$1:$A$" + categoryNames.size());

                CellRangeAddressList catRange =
                        new CellRangeAddressList(1, 500, 3, 3);

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

            // ============ Condition dropdown (column E) ============
            DataValidationHelper helper2 = sheet.getDataValidationHelper();
            DataValidationConstraint condConstraint =
                    helper2.createFormulaListConstraint("Lists!$B$1:$B$2");

            CellRangeAddressList condRange =
                    new CellRangeAddressList(1, 500, 4, 4);

            DataValidation condValidation =
                    helper2.createValidation(condConstraint, condRange);
            condValidation.setShowErrorBox(true);
            condValidation.setErrorStyle(DataValidation.ErrorStyle.STOP);
            condValidation.createErrorBox("Invalid Condition",
                    "Choose NEW or REFURBISHED.");
            sheet.addValidationData(condValidation);

            wb.write(response.getOutputStream());
        }
    }
}
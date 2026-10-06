package com.stockmanagement.service;

import com.stockmanagement.service.DashboardService.DashboardView;
import com.stockmanagement.service.DashboardService.Kpi;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Dashboard report ko Excel (.xlsx) me export karta hai.
 * Sheets: Summary, Product movement, Category stock, Vendor inward, Stock aging,
 * aur (outward permission ho to) Top customers + Approval levels.
 */
@Service
public class DashboardExportService {

    public byte[] toXlsx(DashboardView v) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle head = headerStyle(wb);
            CellStyle title = titleStyle(wb);

            /* ---------- Summary ---------- */
            Sheet s = wb.createSheet("Summary");
            int r = 0;
            put(s, r++, title, "StockFlow report");
            put(s, r++, null, "Company", v.companyLabel());
            put(s, r++, null, "Period", v.rangeLabel() + " (" + v.fromIso() + " to " + v.toIso() + ")");
            put(s, r++, null, "Compared with", v.prevLabel());
            put(s, r++, null, "Generated", v.generatedAt());
            r++;
            put(s, r++, head, "Right now", "Value", "Note");
            for (Kpi k : v.snapshot()) put(s, r++, null, k.label(), k.value(), k.sub());
            r++;
            put(s, r++, head, "This period", "Value", "Change vs previous period");
            for (Kpi k : v.performance()) put(s, r++, null, k.label(), k.value(), k.delta());
            widths(s, 34, 26, 40);

            /* ---------- Product movement ---------- */
            Sheet p = wb.createSheet("Product movement");
            int pr = 0;
            put(p, pr++, head, "Product", "Category", "Inwarded (period)", "Issued (period)", "Available now", "Damaged");
            for (DashboardService.ProductRow x : v.products()) {
                put(p, pr++, null, x.name(), x.category(), x.inward(), x.issued(), x.available(), x.damaged());
            }
            widths(p, 32, 24, 18, 18, 16, 12);

            /* ---------- Category stock ---------- */
            Sheet c = wb.createSheet("Category stock");
            int cr = 0;
            put(c, cr++, head, "Category", "Total units", "Available", "Issued", "Damaged", "Available value");
            for (DashboardService.CategoryRow x : v.categories()) {
                put(c, cr++, null, x.name(), x.total(), x.available(), x.issued(), x.damaged(), x.value());
            }
            widths(c, 30, 14, 14, 12, 12, 20);

            /* ---------- Vendor inward ---------- */
            Sheet vs = wb.createSheet("Vendor inward");
            int vr = 0;
            put(vs, vr++, head, "Vendor", "Units inwarded", "Value", "Share of period inward (%)");
            for (DashboardService.VendorRow x : v.vendors()) {
                put(vs, vr++, null, x.name(), x.units(), x.value(), x.pct());
            }
            widths(vs, 34, 16, 20, 26);

            /* ---------- Stock aging ---------- */
            Sheet ag = wb.createSheet("Stock aging");
            int ar = 0;
            put(ag, ar++, head, "Age of available stock", "Units", "Value", "Share (%)");
            for (DashboardService.AgingRow x : v.aging()) {
                put(ag, ar++, null, x.label(), x.count(), x.value(), x.pct());
            }
            widths(ag, 26, 12, 20, 12);

            if (v.showOutward()) {
                /* ---------- Top customers ---------- */
                Sheet cu = wb.createSheet("Top customers");
                int ur = 0;
                put(cu, ur++, head, "Customer", "Requests issued", "Units issued", "Amount");
                for (DashboardService.CustomerRow x : v.customers()) {
                    put(cu, ur++, null, x.name(), x.requests(), x.units(), x.amount());
                }
                widths(cu, 36, 16, 14, 20);

                /* ---------- Approval levels ---------- */
                Sheet lv = wb.createSheet("Approval levels");
                int lr = 0;
                put(lv, lr++, head, "Approval level", "Average time", "Approvals in period");
                for (DashboardService.LevelRow x : v.levels()) {
                    put(lv, lr++, null, x.level(), x.avg(), x.count());
                }
                widths(lv, 34, 16, 20);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    /* ---------------- helpers ---------------- */

    /** Number -> numeric cell, baaki sab text. style sirf pehle cell pe nahi, poori row pe lagta hai. */
    private void put(Sheet sheet, int rowIdx, CellStyle style, Object... values) {
        Row row = sheet.createRow(rowIdx);
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            Object val = values[i];
            if (val instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else {
                cell.setCellValue(val == null ? "" : val.toString());
            }
            if (style != null) cell.setCellStyle(style);
        }
    }

    private void widths(Sheet sheet, int... chars) {
        for (int i = 0; i < chars.length; i++) sheet.setColumnWidth(i, chars[i] * 256);
    }

    private CellStyle headerStyle(Workbook wb) {
        Font f = wb.createFont();
        f.setBold(true);
        CellStyle st = wb.createCellStyle();
        st.setFont(f);
        return st;
    }

    private CellStyle titleStyle(Workbook wb) {
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        CellStyle st = wb.createCellStyle();
        st.setFont(f);
        return st;
    }
}
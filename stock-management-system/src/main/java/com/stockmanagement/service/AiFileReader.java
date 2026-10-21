package com.stockmanagement.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Chat me dali file ka text nikalta hai (PDF, Excel, Word, txt/csv). Sirf padhta hai, kuch chalata nahi.
 * Scanned PDF / photo ka text yahan nahi nikalta (photo vision model ko seedha jaati hai).
 */
@Component
public class AiFileReader {

    private static final Set<String> TEXT_EXT = Set.of("txt", "csv", "md", "json", "log", "xml", "html", "tsv");
    private static final int MAX_PDF_PAGES = 60;
    private static final int MAX_SHEETS = 5;
    private static final int MAX_ROWS = 1000;
    private static final int MAX_CHARS = 60_000;

    /** null = ye type padh nahi sakte. */
    public String read(String name, String contentType, byte[] bytes) throws IOException {
        String ext = ext(name);
        String text;
        if (ext.equals("pdf") || "application/pdf".equalsIgnoreCase(contentType)) {
            text = pdf(bytes);
        } else if (ext.equals("xlsx") || ext.equals("xls")) {
            text = sheet(bytes);
        } else if (ext.equals("docx")) {
            text = docx(bytes);
        } else if (TEXT_EXT.contains(ext) || (contentType != null && contentType.toLowerCase().startsWith("text/"))) {
            text = new String(bytes, StandardCharsets.UTF_8);
        } else {
            return null;
        }
        if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "\n...[file truncated]";
        return text;
    }

    public static String ext(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private String pdf(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper st = new PDFTextStripper();
            st.setSortByPosition(true);
            st.setStartPage(1);
            st.setEndPage(Math.min(MAX_PDF_PAGES, doc.getNumberOfPages()));
            String t = st.getText(doc).trim();
            return t.isEmpty() ? "(no readable text in this PDF - it may be a scan or photo)" : t;
        }
    }

    private String sheet(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        DataFormatter fmt = new DataFormatter();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            int sheets = Math.min(MAX_SHEETS, wb.getNumberOfSheets());
            for (int i = 0; i < sheets; i++) {
                Sheet sh = wb.getSheetAt(i);
                sb.append("## Sheet: ").append(sh.getSheetName()).append('\n');
                int rows = 0;
                for (Row row : sh) {
                    if (rows++ >= MAX_ROWS) { sb.append("...[more rows not shown]\n"); break; }
                    int last = row.getLastCellNum();
                    for (int c = 0; c < last; c++) {
                        Cell cell = row.getCell(c);
                        if (c > 0) sb.append('\t');
                        if (cell != null) sb.append(fmt.formatCellValue(cell).replace('\n', ' ').replace('\t', ' '));
                    }
                    sb.append('\n');
                    if (sb.length() > MAX_CHARS) return sb.toString();
                }
            }
        }
        return sb.toString();
    }

    private String docx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            for (XWPFParagraph p : d.getParagraphs()) sb.append(p.getText()).append('\n');
            for (XWPFTable t : d.getTables()) {
                for (XWPFTableRow r : t.getRows()) {
                    boolean first = true;
                    for (XWPFTableCell c : r.getTableCells()) {
                        if (!first) sb.append('\t');
                        sb.append(c.getText().replace('\n', ' '));
                        first = false;
                    }
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }
}
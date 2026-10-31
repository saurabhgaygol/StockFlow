package com.stockmanagement.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.util.IOUtils;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    private static final long MAX_UNZIPPED_BYTES = 100L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2000;

    static {
        // Zip-bomb / memory guards for Office files (Apache POI settings are global).
        ZipSecureFile.setMinInflateRatio(0.01);       // reject extreme compression ratios
        IOUtils.setByteArrayMaxOverride(50_000_000);  // single in-memory buffer: max 50 MB
    }

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

    /** Zip-bomb guard (plain JDK): .xlsx/.docx are zip files; refuse if they unpack to too much data. */
    private static void guardZip(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K') return;   // not a zip (old .xls)
        long total = 0;
        int entries = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new IOException("Too many entries in file.");
                int n;
                while ((n = z.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_UNZIPPED_BYTES) throw new IOException("File expands to too much data.");
                }
            }
        }
    }

    private String sheet(byte[] bytes) throws IOException {
        guardZip(bytes);
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
        guardZip(bytes);
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
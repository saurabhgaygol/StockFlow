package com.stockmanagement.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tiny PDF writer (A4, Helvetica) - text, lines and filled rectangles only. No external library.
 * Text is limited to Latin-1; any other character is printed as '?'.
 */
public class SimplePdf {

    public static final float PAGE_W = 595f;
    public static final float PAGE_H = 842f;

    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder cur;

    public int newPage() {
        cur = new StringBuilder();
        pages.add(cur);
        return pages.size();
    }

    public int pageCount() { return pages.size(); }

    /** Text with its baseline at (x, y); y counts from the BOTTOM of the page. */
    public void text(float x, float y, float size, boolean bold, String s) {
        text(x, y, size, bold, s, 0f);
    }

    /** gray: 0 = black, 1 = white */
    public void text(float x, float y, float size, boolean bold, String s, float gray) {
        cur.append("BT ").append(num(gray)).append(" g /").append(bold ? "F2" : "F1").append(' ').append(num(size))
           .append(" Tf ").append(num(x)).append(' ').append(num(y)).append(" Td (").append(escape(s)).append(") Tj ET\n");
    }

    public void line(float x1, float y1, float x2, float y2, float width, float gray) {
        cur.append(num(gray)).append(" G ").append(num(width)).append(" w ")
           .append(num(x1)).append(' ').append(num(y1)).append(" m ")
           .append(num(x2)).append(' ').append(num(y2)).append(" l S\n");
    }

    public void fillRect(float x, float y, float w, float h, float gray) {
        cur.append(num(gray)).append(" g ").append(num(x)).append(' ').append(num(y)).append(' ')
           .append(num(w)).append(' ').append(num(h)).append(" re f\n");
    }

    /** Rough width of a Helvetica string (average glyph ~0.52 em) - only used to cut long text to a column. */
    public static float width(String s, float size) {
        return s == null ? 0 : s.length() * size * 0.52f;
    }

    /** Cut s with ".." so that it fits maxWidth. */
    public static String fit(String s, float size, float maxWidth) {
        if (s == null) return "";
        if (width(s, size) <= maxWidth) return s;
        int n = Math.max(1, (int) (maxWidth / (size * 0.52f)) - 2);
        return s.substring(0, Math.min(n, s.length())) + "..";
    }

    public byte[] build() throws IOException {
        if (pages.isEmpty()) newPage();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int n = pages.size();
        write(out, "%PDF-1.4\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");

        obj(out, offsets, "<< /Type /Catalog /Pages 2 0 R >>");
        obj(out, offsets, "<< /Type /Pages /Kids [ " + kids + "] /Count " + n + " >>");
        obj(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        obj(out, offsets, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < n; i++) {
            obj(out, offsets, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) PAGE_W + " " + (int) PAGE_H
                    + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>");
            byte[] content = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
            offsets.add(out.size());
            write(out, (offsets.size()) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            out.write(content);
            write(out, "\nendstream\nendobj\n");
        }

        int xref = out.size();
        write(out, "xref\n0 " + (offsets.size() + 1) + "\n0000000000 65535 f \n");
        for (int off : offsets) write(out, String.format(Locale.ROOT, "%010d 00000 n \n", off));
        write(out, "trailer\n<< /Size " + (offsets.size() + 1) + " /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void obj(ByteArrayOutputStream out, List<Integer> offsets, String body) throws IOException {
        offsets.add(out.size());
        write(out, offsets.size() + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void write(ByteArrayOutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String num(float f) {
        return String.format(Locale.ROOT, "%.2f", f);
    }

    /** Latin-1 only; ( ) \ and non-ASCII are written as escapes so the content stays plain ASCII. */
    static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == ')' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c >= 32 && c <= 126) {
                sb.append(c);
            } else if (c >= 160 && c <= 255) {
                sb.append(String.format(Locale.ROOT, "\\%03o", (int) c));
            } else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
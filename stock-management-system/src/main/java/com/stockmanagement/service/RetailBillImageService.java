package com.stockmanagement.service;

import com.stockmanagement.entity.RetailBill;
import com.stockmanagement.entity.RetailBillLine;
import com.stockmanagement.entity.RetailBillPayment;
import com.stockmanagement.entity.RetailShopProfile;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Draws a bill as a receipt picture (58 mm or 80 mm wide, as long as the bill needs) so the printer agent can print it
 * straight on the shop's bill printer, with no browser dialog. Same content as the printed receipt page.
 */
@Service
public class RetailBillImageService {

    /** the agent accepts a page up to 300 mm */
    public static final float MAX_HEIGHT_MM = 300f;

    private static final String FONT_NAME = pickFont(new String[]{"Arial", "Liberation Sans", "Helvetica", "Arimo"}, Font.SANS_SERIF);
    private static final float PT = 0.3528f;                 // 1 point in mm
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    private final RetailBillingService billing;
    private final TagPdfService pdfService;

    public RetailBillImageService(RetailBillingService billing, TagPdfService pdfService) {
        this.billing = billing;
        this.pdfService = pdfService;
    }

    public static boolean validSize(String size) {
        return "58".equals(size) || "80".equals(size);
    }

    /** page height in whole millimetres the bill needs at this width */
    @Transactional(readOnly = true)
    public int heightMm(String company, Long billId, String size) {
        Plan p = plan(company, billId, size);
        return p.hMm;
    }

    @Transactional(readOnly = true)
    public byte[] png(String company, Long billId, String size, int dpi) throws IOException {
        Plan p = plan(company, billId, size);
        float px = Math.max(150, Math.min(dpi, 600)) / 25.4f;
        int w = Math.round(p.wMm * px), h = Math.round(p.hMm * px);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setColor(Color.BLACK);
            new Painter(g, px, p.wMm, p.small, p.logo, false).draw(p.d);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ layout

    private record Plan(RetailBillingService.BillDetail d, BufferedImage logo, float wMm, boolean small, int hMm) {}

    private Plan plan(String company, Long billId, String size) {
        if (!validSize(size)) throw new IllegalArgumentException("Direct bill printing is for 58 mm and 80 mm receipts.");
        RetailBillingService.BillDetail d = billing.detail(company, billId);
        BufferedImage logo = pdfService.logoImage(company);
        float w = "58".equals(size) ? 58f : 80f;
        boolean small = "58".equals(size);
        // first pass only measures (no drawing), so the page is exactly as long as the bill
        BufferedImage scratch = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scratch.createGraphics();
        float used;
        try {
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            used = new Painter(g, 203f / 25.4f, w, small, logo, true).draw(d);
        } finally {
            g.dispose();
        }
        int h = (int) Math.ceil(Math.max(used, 40f));
        if (h > MAX_HEIGHT_MM) {
            throw new IllegalArgumentException("This bill is too long for direct receipt printing. Print it from the browser instead.");
        }
        return new Plan(d, logo, w, small, h);
    }

    /** draws the receipt top to bottom; in measure mode nothing is painted and only the length is worked out */
    private static final class Painter {
        private final Graphics2D g;
        private final float s;              // pixels per mm
        private final float w;              // page width, mm
        private final float pad;            // side margin, mm
        private final boolean measure;
        private final float pt;             // base font size
        private final BufferedImage logo;
        private float y;                    // mm from the top

        Painter(Graphics2D g, float pxPerMm, float wMm, boolean small, BufferedImage logo, boolean measure) {
            this.g = g;
            this.s = pxPerMm;
            this.w = wMm;
            this.pad = small ? 1.6f : 3f;
            this.measure = measure;
            this.pt = small ? 7.6f : 9f;
            this.logo = logo;
        }

        float draw(RetailBillingService.BillDetail d) {
            RetailBill b = d.bill();
            RetailShopProfile p = d.profile();
            y = 3f;

            if (logo != null) {
                float maxW = (w - 2 * pad) * 0.9f, maxH = 14f;
                float asp = (float) logo.getWidth() / logo.getHeight();
                float lw = Math.min(maxW, maxH * asp), lh = lw / asp;
                if (!measure) {
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(logo, Math.round((w - lw) / 2 * s), Math.round(y * s), Math.round(lw * s), Math.round(lh * s), null);
                }
                y += lh + 1.5f;
            }
            center(blank(p.getShopName()) ? "Shop" : p.getShopName().trim(), pt + 2.4f, true);
            if (!blank(p.getAddress())) center(p.getAddress().trim(), pt, false);
            if (!blank(p.getGstin())) center("GSTIN " + p.getGstin().trim(), pt, false);
            if (!blank(p.getPhone())) center("Ph " + p.getPhone().trim(), pt, false);
            if (!blank(p.getEmail())) center(p.getEmail().trim(), pt, false);
            if (!blank(p.getWebsite())) center(p.getWebsite().trim(), pt, false);
            y += 0.8f;
            center("TAX INVOICE", pt + 0.6f, true);

            if ("CANCELLED".equals(b.getStatus())) {
                y += 1f;
                float bh = lineH(pt + 1f) + 1.2f;
                if (!measure) {
                    g.setStroke(new BasicStroke(0.35f * s));
                    g.drawRect(Math.round(pad * s), Math.round(y * s), Math.round((w - 2 * pad) * s), Math.round(bh * s));
                }
                y += 0.6f;
                center("CANCELLED", pt + 1f, true);
                y += 0.6f;
            }

            dash();
            row(b.getBillNo(), b.getBillDate() == null ? "" : b.getBillDate().format(DATE), pt, false);
            row("Cashier: " + nz(b.getCreatedByName()), b.getBillDate() == null ? "" : b.getBillDate().format(TIME).toUpperCase(Locale.ENGLISH), pt, false);
            left((nz(b.getCustomerName()) + "  " + nz(b.getCustomerMobile())).trim(), pt, false);
            dash();

            for (RetailBillLine l : d.lines()) {
                y += 0.9f;
                left(nz(l.getItemLabel()), pt, true);
                row(plain(l.getQty()) + " x " + money(l.getUnitPrice()) + " (" + plain(l.getGstRate()) + "%)", money(l.getGrossAmount()), pt, false);
                if (pos(l.getDiscount())) row(blank(l.getOfferName()) ? "Offer" : l.getOfferName(), "-" + money(l.getDiscount()), pt, false);
                if (pos(l.getManualDiscount())) row("Discount", "-" + money(l.getManualDiscount()), pt, false);
            }

            dash();
            row("Total MRP", money(b.getGrossAmount()), pt, false);
            if (pos(b.getItemDiscount())) row("Offer discount", "-" + money(b.getItemDiscount()), pt, false);
            if (pos(b.getManualDiscount())) row("Extra discount", "-" + money(b.getManualDiscount()), pt, false);
            if (pos(b.getBillDiscount())) row(blank(b.getBillOfferName()) ? "Bill offer" : b.getBillOfferName(), "-" + money(b.getBillDiscount()), pt, false);
            if (b.getPointsRedeemed() != null && b.getPointsRedeemed() > 0) row("Points used", "-" + b.getPointsRedeemed() + ".00", pt, false);
            row("Without GST", money(b.getTaxableAmount()), pt, false);
            String half = d.rateRows().size() == 1 ? " " + trimDot(d.rateRows().get(0).gstRate().divide(BigDecimal.valueOf(2), 1, RoundingMode.HALF_UP)) + "%" : "";
            row("CGST" + half, money(b.getCgstAmount()), pt, false);
            row("SGST" + half, money(b.getSgstAmount()), pt, false);
            if (b.getRoundOff() != null && b.getRoundOff().signum() != 0) row("Round off", money(b.getRoundOff()), pt, false);

            // TOTAL between two thick lines
            y += 1.2f;
            thick();
            y += 1f;
            row("TOTAL", rupee(pt + 3f) + money(b.getGrandTotal()), pt + 3f, true);
            y += 0.4f;
            thick();
            y += 1.2f;

            for (RetailBillPayment pay : d.payments()) row(nz(pay.getPayMode()), money(pay.getAmount()), pt, false);

            BigDecimal saved = nzd(b.getItemDiscount()).add(nzd(b.getManualDiscount())).add(nzd(b.getBillDiscount()));
            if (saved.signum() > 0) { y += 1.2f; center("You saved " + rupee(pt) + money(saved), pt, true); }
            long earned = b.getPointsEarned() == null ? 0 : b.getPointsEarned();
            long used = b.getPointsRedeemed() == null ? 0 : b.getPointsRedeemed();
            if (earned > 0 || used > 0) {
                center("Points earned +" + earned + (d.pointsBalance() != null ? " · Balance " + d.pointsBalance().toBigInteger() : ""), pt, false);
            }

            dash();
            center("INSTRUCTIONS", pt - 0.6f, true);
            if (!blank(p.getFooterNote())) {
                int n = 1;
                for (String t : p.getFooterNote().split("\\|")) {
                    if (t.isBlank()) continue;
                    left((n++) + ". " + t.trim(), pt - 0.6f, false);
                }
            } else {
                left("1. Prices include GST.", pt - 0.6f, false);
                left("2. Exchange within 7 days with this bill and tags intact.", pt - 0.6f, false);
                left("3. No cash refund.", pt - 0.6f, false);
                left("4. Keep this bill for warranty.", pt - 0.6f, false);
            }

            barcode(b.getBillNo());
            y += 1.2f;
            center("Thank you, visit again!", pt, true);
            y += 6f;                       // a little paper after the last line so the cutter does not cut the text
            return y;
        }

        // ---------------------------------------------------------------- pieces

        private void barcode(String no) {
            if (blank(no)) return;
            int[] widths = TagDesigns.code128(no);
            int modules = 0;
            for (int v : widths) modules += v;
            float availPx = (w - 2 * pad) * 0.92f * s;
            int mod = Math.max(1, Math.min(3, (int) (availPx / modules)));
            float bw = modules * mod / s;
            float h = 9f;
            y += 2f;
            if (!measure) {
                float x = (w - bw) / 2f;
                boolean bar = true;
                float cx = x * s;
                for (int v : widths) {
                    if (bar) g.fillRect(Math.round(cx), Math.round(y * s), v * mod, Math.round(h * s));
                    cx += v * mod;
                    bar = !bar;
                }
            }
            y += h + 0.6f;
            center(no, pt - 0.4f, false);
        }

        private void dash() {
            y += 1.1f;
            if (!measure) {
                g.setStroke(new BasicStroke(0.22f * s, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{0.9f * s, 0.7f * s}, 0f));
                g.drawLine(Math.round(pad * s), Math.round(y * s), Math.round((w - pad) * s), Math.round(y * s));
            }
            y += 1.1f;
        }

        private void thick() {
            if (!measure) {
                g.setStroke(new BasicStroke(0.5f * s));
                g.drawLine(Math.round(pad * s), Math.round(y * s), Math.round((w - pad) * s), Math.round(y * s));
            }
        }

        private void center(String text, float size, boolean bold) {
            for (String line : wrap(text, size, bold, w - 2 * pad)) {
                float lw = width(line, size, bold);
                text(line, (w - lw) / 2f, size, bold);
                y += lineH(size);
            }
        }

        private void left(String text, float size, boolean bold) {
            for (String line : wrap(text, size, bold, w - 2 * pad)) {
                text(line, pad, size, bold);
                y += lineH(size);
            }
        }

        /** left text and a right-aligned amount; a long left text wraps and the amount stays on its first line */
        private void row(String l, String r, float size, boolean bold) {
            float rw = width(r, size, bold);
            float room = w - 2 * pad - rw - 1.5f;
            List<String> lines = wrap(l, size, bold, Math.max(room, 8f));
            for (int i = 0; i < lines.size(); i++) {
                text(lines.get(i), pad, size, bold);
                if (i == 0) text(r, w - pad - rw, size, bold);
                y += lineH(size);
            }
            if (lines.isEmpty()) {
                text(r, w - pad - rw, size, bold);
                y += lineH(size);
            }
        }

        private void text(String s0, float x, float size, boolean bold) {
            if (measure || s0.isEmpty()) return;
            g.setFont(font(s0, size, bold));
            g.drawString(s0, x * s, (y + size * PT * 0.95f) * s);
        }

        private float lineH(float size) { return size * PT * 1.3f; }

        private float width(String text, float size, boolean bold) {
            FontMetrics fm = g.getFontMetrics(font(text, size, bold));
            return (float) fm.getStringBounds(text, g).getWidth() / s;
        }

        private Font font(String text, float size, boolean bold) {
            Font f = new Font(FONT_NAME, bold ? Font.BOLD : Font.PLAIN, 10);
            // names in other scripts (Hindi, Marathi) need the system fallback fonts
            if (f.canDisplayUpTo(text) != -1) f = new Font(Font.DIALOG, bold ? Font.BOLD : Font.PLAIN, 10);
            return f.deriveFont(size * PT * s);
        }

        private List<String> wrap(String text, float size, boolean bold, float maxMm) {
            List<String> out = new ArrayList<>();
            if (text == null || text.isBlank()) return out;
            StringBuilder cur = new StringBuilder();
            for (String word : text.trim().split("\\s+")) {
                String tryLine = cur.length() == 0 ? word : cur + " " + word;
                if (width(tryLine, size, bold) <= maxMm) { cur = new StringBuilder(tryLine); continue; }
                if (cur.length() > 0) { out.add(cur.toString()); cur = new StringBuilder(); }
                // one word wider than the line: cut it by letters
                String rest = word;
                while (width(rest, size, bold) > maxMm && rest.length() > 1) {
                    int n = rest.length();
                    while (n > 1 && width(rest.substring(0, n), size, bold) > maxMm) n--;
                    out.add(rest.substring(0, n));
                    rest = rest.substring(n);
                }
                cur = new StringBuilder(rest);
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }

        /** the rupee sign when the font has it, else Rs. */
        private String rupee(float size) {
            return new Font(FONT_NAME, Font.BOLD, 10).canDisplay('₹') ? "₹" : "Rs.";
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s) { return s == null ? "" : s; }
    private static BigDecimal nzd(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static boolean pos(BigDecimal v) { return v != null && v.signum() > 0; }
    private static String money(BigDecimal v) { return nzd(v).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String plain(BigDecimal v) { return nzd(v).stripTrailingZeros().toPlainString(); }
    private static String trimDot(BigDecimal v) { String t = v.toPlainString(); return t.endsWith(".0") ? t.substring(0, t.length() - 2) : t; }

    private static String pickFont(String[] wanted, String fallback) {
        try {
            Set<String> names = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String n : wanted) {
                if (names.contains(n)) return n;
            }
        } catch (RuntimeException | Error ignored) {
            // headless server without fonts: the logical font below is used
        }
        return fallback;
    }
}
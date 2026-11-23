package com.stockmanagement.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Tag Studio printers and tag designs.
 *
 *  - PRINTERS: the standard retail label sizes (thermal label printers) and the A4 sheet (any office printer).
 *  - DESIGNS: 10 tag designs. Every design fits itself to every printer size: small stickers show only what fits
 *    (product, size, barcode, MRP), big ones add shop name and details, the 2 x 3 inch hang tag gets a hole mark.
 *
 * A design only draws on a {@link TagCanvas}, so the PDF and the preview picture come from the same code.
 * Everything is black and white: thermal label printers print no grey.
 */
public final class TagDesigns {

    private TagDesigns() {}

    // ------------------------------------------------------------------ printers

    /** w, h = one tag in mm. For a sheet: cols x rows tags on a sheetW x sheetH mm page. */
    public record Printer(String code, String name, String hint, float w, float h,
                          int cols, int rows, float sheetW, float sheetH) {
        public boolean sheet() { return cols > 0; }
    }

    public static final List<Printer> PRINTERS = List.of(
            new Printer("38x25", "Label 38 x 25 mm", "1.5 x 1 inch sticker", 38f, 25f, 0, 0, 0f, 0f),
            new Printer("50x25", "Label 50 x 25 mm", "2 x 1 inch sticker", 50f, 25f, 0, 0, 0f, 0f),
            new Printer("50x38", "Label 50 x 38 mm", "2 x 1.5 inch sticker", 50f, 38f, 0, 0, 0f, 0f),
            new Printer("50x50", "Label 50 x 50 mm", "2 x 2 inch sticker", 50f, 50f, 0, 0, 0f, 0f),
            new Printer("50x75", "Hang tag 50 x 75 mm", "2 x 3 inch tag with hole", 50f, 75f, 0, 0, 0f, 0f),
            new Printer("100x50", "Label 100 x 50 mm", "4 x 2 inch sticker", 100f, 50f, 0, 0, 0f, 0f),
            new Printer("a4", "A4 sheet", "Normal printer, 21 tags per page", 63.5f, 38.1f, 3, 7, 210f, 297f));

    public static final String DEFAULT_PRINTER = "50x38";

    /** "label" and "a4" were the names used before there were more sizes */
    public static Printer printer(String code) {
        String c = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (c.equals("label")) c = DEFAULT_PRINTER;
        for (Printer p : PRINTERS) if (p.code().equals(c)) return p;
        return printer(DEFAULT_PRINTER);
    }

    // ------------------------------------------------------------------ designs

    public record Design(String code, String name, String hint) {
        /** "Standard" or "Premium" (how the Designs page groups them) */
        public String group() { return code.startsWith("premium") ? "Premium" : "Standard"; }
    }

    public static final List<Design> DESIGNS = List.of(
            new Design("classic", "Classic", "Shop name on top, everything centred"),
            new Design("price-first", "Price First", "Product and price on the first line"),
            new Design("big-size", "Big Size", "Size in a big filled box"),
            new Design("minimal", "Minimal", "Only product, barcode and price"),
            new Design("boutique", "Boutique", "Rounded border, shop name in capitals"),
            new Design("black-header", "Black Header", "Shop name in a band on top"),
            new Design("black-footer", "Black Footer", "Price in a band at the bottom"),
            new Design("table", "Table", "Details in boxes, like a garment label"),
            new Design("side-price", "Side Price", "Barcode on the left, price on the right"),
            new Design("scan-first", "Scan First", "Biggest barcode for fast billing"),
            new Design("brand-mrp", "Brand MRP", "Barcode on top, details in rows, full MRP block"),
            new Design("retail-mrp", "Retail MRP", "Big MRP on top, size, barcode at the bottom"),
            new Design("premium", "Premium 1 - Swoosh", "Monogram with swoosh, serif shop name, corner"),
            new Design("premium-frame", "Premium 2 - Frame", "Double border, monogram in a circle"),
            new Design("premium-dark", "Premium 3 - Dark Top", "Filled top block with the shop name in white"),
            new Design("premium-circle", "Premium 4 - Circle", "Circle monogram, size in a big box"),
            new Design("premium-ribbon", "Premium 5 - Ribbon", "Price on a ribbon across the tag"),
            new Design("premium-serif", "Premium 6 - Serif", "Big serif name between double lines"),
            new Design("premium-strip", "Premium 7 - Side Strip", "Strip on the left with the monogram"),
            new Design("premium-badge", "Premium 8 - Badge", "Size in a round badge, price in a pill"));

    /** tag colours offered on the Designs page (any other #RRGGBB can be picked too); black = 0x000000 */
    public record Ink(String name, String hex) {}

    public static final List<Ink> INKS = List.of(
            new Ink("Black", "#000000"), new Ink("Navy", "#1F2A5A"), new Ink("Royal Blue", "#1F4FBF"),
            new Ink("Maroon", "#7A1F2B"), new Ink("Red", "#C62828"), new Ink("Teal", "#0F7C7C"),
            new Ink("Green", "#0F6B3E"), new Ink("Orange", "#E36A0A"), new Ink("Purple", "#6C3CE0"),
            new Ink("Brown", "#6B4226"));

    /** "#7a1f2b" / "7A1F2B" -> "#7A1F2B"; anything else -> null (= black) */
    public static String hex(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.startsWith("#")) t = t.substring(1);
        if (!t.matches("[0-9A-F]{6}")) return null;
        return t.equals("000000") ? null : "#" + t;
    }

    public static int rgb(String hex) {
        String h = hex(hex);
        return h == null ? 0 : Integer.parseInt(h.substring(1), 16);
    }

    /**
     * The six colour designs of an earlier version were premium layouts in a colour. A shop that chose one keeps
     * the same look: returns {layout code, colour} for those codes, null for every other code.
     */
    public static Object[] oldColourDesign(String code) {
        return code == null ? null : switch (code) {
            case "color-royal" -> new Object[]{"premium-dark", "#1F2A5A"};
            case "color-maroon" -> new Object[]{"premium-frame", "#7A1F2B"};
            case "color-teal" -> new Object[]{"premium-strip", "#0F7C7C"};
            case "color-orange" -> new Object[]{"premium-ribbon", "#E36A0A"};
            case "color-green" -> new Object[]{"premium", "#0F6B3E"};
            case "color-purple" -> new Object[]{"premium-badge", "#6C3CE0"};
            default -> null;
        };
    }

    public static final String DEFAULT_DESIGN = "classic";

    public static Design design(String code) {
        for (Design d : DESIGNS) if (d.code().equalsIgnoreCase(code == null ? "" : code.trim())) return d;
        return DESIGNS.get(0);
    }

    // ------------------------------------------------------------------ what a tag shows

    /**
     * mrp: already formatted ("1,499" or "1,499.50"), null = no price on the tag.
     * address / phone / email: the shop's details (for "Marketed by" and customer care), mfg: "11/2026".
     */
    public record TagData(String shop, String product, String size, String colour, String style, String mrp,
                          String barcode, String sizeLabel, String colourLabel, String styleLabel,
                          String address, String phone, String email, String mfg) {
        /** short form used by older code: no shop address and no month of manufacture */
        public TagData(String shop, String product, String size, String colour, String style, String mrp,
                       String barcode, String sizeLabel, String colourLabel, String styleLabel) {
            this(shop, product, size, colour, style, mrp, barcode, sizeLabel, colourLabel, styleLabel, null, null, null, null);
        }
    }

    /** draws one tag of w x h mm (top-left at 0,0 of the canvas) in black */
    public static void draw(TagCanvas c, String designCode, float w, float h, TagData d) {
        draw(c, designCode, w, h, d, 0);
    }

    /**
     * draws one tag in a colour (0xRRGGBB, 0 = black): bands, strips, borders, badges and the shop name take the
     * colour; the barcode and the other text stay black so they read and scan well.
     */
    public static void draw(TagCanvas c, String designCode, float w, float h, TagData d, int ink) {
        String code = design(designCode).code();
        c.ink(ink);
        Box b = new Box(c, w, h, d);
        try {
            drawDesign(b, code);
        } finally {
            c.ink(0);
        }
    }

    private static void drawDesign(Box b, String code) {
        switch (code) {
            case "price-first" -> priceFirst(b);
            case "big-size" -> bigSize(b);
            case "minimal" -> minimal(b);
            case "boutique" -> boutique(b);
            case "black-header" -> blackHeader(b);
            case "black-footer" -> blackFooter(b);
            case "table" -> table(b);
            case "side-price" -> sidePrice(b);
            case "scan-first" -> scanFirst(b);
            case "brand-mrp" -> brandMrp(b);
            case "retail-mrp" -> retailMrp(b);
            case "premium" -> premium(b);
            case "premium-frame", "premium-dark", "premium-circle", "premium-ribbon", "premium-serif",
                 "premium-strip", "premium-badge" -> premiumVariant(b, code);
            default -> classic(b);
        }
    }

    // ------------------------------------------------------------------ the 10 designs

    private static void classic(Box b) {
        Stack s = new Stack(b);
        if (!b.small && b.has(b.d.shop())) s.add(b.brandH(b.shopPt) + 1.2f, (top, hh) -> {
            b.brandC(b.upper(b.d.shop()), top, b.shopPt, true, false);
            float y = top + b.brandH(b.shopPt) + 0.4f;
            b.c.line(b.left, y, b.right, y, 0.25f, false);
        });
        s.add(b.lh(b.prodPt), (top, hh) -> b.textC(b.productLine(b.small), top, b.prodPt, true, false));
        if (!b.small && b.has(b.details())) s.add(b.lh(b.detPt), (top, hh) -> b.textC(b.details(), top, b.detPt, false, false));
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        if (b.has(b.d.mrp())) {
            s.add(b.lh(b.mrpPt), (top, hh) -> b.textC("MRP Rs. " + b.d.mrp(), top, b.mrpPt, true, false));
            if (!b.small) s.add(b.lh(b.notePt), (top, hh) -> b.textC("Incl. of all taxes", top, b.notePt, false, false));
        }
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void priceFirst(Box b) {
        Stack s = new Stack(b);
        float pricePt = b.mrpPt * 1.15f;
        s.add(b.lh(pricePt), (top, hh) -> {
            float pw = 0;
            if (b.has(b.d.mrp())) {
                String price = "Rs. " + b.d.mrp();
                pw = b.textR(price, b.right, top, b.innerW * 0.55f, pricePt, true, false);
            }
            b.textL(b.productLine(b.small), b.left, top + (b.lh(pricePt) - b.lh(b.prodPt)) / 2f,
                    b.innerW - pw - 1.5f, b.prodPt, true, false);
        });
        if (!b.small && (b.has(b.details()) || b.has(b.d.style()))) s.add(b.lh(b.detPt), (top, hh) -> {
            float sw = 0;
            if (b.has(b.d.style())) sw = b.textR(b.d.style(), b.right, top, b.innerW * 0.45f, b.detPt, false, false);
            b.textL(b.sizeColour(false), b.left, top, b.innerW - sw - 1.5f, b.detPt, false, false);
        });
        s.add(0.35f, (top, hh) -> b.c.line(b.left, top + 0.15f, b.right, top + 0.15f, 0.25f, false));
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        if (!b.small && b.has(b.d.shop())) {
            s.add(b.brandH(b.notePt * 1.15f), (top, hh) -> b.brandC(b.d.shop() + (b.has(b.d.mrp()) ? "  |  MRP incl. of all taxes" : ""),
                    top, b.notePt * 1.15f, false, false));
        }
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void bigSize(Box b) {
        float topH = Math.min((b.bottom - b.top) * (b.small ? 0.42f : 0.40f), b.innerW * 0.45f);
        float boxW = Math.min(topH * 1.1f, b.innerW * 0.38f);
        String size = b.has(b.d.size()) ? b.d.size() : "-";
        // black box with the size
        b.c.rect(b.left, b.top, boxW, topH, 1.2f, true, false, 0);
        float sizePt = b.fitPt(size, boxW - 2f, topH / PT * 0.62f, 5f, true);
        float tw = b.c.textWidth(size, sizePt, true);
        b.c.text(size, b.left + (boxW - tw) / 2f, b.top + topH / 2f + sizePt * PT * 0.36f, sizePt, true, true);
        // product, colour, price next to it
        float x = b.left + boxW + 2f, mw = b.right - x;
        Stack side = new Stack(b);
        side.add(b.lh(b.prodPt), (top, hh) -> b.textL(b.d.product(), x, top, mw, b.prodPt, true, false));
        if (b.has(b.d.colour())) side.add(b.lh(b.detPt), (top, hh) -> b.textL(b.d.colour(), x, top, mw, b.detPt, false, false));
        if (!b.small && b.has(b.d.style())) side.add(b.lh(b.detPt), (top, hh) -> b.textL(b.d.style(), x, top, mw, b.detPt, false, false));
        if (b.has(b.d.mrp())) side.add(b.lh(b.mrpPt), (top, hh) -> b.textL("MRP Rs. " + b.d.mrp(), x, top, mw, b.mrpPt, true, false));
        side.layout(b.top, b.top + topH, 0.3f);
        // barcode under it
        Stack s = new Stack(b);
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        if (!b.small && b.has(b.d.shop())) s.add(b.brandH(b.notePt * 1.15f), (top, hh) -> b.brandC(b.d.shop(), top, b.notePt * 1.15f, false, false));
        s.layout(b.top + topH + b.gap * 1.5f, b.bottom, b.gap);
    }

    private static void minimal(Box b) {
        Stack s = new Stack(b);
        s.add(b.lh(b.prodPt), (top, hh) -> b.textC(b.productLine(true), top, b.prodPt, true, false));
        s.flex(b.barMin(), b.barMax() * 1.15f, (top, hh) -> b.bars(top, hh, b.left, b.innerW));
        s.add(b.lh(b.mrpPt), (top, hh) -> {
            float base = top + (b.lh(b.mrpPt) - b.lh(b.codePt)) / 2f;
            if (b.has(b.d.mrp())) {
                float pw = b.textR("Rs. " + b.d.mrp(), b.right, top, b.innerW * 0.5f, b.mrpPt, true, false);
                b.textL(b.d.barcode(), b.left, base, b.innerW - pw - 1f, b.codePt, false, false);
            } else {
                b.textC(b.d.barcode(), base, b.codePt, false, false);
            }
        });
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void boutique(Box b) {
        float inset = Math.min(1.0f, b.pad * 0.45f);
        b.c.rect(inset, inset, b.w - 2 * inset, b.h - 2 * inset, Math.min(3f, b.h * 0.08f), false, false, 0.3f);
        Stack s = new Stack(b);
        if (!b.small && b.has(b.d.shop())) {
            s.add(b.brandH(b.shopPt) + 1.2f, (top, hh) -> {
                b.brandC(b.spaced(b.upper(b.d.shop())), top, b.shopPt, true, false);
                float y = top + b.brandH(b.shopPt) + 0.5f, half = Math.min(6f, b.innerW / 6f);
                b.c.line(b.w / 2f - half, y, b.w / 2f + half, y, 0.25f, false);
            });
        }
        s.add(b.lh(b.prodPt), (top, hh) -> b.textC(b.productLine(b.small), top, b.prodPt, true, false));
        if (!b.small && b.has(b.details())) s.add(b.lh(b.detPt), (top, hh) -> b.textC(b.details(), top, b.detPt, false, false));
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left + 1f, b.innerW - 2f));
        if (b.has(b.d.mrp())) s.add(b.lh(b.mrpPt), (top, hh) -> b.textC("MRP Rs. " + b.d.mrp(), top, b.mrpPt, true, false));
        s.layout(b.top + inset, b.bottom - inset, b.gap);
    }

    private static void blackHeader(Box b) {
        boolean shopHead = !b.small && b.has(b.d.shop());
        String head = shopHead ? b.upper(b.d.shop()) : b.productLine(true);
        float pt = b.small ? b.prodPt : b.shopPt * 1.05f;
        float bandTop = b.top - (b.tall ? 0 : b.pad * 0.6f), bandH = (shopHead ? b.brandH(pt) : b.lh(pt)) + 1.6f;
        b.c.rect(0, bandTop, b.w, bandH, 0, true, false, 0);
        if (shopHead) b.brandCx(head, b.w / 2f, bandTop + 0.8f, b.innerW, pt, true, true, true);
        else b.textC(head, bandTop + 0.8f, pt, true, true);
        Stack s = new Stack(b);
        if (!b.small) {
            s.add(b.lh(b.prodPt), (top, hh) -> b.textC(b.productLine(false), top, b.prodPt, true, false));
            if (b.has(b.details())) s.add(b.lh(b.detPt), (top, hh) -> b.textC(b.details(), top, b.detPt, false, false));
        }
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        if (b.has(b.d.mrp())) s.add(b.lh(b.mrpPt), (top, hh) -> b.textC("MRP Rs. " + b.d.mrp(), top, b.mrpPt, true, false));
        s.layout(bandTop + bandH + b.gap, b.bottom, b.gap);
    }

    private static void blackFooter(Box b) {
        float pt = b.mrpPt * 1.05f;
        float bandH = b.lh(pt) + 1.8f, bandBottom = b.h - (b.pad * 0.6f), bandTop = bandBottom - bandH;
        String foot = b.has(b.d.mrp()) ? "MRP Rs. " + b.d.mrp() : (b.has(b.d.size()) ? b.d.sizeLabel() + " " + b.d.size() : b.d.product());
        b.c.rect(0, bandTop, b.w, bandH, 0, true, false, 0);
        b.textC(foot, bandTop + 0.9f, pt, true, true);
        Stack s = new Stack(b);
        if (!b.small && b.has(b.d.shop())) s.add(b.brandH(b.shopPt), (top, hh) -> b.brandC(b.upper(b.d.shop()), top, b.shopPt, true, false));
        s.add(b.lh(b.prodPt), (top, hh) -> b.textC(b.productLine(b.small), top, b.prodPt, true, false));
        if (!b.small && b.has(b.details())) s.add(b.lh(b.detPt), (top, hh) -> b.textC(b.details(), top, b.detPt, false, false));
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        s.layout(b.top, bandTop - b.gap, b.gap);
    }

    private static void table(Box b) {
        float x0 = b.left - 0.6f, x1 = b.right + 0.6f, y0 = b.top - 0.4f, y1 = b.bottom + 0.4f;
        float line = 0.25f;
        b.c.rect(x0, y0, x1 - x0, y1 - y0, 0, false, false, line);
        float rowP = b.lh(b.prodPt) + 1.2f, rowD = b.lh(b.detPt) + 1.2f, rowM = b.lh(b.mrpPt) + 1.2f;
        float y = y0;
        // product
        b.textC(b.small ? b.productLine(true) : b.d.product(), y + 0.6f, b.prodPt, true, false);
        y += rowP;
        b.c.line(x0, y, x1, y, line, false);
        // size | colour
        if (!b.small) {
            float mid = (x0 + x1) / 2f;
            String l = b.has(b.d.size()) ? b.d.sizeLabel() + ": " + b.d.size() : "";
            String r = b.has(b.d.colour()) ? b.d.colour() : (b.has(b.d.style()) ? b.d.style() : "");
            b.textL(l, x0 + 1f, y + 0.6f, mid - x0 - 2f, b.detPt, false, false);
            b.textL(r, mid + 1f, y + 0.6f, x1 - mid - 2f, b.detPt, false, false);
            b.c.line(mid, y, mid, y + rowD, line, false);
            y += rowD;
            b.c.line(x0, y, x1, y, line, false);
        }
        // price row at the bottom
        float yb = y1;
        if (b.has(b.d.mrp())) {
            yb = y1 - rowM;
            b.c.line(x0, yb, x1, yb, line, false);
            b.textL("MRP", x0 + 1f, yb + 0.6f + (b.lh(b.mrpPt) - b.lh(b.detPt)) / 2f, 12f, b.detPt, false, false);
            b.textR("Rs. " + b.d.mrp(), x1 - 1f, yb + 0.6f, (x1 - x0) * 0.7f, b.mrpPt, true, false);
        }
        // barcode in the middle row, not taller than on other designs
        float pad = Math.min(1.2f, (yb - y) * 0.08f);
        float room = yb - y - 2 * pad, hh = Math.min(room, b.barMax() + b.lh(b.codePt));
        b.barcodeWithText(y + pad + (room - hh) / 2f, hh, b.left, b.innerW);
    }

    private static void sidePrice(Box b) {
        Stack s = new Stack(b);
        s.add(b.lh(b.prodPt), (top, hh) -> b.textL(b.productLine(b.small), b.left, top, b.innerW, b.prodPt, true, false));
        if (!b.small && b.has(b.details())) s.add(b.lh(b.detPt), (top, hh) -> b.textL(b.details(), b.left, top, b.innerW, b.detPt, false, false));
        s.flex(b.barMin(), b.barMax(), (top, hh) -> {
            if (!b.has(b.d.mrp())) { b.barcodeWithText(top, hh, b.left, b.innerW); return; }
            float split = b.left + b.innerW * 0.64f;
            b.barcodeWithText(top, hh, b.left, split - b.left - 1f);
            b.c.line(split, top, split, top + hh, 0.25f, false);
            float rw = b.right - split - 1f, cx = split + 0.5f + rw / 2f + 0.5f;
            float pricePt = b.fitPt("Rs. " + b.d.mrp(), rw, b.mrpPt * 1.25f, 4.5f, true);
            float block = b.lh(b.notePt) + b.lh(pricePt);
            float y = top + (hh - block) / 2f;
            b.textCx("MRP", cx, y, rw, b.notePt * 1.1f, false, false);
            b.textCx("Rs. " + b.d.mrp(), cx, y + b.lh(b.notePt), rw, pricePt, true, false);
        });
        if (!b.small && b.has(b.d.shop())) s.add(b.brandH(b.notePt * 1.1f), (top, hh) -> b.brandL(b.d.shop()
                + (b.has(b.d.mrp()) ? "  |  MRP incl. of all taxes" : ""), b.left, top, b.innerW, b.notePt * 1.1f, false, false));
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void scanFirst(Box b) {
        Stack s = new Stack(b);
        s.add(b.lh(b.detPt * 1.1f), (top, hh) -> {
            float pw = 0;
            if (b.has(b.d.mrp())) pw = b.textR("Rs. " + b.d.mrp(), b.right, top, b.innerW * 0.45f, b.detPt * 1.1f, true, false);
            b.textL(b.productLine(true), b.left, top, b.innerW - pw - 1.5f, b.detPt * 1.1f, false, false);
        });
        s.flex(b.barMin() * 1.3f, b.barMax() * 1.6f, (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        s.layout(b.top, b.bottom, b.gap);
    }

    // ------------------------------------------------------------------ MRP tags like the big brands (declarations)

    /** like a brand garment tag: barcode on top, label/value rows, "Maximum Retail Price" block, marketed by */
    private static void brandMrp(Box b) {
        if (b.wide) { mrpWide(b, true); return; }
        boolean full = b.h >= 60f;
        Stack s = new Stack(b);
        s.flex(b.barMin(), Math.min(b.barMax(), full ? 13f : b.barMax()), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        if (full && b.has(b.d.mfg())) s.add(b.lh(b.notePt * 1.15f), (top, hh) ->
                b.textL("Month & Year of manufacture: " + b.d.mfg(), b.left, top, b.innerW, b.notePt * 1.15f, false, false));
        if (full) {
            float rowPt = b.detPt * 0.95f, labelW = b.innerW * 0.40f;
            for (String[] r : b.rows(true)) s.add(b.lh(rowPt), (top, hh) -> b.row(r[0], r[1], top, labelW, rowPt));
        } else {
            s.add(b.lh(b.prodPt), (top, hh) -> b.textL(b.productLine(true), b.left, top, b.innerW, b.prodPt, true, false));
        }
        if (b.has(b.d.mrp())) {
            if (!b.small) s.add(b.lh(b.detPt), (top, hh) -> b.textL("Maximum Retail Price", b.left, top, b.innerW, b.detPt, true, false));
            float big = b.mrpPt * (b.small ? 1.1f : 1.45f);
            s.add(b.lh(big), (top, hh) -> b.textL("Rs. " + b.mrp2(), b.left, top, b.innerW, big, true, false));
            s.add(b.lh(b.notePt * 1.1f), (top, hh) -> b.textL("Inclusive of all Taxes", b.left, top, b.innerW, b.notePt * 1.1f, false, false));
        }
        if (full) b.legal(s, false);
        else if (!b.small && b.has(b.d.shop())) s.add(b.lh(b.notePt), (top, hh) ->
                b.textL("Marketed by: " + b.d.shop(), b.left, top, b.innerW, b.notePt, false, false));
        s.layout(b.top, b.bottom, b.gap * 0.8f);
    }

    /** like a big retail chain tag: MRP first in large type, size, details, barcode at the bottom */
    private static void retailMrp(Box b) {
        if (b.wide) { mrpWide(b, false); return; }
        boolean full = b.h >= 60f;
        Stack s = new Stack(b);
        if (b.has(b.d.mrp())) {
            if (!b.small) s.add(b.lh(b.notePt * 1.1f), (top, hh) -> b.textC("MRP (incl. of all taxes)", top, b.notePt * 1.1f, false, false));
            float big = b.mrpPt * (b.small ? 1.2f : 1.7f);
            s.add(b.lh(big), (top, hh) -> b.textC((b.small ? "MRP Rs. " : "Rs. ") + b.mrp2(), top, big, true, false));
            s.add(0.3f, (top, hh) -> b.c.line(b.left, top + 0.1f, b.right, top + 0.1f, 0.25f, false));
        }
        if (b.has(b.d.size())) {
            float sp = b.prodPt * (b.small ? 1f : 1.15f);
            s.add(b.lh(sp), (top, hh) -> b.textL(b.d.sizeLabel() + ": " + b.d.size(), b.left, top, b.innerW, sp, true, false));
        }
        if (!b.small) {
            float rowPt = b.notePt * 1.25f;
            List<String[]> rows = b.rows(full, false);               // size is already shown big above
            for (int i = 0; i < rows.size(); i += 2) {
                String[] l = rows.get(i), r = i + 1 < rows.size() ? rows.get(i + 1) : null;
                s.add(b.lh(rowPt), (top, hh) -> {
                    float half = b.innerW / 2f;
                    b.textL(l[0] + ": " + l[1], b.left, top, half - 1f, rowPt, false, false);
                    if (r != null) b.textL(r[0] + ": " + r[1], b.left + half, top, half, rowPt, false, false);
                });
            }
        }
        s.flex(b.barMin(), b.barMax(), (top, hh) -> b.barcodeWithText(top, hh, b.left, b.innerW));
        if (full) b.legal(s, true);
        else if (!b.small && b.has(b.d.shop())) s.add(b.lh(b.notePt), (top, hh) ->
                b.textC("Marketed by: " + b.d.shop(), top, b.notePt, false, false));
        s.layout(b.top, b.bottom, b.gap * 0.8f);
    }

    /** 100 x 50: details and price on the left, barcode and "marketed by" on the right */
    private static void mrpWide(Box b, boolean barcodeTop) {
        float split = b.left + b.innerW * 0.52f, lw = split - b.left - 1.5f, rx = split + 1.5f, rw = b.right - rx;
        b.c.line(split, b.top, split, b.bottom, 0.25f, false);
        Stack l = new Stack(b);
        float rowPt = b.detPt * 0.85f;
        for (String[] r : b.rows(true)) l.add(b.lh(rowPt), (top, hh) -> b.rowAt(r[0], r[1], b.left, top, lw * 0.42f, lw, rowPt));
        if (b.has(b.d.mrp())) {
            l.add(b.lh(b.notePt * 1.2f), (top, hh) -> b.textL("Maximum Retail Price", b.left, top, lw, b.notePt * 1.2f, true, false));
            float big = b.mrpPt * 1.25f;
            l.add(b.lh(big), (top, hh) -> b.textL("Rs. " + b.mrp2(), b.left, top, lw, big, true, false));
            l.add(b.lh(b.notePt), (top, hh) -> b.textL("Inclusive of all Taxes", b.left, top, lw, b.notePt, false, false));
        }
        l.layout(b.top, b.bottom, b.gap * 0.6f);
        Stack r = new Stack(b);
        r.flex(b.barMin(), b.barMax() * 0.8f, (top, hh) -> b.barcodeWithText(top, hh, rx, rw));
        if (b.has(b.d.mfg())) r.add(b.lh(b.notePt), (top, hh) -> b.textL("Mfd: " + b.d.mfg(), rx, top, rw, b.notePt, false, false));
        b.legalAt(r, rx, rw);
        r.layout(b.top, b.bottom, b.gap * 0.6f);
    }

    // ------------------------------------------------------------------ premium hang tag

    /**
     * Premium boutique tag: monogram of the shop's first letter with a swoosh, shop name in serif capitals, product as
     * a spaced tagline, size in a pill, spaced barcode digits, big MRP, colour / style / qty columns, website or phone
     * at the bottom and a black corner. Smaller stickers keep only what fits.
     */
    private static void premium(Box b) {
        if (b.wide) { premiumWide(b); return; }
        boolean full = b.tall, big = b.h >= 48f, mid = !b.small;   // full = hang tag: columns + phone line
        float cornerH = big ? b.h * 0.07f : 0f;
        if (big) b.c.poly(new float[]{b.w * 0.36f, b.h, b.w, b.h - cornerH, b.w, b.h}, true, 0);
        Stack s = new Stack(b);
        if (big) {
            float mh = Math.min(13f, (b.bottom - b.top) * 0.17f);
            s.add(mh, (top, hh) -> b.monogram(b.w / 2f, top, hh));
        }
        if (mid || !b.has(b.d.size())) {
            float np = b.prodPt * (big ? 1.75f : 1.3f);
            s.add(b.lh(np), (top, hh) -> b.serifBrand(b.upper(b.d.shop()), b.w / 2f, top, b.innerW, np, false, !big));
        }
        if (mid && b.has(b.d.product())) {
            float tp = b.notePt * 1.25f;
            s.add(b.lh(tp), (top, hh) -> b.taglineC(b.upper(b.d.product()), top, tp));
        }
        if (b.has(b.d.size())) {
            float sp = b.detPt * (b.small ? 1.1f : 1.3f);
            s.add(b.lh(sp) + 1.6f, (top, hh) -> b.sizePill(top, hh, sp));
        }
        s.flex(b.barMin(), Math.min(b.barMax(), big ? 12f : b.barMax()), (top, hh) -> b.barcodeSpaced(top, hh, b.left, b.innerW));
        if (b.has(b.d.mrp())) {
            if (mid) s.add(0.3f, (top, hh) -> b.c.line(b.left, top + 0.1f, b.right, top + 0.1f, 0.25f, false));
            float mp = b.mrpPt * (b.small ? 1f : 1.3f);
            s.add(b.lh(mp), (top, hh) -> b.textC("MRP Rs. " + b.mrp2(), top, mp, true, false));
            if (mid) s.add(b.lh(b.notePt * 1.2f), (top, hh) -> b.textC("(Incl. of all taxes)", top, b.notePt * 1.2f, false, false));
        }
        if (full) {
            List<String[]> cols = new ArrayList<>();
            if (b.has(b.d.colour())) cols.add(new String[]{b.upper(b.d.colourLabel()), b.d.colour()});
            if (b.has(b.d.style())) cols.add(new String[]{b.upper(b.d.styleLabel()), b.d.style()});
            cols.add(new String[]{"QTY", "1 N"});
            float lp = b.notePt, vp = b.notePt * 1.25f;
            s.add(b.lh(lp) + b.lh(vp) + 0.4f, (top, hh) -> {
                float cw = b.innerW / cols.size();
                for (int i = 0; i < cols.size(); i++) {
                    float cx = b.left + cw * i + cw / 2f;
                    b.textCx(cols.get(i)[0], cx, top, cw - 1.5f, lp, false, false);
                    b.textCx(cols.get(i)[1], cx, top + b.lh(lp) + 0.4f, cw - 1.5f, vp, true, false);
                    if (i > 0) b.c.line(b.left + cw * i, top, b.left + cw * i, top + hh, 0.2f, false);
                }
            });
            String bottom = b.d.phone();
            if (b.has(bottom)) s.add(b.lh(b.notePt * 1.1f), (top, hh) -> b.taglineC(bottom, top, b.notePt * 1.1f));
        }
        s.layout(b.top, b.bottom - cornerH * 0.55f, b.gap);
    }

    /** 100 x 50: brand block on the left, size + barcode + price on the right */
    private static void premiumWide(Box b) {
        float split = b.left + b.innerW * 0.46f, lw = split - b.left - 2f, rx = split + 2f, rw = b.right - rx;
        b.c.poly(new float[]{b.w * 0.62f, b.h, b.w, b.h - b.h * 0.10f, b.w, b.h}, true, 0);
        b.c.line(split, b.top + 2f, split, b.bottom - 2f, 0.25f, false);
        Stack l = new Stack(b);
        l.add(Math.min(13f, b.h * 0.26f), (top, hh) -> b.monogram(b.left + lw / 2f, top, hh));
        float np = b.prodPt * 1.25f;
        l.add(b.lh(np), (top, hh) -> b.serifC(b.upper(b.d.shop()), b.left + lw / 2f, top, lw, np));
        if (b.has(b.d.product())) l.add(b.lh(b.notePt * 1.2f), (top, hh) -> b.textCx(b.spaced(b.upper(b.d.product())), b.left + lw / 2f, top, lw, b.notePt * 1.2f, false, false));
        l.layout(b.top, b.bottom, b.gap);
        Stack r = new Stack(b);
        if (b.has(b.d.size())) {
            float sp = b.detPt * 1.2f;
            r.add(b.lh(sp) + 1.6f, (top, hh) -> b.sizePillAt(rx + rw / 2f, top, hh, sp));
        }
        r.flex(b.barMin(), b.barMax() * 0.75f, (top, hh) -> b.barcodeSpaced(top, hh, rx, rw));
        if (b.has(b.d.mrp())) {
            float mp = b.mrpPt * 1.1f;
            r.add(b.lh(mp), (top, hh) -> b.textCx("MRP Rs. " + b.mrp2(), rx + rw / 2f, top, rw, mp, true, false));
            r.add(b.lh(b.notePt * 1.1f), (top, hh) -> b.textCx("(Incl. of all taxes)", rx + rw / 2f, top, rw, b.notePt * 1.1f, false, false));
        }
        r.layout(b.top, b.bottom - b.h * 0.08f, b.gap);
    }

    // ------------------------------------------------------------------ premium 2..8

    private static void premiumVariant(Box b, String v) {
        if (b.small) { premiumSmall(b, v); return; }
        switch (v) {
            case "premium-frame" -> premiumFrame(b);
            case "premium-dark" -> premiumDark(b);
            case "premium-circle" -> premiumCircle(b);
            case "premium-ribbon" -> premiumRibbon(b);
            case "premium-serif" -> premiumSerif(b);
            case "premium-strip" -> premiumStrip(b);
            default -> premiumBadge(b);
        }
    }

    /** small stickers: size pill, barcode, price, plus the variant's mark (band, strip, frame ...) */
    private static void premiumSmall(Box b, String v) {
        float x = b.left, mw = b.innerW, top = b.top, bottom = b.bottom;
        switch (v) {
            case "premium-dark" -> {
                float bh = b.lh(b.prodPt) + 1.4f;
                b.c.rect(0, 0, b.w, bh + b.pad * 0.5f, 0, true, false, 0);
                b.serifC(b.upper(b.d.shop()), b.w / 2f, b.pad * 0.5f + 0.5f, mw, b.prodPt, true);
                top = bh + b.pad * 0.5f + b.gap;
            }
            case "premium-strip" -> {
                float sw = Math.min(6f, b.w * 0.12f);
                b.c.rect(0, 0, sw, b.h, 0, true, false, 0);
                x = sw + b.pad * 0.8f; mw = b.right - x;
            }
            case "premium-frame", "premium-serif" -> b.c.rect(0.8f, 0.8f, b.w - 1.6f, b.h - 1.6f, 0, false, false, 0.35f);
            default -> { }
        }
        float fx = x, fw = mw, cx = x + mw / 2f;
        Stack s = new Stack(b);
        if (b.has(b.d.size())) s.add(b.lh(b.detPt * 1.1f) + 1.4f, (t, hh) -> b.sizePillAt(cx, t, hh, b.detPt * 1.1f));
        s.flex(b.barMin(), b.barMax(), (t, hh) -> b.barcodeSpaced(t, hh, fx, fw));
        if (b.has(b.d.mrp())) {
            if (v.equals("premium-ribbon") || v.equals("premium-badge")) {
                float pt = b.mrpPt;
                s.add(b.lh(pt) + 1.2f, (t, hh) -> b.blackPill("MRP Rs. " + b.mrp2(), cx, t, hh, fw, pt, v.equals("premium-badge")));
            } else {
                s.add(b.lh(b.mrpPt), (t, hh) -> b.textCx("MRP Rs. " + b.mrp2(), cx, t, fw, b.mrpPt, true, false));
            }
        }
        s.layout(top, bottom, b.gap);
    }

    private static void premiumFrame(Box b) {
        float o = Math.min(1.2f, b.pad * 0.45f), in = o + 1.0f;
        b.c.rect(o, o, b.w - 2 * o, b.h - 2 * o, 0, false, false, 0.6f);
        b.c.rect(in, in, b.w - 2 * in, b.h - 2 * in, 0, false, false, 0.2f);
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float x = in + 1.5f, mw = b.w - 2 * x, cx = b.w / 2f;
        Stack s = new Stack(b);
        if (big) s.add(Math.min(12f, b.h * 0.16f), (t, hh) -> b.circleMono(cx, t, hh));
        float np = b.prodPt * (big ? 1.6f : 1.3f);
        s.add(b.lh(np), (t, hh) -> b.serifBrand(b.upper(b.d.shop()), cx, t, mw, np, false, !big));
        if (b.has(b.d.product())) s.add(b.lh(b.notePt * 1.2f), (t, hh) -> b.taglineAt(b.upper(b.d.product()), cx, t, mw, b.notePt * 1.2f));
        if (b.has(b.d.size())) s.add(b.lh(b.detPt * 1.25f) + 1.6f, (t, hh) -> b.sizePillAt(cx, t, hh, b.detPt * 1.25f));
        s.flex(b.barMin(), Math.min(b.barMax(), 12f), (t, hh) -> b.barcodeSpaced(t, hh, x, mw));
        b.mrpBlock(s, cx, mw, 1.25f);
        if (b.tall) b.columns(s, x, mw);
        s.layout(in + (b.tall ? 6.5f : 1.2f), b.h - in - 1.2f, b.gap);
    }

    private static void premiumDark(Box b) {
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float bandH = big ? (b.bottom - b.top) * 0.36f + b.top : b.lh(b.prodPt * 1.35f) + b.lh(b.notePt * 1.1f) + 3.5f;
        if (b.tall) bandH = Math.max(bandH, b.top + (b.bottom - b.top) * 0.36f);
        float bandTop = b.tall ? b.top - 1.5f : 0f;                        // hang tag: the hole stays above the band
        b.c.rect(0, bandTop, b.w, bandH - bandTop, 0, true, false, 0);
        float cx = b.w / 2f;
        Stack band = new Stack(b);
        if (big) {
            float mh = Math.min(12f, bandH * 0.38f);
            band.add(mh, (t, hh) -> {
                if (b.hasLogo()) {                       // logo on a white card so it shows on the dark band
                    float a = b.c.logoAspect(), lw = Math.min(b.innerW * 0.8f, (hh - 1.2f) * a), lh = lw / a;
                    b.c.rect(cx - lw / 2f - 1f, t + (hh - lh) / 2f - 0.6f, lw + 2f, lh + 1.2f, 1f, true, true, 0);
                    b.c.logo(cx - lw / 2f, t + (hh - lh) / 2f, lw, lh);
                    return;
                }
                String letter = b.letter();
                float pt = hh * 0.92f / (0.66f * PT), lw = b.c.serifWidth(letter, pt);
                b.c.serifText(letter, cx - lw / 2f, t + hh * 0.92f, pt, true);
            });
        }
        float np = b.prodPt * (big ? 1.5f : 1.35f);
        band.add(b.lh(np), (t, hh) -> b.serifBrand(b.upper(b.d.shop()), cx, t, b.innerW, np, true, !big));
        if (b.has(b.d.product())) band.add(b.lh(b.notePt * 1.1f), (t, hh) -> b.textCx(b.spacedFit(b.upper(b.d.product()), b.innerW, b.notePt * 1.1f),
                cx, t, b.innerW, b.notePt * 1.1f, false, true));
        band.layout(bandTop + 1f, bandH - 1f, b.gap);
        Stack s = new Stack(b);
        if (b.has(b.d.size())) s.add(b.lh(b.detPt * 1.25f) + 1.6f, (t, hh) -> b.sizePillAt(cx, t, hh, b.detPt * 1.25f));
        s.flex(b.barMin(), Math.min(b.barMax(), 12f), (t, hh) -> b.barcodeSpaced(t, hh, b.left, b.innerW));
        b.mrpBlock(s, cx, b.innerW, 1.25f);
        if (b.tall) b.columns(s, b.left, b.innerW);
        s.layout(bandH + b.gap, b.bottom, b.gap);
    }

    private static void premiumCircle(Box b) {
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float cx = b.w / 2f;
        Stack s = new Stack(b);
        if (big) s.add(Math.min(14f, b.h * 0.19f), (t, hh) -> b.circleMono(cx, t, hh));
        float np = b.prodPt * (big ? 1.5f : 1.25f);
        s.add(b.lh(np), (t, hh) -> b.serifBrand(b.upper(b.d.shop()), cx, t, b.innerW, np, false, !big));
        s.add(1.2f, (t, hh) -> {
            b.c.line(b.left + b.innerW * 0.2f, t + 0.2f, b.right - b.innerW * 0.2f, t + 0.2f, 0.3f, false);
            b.c.line(b.left + b.innerW * 0.3f, t + 1.0f, b.right - b.innerW * 0.3f, t + 1.0f, 0.15f, false);
        });
        s.flex(b.barMin(), Math.min(b.barMax(), 11f), (t, hh) -> b.barcodeSpaced(t, hh, b.left, b.innerW));
        float rowH = b.lh(b.mrpPt * 1.3f) + b.lh(b.notePt) + 1f;
        s.add(rowH, (t, hh) -> {
            float bx = b.left, bw = 0;
            if (b.has(b.d.size())) {
                bw = Math.min(hh * 1.2f, b.innerW * 0.32f);
                b.c.rect(bx, t, bw, hh, 1f, false, false, 0.4f);
                b.textCx(b.upper(b.d.sizeLabel()), bx + bw / 2f, t + 0.4f, bw - 1f, b.notePt * 0.95f, false, false);
                float sp = b.fitPt(b.d.size(), bw - 2f, (hh - b.lh(b.notePt)) / PT * 0.7f, 5f, true);
                b.textCx(b.d.size(), bx + bw / 2f, t + b.lh(b.notePt) + 0.2f, bw - 1f, sp, true, false);
            }
            if (b.has(b.d.mrp())) {
                float px = bx + bw + 2f, pw = b.right - px;
                b.textCx("MRP Rs. " + b.mrp2(), px + pw / 2f, t + 0.3f, pw, b.mrpPt * 1.3f, true, false);
                b.textCx("(Incl. of all taxes)", px + pw / 2f, t + b.lh(b.mrpPt * 1.3f) + 0.3f, pw, b.notePt, false, false);
            }
        });
        if (b.tall) b.columns(s, b.left, b.innerW);
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void premiumRibbon(Box b) {
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float cx = b.w / 2f;
        Stack s = new Stack(b);
        if (big) s.add(Math.min(11f, b.h * 0.15f), (t, hh) -> b.monogram(cx, t, hh));
        float np = b.prodPt * (big ? 1.55f : 1.3f);
        s.add(b.lh(np), (t, hh) -> b.serifBrand(b.upper(b.d.shop()), cx, t, b.innerW, np, false, !big));
        if (b.has(b.d.product())) s.add(b.lh(b.notePt * 1.2f), (t, hh) -> b.taglineAt(b.upper(b.d.product()), cx, t, b.innerW, b.notePt * 1.2f));
        s.flex(b.barMin(), Math.min(b.barMax(), 12f), (t, hh) -> b.barcodeSpaced(t, hh, b.left, b.innerW));
        if (b.has(b.d.mrp())) {
            float pt = b.mrpPt * 1.2f;
            s.add(b.lh(pt) + 2.4f, (t, hh) -> {
                b.c.rect(0, t, b.w, hh, 0, true, false, 0);
                b.textCx("MRP Rs. " + b.mrp2(), cx, t + 1.2f, b.innerW, pt, true, true);
            });
            s.add(b.lh(b.notePt), (t, hh) -> b.textC("(Incl. of all taxes)", t, b.notePt, false, false));
        }
        List<String> facts = new ArrayList<>();
        if (b.has(b.d.size())) facts.add(b.upper(b.d.sizeLabel()) + " " + b.d.size());
        if (b.has(b.d.colour())) facts.add(b.d.colour());
        if (b.has(b.d.style())) facts.add(b.d.style());
        if (!facts.isEmpty()) s.add(b.lh(b.detPt), (t, hh) -> b.textC(String.join("  |  ", facts), t, b.detPt, true, false));
        if (b.tall && b.has(b.d.phone())) s.add(b.lh(b.notePt * 1.1f), (t, hh) -> b.taglineC(b.d.phone(), t, b.notePt * 1.1f));
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void premiumSerif(Box b) {
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float cx = b.w / 2f;
        Stack s = new Stack(b);
        s.add(1.3f, (t, hh) -> b.doubleRule(t));
        float np = b.prodPt * (big ? 1.95f : 1.45f);
        s.add(b.lh(np), (t, hh) -> b.serifBrand(b.upper(b.d.shop()), cx, t, b.innerW, np, false, true));
        if (b.has(b.d.product())) s.add(b.lh(b.notePt * 1.2f), (t, hh) -> b.textC(b.spacedFit(b.upper(b.d.product()), b.innerW, b.notePt * 1.2f), t, b.notePt * 1.2f, false, false));
        s.add(1.3f, (t, hh) -> b.doubleRule(t));
        if (b.has(b.d.size())) s.add(b.lh(b.detPt * 1.25f) + 1.6f, (t, hh) -> b.sizePillAt(cx, t, hh, b.detPt * 1.25f));
        s.flex(b.barMin(), Math.min(b.barMax(), 12f), (t, hh) -> b.barcodeSpaced(t, hh, b.left, b.innerW));
        b.mrpBlock(s, cx, b.innerW, 1.25f);
        if (b.tall) b.columns(s, b.left, b.innerW);
        s.layout(b.top, b.bottom, b.gap * 1.1f);
    }

    private static void premiumStrip(Box b) {
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float sw = Math.min(big ? 11f : 8f, b.w * 0.18f);
        b.c.rect(0, 0, sw, b.h, 0, true, false, 0);
        if (b.hasLogo()) {                               // logo on a white square at the top of the strip
            float box = sw * 0.8f, bx = (sw - box) / 2f, by = b.top;
            b.c.rect(bx, by, box, box, 0.8f, true, true, 0);
            b.logoFit(sw / 2f, by + box * 0.1f, box * 0.8f, box * 0.8f);
        }
        String letter = b.hasLogo() ? "" : b.letter();
        float lpt = (sw * 0.8f) / (0.7f * PT);
        while (lpt > 4f && b.c.serifWidth(letter, lpt) > sw * 0.78f) lpt -= 0.5f;    // wide letters (M, W) fit the strip
        float lw = b.c.serifWidth(letter, lpt);
        if (!letter.isEmpty()) b.c.serifText(letter, (sw - lw) / 2f, b.top + sw * 0.85f, lpt, true);
        float x = sw + Math.max(1.8f, b.pad * 0.8f), mw = b.right - x, cx = x + mw / 2f;
        Stack s = new Stack(b);
        float np = b.prodPt * (big ? 1.5f : 1.25f);
        s.add(b.lh(np), (t, hh) -> b.serifC(b.upper(b.d.shop()), cx, t, mw, np));
        if (b.has(b.d.product())) s.add(b.lh(b.notePt * 1.2f), (t, hh) -> b.taglineAt(b.upper(b.d.product()), cx, t, mw, b.notePt * 1.2f));
        if (b.has(b.d.size())) s.add(b.lh(b.detPt * 1.25f) + 1.6f, (t, hh) -> b.sizePillAt(cx, t, hh, b.detPt * 1.25f));
        s.flex(b.barMin(), Math.min(b.barMax(), 12f), (t, hh) -> b.barcodeSpaced(t, hh, x, mw));
        b.mrpBlock(s, cx, mw, 1.2f);
        if (b.tall) b.columns(s, x, mw);
        s.layout(b.top, b.bottom, b.gap);
    }

    private static void premiumBadge(Box b) {
        boolean big = b.h >= 48f && !b.wide;          // 100 x 50: no monogram, barcode needs the height
        float cx = b.w / 2f;
        Stack s = new Stack(b);
        if (b.has(b.d.size())) {
            float d = Math.min(big ? 15f : 11f, (b.bottom - b.top) * 0.3f);
            s.add(d, (t, hh) -> {
                b.circleFill(cx, t + hh / 2f, hh / 2f);
                b.textCx(b.upper(b.d.sizeLabel()), cx, t + hh * 0.2f, hh * 0.8f, b.notePt, false, true);
                float sp = b.fitPt(b.d.size(), hh * 0.75f, hh * 0.42f / PT, 5f, true);
                b.textCx(b.d.size(), cx, t + hh * 0.2f + b.lh(b.notePt), hh * 0.75f, sp, true, true);
            });
        }
        float np = b.prodPt * (big ? 1.5f : 1.25f);
        s.add(b.lh(np), (t, hh) -> b.serifBrand(b.upper(b.d.shop()), cx, t, b.innerW, np, false, true));
        if (b.has(b.d.product())) s.add(b.lh(b.notePt * 1.2f), (t, hh) -> b.taglineAt(b.upper(b.d.product()), cx, t, b.innerW, b.notePt * 1.2f));
        s.flex(b.barMin(), Math.min(b.barMax(), 12f), (t, hh) -> b.barcodeSpaced(t, hh, b.left, b.innerW));
        if (b.has(b.d.mrp())) {
            float pt = b.mrpPt * 1.15f;
            s.add(b.lh(pt) + 1.8f, (t, hh) -> b.blackPill("MRP Rs. " + b.mrp2(), cx, t, hh, b.innerW, pt, true));
            s.add(b.lh(b.notePt), (t, hh) -> b.textC("(Incl. of all taxes)", t, b.notePt, false, false));
        }
        if (b.tall) b.columns(s, b.left, b.innerW);
        s.layout(b.top, b.bottom, b.gap);
    }

    // ------------------------------------------------------------------ layout helpers

    static final float PT = 0.3528f;                 // 1 point in mm
    static final float DOT = 25.4f / 203f;           // one dot of a 203 dpi label printer

    /** sizes and margins worked out once for a tag */
    private static final class Box {
        final TagCanvas c; final TagData d; final float w, h;
        final boolean small, tall, wide;
        final float pad, left, right, top, bottom, innerW, gap;
        final float shopPt, prodPt, detPt, codePt, mrpPt, notePt;

        Box(TagCanvas c, float w, float h, TagData d) {
            this.c = c; this.w = w; this.h = h; this.d = d;
            small = h < 30f || w < 42f;
            tall = h > w * 1.3f;
            wide = w > h * 1.6f && h >= 40f;
            float k = Math.max(0.62f, Math.min(1.45f, Math.min(w / 50f, tall ? w / 50f * 1.12f : h / 38f)));
            if (small) k *= 1.08f;
            pad = Math.max(1.5f, Math.min(3.2f, Math.min(w, h) * 0.055f));
            left = pad; right = w - pad; innerW = right - left;
            top = pad + (tall ? 6.5f : 0f);
            bottom = h - pad;
            gap = Math.max(0.35f, Math.min(1.4f, h * 0.018f));
            shopPt = 6.6f * k; prodPt = 8.4f * k; detPt = 6.2f * k; codePt = Math.max(4.3f, 5.4f * k);
            mrpPt = 8.8f * k; notePt = Math.max(3.8f, 4.5f * k);
            if (tall) {                                   // hang tag: hole to put the thread through
                c.circle(w / 2f, pad + 2.4f, 1.6f, 0.3f);
            }
        }

        boolean has(String s) { return s != null && !s.isBlank(); }
        String upper(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT); }
        String spaced(String s) {
            StringBuilder sb = new StringBuilder();
            for (char ch : s.toCharArray()) { if (sb.length() > 0) sb.append(' '); sb.append(ch); }
            return c.textWidth(sb.toString(), shopPt, true) <= innerW ? sb.toString() : s;
        }
        float lh(float pt) { return pt * PT * 1.22f; }

        /** "Shirt" or, for small tags, "Shirt - M" */
        String productLine(boolean withSize) {
            String p = has(d.product()) ? d.product() : "";
            return withSize && has(d.size()) ? p + " - " + d.size() : p;
        }
        String sizeColour(boolean labels) {
            List<String> parts = new ArrayList<>();
            if (has(d.size())) parts.add(labels ? d.sizeLabel() + ": " + d.size() : d.sizeLabel() + " " + d.size());
            if (has(d.colour())) parts.add(d.colour());
            return String.join("  |  ", parts);
        }
        /** "Size: M  |  Navy Blue  |  MV-204" */
        String details() {
            List<String> parts = new ArrayList<>();
            if (has(d.size())) parts.add(d.sizeLabel() + ": " + d.size());
            if (has(d.colour())) parts.add(d.colour());
            if (has(d.style())) parts.add(d.style());
            return String.join("  |  ", parts);
        }

        /** "1,499" -> "1,499.00" (the MRP block of brand tags always shows paise) */
        String mrp2() {
            String m = d.mrp() == null ? "" : d.mrp();
            return m.contains(".") ? m : m + ".00";
        }
        /** label / value rows of the MRP designs (empty values are left out) */
        List<String[]> rows(boolean all) { return rows(all, true); }
        List<String[]> rows(boolean all, boolean withSize) {
            List<String[]> r = new ArrayList<>();
            if (all && has(d.style())) r.add(new String[]{d.styleLabel(), d.style()});
            if (withSize && has(d.size())) r.add(new String[]{d.sizeLabel(), d.size()});
            if (has(d.colour())) r.add(new String[]{d.colourLabel(), d.colour()});
            if (has(d.product())) r.add(new String[]{"Product", d.product()});
            r.add(new String[]{"Net Quantity", "1 Number"});
            return r;
        }
        void row(String label, String value, float top, float labelW, float pt) { rowAt(label, value, left, top, labelW, innerW, pt); }
        void rowAt(String label, String value, float x, float top, float labelW, float maxW, float pt) {
            textL(label, x, top, labelW - 1f, pt, false, false);
            textL(value, x + labelW, top, maxW - labelW, pt, true, false);
        }
        /** text broken into lines that fit maxW (at most maxLines, the last one cut with "..") */
        List<String> wrap(String s, float maxW, float pt, boolean bold, int maxLines) {
            List<String> out = new ArrayList<>();
            if (!has(s)) return out;
            String line = "";
            for (String word : s.trim().split("\\s+")) {
                String next = line.isEmpty() ? word : line + " " + word;
                if (c.textWidth(next, pt, bold) <= maxW || line.isEmpty()) {
                    line = next;
                } else {
                    out.add(line);
                    line = word;
                    if (out.size() == maxLines) break;
                }
            }
            if (out.size() < maxLines && !line.isEmpty()) out.add(line);
            if (out.size() == maxLines) {
                String last = out.get(maxLines - 1);
                out.set(maxLines - 1, clip(last, maxW, pt, bold));
            }
            return out;
        }
        /** "Marketed by" block: shop, address, customer care */
        void legal(Stack s, boolean centred) { legalAt(s, left, innerW, centred); }
        void legalAt(Stack s, float x, float maxW) { legalAt(s, x, maxW, false); }
        void legalAt(Stack s, float x, float maxW, boolean centred) {
            float pt = notePt;
            if (has(d.shop())) s.add(lh(pt), (top, hh) -> line("Marketed by: " + d.shop(), x, maxW, top, pt, true, centred));
            for (String ln : wrap(d.address(), maxW, pt, false, tall ? 3 : 2)) s.add(lh(pt), (top, hh) -> line(ln, x, maxW, top, pt, false, centred));
            String care = String.join("  |  ", java.util.stream.Stream.of(d.phone(), d.email()).filter(this::has).toList());
            if (has(care)) s.add(lh(pt), (top, hh) -> line("Customer care: " + care, x, maxW, top, pt, false, centred));
        }
        private void line(String t, float x, float maxW, float top, float pt, boolean bold, boolean centred) {
            if (centred) textCx(t, x + maxW / 2f, top, maxW, pt, bold, false);
            else textL(t, x, top, maxW, pt, bold, false);
        }

        String letter() {
            String name = has(d.shop()) ? d.shop().trim() : "S";
            for (char ch : name.toCharArray()) if (Character.isLetterOrDigit(ch)) return String.valueOf(ch).toUpperCase(Locale.ROOT);
            return "S";
        }
        boolean hasLogo() { return c.logoAspect() > 0f; }
        /** the shop's logo, as big as fits in maxW x hh, centred on cx */
        void logoFit(float cx, float top, float maxW, float hh) {
            float a = c.logoAspect();
            float lw = Math.min(maxW, hh * a), lh = lw / a;
            c.logo(cx - lw / 2f, top + (hh - lh) / 2f, lw, lh);
        }
        /** height of the logo put beside a shop-name line of size pt (0 when the shop has no logo) */
        float logoH(float pt) { return hasLogo() ? lh(pt) * 1.45f : 0f; }
        /** row height of a shop-name line, taller when the logo sits beside it */
        float brandH(float pt) { return Math.max(lh(pt), logoH(pt)); }
        /** shop logo + name on one line, centred on cx; card = white card behind the logo (for black bands) */
        void brandCx(String name, float cx, float top, float maxW, float pt, boolean bold, boolean white, boolean card) {
            if (!hasLogo()) { textCx(name, cx, top, maxW, pt, bold, white); return; }
            float[] g = brandGeom(name, maxW, pt, bold);
            brandAt(name, cx - g[0] / 2f, top, pt, bold, white, card, g);
        }
        void brandC(String name, float top, float pt, boolean bold, boolean white) { brandCx(name, w / 2f, top, innerW, pt, bold, white, false); }
        /** left-aligned shop logo + name; returns the width used */
        float brandL(String name, float x, float top, float maxW, float pt, boolean bold, boolean white) {
            if (!hasLogo()) return textL(name, x, top, maxW, pt, bold, white);
            float[] g = brandGeom(name, maxW, pt, bold);
            brandAt(name, x, top, pt, bold, white, false, g);
            return g[0];
        }
        /** {total width, logo width, logo height, gap, text pt} */
        private float[] brandGeom(String name, float maxW, float pt, boolean bold) {
            float a = c.logoAspect(), lw = Math.min(logoH(pt) * a, maxW * 0.4f), lhh = lw / a;
            float gap = has(name) ? Math.max(0.8f, lh(pt) * 0.35f) : 0f, room = maxW - lw - gap;
            float p = has(name) ? fitPt(name, room, pt, Math.max(3.8f, pt * 0.72f), bold) : pt;
            float tw = has(name) ? c.textWidth(clip(name, room, p, bold), p, bold) : 0f;
            return new float[]{lw + gap + tw, lw, lhh, gap, p, room};
        }
        private void brandAt(String name, float x, float top, float pt, boolean bold, boolean white, boolean card, float[] g) {
            float rowH = brandH(pt), lw = g[1], lhh = g[2], ly = top + (rowH - lhh) / 2f;
            if (card) c.rect(x - 0.7f, ly - 0.5f, lw + 1.4f, lhh + 1f, 0.6f, true, true, 0);
            c.logo(x, ly, lw, lhh);
            if (!has(name)) return;
            float p = g[4], ty = top + (rowH - lh(pt)) / 2f;
            c.text(clip(name, g[5], p, bold), x + lw + g[3], base(ty, pt) - (pt - p) * PT * 0.45f, p, bold, white);
        }
        /** shop's logo, or its first letter in serif, inside a thin circle */
        void circleMono(float cx, float top, float hh) {
            float r = hh / 2f;
            c.circle(cx, top + r, r, 0.35f);
            c.circle(cx, top + r, r - 0.8f, 0.15f);
            if (hasLogo()) { float side = (r - 0.8f) * 1.35f; logoFit(cx, top + r - side / 2f, side, side); return; }
            String letter = letter();
            float pt = r * 1.0f / (0.66f * PT), lw = c.serifWidth(letter, pt);
            c.serifText(letter, cx - lw / 2f, top + r + r * 0.48f, pt, false);
        }
        void circleFill(float cx, float cy, float r) {
            int n = 48;
            float[] xy = new float[n * 2];
            for (int i = 0; i < n; i++) {
                double a = 2 * Math.PI * i / n;
                xy[i * 2] = cx + (float) Math.cos(a) * r;
                xy[i * 2 + 1] = cy + (float) Math.sin(a) * r;
            }
            c.poly(xy, true, 0);
        }
        /** white bold text on a black (rounded) bar */
        void blackPill(String text, float cx, float top, float hh, float maxW, float pt, boolean round) {
            float tw = Math.min(maxW, c.textWidth(text, pt, true) + 6f);
            c.rect(cx - tw / 2f, top, tw, hh, round ? hh / 2f : 0.6f, true, false, 0);
            textCx(text, cx, top + (hh - lh(pt)) / 2f, tw - 2f, pt, true, true);
        }
        /** "———  S H I R T  ———" centred on cx inside maxW */
        void taglineAt(String s, float cx, float top, float maxW, float pt) {
            String t = spacedFit(s, maxW * 0.7f, pt);
            float tw = Math.min(c.textWidth(t, pt, false), maxW * 0.7f);
            textCx(t, cx, top, maxW, pt, false, false);
            float y = top + lh(pt) * 0.5f, gap = 1.5f, l = cx - maxW / 2f, r = cx + maxW / 2f;
            if (cx - tw / 2f - gap - l > 2f) {
                c.line(l, y, cx - tw / 2f - gap, y, 0.22f, false);
                c.line(cx + tw / 2f + gap, y, r, y, 0.22f, false);
            }
        }
        void doubleRule(float top) {
            c.line(left, top + 0.2f, right, top + 0.2f, 0.4f, false);
            c.line(left, top + 1.0f, right, top + 1.0f, 0.15f, false);
        }
        /** "MRP Rs. 599.00" + "(Incl. of all taxes)" */
        void mrpBlock(Stack s, float cx, float maxW, float scale) {
            if (!has(d.mrp())) return;
            float mp = mrpPt * scale;
            s.add(lh(mp), (t, hh) -> textCx("MRP Rs. " + mrp2(), cx, t, maxW, mp, true, false));
            s.add(lh(notePt * 1.15f), (t, hh) -> textCx("(Incl. of all taxes)", cx, t, maxW, notePt * 1.15f, false, false));
        }
        /** COLOUR | STYLE | QTY columns (hang tags) */
        void columns(Stack s, float x, float maxW) {
            List<String[]> cols = new ArrayList<>();
            if (has(d.colour())) cols.add(new String[]{upper(d.colourLabel()), d.colour()});
            if (has(d.style())) cols.add(new String[]{upper(d.styleLabel()), d.style()});
            cols.add(new String[]{"QTY", "1 N"});
            float lp = notePt, vp = notePt * 1.25f;
            s.add(lh(lp) + lh(vp) + 0.4f, (top, hh) -> {
                float cw = maxW / cols.size();
                for (int i = 0; i < cols.size(); i++) {
                    float ccx = x + cw * i + cw / 2f;
                    textCx(cols.get(i)[0], ccx, top, cw - 1.5f, lp, false, false);
                    textCx(cols.get(i)[1], ccx, top + lh(lp) + 0.4f, cw - 1.5f, vp, true, false);
                    if (i > 0) c.line(x + cw * i, top, x + cw * i, top + hh, 0.2f, false);
                }
            });
        }
        /** shop's first letter, big serif, with a swoosh through it */
        void monogram(float cx, float top, float hh) {
            if (hasLogo()) { logoFit(cx, top, Math.min(innerW, hh * 3.2f), hh); return; }      // the shop's own logo
            String name = has(d.shop()) ? d.shop().trim() : "S";
            String letter = "S";
            for (char ch : name.toCharArray()) if (Character.isLetterOrDigit(ch)) { letter = String.valueOf(ch).toUpperCase(Locale.ROOT); break; }
            float pt = hh * 0.92f / (0.66f * PT);                       // cap height of Times is about 0.66 em
            float lw = c.serifWidth(letter, pt);
            c.serifText(letter, cx - lw / 2f, top + hh * 0.92f, pt, false);
            // swoosh: thin crescent from lower left to upper right
            int n = 22;
            float ax = cx - lw * 1.05f, ay = top + hh * 1.08f, qx = cx - lw * 0.05f, qy = top + hh * 0.78f, bx = cx + lw * 1.2f, by = top + hh * 0.52f;
            float thick = hh * 0.09f;
            float[] up = new float[(n + 1) * 2], lo = new float[(n + 1) * 2];
            for (int i = 0; i <= n; i++) {
                float t = (float) i / n, u = 1 - t;
                float x = u * u * ax + 2 * u * t * qx + t * t * bx, y = u * u * ay + 2 * u * t * qy + t * t * by;
                float dx = 2 * u * (qx - ax) + 2 * t * (bx - qx), dy = 2 * u * (qy - ay) + 2 * t * (by - qy);
                float len = (float) Math.sqrt(dx * dx + dy * dy), nx = -dy / len, ny = dx / len;
                float th = thick * (float) Math.sin(Math.PI * Math.pow(t, 0.8)) * 0.5f;
                up[i * 2] = x - nx * th; up[i * 2 + 1] = y - ny * th;
                lo[i * 2] = x + nx * th; lo[i * 2 + 1] = y + ny * th;
            }
            float[] shape = new float[(n + 1) * 4];
            for (int i = 0; i <= n; i++) { shape[i * 2] = up[i * 2]; shape[i * 2 + 1] = up[i * 2 + 1]; }
            for (int i = 0; i <= n; i++) { int j = n - i; shape[(n + 1) * 2 + i * 2] = lo[j * 2]; shape[(n + 1) * 2 + i * 2 + 1] = lo[j * 2 + 1]; }
            c.poly(shape, true, 0);
        }
        /** bold serif text centred on cx, shrunk to fit */
        void serifC(String s, float cx, float top, float maxW, float pt) { serifC(s, cx, top, maxW, pt, false); }
        void serifC(String s, float cx, float top, float maxW, float pt, boolean white) {
            if (!has(s)) return;
            float p = pt;
            while (p > 4f && c.serifWidth(s, p) > maxW) p -= 0.25f;
            String t = s;
            while (t.length() > 2 && c.serifWidth(t, p) > maxW) t = t.substring(0, t.length() - 1);
            c.serifText(t, cx - c.serifWidth(t, p) / 2f, base(top, pt) - (pt - p) * PT * 0.45f, p, white);
        }
        /**
         * shop name in serif with the logo just before it, centred on cx; used where the design shows no big logo.
         * On a dark band (white text) the logo sits on a small white card. withLogo = false prints the name alone.
         */
        void serifBrand(String s, float cx, float top, float maxW, float pt, boolean white, boolean withLogo) {
            if (!withLogo || !hasLogo()) { serifC(s, cx, top, maxW, pt, white); return; }
            float a = c.logoAspect(), lhh = lh(pt) * (white ? 0.86f : 0.98f), lw = Math.min(lhh * a, maxW * 0.35f);
            lhh = lw / a;
            float gap = has(s) ? Math.max(1f, lh(pt) * 0.3f) : 0f, room = maxW - lw - gap - (white ? 1.4f : 0f);
            float p = pt;
            String t = has(s) ? s : "";
            while (p > 4f && has(t) && c.serifWidth(t, p) > room) p -= 0.25f;
            while (t.length() > 2 && c.serifWidth(t, p) > room) t = t.substring(0, t.length() - 1);
            float tw = has(t) ? c.serifWidth(t, p) : 0f, x = cx - (lw + gap + tw) / 2f, ly = top + (lh(pt) - lhh) / 2f;
            if (white) c.rect(x - 0.6f, ly - 0.4f, lw + 1.2f, lhh + 0.8f, 0.6f, true, true, 0);
            c.logo(x, ly, lw, lhh);
            if (has(t)) c.serifText(t, x + lw + gap, base(top, pt) - (pt - p) * PT * 0.45f, p, white);
        }
        /** "———  S H I R T  ———" centred */
        void taglineC(String s, float top, float pt) {
            String t = spacedFit(s, innerW * 0.7f, pt);
            float tw = c.textWidth(t, pt, false);
            float tw2 = Math.min(tw, innerW * 0.7f);
            textC(t, top, pt, false, false);
            float y = top + lh(pt) * 0.5f, gap = 1.5f;
            if (w / 2f - tw2 / 2f - gap - left > 2f) {
                c.line(left, y, w / 2f - tw2 / 2f - gap, y, 0.22f, false);
                c.line(w / 2f + tw2 / 2f + gap, y, right, y, 0.22f, false);
            }
        }
        String spacedFit(String s, float maxW, float pt) {
            StringBuilder sb = new StringBuilder();
            for (char ch : s.toCharArray()) { if (sb.length() > 0) sb.append(' '); sb.append(ch); }
            return c.textWidth(sb.toString(), pt, false) <= maxW ? sb.toString() : s;
        }
        void sizePill(float top, float hh, float pt) { sizePillAt(w / 2f, top, hh, pt); }
        void sizePillAt(float cx, float top, float hh, float pt) {
            String label = upper(d.sizeLabel()) + " : ", val = d.size();
            float lw = c.textWidth(label, pt * 0.8f, false), vw = c.textWidth(val, pt, true);
            float pw = Math.min(innerW, lw + vw + 5f);
            c.rect(cx - pw / 2f, top, pw, hh, hh / 2f, false, false, 0.3f);
            float x = cx - (lw + vw) / 2f, base = top + hh / 2f + pt * PT * 0.36f;
            c.text(label, x, base, pt * 0.8f, false, false);
            c.text(val, x + lw, base, pt, true, false);
        }
        /** bars + the barcode digits spread out under them */
        void barcodeSpaced(float top, float hh, float x, float maxW) {
            float th = lh(codePt * 1.1f);
            float bh = Math.max(2.5f, hh - th);
            bars(top, bh, x, maxW);
            String code = spacedFit(d.barcode(), maxW * 0.95f, codePt * 1.1f);
            textCx(code, x + maxW / 2f, top + bh + 0.2f, maxW, codePt * 1.1f, false, false);
        }

        float fitPt(String s, float maxW, float pt, float minPt, boolean bold) {
            float p = pt;
            while (p > minPt && c.textWidth(s, p, bold) > maxW) p -= 0.25f;
            return p;
        }
        String clip(String s, float maxW, float pt, boolean bold) {
            if (s == null) return "";
            if (c.textWidth(s, pt, bold) <= maxW) return s;
            String t = s;
            while (t.length() > 1 && c.textWidth(t + "..", pt, bold) > maxW) t = t.substring(0, t.length() - 1);
            return t.trim() + "..";
        }
        /** baseline for a line whose box starts at top */
        float base(float top, float pt) { return top + pt * PT * 0.95f; }

        void textC(String s, float top, float pt, boolean bold, boolean white) { textCx(s, w / 2f, top, innerW, pt, bold, white); }
        void textCx(String s, float cx, float top, float maxW, float pt, boolean bold, boolean white) {
            if (!has(s)) return;
            float p = fitPt(s, maxW, pt, Math.max(3.8f, pt * 0.72f), bold);
            String t = clip(s, maxW, p, bold);
            c.text(t, cx - c.textWidth(t, p, bold) / 2f, base(top, pt) - (pt - p) * PT * 0.45f, p, bold, white);
        }
        /** returns the width used */
        float textL(String s, float x, float top, float maxW, float pt, boolean bold, boolean white) {
            if (!has(s) || maxW <= 1f) return 0;
            float p = fitPt(s, maxW, pt, Math.max(3.8f, pt * 0.72f), bold);
            String t = clip(s, maxW, p, bold);
            c.text(t, x, base(top, pt) - (pt - p) * PT * 0.45f, p, bold, white);
            return c.textWidth(t, p, bold);
        }
        float textR(String s, float xr, float top, float maxW, float pt, boolean bold, boolean white) {
            if (!has(s) || maxW <= 1f) return 0;
            float p = fitPt(s, maxW, pt, Math.max(3.8f, pt * 0.72f), bold);
            String t = clip(s, maxW, p, bold);
            float tw = c.textWidth(t, p, bold);
            c.text(t, xr - tw, base(top, pt) - (pt - p) * PT * 0.45f, p, bold, white);
            return tw;
        }

        float barMin() { return Math.max(4.5f, h * 0.16f); }
        float barMax() { return Math.max(8f, Math.min(h * 0.42f, 22f)); }

        /** bars + the barcode text under them, inside top..top+hh */
        void barcodeWithText(float top, float hh, float x, float maxW) {
            float th = lh(codePt);
            bars(top, Math.max(2.5f, hh - th), x, maxW);
            textCx(d.barcode(), x + maxW / 2f, top + Math.max(2.5f, hh - th) + 0.15f, maxW, codePt, false, false);
        }
        /** Code 128 bars centred in x..x+maxW; bar width snapped to whole printer dots so it scans well */
        void bars(float top, float hh, float x, float maxW) {
            int[] widths = code128(d.barcode());
            int modules = 0;
            for (int v : widths) modules += v;
            // widest whole-dot bar that still leaves at least 4 modules of white on each side
            float m = 0;
            for (int k = 3; k >= 1; k--) {
                if ((modules + 8) * k * DOT <= maxW) { m = k * DOT; break; }
            }
            if (m == 0) m = maxW / (modules + 8);          // very narrow space: as wide as fits
            float bw = modules * m;
            c.bars(x + (maxW - bw) / 2f, top, hh, m, widths);
        }
    }

    private interface Part { void draw(float top, float height); }

    /** items one under the other; the flex item (barcode) takes the free height, spare height is spread evenly */
    private static final class Stack {
        private final List<Float> heights = new ArrayList<>();
        private final List<Part> parts = new ArrayList<>();
        private int flex = -1;
        private float flexMin, flexMax;
        Stack(Box b) {}

        Stack add(float h, Part p) { heights.add(h); parts.add(p); return this; }
        Stack flex(float min, float max, Part p) { flex = parts.size(); flexMin = min; flexMax = max; heights.add(0f); parts.add(p); return this; }

        void layout(float top, float bottom, float gap) {
            int n = parts.size();
            if (n == 0) return;
            float fixed = gap * (n - 1);
            for (int i = 0; i < n; i++) if (i != flex) fixed += heights.get(i);
            float avail = bottom - top - fixed;
            float fh = 0;
            if (flex >= 0) fh = Math.max(Math.min(avail, flexMax), Math.min(flexMin, Math.max(avail, 2.5f)));
            float spare = Math.max(0, avail - fh);
            float extra = spare / (n + 1);
            float y = top + extra;
            for (int i = 0; i < n; i++) {
                float hh = i == flex ? fh : heights.get(i);
                parts.get(i).draw(y, hh);
                y += hh + gap + extra;
            }
        }
    }

    // ------------------------------------------------------------------ Code 128

    /** Helvetica (WinAnsi) can only print Latin-1: anything else becomes '?' */
    static String latin(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) sb.append((ch >= 32 && ch <= 126) || (ch >= 160 && ch <= 255) ? ch : '?');
        return sb.toString();
    }

    private static final String[] C128 = {
        "212222", "222122", "222221", "121223", "121322", "131222", "122213", "122312", "132212", "221213",
        "221312", "231212", "112232", "122132", "122231", "113222", "123122", "123221", "223211", "221132",
        "221231", "213212", "223112", "312131", "311222", "321122", "321221", "312212", "322112", "322211",
        "212123", "212321", "232121", "111323", "131123", "131321", "112313", "132113", "132311", "211313",
        "231113", "231311", "112133", "112331", "132131", "113123", "113321", "133121", "313121", "211331",
        "231131", "213113", "213311", "213131", "311123", "311321", "331121", "312113", "312311", "332111",
        "314111", "221411", "431111", "111224", "111422", "121124", "121421", "141122", "141221", "112214",
        "112412", "122114", "122411", "142112", "142211", "241211", "221114", "413111", "241112", "134111",
        "111242", "121142", "121241", "114212", "124112", "124211", "411212", "421112", "421211", "212141",
        "214121", "412121", "111143", "111341", "131141", "114113", "114311", "411113", "411311", "113141",
        "114131", "311141", "411131", "211412", "211214", "211232", "2331112"
    };

    private static final int CODE_C = 99, CODE_B = 100, START_B = 104, START_C = 105, STOP = 106;

    /**
     * Module widths of the whole symbol (start, data, checksum, stop). Runs of digits are packed two per symbol
     * (Code C), the rest uses Code B, so the barcode stays short and its bars stay wide enough on small labels.
     */
    static int[] code128(String text) {
        String s = latin(text);
        List<Integer> codes = new ArrayList<>();
        int i = 0, n = s.length();
        boolean c = digits(s, 0) >= 4 && digits(s, 0) % 2 == 0 || digits(s, 0) == n && n >= 2 && n % 2 == 0;
        codes.add(c ? START_C : START_B);
        while (i < n) {
            if (c) {
                if (digits(s, i) >= 2) {
                    codes.add((s.charAt(i) - '0') * 10 + (s.charAt(i + 1) - '0'));
                    i += 2;
                    continue;
                }
                codes.add(CODE_B);
                c = false;
            }
            int run = digits(s, i);
            if ((run >= 4 && i + run == n) || run >= 6) {
                if (run % 2 == 1) {
                    codes.add(s.charAt(i) - 32);
                    i++;
                }
                codes.add(CODE_C);
                c = true;
                continue;
            }
            int ch = s.charAt(i);
            codes.add((ch >= 32 && ch <= 127) ? ch - 32 : '?' - 32);
            i++;
        }
        long sum = codes.get(0);
        for (int k = 1; k < codes.size(); k++) sum += (long) codes.get(k) * k;
        codes.add((int) (sum % 103));
        codes.add(STOP);
        StringBuilder widths = new StringBuilder();
        for (int code : codes) widths.append(C128[code]);
        int[] out = new int[widths.length()];
        for (int k = 0; k < out.length; k++) out[k] = widths.charAt(k) - '0';
        return out;
    }

    private static int digits(String s, int from) {
        int k = from;
        while (k < s.length() && s.charAt(k) >= '0' && s.charAt(k) <= '9') k++;
        return k - from;
    }
}
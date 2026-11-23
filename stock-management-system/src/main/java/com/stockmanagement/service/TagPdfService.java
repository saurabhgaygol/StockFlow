package com.stockmanagement.service;

import com.stockmanagement.entity.TagDesignChoice;
import com.stockmanagement.entity.TagPrintSetting;
import com.stockmanagement.entity.RetailShopLogo;
import com.stockmanagement.repository.RetailShopProfileRepository;
import com.stockmanagement.repository.TagDesignChoiceRepository;
import com.stockmanagement.repository.TagPrintSettingRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tag PDFs and design previews.
 *
 *  - Every company has one printer it prints with (tag_print_setting) and, for every printer, the tag design it chose
 *    on the Designs page (tag_design_choice). A printer without a choice prints in Classic. Choices stay until someone
 *    in the shop changes them, and every PDF is made with the design chosen for its printer.
 *  - Label printers: one tag per page, page size = sticker size. A4: 21 tags per sheet with light cut lines.
 *  - The design itself is drawn by {@link TagDesigns}; this class only gives it a PDF (or picture) to draw on.
 */
@Service
public class TagPdfService {

    private static final Logger log = LoggerFactory.getLogger(TagPdfService.class);

    private static final float MM = 72f / 25.4f;

    private final RetailShopProfileRepository profileRepo;
    private final TagPrintSettingRepository settingRepo;
    private final TagDesignChoiceRepository choiceRepo;
    private final RetailLogoService logoService;
    private final TagStudioService tagStudioService;

    public TagPdfService(RetailShopProfileRepository profileRepo, TagPrintSettingRepository settingRepo,
                         TagDesignChoiceRepository choiceRepo, RetailLogoService logoService,
                         TagStudioService tagStudioService) {
        this.profileRepo = profileRepo;
        this.settingRepo = settingRepo;
        this.choiceRepo = choiceRepo;
        this.logoService = logoService;
        this.tagStudioService = tagStudioService;
    }

    // ------------------------------------------------------------------ shop logo
    // The logo is the one saved in Shop profile (Settings > Shop features), the same one printed on bills.
    // It is trimmed and made at most 600 px once, and kept in memory until the shop changes it.

    private static final int LOGO_MAX_PX = 600;
    private record CachedLogo(Object version, BufferedImage image) {}
    private final Map<String, CachedLogo> logoCache = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public boolean hasLogo(String company) {
        return logoService.has(company);
    }

    /** the shop profile logo ready for tags (blank margins cut), or null when the shop has none */
    @Transactional(readOnly = true)
    public BufferedImage logoImage(String company) {
        Optional<RetailShopLogo> row = logoService.get(company);
        if (row.isEmpty()) {
            logoCache.remove(company);
            return null;
        }
        RetailShopLogo l = row.get();
        Object version = l.getUpdatedAt() != null ? l.getUpdatedAt() : (long) l.getImageData().length;
        CachedLogo c = logoCache.get(company);
        if (c != null && c.version().equals(version)) return c.image();
        BufferedImage img = null;
        try {
            BufferedImage in = ImageIO.read(new ByteArrayInputStream(l.getImageData()));
            if (in != null) img = shrink(trim(toArgb(in)), LOGO_MAX_PX);
        } catch (IOException | RuntimeException e) {
            log.warn("Shop logo of {} could not be read for tags", company, e);
        }
        if (img != null) logoCache.put(company, new CachedLogo(version, img));
        return img;
    }

    /**
     * Changes when the company, its shop details or its logo change. Added to the preview picture links so a
     * browser never shows the pictures of another company (or old ones) from its cache.
     */
    @Transactional(readOnly = true)
    public String previewKey(String company) {
        Object logoVersion = logoService.get(company).map(l -> (Object) l.getUpdatedAt()).orElse("none");
        return Integer.toHexString(java.util.Objects.hash(company, shop(company), logoVersion));
    }

    /** the trimmed logo as PNG, for the Designs page */
    @Transactional(readOnly = true)
    public Optional<byte[]> logoPng(String company) {
        BufferedImage img = logoImage(company);
        if (img == null) return Optional.empty();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            return Optional.empty();
        }
        return Optional.of(out.toByteArray());
    }

    private static BufferedImage toArgb(BufferedImage in) {
        BufferedImage out = new BufferedImage(in.getWidth(), in.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(in, 0, 0, null);
        g.dispose();
        return out;
    }

    /** cuts transparent and near-white margins */
    private static BufferedImage trim(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight(), x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y), a = (p >>> 24) & 0xFF, r = (p >> 16) & 0xFF, gr = (p >> 8) & 0xFF, b = p & 0xFF;
                boolean blank = a < 16 || (r > 242 && gr > 242 && b > 242);
                if (!blank) {
                    if (x < x0) x0 = x;
                    if (y < y0) y0 = y;
                    if (x > x1) x1 = x;
                    if (y > y1) y1 = y;
                }
            }
        }
        if (x1 < 0) return img;
        return img.getSubimage(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private static BufferedImage shrink(BufferedImage img, int max) {
        int w = img.getWidth(), h = img.getHeight();
        double k = Math.min(1.0, (double) max / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------------ print setting (per company)

    /** the printer the shop prints with, and the design chosen for it */
    public record Setting(TagDesigns.Printer printer, TagDesigns.Design design) {}

    @Transactional(readOnly = true)
    public Setting setting(String company) {
        TagDesigns.Printer p = settingRepo.findByCompanyName(company)
                .map(s -> TagDesigns.printer(s.getPrinterCode()))
                .orElse(TagDesigns.printer(TagDesigns.DEFAULT_PRINTER));
        return new Setting(p, designFor(company, p.code()));
    }

    /** design + colour chosen for one printer; hex null = black */
    public record Choice(TagDesigns.Design design, String hex) {
        public int ink() { return TagDesigns.rgb(hex); }
    }

    /** design chosen for this printer; none chosen yet = Classic. A4 has no designs of its own: it uses the 50 x 38 one */
    @Transactional(readOnly = true)
    public TagDesigns.Design designFor(String company, String printerCode) {
        return choiceFor(company, printerCode).design();
    }

    @Transactional(readOnly = true)
    public Choice choiceFor(String company, String printerCode) {
        TagDesigns.Printer p = TagDesigns.printer(printerCode);
        String key = p.sheet() ? TagDesigns.DEFAULT_PRINTER : p.code();
        return choiceRepo.findByCompanyNameAndPrinterCode(company, key)
                .map(TagPdfService::choice)
                .orElse(new Choice(TagDesigns.design(TagDesigns.DEFAULT_DESIGN), null));
    }

    private static Choice choice(TagDesignChoice c) {
        Object[] old = TagDesigns.oldColourDesign(c.getDesignCode());      // the six colour designs of before
        if (old != null) {
            return new Choice(TagDesigns.design((String) old[0]), c.getInkColor() != null ? TagDesigns.hex(c.getInkColor()) : (String) old[1]);
        }
        return new Choice(TagDesigns.design(c.getDesignCode()), TagDesigns.hex(c.getInkColor()));
    }

    /** printer code -> chosen design and colour, for every printer (Classic in black where nothing was chosen) */
    @Transactional(readOnly = true)
    public Map<String, Choice> choices(String company) {
        Map<String, Choice> m = new LinkedHashMap<>();
        for (TagDesigns.Printer p : TagDesigns.PRINTERS) m.put(p.code(), new Choice(TagDesigns.design(TagDesigns.DEFAULT_DESIGN), null));
        for (TagDesignChoice c : choiceRepo.findByCompanyName(company)) {
            if (m.containsKey(c.getPrinterCode())) m.put(c.getPrinterCode(), choice(c));
        }
        for (TagDesigns.Printer p : TagDesigns.PRINTERS) if (p.sheet()) m.put(p.code(), m.get(TagDesigns.DEFAULT_PRINTER));
        return m;
    }

    /** the printer the shop prints with */
    @Transactional
    public Setting savePrinter(String company, String printerCode, Long userId, String userName) {
        TagDesigns.Printer p = known(printerCode);
        TagPrintSetting s = settingRepo.findByCompanyName(company).orElseGet(() -> {
            TagPrintSetting n = new TagPrintSetting();
            n.setCompanyName(company);
            n.setDesignCode(TagDesigns.DEFAULT_DESIGN);
            return n;
        });
        s.setPrinterCode(p.code());
        s.setUpdatedByUserId(userId);
        s.setUpdatedByName(userName);
        settingRepo.save(s);
        return new Setting(p, designFor(company, p.code()));
    }

    /**
     * the design and/or colour used for every tag printed on this printer (null = keep as it is;
     * colour "#000000" = black)
     */
    @Transactional
    public Choice saveChoice(String company, String printerCode, String designCode, String colour, Long userId, String userName) {
        TagDesigns.Printer asked = known(printerCode);
        // A4 prints with the 50 x 38 design
        TagDesigns.Printer p = asked.sheet() ? TagDesigns.printer(TagDesigns.DEFAULT_PRINTER) : asked;
        if (designCode != null && TagDesigns.DESIGNS.stream().noneMatch(d -> d.code().equals(designCode))) {
            throw new IllegalArgumentException("Unknown tag design.");
        }
        if (colour != null && !colour.trim().matches("#?[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("Unknown colour.");
        }
        TagDesignChoice c = choiceRepo.findByCompanyNameAndPrinterCode(company, p.code()).orElseGet(() -> {
            TagDesignChoice n = new TagDesignChoice();
            n.setCompanyName(company);
            n.setPrinterCode(p.code());
            n.setDesignCode(TagDesigns.DEFAULT_DESIGN);
            return n;
        });
        Choice before = choice(c);
        c.setDesignCode(designCode != null ? designCode : before.design().code());   // old colour designs become their layout
        c.setInkColor(colour != null ? TagDesigns.hex(colour) : before.hex());
        c.setUpdatedByUserId(userId);
        c.setUpdatedByName(userName);
        choiceRepo.save(c);
        return choice(c);
    }

    private static TagDesigns.Printer known(String printerCode) {
        if (printerCode == null || TagDesigns.PRINTERS.stream().noneMatch(p -> p.code().equals(printerCode))) {
            throw new IllegalArgumentException("Unknown printer.");
        }
        return TagDesigns.printer(printerCode);
    }

    // ------------------------------------------------------------------ PDF

    /** tags in the given order (copies already repeated); printerCode null = the company's printer */
    public byte[] pdf(String company, TagBatchService.BatchDetail d, List<TagBatchService.TagView> tags, String printerCode)
            throws IOException {
        return pdf(company, d, tags, printerCode, false);
    }

    /** blank edge left on an A4 sheet (most office printers cannot print the last 4-5 mm) */
    public static final float A4_MARGIN = 6f;

    /** empty space between two tags on an A4 sheet, so each one can be cut out on its own line */
    public static final float A4_GAP = 3f;

    /**
     * How tags of one size sit on an A4 sheet: {columns, rows, landscape 1/0}. The way round that fits more tags wins
     * (portrait when equal).
     */
    public static int[] a4Grid(float w, float h) {
        float g = A4_GAP;
        int pc = (int) ((210f - 2 * A4_MARGIN + g) / (w + g)), pr = (int) ((297f - 2 * A4_MARGIN + g) / (h + g));
        int lc = (int) ((297f - 2 * A4_MARGIN + g) / (w + g)), lr = (int) ((210f - 2 * A4_MARGIN + g) / (h + g));
        return lc * lr > pc * pr ? new int[]{lc, lr, 1} : new int[]{pc, pr, 0};
    }

    /**
     * a4 = true: the tags keep the size of the chosen printer but are laid out side by side on A4 pages
     * (to print on a normal printer and cut). The "A4 sheet" printer itself always prints on A4.
     */
    public byte[] pdf(String company, TagBatchService.BatchDetail d, List<TagBatchService.TagView> tags, String printerCode,
                      boolean a4) throws IOException {
        if (tags == null || tags.isEmpty()) throw new IllegalArgumentException("No tags selected.");
        TagDesigns.Printer p = printerCode == null || printerCode.isBlank() ? setting(company).printer() : TagDesigns.printer(printerCode);
        Choice choice = choiceFor(company, p.code());                       // design + colour chosen for this printer
        String design = choice.design().code();
        int ink = choice.ink();
        Shop shop = shop(company);
        TagStudioService.TagFieldSpec spec = d.spec();
        String mfg = d.batch().getCreatedAt() == null ? null
                : d.batch().getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy"));

        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle(d.batch().getBatchNo() + " tags");
            info.setCreator("StockFlow Tag Studio");
            PDType1Font regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font serif = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
            BufferedImage logoImg = logoImage(company);
            Logo logo = logoImg == null ? null
                    : new Logo(LosslessFactory.createFromImage(doc, logoImg), (float) logoImg.getWidth() / logoImg.getHeight());

            int cols = p.cols(), rows = p.rows();
            float sheetW = p.sheetW(), sheetH = p.sheetH(), gap = 0f;              // the A4 sticker sheet has no gaps
            if (a4 && !p.sheet()) {
                int[] g = a4Grid(p.w(), p.h());
                cols = g[0]; rows = g[1]; gap = A4_GAP;
                sheetW = g[2] == 1 ? 297f : 210f;
                sheetH = g[2] == 1 ? 210f : 297f;
                if (cols < 1 || rows < 1) { cols = 0; rows = 0; }                // cannot happen with today's sizes
            }
            if (cols < 1) {
                for (TagBatchService.TagView t : tags) {
                    PDPage page = new PDPage(new PDRectangle(p.w() * MM, p.h() * MM));
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        TagDesigns.draw(new PdfCanvas(cs, regular, bold, serif, logo, 0, 0, p.h()), design, p.w(), p.h(), data(shop, spec, t, mfg), ink);
                    }
                }
            } else {
                int perPage = cols * rows;
                final float sw = sheetW, sh = sheetH;
                float left = (sw - cols * p.w() - (cols - 1) * gap) / 2f, top = (sh - rows * p.h() - (rows - 1) * gap) / 2f;
                for (int i = 0; i < tags.size(); i += perPage) {
                    PDPage page = new PDPage(new PDRectangle(sw * MM, sh * MM));
                    doc.addPage(page);
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        for (int k = 0; k < perPage && i + k < tags.size(); k++) {
                            int col = k % cols, row = k / cols;
                            float ox = (left + col * (p.w() + gap)) * MM;
                            float oy = (sh - (top + row * (p.h() + gap) + p.h())) * MM;
                            // light cut line around each tag
                            cs.setStrokingColor(0.8f);
                            cs.setLineWidth(0.3f);
                            cs.addRect(ox, oy, p.w() * MM, p.h() * MM);
                            cs.stroke();
                            TagDesigns.draw(new PdfCanvas(cs, regular, bold, serif, logo, ox, oy, p.h()), design, p.w(), p.h(),
                                    data(shop, spec, tags.get(i + k), mfg), ink);
                        }
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    // ------------------------------------------------------------------ preview picture (Designs page)

    /** PNG of one sample tag of this shop in the given design and printer size */
    public byte[] preview(String company, String printerCode, String designCode, String colour) throws IOException {
        TagDesigns.Printer p = TagDesigns.printer(printerCode);
        List<String> types = tagStudioService.businessTypes(company);
        TagStudioService.TagFieldSpec spec = tagStudioService.fieldSpec(types.isEmpty() ? "GENERAL" : types.get(0));
        Shop shop = shop(company);
        TagDesigns.TagData sample = new TagDesigns.TagData(shop.name(),
                first(spec.categories(), "Product"), first(spec.sizes(), "M"), first(spec.colours(), "Black"),
                "ST-101", "1,499", "T2026000001-001", spec.sizeLabel(), spec.colourLabel(), spec.styleLabel(),
                shop.address(), shop.phone(), shop.email(),
                java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy")));
        float px = p.w() > 70 ? 7f : 9f;                                   // about 450 px wide
        BufferedImage img = TagImageCanvas.render(TagDesigns.design(designCode).code(), p.w(), p.h(), sample, px,
                TagDesigns.rgb(colour), logoImage(company));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /**
     * One tag as a picture at the printer's resolution (dpi), for the Printer Agent: it prints the picture on the
     * shop's real printer. Same design, colour, shop name and logo as the PDF.
     */
    @Transactional(readOnly = true)
    public byte[] tagPng(String company, TagBatchService.BatchDetail d, TagBatchService.TagView t, String printerCode, int dpi)
            throws IOException {
        TagDesigns.Printer p = TagDesigns.printer(printerCode);
        Choice choice = choiceFor(company, p.code());
        String mfg = d.batch().getCreatedAt() == null ? null
                : d.batch().getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy"));
        TagDesigns.TagData data = data(shop(company), d.spec(), t, mfg);
        int use = Math.max(150, Math.min(dpi, 600));
        BufferedImage img = TagImageCanvas.render(choice.design().code(), p.w(), p.h(), data, use / 25.4f, choice.ink(), logoImage(company));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ helpers

    /** shop details printed on the tags (from Retail > Shop profile); name falls back to the company name */
    private record Shop(String name, String address, String phone, String email) {}

    private Shop shop(String company) {
        return profileRepo.findByCompanyName(company)
                .map(p -> new Shop(p.getShopName() == null || p.getShopName().isBlank() ? company : p.getShopName().trim(),
                        trim(p.getAddress()), trim(p.getPhone()), trim(p.getEmail())))
                .orElse(new Shop(company, null, null, null));
    }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim().replaceAll("\\s+", " "); }

    private static TagDesigns.TagData data(Shop shop, TagStudioService.TagFieldSpec spec, TagBatchService.TagView t, String mfg) {
        return new TagDesigns.TagData(shop.name(), t.category(), t.size(), t.colour(), t.styleCode(),
                t.mrp() == null ? null : money(t.mrp()), t.barcode(), spec.sizeLabel(), spec.colourLabel(), spec.styleLabel(),
                shop.address(), shop.phone(), shop.email(), mfg);
    }

    private static String first(List<String> list, String fallback) {
        return list == null || list.isEmpty() ? fallback : list.get(0);
    }

    /** 1499 -> "1,499", 1499.5 -> "1,499.50" */
    static String money(BigDecimal v) {
        boolean whole = v.stripTrailingZeros().scale() <= 0;
        DecimalFormat df = new DecimalFormat(whole ? "#,##0" : "#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return df.format(v);
    }

    /** the shop's logo, made once per PDF and shared by every tag in it */
    private record Logo(PDImageXObject image, float aspect) {}

    /** draws on one tag of a PDF page; (ox, oy) = bottom-left corner of the tag in points */
    private static final class PdfCanvas implements TagCanvas {
        private static final float K = 0.5523f;             // bezier constant for round corners
        private final PDPageContentStream cs;
        private final PDType1Font regular, bold, serif;
        private final Logo logo;
        private final float ox, oy, hMm;
        private float inkR, inkG, inkB;                      // 0,0,0 = black

        @Override
        public void ink(int rgb) {
            inkR = ((rgb >> 16) & 0xFF) / 255f;
            inkG = ((rgb >> 8) & 0xFF) / 255f;
            inkB = (rgb & 0xFF) / 255f;
        }

        private void fillInk(boolean white) throws IOException {
            if (white) cs.setNonStrokingColor(1f, 1f, 1f);
            else cs.setNonStrokingColor(inkR, inkG, inkB);
        }

        private void strokeInk(boolean white) throws IOException {
            if (white) cs.setStrokingColor(1f, 1f, 1f);
            else cs.setStrokingColor(inkR, inkG, inkB);
        }

        PdfCanvas(PDPageContentStream cs, PDType1Font regular, PDType1Font bold, PDType1Font serif, Logo logo,
                  float ox, float oy, float hMm) {
            this.cs = cs; this.regular = regular; this.bold = bold; this.serif = serif; this.logo = logo;
            this.ox = ox; this.oy = oy; this.hMm = hMm;
        }

        @Override
        public float logoAspect() { return logo == null ? 0f : logo.aspect(); }

        @Override
        public void logo(float x, float y, float w, float h) {
            if (logo == null) return;
            try {
                cs.drawImage(logo.image(), px(x), py(y + h), w * MM, h * MM);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public float serifWidth(String text, float pt) {
            try {
                return serif.getStringWidth(TagDesigns.latin(text)) / 1000f * pt / MM;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void serifText(String text, float x, float baselineY, float pt, boolean white) {
            try {
                cs.beginText();
                fillInk(white);
                cs.setFont(serif, pt);
                cs.newLineAtOffset(px(x), py(baselineY));
                cs.showText(TagDesigns.latin(text));
                cs.endText();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void poly(float[] xy, boolean fill, float strokeMm) {
            try {
                cs.moveTo(px(xy[0]), py(xy[1]));
                for (int i = 2; i + 1 < xy.length; i += 2) cs.lineTo(px(xy[i]), py(xy[i + 1]));
                cs.closePath();
                if (fill) {
                    fillInk(false);
                    cs.fill();
                } else {
                    strokeInk(false);
                    cs.setLineWidth(strokeMm * MM);
                    cs.stroke();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private float px(float x) { return ox + x * MM; }
        private float py(float y) { return oy + (hMm - y) * MM; }

        @Override
        public float textWidth(String text, float pt, boolean b) {
            try {
                return (b ? bold : regular).getStringWidth(TagDesigns.latin(text)) / 1000f * pt / MM;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void text(String text, float x, float baselineY, float pt, boolean b, boolean white) {
            try {
                cs.beginText();
                if (white) cs.setNonStrokingColor(1f, 1f, 1f); else cs.setNonStrokingColor(0f, 0f, 0f);
                cs.setFont(b ? bold : regular, pt);
                cs.newLineAtOffset(px(x), py(baselineY));
                cs.showText(TagDesigns.latin(text));
                cs.endText();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void rect(float x, float y, float w, float h, float radius, boolean fill, boolean white, float strokeMm) {
            try {
                float l = px(x), r = px(x + w), t = py(y), btm = py(y + h);
                if (radius > 0) {
                    float rr = Math.min(radius * MM, Math.min(r - l, t - btm) / 2f), k = K * rr;
                    cs.moveTo(l + rr, btm);
                    cs.lineTo(r - rr, btm);
                    cs.curveTo(r - rr + k, btm, r, btm + rr - k, r, btm + rr);
                    cs.lineTo(r, t - rr);
                    cs.curveTo(r, t - rr + k, r - rr + k, t, r - rr, t);
                    cs.lineTo(l + rr, t);
                    cs.curveTo(l + rr - k, t, l, t - rr + k, l, t - rr);
                    cs.lineTo(l, btm + rr);
                    cs.curveTo(l, btm + rr - k, l + rr - k, btm, l + rr, btm);
                    cs.closePath();
                } else {
                    cs.addRect(l, btm, r - l, t - btm);
                }
                if (fill) {
                    fillInk(white);
                    cs.fill();
                } else {
                    strokeInk(white);
                    cs.setLineWidth(strokeMm * MM);
                    cs.stroke();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void line(float x1, float y1, float x2, float y2, float strokeMm, boolean white) {
            try {
                strokeInk(white);
                cs.setLineWidth(strokeMm * MM);
                cs.moveTo(px(x1), py(y1));
                cs.lineTo(px(x2), py(y2));
                cs.stroke();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void circle(float cx, float cy, float r, float strokeMm) {
            try {
                float x = px(cx), y = py(cy), rr = r * MM, k = K * rr;
                cs.moveTo(x + rr, y);
                cs.curveTo(x + rr, y + k, x + k, y + rr, x, y + rr);
                cs.curveTo(x - k, y + rr, x - rr, y + k, x - rr, y);
                cs.curveTo(x - rr, y - k, x - k, y - rr, x, y - rr);
                cs.curveTo(x + k, y - rr, x + rr, y - k, x + rr, y);
                cs.closePath();
                strokeInk(false);
                cs.setLineWidth(strokeMm * MM);
                cs.stroke();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public void bars(float x, float y, float h, float moduleMm, int[] widths) {
            try {
                cs.setNonStrokingColor(0f, 0f, 0f);              // bars always black, so they scan
                float cx = x;
                boolean bar = true;
                for (int wv : widths) {
                    float bw = wv * moduleMm;
                    if (bar) cs.addRect(px(cx), py(y + h), bw * MM, h * MM);
                    cx += bw;
                    bar = !bar;
                }
                cs.fill();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
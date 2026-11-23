package com.stockmanagement.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Draws a tag design into a picture (PNG preview on the Designs page). Uses Arial / Liberation Sans, which have the
 * same letter widths as the Helvetica of the PDF, so the preview matches the print.
 */
public class TagImageCanvas implements TagCanvas {

    private static final String FONT_NAME = pickFont(new String[]{"Arial", "Liberation Sans", "Helvetica", "Arimo"}, Font.SANS_SERIF);
    private static final String SERIF_NAME = pickFont(new String[]{"Times New Roman", "Liberation Serif", "Times", "Tinos"}, Font.SERIF);

    private final Graphics2D g;
    private final float s;                 // pixels per mm
    private final Font regular, bold, serif;
    private Color ink = Color.BLACK;
    private BufferedImage logoImage;

    private TagImageCanvas(Graphics2D g, float pxPerMm) {
        this.g = g;
        this.s = pxPerMm;
        this.regular = new Font(FONT_NAME, Font.PLAIN, 10);
        this.bold = new Font(FONT_NAME, Font.BOLD, 10);
        this.serif = new Font(SERIF_NAME, Font.BOLD, 10);
    }

    /** one tag as a picture, white background, a thin grey edge so the tag size is visible */
    public static BufferedImage render(String design, float wMm, float hMm, TagDesigns.TagData data, float pxPerMm) {
        return render(design, wMm, hMm, data, pxPerMm, 0);
    }

    /** same, in a colour (0xRRGGBB, 0 = black) */
    public static BufferedImage render(String design, float wMm, float hMm, TagDesigns.TagData data, float pxPerMm, int ink) {
        return render(design, wMm, hMm, data, pxPerMm, ink, null);
    }

    /** same, with the shop's logo (null = monogram) */
    public static BufferedImage render(String design, float wMm, float hMm, TagDesigns.TagData data, float pxPerMm, int ink,
                                       BufferedImage logo) {
        int w = Math.round(wMm * pxPerMm), h = Math.round(hMm * pxPerMm);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            TagImageCanvas canvas = new TagImageCanvas(g, pxPerMm);
            canvas.logoImage = logo;
            TagDesigns.draw(canvas, design, wMm, hMm, data, ink);
            g.setColor(new Color(0xD5D8E2));
            g.setStroke(new BasicStroke(1f));
            g.drawRect(0, 0, w - 1, h - 1);
        } finally {
            g.dispose();
        }
        return img;
    }

    private Font font(float pt, boolean b) {
        return (b ? bold : regular).deriveFont(pt * TagDesigns.PT * s);
    }

    @Override
    public float textWidth(String text, float pt, boolean b) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        return (float) font(pt, b).getStringBounds(TagDesigns.latin(text), frc).getWidth() / s;
    }

    @Override
    public void text(String text, float x, float baselineY, float pt, boolean b, boolean white) {
        g.setColor(white ? Color.WHITE : Color.BLACK);
        g.setFont(font(pt, b));
        g.drawString(TagDesigns.latin(text), x * s, baselineY * s);
    }

    @Override
    public void rect(float x, float y, float w, float h, float radius, boolean fill, boolean white, float strokeMm) {
        g.setColor(white ? Color.WHITE : ink);
        java.awt.Shape r = radius > 0
                ? new RoundRectangle2D.Float(x * s, y * s, w * s, h * s, 2 * radius * s, 2 * radius * s)
                : new Rectangle2D.Float(x * s, y * s, w * s, h * s);
        if (fill) {
            g.fill(r);
        } else {
            g.setStroke(new BasicStroke(Math.max(1f, strokeMm * s)));
            g.draw(r);
        }
    }

    @Override
    public void line(float x1, float y1, float x2, float y2, float strokeMm, boolean white) {
        g.setColor(white ? Color.WHITE : ink);
        g.setStroke(new BasicStroke(Math.max(1f, strokeMm * s)));
        g.draw(new Line2D.Float(x1 * s, y1 * s, x2 * s, y2 * s));
    }

    @Override
    public void circle(float cx, float cy, float r, float strokeMm) {
        g.setColor(ink);
        g.setStroke(new BasicStroke(Math.max(1f, strokeMm * s)));
        g.draw(new Ellipse2D.Float((cx - r) * s, (cy - r) * s, 2 * r * s, 2 * r * s));
    }

    @Override
    public void bars(float x, float y, float h, float moduleMm, int[] widths) {
        g.setColor(Color.BLACK);
        float cx = x;
        boolean bar = true;
        for (int wv : widths) {
            float bw = wv * moduleMm;
            if (bar) g.fill(new Rectangle2D.Float(cx * s, y * s, bw * s, h * s));
            cx += bw;
            bar = !bar;
        }
    }

    @Override
    public float serifWidth(String text, float pt) {
        FontRenderContext frc = new FontRenderContext(null, true, true);
        return (float) serif.deriveFont(pt * TagDesigns.PT * s).getStringBounds(TagDesigns.latin(text), frc).getWidth() / s;
    }

    @Override
    public void serifText(String text, float x, float baselineY, float pt, boolean white) {
        g.setColor(white ? Color.WHITE : ink);
        g.setFont(serif.deriveFont(pt * TagDesigns.PT * s));
        g.drawString(TagDesigns.latin(text), x * s, baselineY * s);
    }

    @Override
    public void poly(float[] xy, boolean fill, float strokeMm) {
        java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
        path.moveTo(xy[0] * s, xy[1] * s);
        for (int i = 2; i + 1 < xy.length; i += 2) path.lineTo(xy[i] * s, xy[i + 1] * s);
        path.closePath();
        g.setColor(ink);
        if (fill) {
            g.fill(path);
        } else {
            g.setStroke(new BasicStroke(Math.max(1f, strokeMm * s)));
            g.draw(path);
        }
    }

    @Override
    public float logoAspect() {
        return logoImage == null ? 0f : (float) logoImage.getWidth() / logoImage.getHeight();
    }

    @Override
    public void logo(float x, float y, float w, float h) {
        if (logoImage == null) return;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(logoImage, Math.round(x * s), Math.round(y * s), Math.round(w * s), Math.round(h * s), null);
    }

    @Override
    public void ink(int rgb) {
        ink = new Color(rgb & 0xFFFFFF);
    }

    private static String pickFont(String[] wanted, String fallback) {
        try {
            Set<String> names = new HashSet<>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String n : wanted) {
                if (names.contains(n)) return n;
            }
        } catch (RuntimeException | Error ignored) {
            // headless server without fonts: fall back to the logical font below
        }
        return fallback;
    }
}
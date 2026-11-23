package com.stockmanagement.service;

/**
 * Where a tag design draws. Units are millimetres, origin at the top-left corner of the tag, y grows downwards.
 * Black and white by default (thermal label printers print no grey). Colour designs set an ink colour with
 * {@link #ink(int)}: it is used for filled shapes, lines, circles and serif text. Normal text and barcode bars always stay
 * black so they are easy to read and scan. Text size is in points (1 pt = 0.3528 mm).
 *
 * Two implementations: the PDF one in {@link TagPdfService} (what gets printed) and a picture one used for the
 * design previews, so a preview is drawn by exactly the same design code as the print.
 */
public interface TagCanvas {

    /** width in mm of the text at this size */
    float textWidth(String text, float pt, boolean bold);

    /** text with its left end at x and its baseline at y */
    void text(String text, float x, float baselineY, float pt, boolean bold, boolean white);

    /** filled (black or white) or outlined rectangle; radius 0 = square corners */
    void rect(float x, float y, float w, float h, float radius, boolean fill, boolean white, float strokeMm);

    void line(float x1, float y1, float x2, float y2, float strokeMm, boolean white);

    void circle(float cx, float cy, float r, float strokeMm);

    /** barcode bars: widths are module counts (bar, space, bar ...) starting with a bar */
    void bars(float x, float y, float h, float moduleMm, int[] widths);

    /** width in mm of bold serif text (Times), used for shop names on the Premium design */
    float serifWidth(String text, float pt);

    /** bold serif text with its left end at x and its baseline at y */
    void serifText(String text, float x, float baselineY, float pt, boolean white);

    /** closed shape through the points x0,y0,x1,y1,... (filled black or outlined) */
    void poly(float[] xy, boolean fill, float strokeMm);

    /** colour (0xRRGGBB) used instead of black for shapes, lines and serif text; 0 = black */
    void ink(int rgb);

    /** width / height of the shop's own logo; 0 = the shop has not uploaded one (designs then use a monogram) */
    float logoAspect();

    /** the shop's logo stretched to exactly x, y, w, h (callers keep its shape using logoAspect) */
    void logo(float x, float y, float w, float h);
}
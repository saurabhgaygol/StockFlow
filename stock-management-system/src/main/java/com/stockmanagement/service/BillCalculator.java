package com.stockmanagement.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * All the bill arithmetic in one place (no database, no Spring), so it can be tested on its own.
 *
 * Prices INCLUDE GST. The customer pays "Total payable"; the breakup is worked out backwards:
 *   taxable = amount / (1 + rate/100),  GST = amount - taxable,  CGST = SGST = GST / 2 (same state).
 *
 * Order of discounts on every line:
 *   1) item offer      (from the offers module)
 *   2) manual discount (cashier, with a reason)
 *   3) bill discount   (bill offer / coupon / birthday offer) and points redeemed, shared over the lines
 *      in proportion to what each line still costs.
 */
public final class BillCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWO = new BigDecimal("2");

    private BillCalculator() {}

    /** One cart line. unitPrice is what the customer sees (GST included). */
    public record LineIn(String key, BigDecimal unitPrice, BigDecimal qty, BigDecimal gstRate,
                         BigDecimal itemDiscount, BigDecimal manualDiscount) {}

    /**
     * billDiscount = rupees off the whole bill (bill offer, coupon, birthday), already worked out by the caller.
     * pointsRedeemed = rupees paid with loyalty points (1 point = 1 rupee, already checked against the balance).
     */
    public record Input(List<LineIn> lines, BigDecimal billDiscount, BigDecimal pointsRedeemed) {}

    public record LineOut(String key, BigDecimal grossAmount, BigDecimal itemDiscount, BigDecimal manualDiscount,
                          BigDecimal billShare, BigDecimal lineTotal, BigDecimal taxable,
                          BigDecimal cgst, BigDecimal sgst) {}

    /** one row of the HSN-wise / rate-wise GST summary printed under the bill */
    public record RateRow(BigDecimal gstRate, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst) {}

    public record Result(List<LineOut> lines, List<RateRow> rateRows,
                         BigDecimal gross, BigDecimal itemDiscount, BigDecimal manualDiscount,
                         BigDecimal billDiscount, BigDecimal pointsRedeemed,
                         BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
                         BigDecimal roundOff, BigDecimal grandTotal) {}

    public static Result compute(Input in) {
        List<LineIn> src = in.lines();
        int n = src.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] item = new BigDecimal[n];
        BigDecimal[] manual = new BigDecimal[n];
        BigDecimal[] net = new BigDecimal[n];            // after item + manual discount
        BigDecimal netSum = BigDecimal.ZERO;

        for (int i = 0; i < n; i++) {
            LineIn l = src.get(i);
            gross[i] = money(nz(l.unitPrice()).multiply(nz(l.qty())));
            // a discount can never be more than the line is worth
            item[i] = money(nz(l.itemDiscount())).min(gross[i]).max(BigDecimal.ZERO);
            manual[i] = money(nz(l.manualDiscount())).min(gross[i].subtract(item[i])).max(BigDecimal.ZERO);
            net[i] = gross[i].subtract(item[i]).subtract(manual[i]);
            netSum = netSum.add(net[i]);
        }

        // bill discount and points are shared over the lines; neither can be more than the bill
        BigDecimal billDisc = money(nz(in.billDiscount())).min(netSum).max(BigDecimal.ZERO);
        BigDecimal points = money(nz(in.pointsRedeemed())).min(netSum.subtract(billDisc)).max(BigDecimal.ZERO);
        BigDecimal[] billShare = share(net, netSum, billDisc.add(points));

        BigDecimal tGross = BigDecimal.ZERO, tItem = BigDecimal.ZERO, tManual = BigDecimal.ZERO;
        BigDecimal tTaxable = BigDecimal.ZERO, tCgst = BigDecimal.ZERO, tSgst = BigDecimal.ZERO, tPay = BigDecimal.ZERO;
        List<LineOut> out = new ArrayList<>();
        List<RateRow> rates = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            LineIn l = src.get(i);
            BigDecimal rate = nz(l.gstRate());
            BigDecimal pay = net[i].subtract(billShare[i]);                       // what the customer pays for this line
            BigDecimal taxable = rate.signum() == 0 ? pay
                    : pay.multiply(HUNDRED).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
            BigDecimal gst = pay.subtract(taxable);
            BigDecimal cgst = gst.divide(TWO, 2, RoundingMode.HALF_UP);
            BigDecimal sgst = gst.subtract(cgst);                                 // cgst + sgst always equals the GST exactly
            out.add(new LineOut(l.key(), gross[i], item[i], manual[i], billShare[i], pay, taxable, cgst, sgst));

            tGross = tGross.add(gross[i]);
            tItem = tItem.add(item[i]);
            tManual = tManual.add(manual[i]);
            tTaxable = tTaxable.add(taxable);
            tCgst = tCgst.add(cgst);
            tSgst = tSgst.add(sgst);
            tPay = tPay.add(pay);
            addRate(rates, rate, taxable, cgst, sgst);
        }

        BigDecimal grand = tPay.setScale(0, RoundingMode.HALF_UP).setScale(2);
        BigDecimal roundOff = grand.subtract(tPay);
        return new Result(out, rates, tGross, tItem, tManual, billDisc, points,
                tTaxable, tCgst, tSgst, roundOff, grand);
    }

    /** Buy X Get Y free on one line: every (X + Y) pieces, Y of them are free. */
    public static BigDecimal buyXGetYDiscount(BigDecimal unitPrice, BigDecimal qty, int buy, int get) {
        if (unitPrice == null || qty == null || buy <= 0 || get <= 0) return BigDecimal.ZERO;
        int pieces = qty.setScale(0, RoundingMode.DOWN).intValue();
        int free = (pieces / (buy + get)) * get;
        return money(unitPrice.multiply(BigDecimal.valueOf(free)));
    }

    /** Whole points the customer earns on this bill: rate % of what was actually paid, rounded DOWN. */
    public static long pointsEarned(BigDecimal amountPaid, BigDecimal ratePercent) {
        if (amountPaid == null || ratePercent == null || amountPaid.signum() <= 0 || ratePercent.signum() <= 0) return 0;
        return amountPaid.multiply(ratePercent).divide(HUNDRED, 0, RoundingMode.DOWN).longValue();
    }

    // -------------------------------------------------------------- helpers

    /** Share "total" over the lines in proportion to base[i]; the last line takes the rounding rest. */
    private static BigDecimal[] share(BigDecimal[] base, BigDecimal baseSum, BigDecimal total) {
        int n = base.length;
        BigDecimal[] out = new BigDecimal[n];
        BigDecimal used = BigDecimal.ZERO;
        int last = -1;
        for (int i = 0; i < n; i++) if (base[i].signum() > 0) last = i;
        for (int i = 0; i < n; i++) {
            if (base[i].signum() <= 0 || baseSum.signum() <= 0 || total.signum() <= 0) {
                out[i] = BigDecimal.ZERO.setScale(2);
            } else if (i == last) {
                out[i] = total.subtract(used).min(base[i]);
            } else {
                out[i] = total.multiply(base[i]).divide(baseSum, 2, RoundingMode.HALF_UP).min(base[i]);
            }
            used = used.add(out[i]);
        }
        return out;
    }

    private static void addRate(List<RateRow> rows, BigDecimal rate, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst) {
        for (int i = 0; i < rows.size(); i++) {
            RateRow r = rows.get(i);
            if (r.gstRate().compareTo(rate) == 0) {
                rows.set(i, new RateRow(rate, r.taxable().add(taxable), r.cgst().add(cgst), r.sgst().add(sgst)));
                return;
            }
        }
        rows.add(new RateRow(rate, taxable, cgst, sgst));
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
}
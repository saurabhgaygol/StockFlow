package com.stockmanagement.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Numbers for the Retail dashboard (shown instead of the stock dashboard to companies that have the Retail feature).
 * Every query is limited to the company; cancelled bills never count as sales.
 */
@Service
public class RetailDashboardService {

    /** pieces at or below this are shown as "running low" */
    public static final int LOW_STOCK_AT = 5;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_LONG = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter BILL_TIME = DateTimeFormatter.ofPattern("dd MMM, h:mm a", Locale.ENGLISH);

    private final JdbcTemplate jdbc;
    private final CompanyFeatureService featureService;

    public RetailDashboardService(JdbcTemplate jdbc, CompanyFeatureService featureService) {
        this.jdbc = jdbc;
        this.featureService = featureService;
    }

    // ------------------------------------------------------------------ view

    public record DayBar(String label, String date, BigDecimal amount, int heightPct) {}
    public record TopItem(String name, BigDecimal qty, BigDecimal amount) {}
    public record LowItem(String name, String size, String colour, BigDecimal qty) {}
    public record RecentBill(String billNo, String when, String customer, BigDecimal total, boolean cancelled) {}
    public record PayMix(String mode, BigDecimal amount, int pct) {}

    public record View(
            boolean billing, boolean stock, String today, String monthLabel,
            BigDecimal todaySales, long todayBills, BigDecimal todayItems, BigDecimal todayAvg, BigDecimal todayGst,
            BigDecimal monthSales, long monthBills, BigDecimal monthGst, long monthCancelled,
            BigDecimal yesterdaySales, String vsYesterday, boolean vsUp,
            List<DayBar> week, BigDecimal weekTotal,
            List<PayMix> payMix, List<TopItem> topItems, List<RecentBill> recent,
            long variantCount, BigDecimal piecesInStock, BigDecimal stockValue, long outOfStock, List<LowItem> low,
            long customers, long newCustomers) {}

    /** True when this company should see the Retail dashboard (it has the Retail products/stock feature). */
    public boolean applies(String company) {
        return company != null && !company.isBlank()
                && featureService.isEnabled(company, CompanyFeatureService.RETAIL_VARIANT_STOCK);
    }

    @Transactional(readOnly = true)
    public View build(String company) {
        boolean billing = featureService.isEnabled(company, CompanyFeatureService.RETAIL_BILLING);
        boolean stock = featureService.isEnabled(company, CompanyFeatureService.RETAIL_VARIANT_STOCK);

        LocalDate today = LocalDate.now();
        LocalDateTime dayStart = today.atStartOfDay();
        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime monthStart = today.withDayOfMonth(1).atStartOfDay();
        LocalDateTime yStart = dayStart.minusDays(1);

        BigDecimal todaySales = BigDecimal.ZERO, todayItems = BigDecimal.ZERO, todayGst = BigDecimal.ZERO;
        BigDecimal monthSales = BigDecimal.ZERO, monthGst = BigDecimal.ZERO, ySales = BigDecimal.ZERO;
        long todayBills = 0, monthBills = 0, monthCancelled = 0;
        List<DayBar> week = new ArrayList<>();
        BigDecimal weekTotal = BigDecimal.ZERO;
        List<PayMix> payMix = new ArrayList<>();
        List<TopItem> top = new ArrayList<>();
        List<RecentBill> recent = new ArrayList<>();

        if (billing) {
            todaySales = sum("SELECT COALESCE(SUM(grand_total),0) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, dayStart, dayEnd);
            todayBills = count("SELECT COUNT(*) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, dayStart, dayEnd);
            todayGst = sum("SELECT COALESCE(SUM(cgst_amount+sgst_amount),0) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, dayStart, dayEnd);
            todayItems = sum("SELECT COALESCE(SUM(l.qty),0) FROM retail_bill_line l JOIN retail_bill b ON b.id=l.bill_id "
                    + "WHERE b.company_name=? AND b.status='COMPLETED' AND b.bill_date>=? AND b.bill_date<?", company, dayStart, dayEnd);
            monthSales = sum("SELECT COALESCE(SUM(grand_total),0) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, monthStart, dayEnd);
            monthBills = count("SELECT COUNT(*) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, monthStart, dayEnd);
            monthGst = sum("SELECT COALESCE(SUM(cgst_amount+sgst_amount),0) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, monthStart, dayEnd);
            monthCancelled = count("SELECT COUNT(*) FROM retail_bill WHERE company_name=? AND status='CANCELLED' AND bill_date>=? AND bill_date<?",
                    company, monthStart, dayEnd);
            ySales = sum("SELECT COALESCE(SUM(grand_total),0) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                    company, yStart, dayStart);

            // last 7 days, today last
            BigDecimal max = BigDecimal.ZERO;
            List<BigDecimal> amts = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDateTime s = dayStart.minusDays(i);
                BigDecimal a = sum("SELECT COALESCE(SUM(grand_total),0) FROM retail_bill WHERE company_name=? AND status='COMPLETED' AND bill_date>=? AND bill_date<?",
                        company, s, s.plusDays(1));
                amts.add(a);
                weekTotal = weekTotal.add(a);
                if (a.compareTo(max) > 0) max = a;
            }
            for (int i = 6, k = 0; i >= 0; i--, k++) {
                LocalDate d = today.minusDays(i);
                BigDecimal a = amts.get(k);
                int h = max.signum() == 0 ? 0 : a.multiply(BigDecimal.valueOf(100)).divide(max, 0, RoundingMode.HALF_UP).intValue();
                if (a.signum() > 0 && h < 4) h = 4;
                week.add(new DayBar(i == 0 ? "Today" : DAY.format(d), DAY_LONG.format(d), a, h));
            }

            // how customers paid this month
            List<Object[]> pm = jdbc.query(
                    "SELECT p.pay_mode, COALESCE(SUM(p.amount),0) FROM retail_bill_payment p JOIN retail_bill b ON b.id=p.bill_id "
                            + "WHERE b.company_name=? AND b.status='COMPLETED' AND b.bill_date>=? AND b.bill_date<? GROUP BY p.pay_mode ORDER BY 2 DESC",
                    (rs, n) -> new Object[]{rs.getString(1), rs.getBigDecimal(2)},
                    company, Timestamp.valueOf(monthStart), Timestamp.valueOf(dayEnd));
            BigDecimal pmTotal = BigDecimal.ZERO;
            for (Object[] r : pm) pmTotal = pmTotal.add((BigDecimal) r[1]);
            for (Object[] r : pm) {
                BigDecimal a = (BigDecimal) r[1];
                int pct = pmTotal.signum() == 0 ? 0 : a.multiply(BigDecimal.valueOf(100)).divide(pmTotal, 0, RoundingMode.HALF_UP).intValue();
                payMix.add(new PayMix(payLabel((String) r[0]), a, pct));
            }

            // best sellers this month
            top = jdbc.query(
                    "SELECT l.item_label, SUM(l.qty), SUM(l.line_total) FROM retail_bill_line l JOIN retail_bill b ON b.id=l.bill_id "
                            + "WHERE b.company_name=? AND b.status='COMPLETED' AND b.bill_date>=? AND b.bill_date<? "
                            + "GROUP BY l.item_label ORDER BY SUM(l.qty) DESC, SUM(l.line_total) DESC LIMIT 5",
                    (rs, n) -> new TopItem(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                    company, Timestamp.valueOf(monthStart), Timestamp.valueOf(dayEnd));

            recent = jdbc.query(
                    "SELECT bill_no, bill_date, customer_name, grand_total, status FROM retail_bill WHERE company_name=? ORDER BY id DESC LIMIT 6",
                    (rs, n) -> new RecentBill(rs.getString(1), BILL_TIME.format(rs.getTimestamp(2).toLocalDateTime()),
                            rs.getString(3) == null || rs.getString(3).isBlank() ? "Walk-in" : rs.getString(3),
                            rs.getBigDecimal(4), "CANCELLED".equals(rs.getString(5))),
                    company);
        }

        long variantCount = 0, outOfStock = 0, customers = 0, newCustomers = 0;
        BigDecimal pieces = BigDecimal.ZERO, stockValue = BigDecimal.ZERO;
        List<LowItem> low = new ArrayList<>();
        if (stock) {
            variantCount = count("SELECT COUNT(*) FROM retail_variant WHERE company_name=? AND status='ACTIVE'", company);
            pieces = sum("SELECT COALESCE(SUM(qty),0) FROM retail_variant WHERE company_name=? AND status='ACTIVE'", company);
            stockValue = sum("SELECT COALESCE(SUM(qty * COALESCE(sale_price, mrp, 0)),0) FROM retail_variant WHERE company_name=? AND status='ACTIVE'", company);
            outOfStock = count("SELECT COUNT(*) FROM retail_variant WHERE company_name=? AND status='ACTIVE' AND qty<=0", company);
            low = jdbc.query(
                    "SELECT CONCAT(p.category_name, ' / ', p.product_name), v.size_label, v.colour, v.qty FROM retail_variant v "
                            + "LEFT JOIN product_category p ON p.id=v.product_id "
                            + "WHERE v.company_name=? AND v.status='ACTIVE' AND v.qty<=? ORDER BY v.qty ASC, p.product_name LIMIT 8",
                    (rs, n) -> new LowItem(rs.getString(1) == null ? "-" : rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)),
                    company, LOW_STOCK_AT);
        }
        if (billing) {
            customers = count("SELECT COUNT(*) FROM retail_customer WHERE company_name=?", company);
            newCustomers = count("SELECT COUNT(*) FROM retail_customer WHERE company_name=? AND created_at>=?", company, monthStart);
        }

        BigDecimal avg = todayBills == 0 ? BigDecimal.ZERO : todaySales.divide(BigDecimal.valueOf(todayBills), 2, RoundingMode.HALF_UP);

        String vs = "";
        boolean up = true;
        if (billing) {
            if (ySales.signum() == 0) {
                vs = todaySales.signum() > 0 ? "No sales yesterday" : "";
            } else {
                BigDecimal pct = todaySales.subtract(ySales).multiply(BigDecimal.valueOf(100)).divide(ySales, 0, RoundingMode.HALF_UP);
                up = pct.signum() >= 0;
                vs = (up ? "+" : "") + pct.toPlainString() + "% vs yesterday";
            }
        }

        return new View(billing, stock, DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.ENGLISH).format(today),
                DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH).format(today),
                todaySales, todayBills, todayItems, avg, todayGst,
                monthSales, monthBills, monthGst, monthCancelled,
                ySales, vs, up, week, weekTotal, payMix, top, recent,
                variantCount, pieces, stockValue, outOfStock, low, customers, newCustomers);
    }

    // ------------------------------------------------------------------ helpers

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, fix(args));
        return v == null ? BigDecimal.ZERO : v;
    }

    private long count(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, fix(args));
        return v == null ? 0 : v;
    }

    /** LocalDateTime -> Timestamp so every JDBC driver binds it the same way */
    private static Object[] fix(Object[] args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] instanceof LocalDateTime ? Timestamp.valueOf((LocalDateTime) args[i]) : args[i];
        }
        return out;
    }

    private static String payLabel(String mode) {
        if (mode == null) return "Other";
        switch (mode.toUpperCase(Locale.ROOT)) {
            case "CASH": return "Cash";
            case "UPI": return "UPI";
            case "CARD": return "Card";
            default: return mode;
        }
    }
}
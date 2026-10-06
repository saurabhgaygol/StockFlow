package com.stockmanagement.service;

import com.stockmanagement.entity.Permission;
import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.StockOutwardApproval;
import com.stockmanagement.entity.StockOutwardRequest;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.entity.Vendor;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.StockInwardRepository;
import com.stockmanagement.repository.StockOutwardApprovalRepository;
import com.stockmanagement.repository.StockOutwardRequestRepository;
import com.stockmanagement.repository.VendorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dashboard / report ke saare numbers yahin se aate hain.
 *
 * Scope (StockOutwardService.list jaisa hi):
 *  - SUPER_ADMIN            -> saari companies
 *  - STOCK_OUT_VIEW_ALL     -> apni company ki saari outward requests
 *  - STOCK_OUT_VIEW         -> sirf wo requests jisme user involved hai
 *  - Outward wale sections tabhi dikhte hain jab user ke paas outward view permission ho.
 *
 * Period filter: last-7 / last-30 / last-90 / this-month / this-year / custom (from, to).
 * Har period ka compare uske theek pehle wale barabar-lambe period se hota hai.
 *
 * NOTE: numbers in-memory nikalte hain (company ka saara stock + requests load hota hai).
 * Bahut bada data ho jaye to GROUP BY queries me shift karna.
 */
@Service
public class DashboardService {

    public static final String DEFAULT_RANGE = "last-30";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TITLE_FMT = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH);
    private static final int WARRANTY_WINDOW_DAYS = 30;
    private static final int ID_CHUNK = 1000;

    private final StockInwardRepository stockRepo;
    private final ProductCategoryRepository categoryRepo;
    private final VendorRepository vendorRepo;
    private final StockOutwardRequestRepository requestRepo;
    private final StockOutwardApprovalRepository approvalRepo;
    private final UserPermissionService permissionService;

    public DashboardService(StockInwardRepository stockRepo,
                            ProductCategoryRepository categoryRepo,
                            VendorRepository vendorRepo,
                            StockOutwardRequestRepository requestRepo,
                            StockOutwardApprovalRepository approvalRepo,
                            UserPermissionService permissionService) {
        this.stockRepo = stockRepo;
        this.categoryRepo = categoryRepo;
        this.vendorRepo = vendorRepo;
        this.requestRepo = requestRepo;
        this.approvalRepo = approvalRepo;
        this.permissionService = permissionService;
    }

    /* ============================================================
       VIEW MODELS (Thymeleaf me method-call syntax: dash.rangeLabel())
       ============================================================ */
    public record Period(String key, String label, LocalDate from, LocalDate to,
                         LocalDate prevFrom, LocalDate prevTo) {}

    public record Kpi(String label, String value, String sub, String accent, String href,
                      String delta, String deltaClass) {}

    public record StatusSlice(String label, long count, String color, int pct) {}
    public record Bucket(String label, String title, boolean showLabel,
                         long inward, long issued, int inPct, int outPct) {}
    public record FunnelRow(String label, long count, int pct, String css) {}
    public record AgingRow(String label, long count, String value, int pct, String css) {}
    public record LevelRow(String level, String avg, long count, int pct) {}
    public record ProductRow(String name, String category, long inward, long issued,
                             long available, long damaged) {}
    public record CategoryRow(String name, long total, long available, long issued,
                              long damaged, String value) {}
    public record VendorRow(String name, long units, String value, int pct) {}
    public record CustomerRow(String name, long requests, long units, String amount, int pct) {}
    public record RequestLine(String requestNo, String customer, String amount, String statusLabel,
                              String statusClass, String level, String age, String url) {}
    public record WarrantyLine(String imei, String product, String endDate, long daysLeft) {}

    public record DashboardView(
            String greetingName, String companyLabel, String today,
            String rangeKey, String rangeLabel, String prevLabel, String fromIso, String toIso,
            boolean showOutward,
            List<Kpi> snapshot, List<Kpi> performance,
            List<Bucket> trend, String trendUnit,
            long totalUnits, List<StatusSlice> slices, String donutGradient,
            List<FunnelRow> funnel, String avgTurnaround,
            List<AgingRow> aging,
            List<LevelRow> levels,
            List<ProductRow> products, List<CategoryRow> categories,
            List<VendorRow> vendors, List<CustomerRow> customers,
            long awaitingMe, List<RequestLine> awaiting, List<RequestLine> recentRequests,
            long expiringCount, List<WarrantyLine> expiring,
            String generatedAt) {}

    /* ============================================================
       BUILD
       ============================================================ */
    public DashboardView build(CustomUserDetails actor) {
        return build(actor, null, null, null);
    }

    @Transactional(readOnly = true)
    public DashboardView build(CustomUserDetails actor, String rangeKey, String fromStr, String toStr) {
        UserTable me = actor.getUser();
        Set<String> codes = permissionService.getAllowedPermissionDetails(actor.getUserId()).stream()
                .map(Permission::getPermissionCode).collect(Collectors.toSet());
        boolean superAdmin = codes.contains("SUPER_ADMIN");
        boolean viewAll = superAdmin || codes.contains("STOCK_OUT_VIEW_ALL");
        boolean showOutward = viewAll || codes.contains("STOCK_OUT_VIEW");
        String company = me.getCompanyName();
        LocalDate today = LocalDate.now();

        Period p = resolve(rangeKey, fromStr, toStr, today);
        LocalDateTime start = p.from().atStartOfDay();
        LocalDateTime end = p.to().plusDays(1).atStartOfDay();
        LocalDateTime pStart = p.prevFrom().atStartOfDay();
        LocalDateTime pEnd = p.prevTo().plusDays(1).atStartOfDay();
        String prevLabel = p.prevFrom().format(DATE_FMT) + " \u2013 " + p.prevTo().format(DATE_FMT);

        /* ---------- STOCK: load ---------- */
        List<StockInward> units = superAdmin
                ? stockRepo.findAll()
                : stockRepo.findByCompanyNameOrderByCreatedAtDesc(company);
        List<ProductCategory> products = superAdmin
                ? categoryRepo.findAll()
                : categoryRepo.findByCompanyName(company);

        Map<Long, String> productNames = new HashMap<>();
        Map<Long, String> categoryNames = new HashMap<>();
        for (ProductCategory pc : products) {
            if (pc.getProductName() != null && !pc.getProductName().isBlank()) {
                productNames.put(pc.getId(), pc.getProductName().trim());
            }
            if (pc.getCategoryName() != null && !pc.getCategoryName().isBlank()) {
                categoryNames.put(pc.getId(), pc.getCategoryName().trim());
            }
        }

        /* ---------- STOCK: snapshot ---------- */
        Map<String, Long> byStatus = units.stream().collect(Collectors.groupingBy(
                u -> u.getStatus() == null ? "OTHER" : u.getStatus(), Collectors.counting()));
        long total = units.size();
        long available = byStatus.getOrDefault("AVAILABLE", 0L);
        long issuedAll = byStatus.getOrDefault("ISSUED", 0L);
        long damaged = byStatus.getOrDefault("DAMAGED", 0L);
        long reserved = byStatus.getOrDefault("RESERVED", 0L);
        long other = Math.max(0, total - available - issuedAll - damaged - reserved);

        BigDecimal availableValue = BigDecimal.ZERO;
        for (StockInward u : units) {
            if ("AVAILABLE".equals(u.getStatus())) availableValue = availableValue.add(nz(u.getUnitPrice()));
        }

        List<StatusSlice> slices = new ArrayList<>();
        addSlice(slices, "Available", available, "#1c7a43", total);
        addSlice(slices, "Issued", issuedAll, "#6c3ce0", total);
        addSlice(slices, "Reserved", reserved, "#d97706", total);
        addSlice(slices, "Damaged", damaged, "#c0392b", total);
        addSlice(slices, "Other", other, "#9ca3af", total);
        String donut = donutGradient(slices, total);

        /* ---------- STOCK: period numbers ---------- */
        long inwardCur = 0, inwardPrev = 0, issuedCur = 0, issuedPrev = 0;
        BigDecimal inwardValCur = BigDecimal.ZERO, inwardValPrev = BigDecimal.ZERO;
        Map<Long, Long> issuedUnitsByRequest = new HashMap<>();
        for (StockInward u : units) {
            if (in(u.getCreatedAt(), start, end)) {
                inwardCur++;
                inwardValCur = inwardValCur.add(nz(u.getUnitPrice()));
            } else if (in(u.getCreatedAt(), pStart, pEnd)) {
                inwardPrev++;
                inwardValPrev = inwardValPrev.add(nz(u.getUnitPrice()));
            }
            if (in(u.getIssuedAt(), start, end)) issuedCur++;
            else if (in(u.getIssuedAt(), pStart, pEnd)) issuedPrev++;
            if (u.getOutwardRequestId() != null && "ISSUED".equals(u.getStatus())) {
                issuedUnitsByRequest.merge(u.getOutwardRequestId(), 1L, Long::sum);
            }
        }

        /* ---------- TREND (inward vs issued) ---------- */
        long days = ChronoUnit.DAYS.between(p.from(), p.to()) + 1;
        boolean daily = days <= 31;
        Map<String, long[]> acc = new LinkedHashMap<>();
        if (daily) {
            for (LocalDate d = p.from(); !d.isAfter(p.to()); d = d.plusDays(1)) acc.put(d.toString(), new long[2]);
        } else {
            for (YearMonth ym = YearMonth.from(p.from()); !ym.isAfter(YearMonth.from(p.to())); ym = ym.plusMonths(1)) {
                acc.put(ym.toString(), new long[2]);
            }
        }
        for (StockInward u : units) {
            if (in(u.getCreatedAt(), start, end)) {
                long[] c = acc.get(daily ? u.getCreatedAt().toLocalDate().toString() : YearMonth.from(u.getCreatedAt()).toString());
                if (c != null) c[0]++;
            }
            if (in(u.getIssuedAt(), start, end)) {
                long[] c = acc.get(daily ? u.getIssuedAt().toLocalDate().toString() : YearMonth.from(u.getIssuedAt()).toString());
                if (c != null) c[1]++;
            }
        }
        long maxBucket = 1;
        for (long[] c : acc.values()) maxBucket = Math.max(maxBucket, Math.max(c[0], c[1]));
        List<Bucket> trend = new ArrayList<>();
        int idx = 0;
        for (Map.Entry<String, long[]> e : acc.entrySet()) {
            long[] c = e.getValue();
            String label, title;
            boolean show;
            if (daily) {
                LocalDate d = LocalDate.parse(e.getKey());
                label = String.valueOf(d.getDayOfMonth());
                title = d.format(DAY_TITLE_FMT);
                show = idx % 5 == 0;
            } else {
                YearMonth ym = YearMonth.parse(e.getKey());
                label = ym.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + String.valueOf(ym.getYear()).substring(2);
                title = ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
                show = true;
            }
            trend.add(new Bucket(label, title, show, c[0], c[1], pct(c[0], maxBucket), pct(c[1], maxBucket)));
            idx++;
        }

        /* ---------- STOCK AGING (available units) ---------- */
        long[] ageCount = new long[4];
        BigDecimal[] ageVal = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        for (StockInward u : units) {
            if (!"AVAILABLE".equals(u.getStatus()) || u.getCreatedAt() == null) continue;
            long age = ChronoUnit.DAYS.between(u.getCreatedAt().toLocalDate(), today);
            int b = age <= 30 ? 0 : age <= 60 ? 1 : age <= 90 ? 2 : 3;
            ageCount[b]++;
            ageVal[b] = ageVal[b].add(nz(u.getUnitPrice()));
        }
        String[] ageLabels = {"0\u201330 days", "31\u201360 days", "61\u201390 days", "90+ days"};
        String[] ageCss = {"a1", "a2", "a3", "a4"};
        List<AgingRow> aging = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            aging.add(new AgingRow(ageLabels[i], ageCount[i], inr(ageVal[i]),
                    available == 0 ? 0 : (int) Math.round(ageCount[i] * 100.0 / available), ageCss[i]));
        }

        /* ---------- PRODUCT + CATEGORY tables ---------- */
        Map<String, long[]> prodAgg = new LinkedHashMap<>();       // [inward, issued, available, damaged]
        Map<String, String> prodCat = new HashMap<>();
        Map<String, String> prodDisplay = new HashMap<>();
        Map<String, long[]> catAgg = new LinkedHashMap<>();        // [total, available, issued, damaged]
        Map<String, BigDecimal> catVal = new HashMap<>();
        for (StockInward u : units) {
            String pName = u.getProductId() == null ? null : productNames.get(u.getProductId());
            if (pName == null) pName = "Unassigned";
            String key = pName.toLowerCase(Locale.ROOT);
            String cName = categoryNames.get(u.getCategoryId());
            if (cName == null) cName = categoryNames.get(u.getProductId());
            if (cName == null) cName = "Uncategorised";

            long[] pa = prodAgg.computeIfAbsent(key, k -> new long[4]);
            prodDisplay.putIfAbsent(key, pName);
            prodCat.putIfAbsent(key, cName);
            if (in(u.getCreatedAt(), start, end)) pa[0]++;
            if (in(u.getIssuedAt(), start, end)) pa[1]++;
            if ("AVAILABLE".equals(u.getStatus())) pa[2]++;
            if ("DAMAGED".equals(u.getStatus())) pa[3]++;

            long[] ca = catAgg.computeIfAbsent(cName, k -> new long[4]);
            ca[0]++;
            if ("AVAILABLE".equals(u.getStatus())) {
                ca[1]++;
                catVal.merge(cName, nz(u.getUnitPrice()), BigDecimal::add);
            }
            if ("ISSUED".equals(u.getStatus())) ca[2]++;
            if ("DAMAGED".equals(u.getStatus())) ca[3]++;
        }
        List<ProductRow> productRows = prodAgg.entrySet().stream()
                .map(e -> new ProductRow(prodDisplay.get(e.getKey()), prodCat.get(e.getKey()),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .sorted((a, b) -> {
                    int c = Long.compare(b.issued(), a.issued());
                    if (c == 0) c = Long.compare(b.available(), a.available());
                    return c != 0 ? c : a.name().compareToIgnoreCase(b.name());
                })
                .collect(Collectors.toList());
        List<CategoryRow> categoryRows = catAgg.entrySet().stream()
                .map(e -> new CategoryRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2],
                        e.getValue()[3], inr(catVal.getOrDefault(e.getKey(), BigDecimal.ZERO))))
                .sorted((a, b) -> Long.compare(b.total(), a.total()))
                .collect(Collectors.toList());

        /* ---------- VENDOR inward (period) ---------- */
        Map<Long, long[]> vendUnits = new HashMap<>();
        Map<Long, BigDecimal> vendVal = new HashMap<>();
        for (StockInward u : units) {
            if (!in(u.getCreatedAt(), start, end)) continue;
            Long vid = u.getVendorId();
            vendUnits.computeIfAbsent(vid, k -> new long[1])[0]++;
            vendVal.merge(vid, nz(u.getUnitPrice()), BigDecimal::add);
        }
        Map<Long, String> vendorNames = new HashMap<>();
        Set<Long> vids = new HashSet<>(vendUnits.keySet());
        vids.remove(null);
        if (!vids.isEmpty()) {
            for (Vendor v : vendorRepo.findAllById(vids)) vendorNames.put(v.getId(), v.getVendorName());
        }
        final long inwardCurF = inwardCur;
        List<VendorRow> vendorRows = vendUnits.entrySet().stream()
                .map(e -> new VendorRow(
                        e.getKey() == null ? "Unknown vendor" : vendorNames.getOrDefault(e.getKey(), "Vendor #" + e.getKey()),
                        e.getValue()[0], inr(vendVal.getOrDefault(e.getKey(), BigDecimal.ZERO)),
                        inwardCurF == 0 ? 0 : (int) Math.round(e.getValue()[0] * 100.0 / inwardCurF)))
                .sorted((a, b) -> Long.compare(b.units(), a.units()))
                .collect(Collectors.toList());

        /* ---------- WARRANTY ---------- */
        LocalDate limit = today.plusDays(WARRANTY_WINDOW_DAYS);
        List<StockInward> expiringUnits = units.stream()
                .filter(u -> u.getWarrantyEndDate() != null
                        && !u.getWarrantyEndDate().isBefore(today)
                        && !u.getWarrantyEndDate().isAfter(limit))
                .sorted(Comparator.comparing(StockInward::getWarrantyEndDate))
                .collect(Collectors.toList());
        List<WarrantyLine> expiring = expiringUnits.stream().limit(5)
                .map(u -> new WarrantyLine(
                        u.getImeiNumber(),
                        productNames.getOrDefault(u.getProductId(), "-"),
                        u.getWarrantyEndDate().format(DATE_FMT),
                        ChronoUnit.DAYS.between(today, u.getWarrantyEndDate())))
                .collect(Collectors.toList());

        /* ---------- OUTWARD ---------- */
        long awaitingMe = 0, pendingNow = 0, onHoldNow = 0;
        long raisedCur = 0, raisedPrev = 0;
        long closedIssued = 0, closedAll = 0;
        long turnaroundSum = 0, turnaroundCnt = 0;
        List<FunnelRow> funnel = new ArrayList<>();
        List<LevelRow> levels = new ArrayList<>();
        List<CustomerRow> customerRows = new ArrayList<>();
        List<RequestLine> awaiting = new ArrayList<>();
        List<RequestLine> recentRequests = new ArrayList<>();

        if (showOutward) {
            List<StockOutwardRequest> requests;
            if (superAdmin) {
                requests = requestRepo.findAllByOrderByCreatedAtDesc();
            } else if (viewAll) {
                requests = requestRepo.findByCompanyNameOrderByCreatedAtDesc(company);
            } else {
                requests = requestRepo.findInvolving(company, me.getId(), me.getRoleId());
            }

            Map<Long, List<StockOutwardApproval>> approvalsBy = approvalsFor(
                    requests.stream().map(StockOutwardRequest::getId).collect(Collectors.toList()));

            long fRaised = 0, fIssued = 0, fRejected = 0, fCancelled = 0, fOpen = 0;
            Map<String, long[]> levelAgg = new LinkedHashMap<>();   // [sumMinutes, count]
            Map<String, long[]> custAgg = new LinkedHashMap<>();    // [requests, units]
            Map<String, BigDecimal> custAmt = new HashMap<>();

            for (StockOutwardRequest r : requests) {
                String st = r.getStatus();
                boolean open = StockOutwardService.PENDING.equals(st) || StockOutwardService.ON_HOLD.equals(st);
                List<StockOutwardApproval> approvals = approvalsBy.getOrDefault(r.getId(), List.of());

                StockOutwardApproval cur = null;
                if (StockOutwardService.PENDING.equals(st)) {
                    cur = approvals.stream()
                            .filter(a -> a.getLevelNo().equals(r.getCurrentLevel()))
                            .findFirst().orElse(null);
                    pendingNow++;
                } else if (StockOutwardService.ON_HOLD.equals(st)) {
                    onHoldNow++;
                }

                // raised in period / previous period
                if (in(r.getCreatedAt(), start, end)) {
                    raisedCur++;
                    fRaised++;
                    if (StockOutwardService.ISSUED.equals(st)) fIssued++;
                    else if (StockOutwardService.REJECTED.equals(st)) fRejected++;
                    else if (StockOutwardService.CANCELLED.equals(st)) fCancelled++;
                    else if (open) fOpen++;
                } else if (in(r.getCreatedAt(), pStart, pEnd)) {
                    raisedPrev++;
                }

                // closed in period
                if (!open && in(r.getCompletedAt(), start, end)) {
                    closedAll++;
                    if (StockOutwardService.ISSUED.equals(st)) {
                        closedIssued++;
                        if (r.getCreatedAt() != null) {
                            turnaroundSum += Math.max(0, Duration.between(r.getCreatedAt(), r.getCompletedAt()).toMinutes());
                            turnaroundCnt++;
                        }
                        String cust = customerKey(r);
                        long[] ca = custAgg.computeIfAbsent(cust, k -> new long[2]);
                        ca[0]++;
                        ca[1] += issuedUnitsByRequest.getOrDefault(r.getId(), 0L);
                        custAmt.merge(cust, nz(r.getTotalAmount()), BigDecimal::add);
                    }
                }

                // level-wise approval time (approvals acted in period)
                LocalDateTime prevAt = r.getCreatedAt();
                List<StockOutwardApproval> ordered = new ArrayList<>(approvals);
                ordered.sort(Comparator.comparing(StockOutwardApproval::getLevelNo));
                for (StockOutwardApproval a : ordered) {
                    boolean done = "APPROVED".equals(a.getStatus()) || "REJECTED".equals(a.getStatus());
                    if (!done || a.getActedAt() == null || prevAt == null) break;
                    if (in(a.getActedAt(), start, end)) {
                        long mins = Math.max(0, Duration.between(prevAt, a.getActedAt()).toMinutes());
                        long[] la = levelAgg.computeIfAbsent(a.getLevelName(), k -> new long[2]);
                        la[0] += mins;
                        la[1]++;
                    }
                    prevAt = a.getActedAt();
                }

                // awaiting me
                boolean mine = r.getRequestedById().equals(me.getId());
                if (cur != null && !mine && assignedTo(me, cur)) {
                    awaitingMe++;
                    if (awaiting.size() < 5) awaiting.add(line(r, cur));
                }
                if (recentRequests.size() < 6) recentRequests.add(line(r, cur));
            }

            funnel.add(new FunnelRow("Raised", fRaised, fRaised == 0 ? 0 : 100, "raised"));
            funnel.add(new FunnelRow("Issued", fIssued, pct100(fIssued, fRaised), "issued"));
            funnel.add(new FunnelRow("In progress", fOpen, pct100(fOpen, fRaised), "open"));
            funnel.add(new FunnelRow("Rejected", fRejected, pct100(fRejected, fRaised), "rejected"));
            funnel.add(new FunnelRow("Cancelled", fCancelled, pct100(fCancelled, fRaised), "cancelled"));

            long maxAvg = 1;
            for (long[] la : levelAgg.values()) if (la[1] > 0) maxAvg = Math.max(maxAvg, la[0] / la[1]);
            for (Map.Entry<String, long[]> e : levelAgg.entrySet()) {
                long avg = e.getValue()[1] == 0 ? 0 : e.getValue()[0] / e.getValue()[1];
                levels.add(new LevelRow(e.getKey(), dur(avg), e.getValue()[1], pct(avg, maxAvg)));
            }
            levels.sort((a, b) -> Integer.compare(b.pct(), a.pct()));

            BigDecimal maxAmt = custAmt.values().stream().max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
            for (Map.Entry<String, long[]> e : custAgg.entrySet()) {
                BigDecimal amt = custAmt.getOrDefault(e.getKey(), BigDecimal.ZERO);
                int w = maxAmt.signum() <= 0 ? 0
                        : (int) Math.max(4, amt.multiply(BigDecimal.valueOf(100)).divide(maxAmt, 0, RoundingMode.HALF_UP).longValue());
                customerRows.add(new CustomerRow(e.getKey(), e.getValue()[0], e.getValue()[1], inr(amt), amt.signum() <= 0 ? 0 : w));
            }
            customerRows.sort((a, b) -> Integer.compare(b.pct(), a.pct()));
        }

        String avgTurnaround = turnaroundCnt == 0 ? "\u2013" : dur(turnaroundSum / turnaroundCnt);
        String approvalRate = closedAll == 0 ? "\u2013" : Math.round(closedIssued * 100.0 / closedAll) + "%";

        /* ---------- KPIs ---------- */
        List<Kpi> snapshot = new ArrayList<>();
        if (showOutward) {
            snapshot.add(new Kpi("Awaiting my approval", String.valueOf(awaitingMe), "Outward requests needing you",
                    "purple", "/settings/outward", "", ""));
            snapshot.add(new Kpi("Open requests", String.valueOf(pendingNow + onHoldNow),
                    onHoldNow + " on hold", "amber", "/settings/outward", "", ""));
        }
        snapshot.add(new Kpi("Total units", String.valueOf(total), issuedAll + " issued so far",
                "blue", "/settings/stock-inward", "", ""));
        snapshot.add(new Kpi("Available in stock", String.valueOf(available), "Ready to issue",
                "green", "/settings/stock-inward", "", ""));
        snapshot.add(new Kpi("Available stock value", inr(availableValue), "At purchase price",
                "purple", "", "", ""));
        if (damaged > 0) {
            snapshot.add(new Kpi("Damaged units", String.valueOf(damaged), "Not available for issue",
                    "red", "", "", ""));
        }

        List<Kpi> performance = new ArrayList<>();
        String[] d1 = delta(inwardCur, inwardPrev);
        performance.add(new Kpi("Units inwarded", String.valueOf(inwardCur), "in this period",
                "blue", "", d1[0], d1[1]));
        String[] d2 = delta(issuedCur, issuedPrev);
        performance.add(new Kpi("Units issued", String.valueOf(issuedCur), "in this period",
                "green", "", d2[0], d2[1]));
        String[] d3 = delta(inwardValCur.doubleValue(), inwardValPrev.doubleValue());
        performance.add(new Kpi("Inward value", inr(inwardValCur), "at purchase price",
                "purple", "", d3[0], d3[1]));
        if (showOutward) {
            String[] d4 = delta(raisedCur, raisedPrev);
            performance.add(new Kpi("Requests raised", String.valueOf(raisedCur), "outward requests",
                    "amber", "", d4[0], d4[1]));
            performance.add(new Kpi("Approval rate", approvalRate,
                    closedIssued + " of " + closedAll + " closed were issued", "green", "", "", ""));
            performance.add(new Kpi("Avg. turnaround", avgTurnaround, "raised \u2192 issued",
                    "orange", "", "", ""));
        }

        String first = me.getFirstName() == null || me.getFirstName().isBlank() ? me.getUsername() : me.getFirstName();
        String companyLabel = superAdmin ? "All companies" : (company == null ? "" : company);

        return new DashboardView(
                first, companyLabel, today.format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy", Locale.ENGLISH)),
                p.key(), p.label(), prevLabel, p.from().toString(), p.to().toString(),
                showOutward,
                snapshot, performance,
                trend, daily ? "Daily" : "Monthly",
                total, slices, donut,
                funnel, avgTurnaround,
                aging, levels,
                productRows, categoryRows, vendorRows, customerRows,
                awaitingMe, awaiting, recentRequests,
                expiringUnits.size(), expiring,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH)));
    }

    /* ============================================================
       PERIOD
       ============================================================ */
    static Period resolve(String key, String fromStr, String toStr, LocalDate today) {
        String k = key == null ? DEFAULT_RANGE : key.trim().toLowerCase(Locale.ROOT);
        LocalDate from;
        LocalDate to = today;
        String label;
        switch (k) {
            case "last-7" -> { from = today.minusDays(6); label = "Last 7 days"; }
            case "last-90" -> { from = today.minusDays(89); label = "Last 90 days"; }
            case "this-month" -> { from = today.withDayOfMonth(1); label = "This month"; }
            case "this-year" -> { from = today.withDayOfYear(1); label = "This year"; }
            case "custom" -> {
                LocalDate f = parseDate(fromStr);
                LocalDate t = parseDate(toStr);
                if (f == null || t == null) {
                    k = "last-30";
                    from = today.minusDays(29);
                    label = "Last 30 days";
                } else {
                    if (f.isAfter(t)) { LocalDate x = f; f = t; t = x; }
                    if (t.isAfter(today)) t = today;
                    if (f.isAfter(t)) f = t;
                    if (ChronoUnit.DAYS.between(f, t) > 729) f = t.minusDays(729);
                    from = f;
                    to = t;
                    label = f.format(DATE_FMT) + " \u2013 " + t.format(DATE_FMT);
                }
            }
            default -> { k = "last-30"; from = today.minusDays(29); label = "Last 30 days"; }
        }
        long len = ChronoUnit.DAYS.between(from, to) + 1;
        LocalDate prevTo = from.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(len - 1);
        return new Period(k, label, from, to, prevFrom, prevTo);
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /* ============================================================
       HELPERS
       ============================================================ */
    private Map<Long, List<StockOutwardApproval>> approvalsFor(List<Long> ids) {
        Map<Long, List<StockOutwardApproval>> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i += ID_CHUNK) {
            List<Long> chunk = ids.subList(i, Math.min(ids.size(), i + ID_CHUNK));
            for (StockOutwardApproval a : approvalRepo.findByRequestIdIn(chunk)) {
                out.computeIfAbsent(a.getRequestId(), k -> new ArrayList<>()).add(a);
            }
        }
        return out;
    }

    private static boolean in(LocalDateTime t, LocalDateTime s, LocalDateTime e) {
        return t != null && !t.isBefore(s) && t.isBefore(e);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String customerKey(StockOutwardRequest r) {
        String c = r.getCustomerCompany();
        if (c != null && !c.isBlank()) return c.trim();
        String n = r.getCustomerName();
        return n == null || n.isBlank() ? "Unknown customer" : n.trim();
    }

    private void addSlice(List<StatusSlice> list, String label, long count, String color, long total) {
        if (count > 0) list.add(new StatusSlice(label, count, color, (int) Math.round(count * 100.0 / total)));
    }

    /** conic-gradient(color 0 x%, color x% y%, ...) */
    private String donutGradient(List<StatusSlice> slices, long total) {
        if (total == 0 || slices.isEmpty()) return "conic-gradient(#e5e7eb 0 100%)";
        StringBuilder sb = new StringBuilder("conic-gradient(");
        double from = 0;
        for (int i = 0; i < slices.size(); i++) {
            StatusSlice s = slices.get(i);
            double to = i == slices.size() - 1 ? 100.0 : from + (s.count() * 100.0 / total);
            if (i > 0) sb.append(", ");
            sb.append(s.color()).append(' ')
              .append(String.format(Locale.ROOT, "%.2f", from)).append("% ")
              .append(String.format(Locale.ROOT, "%.2f", to)).append('%');
            from = to;
        }
        return sb.append(')').toString();
    }

    /** bar width: min 4% taaki chhota bar bhi dikhe */
    private int pct(long value, long max) {
        if (max <= 0 || value <= 0) return 0;
        return (int) Math.max(4, Math.round(value * 100.0 / max));
    }

    /** real percentage (legend / funnel) */
    private int pct100(long value, long total) {
        if (total <= 0 || value <= 0) return 0;
        return (int) Math.round(value * 100.0 / total);
    }

    /** [text, css] : +12% / -5% / new / - */
    private String[] delta(double cur, double prev) {
        if (prev == 0) return cur == 0 ? new String[]{"\u2013", "flat"} : new String[]{"new", "up"};
        long r = Math.round((cur - prev) * 100.0 / prev);
        if (r == 0) return new String[]{"0%", "flat"};
        return new String[]{(r > 0 ? "+" : "") + r + "%", r > 0 ? "up" : "down"};
    }

    /** Indian grouping: ₹12,34,567 */
    static String inr(BigDecimal v) {
        long n = (v == null ? BigDecimal.ZERO : v).setScale(0, RoundingMode.HALF_UP).longValue();
        String s = Long.toString(Math.abs(n));
        String res;
        if (s.length() <= 3) {
            res = s;
        } else {
            String last3 = s.substring(s.length() - 3);
            String rest = s.substring(0, s.length() - 3).replaceAll("(\\d)(?=(\\d{2})+$)", "$1,");
            res = rest + "," + last3;
        }
        return (n < 0 ? "-" : "") + "\u20B9" + res;
    }

    /** 75 -> 1h 15m, 1500 -> 1d 1h */
    static String dur(long minutes) {
        if (minutes < 1) return "<1m";
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        if (h < 24) return h + "h " + (minutes % 60) + "m";
        return (h / 24) + "d " + (h % 24) + "h";
    }

    private boolean assignedTo(UserTable me, StockOutwardApproval a) {
        if (ApprovalChainService.TYPE_USER.equals(a.getApproverType())) {
            return me.getId().equals(a.getApproverUserId());
        }
        return me.getRoleId() != null && me.getRoleId().equals(a.getApproverRoleId());
    }

    private RequestLine line(StockOutwardRequest r, StockOutwardApproval cur) {
        String customer = customerKey(r);
        String status = r.getStatus();
        String label;
        String css;
        switch (status) {
            case StockOutwardService.ISSUED -> { label = "Issued"; css = "ok"; }
            case StockOutwardService.REJECTED -> { label = "Rejected"; css = "bad"; }
            case StockOutwardService.CANCELLED -> { label = "Cancelled"; css = "muted"; }
            case StockOutwardService.ON_HOLD -> { label = "On hold"; css = "hold"; }
            default -> { label = "Pending"; css = "pending"; }
        }
        String level = switch (status) {
            case StockOutwardService.ISSUED -> "Completed";
            case StockOutwardService.CANCELLED -> "Cancelled";
            case StockOutwardService.REJECTED -> "Rejected at L" + r.getCurrentLevel();
            default -> cur == null
                    ? "L" + r.getCurrentLevel() + "/" + r.getTotalLevels()
                    : "L" + r.getCurrentLevel() + "/" + r.getTotalLevels() + " \u00B7 " + cur.getLevelName();
        };
        boolean open = StockOutwardService.PENDING.equals(status) || StockOutwardService.ON_HOLD.equals(status);
        return new RequestLine(
                r.getRequestNo(),
                customer,
                inr(r.getTotalAmount()),
                label, css, level,
                open ? age(r.getUpdatedAt()) : "",
                StockOutwardService.detailUrl(r.getId()));
    }

    private String age(LocalDateTime from) {
        if (from == null) return "";
        long minutes = Duration.between(from, LocalDateTime.now()).toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h";
        return (hours / 24) + "d";
    }
}
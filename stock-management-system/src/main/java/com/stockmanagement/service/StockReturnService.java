package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.StockOutwardRequest;
import com.stockmanagement.entity.StockReturn;
import com.stockmanagement.entity.StockUnitHistory;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.entity.Vendor;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.StockInwardRepository;
import com.stockmanagement.repository.StockOutwardRequestRepository;
import com.stockmanagement.repository.StockReturnRepository;
import com.stockmanagement.repository.StockUnitHistoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Customer Return + Vendor Repair  ("return case").
 *
 * A sold unit (ISSUED) comes back. The SAME stock_inward row (same IMEI) is used - never a new inward.
 * One return case = one row in stock_return. When the device comes back the user decides:
 *   RESTOCK  -> unit AVAILABLE (REFURBISHED / NEW)            case CLOSED
 *   DAMAGED  -> unit DAMAGED                                  case CLOSED
 *   VENDOR   -> unit AT_VENDOR (sent for repair)              case WITH_VENDOR
 * and later, when the vendor gives it back (receiveFromVendor):
 *   REPAIRED        -> unit AVAILABLE (REFURBISHED / NEW)     case CLOSED
 *   REPLACED        -> old unit REPLACED, a NEW unit (new IMEI) is added as AVAILABLE   case CLOSED
 *   NOT_REPAIRABLE  -> unit DAMAGED                           case CLOSED
 * Exchange: on top of any of the above, a NEW device (AVAILABLE, same product) can be given to the
 * same customer in the same save.
 * Every step is also written to the unit's Device History.
 */
@Service
public class StockReturnService {

    private static final Logger log = LoggerFactory.getLogger(StockReturnService.class);

    public static final String PERMISSION = "STOCK_RETURN";
    public static final String EXCHANGE_PERMISSION = "STOCK_EXCHANGE";

    public static final String RESTOCK = "RESTOCK";
    public static final String DAMAGED = "DAMAGED";
    public static final String VENDOR = "VENDOR";

    public static final String WITH_VENDOR = "WITH_VENDOR";
    public static final String CLOSED = "CLOSED";

    private static final Set<String> RESOLUTIONS = Set.of(RESTOCK, DAMAGED, VENDOR);
    private static final Set<String> GOOD_CONDITIONS = Set.of("REFURBISHED", "NEW");
    private static final Set<String> VENDOR_RESULTS = Set.of("REPAIRED", "REPLACED", "NOT_REPAIRABLE");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);

    private final StockInwardRepository stockRepo;
    private final StockReturnRepository returnRepo;
    private final StockUnitHistoryRepository historyRepo;
    private final StockOutwardRequestRepository requestRepo;
    private final StockUnitHistoryService historyService;
    private final UserPermissionService permissionService;
    private final AuditLogService auditLogService;
    private final VendorService vendorService;
    private final ProductCategoryRepository categoryRepo;

    public StockReturnService(StockInwardRepository stockRepo,
                              StockReturnRepository returnRepo,
                              StockUnitHistoryRepository historyRepo,
                              StockOutwardRequestRepository requestRepo,
                              StockUnitHistoryService historyService,
                              UserPermissionService permissionService,
                              AuditLogService auditLogService,
                              VendorService vendorService,
                              ProductCategoryRepository categoryRepo) {
        this.stockRepo = stockRepo;
        this.returnRepo = returnRepo;
        this.historyRepo = historyRepo;
        this.requestRepo = requestRepo;
        this.historyService = historyService;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
        this.vendorService = vendorService;
        this.categoryRepo = categoryRepo;
    }

    // ===================== forms (what the screen sends) =====================

    public record ReturnForm(Long stockId, String resolution, String conditionAfter, LocalDate returnDate,
                             String reason, Long vendorId, String challanNo, String vendorIssue,
                             LocalDate sentDate, LocalDate expectedBackDate, String exchangeImei) {}

    public record ReceiveForm(Long caseId, String result, LocalDate receivedDate, String conditionAfter,
                              String replacementImei, BigDecimal repairCost, String notes) {}

    // ===================== small data holders for the screen =====================

    public record SaleInfo(Long requestId, String requestNo, String customerName,
                           BigDecimal price, LocalDateTime soldAt, String soldBy) {
        public Long getRequestId() { return requestId; }
        public String getRequestNo() { return requestNo; }
        public String getCustomerName() { return customerName; }
        public BigDecimal getPrice() { return price; }
        public LocalDateTime getSoldAt() { return soldAt; }
        public String getSoldBy() { return soldBy; }
    }

    /** state: IN (in warranty), EXPIRED, UNKNOWN (warranty not recorded). */
    public record WarrantyInfo(LocalDate endDate, String label, String state) {
        public LocalDate getEndDate() { return endDate; }
        public String getLabel() { return label; }
        public String getState() { return state; }
    }

    public record LookupResult(StockInward unit, String productName, String vendorName,
                               SaleInfo sale, WarrantyInfo warranty,
                               long timesSold, long timesReturned,
                               boolean canReturn, String message,
                               List<Vendor> vendors,
                               boolean canExchange, List<String> exchangeImeis) {
        public StockInward getUnit() { return unit; }
        public String getProductName() { return productName; }
        public String getVendorName() { return vendorName; }
        public SaleInfo getSale() { return sale; }
        public WarrantyInfo getWarranty() { return warranty; }
        public long getTimesSold() { return timesSold; }
        public long getTimesReturned() { return timesReturned; }
        public boolean getCanReturn() { return canReturn; }
        public String getMessage() { return message; }
        public List<Vendor> getVendors() { return vendors; }
        public boolean getCanExchange() { return canExchange; }
        public List<String> getExchangeImeis() { return exchangeImeis; }
    }

    /** One device that is at a vendor right now. */
    public record VendorCase(StockReturn kase, long daysAtVendor, boolean overdue) {
        public StockReturn getKase() { return kase; }
        public long getDaysAtVendor() { return daysAtVendor; }
        public boolean getOverdue() { return overdue; }
    }

    // ===================== lookup / lists =====================

    @Transactional(readOnly = true)
    public LookupResult lookup(String imeiRaw, CustomUserDetails actor) {
        requireAccess(actor);
        String imei = imeiRaw == null ? "" : imeiRaw.trim();
        if (imei.isEmpty()) {
            throw new IllegalArgumentException("Type the full IMEI of the returned device.");
        }

        UserTable me = actor.getUser();
        Optional<StockInward> found = isSuperAdmin(actor)
                ? stockRepo.findFirstByImeiNumberOrderByIdDesc(imei)
                : stockRepo.findByImeiNumberAndCompanyName(imei, me.getCompanyName());

        if (found.isEmpty()) {
            return new LookupResult(null, null, null, null, null, 0, 0, false,
                    "No unit with IMEI \"" + imei + "\" was found in your stock. Check the number and try again.",
                    List.of(), false, List.of());
        }

        StockInward unit = found.get();
        SaleInfo sale = saleInfoOf(unit);
        WarrantyInfo warranty = warrantyOf(unit, LocalDate.now());
        long sold = historyRepo.countByStockIdAndEventType(unit.getId(), StockUnitHistoryService.SOLD);
        long returned = historyRepo.countByStockIdAndEventType(unit.getId(), StockUnitHistoryService.RETURNED);

        boolean canReturn = "ISSUED".equals(unit.getStatus());
        String message = null;
        if (!canReturn) {
            String status = String.valueOf(unit.getStatus());
            if ("AT_VENDOR".equals(status)) {
                Optional<StockReturn> open = returnRepo
                        .findFirstByStockIdAndCaseStatusOrderByIdDesc(unit.getId(), WITH_VENDOR);
                message = open.map(c -> "This unit is with vendor " + c.getVendorName()
                                + (c.getSentDate() != null ? " since " + c.getSentDate().format(DATE_FMT) : "")
                                + " (case " + c.getCaseNo() + "). Receive it back from the \"Currently with vendor\" list below.")
                        .orElse("This unit is with a vendor. Receive it back from the \"Currently with vendor\" list below.");
            } else if ("AVAILABLE".equals(status)) {
                message = "This unit is already in stock (AVAILABLE) - it was not sold, or it has already been returned.";
            } else if ("DAMAGED".equals(status)) {
                message = "This unit is already in stock as DAMAGED - it is not with a customer.";
            } else if ("REPLACED".equals(status)) {
                message = "This unit was replaced by the vendor and is no longer in stock.";
            } else {
                message = "This unit has status " + status + " and cannot be returned from here.";
            }
        }

        List<Vendor> vendors = canReturn
                ? vendorService.getVendorsForCompany(unit.getCompanyName())
                : List.of();

        boolean canExchange = canReturn && hasExchangeAccess(actor);
        List<String> exchangeImeis = canExchange ? availableSameProduct(unit) : List.of();

        return new LookupResult(unit, unit.getProductName(), unit.getVendorName(),
                sale, warranty, sold, returned, canReturn, message, vendors, canExchange, exchangeImeis);
    }

    @Transactional(readOnly = true)
    public List<StockReturn> recent(CustomUserDetails actor) {
        requireAccess(actor);
        return isSuperAdmin(actor)
                ? returnRepo.findTop20ByOrderByIdDesc()
                : returnRepo.findTop20ByCompanyNameOrderByIdDesc(actor.getUser().getCompanyName());
    }

    /** Devices that are at a vendor right now, oldest first. */
    @Transactional(readOnly = true)
    public List<VendorCase> withVendor(CustomUserDetails actor) {
        requireAccess(actor);
        List<StockReturn> cases = isSuperAdmin(actor)
                ? returnRepo.findByCaseStatusOrderBySentDateAscIdAsc(WITH_VENDOR)
                : returnRepo.findByCompanyNameAndCaseStatusOrderBySentDateAscIdAsc(
                        actor.getUser().getCompanyName(), WITH_VENDOR);
        LocalDate today = LocalDate.now();
        List<VendorCase> out = new ArrayList<>();
        for (StockReturn c : cases) {
            long days = c.getSentDate() == null ? 0 : ChronoUnit.DAYS.between(c.getSentDate(), today);
            boolean overdue = c.getExpectedBackDate() != null && today.isAfter(c.getExpectedBackDate());
            out.add(new VendorCase(c, days, overdue));
        }
        return out;
    }

    // ===================== 1) the customer return =====================

    @Transactional
    public StockReturn doReturn(ReturnForm f, CustomUserDetails actor, String ip) {
        requireAccess(actor);
        UserTable me = actor.getUser();
        String actorName = ApprovalChainService.fullName(me);

        String res = f.resolution() == null ? "" : f.resolution().trim().toUpperCase(Locale.ROOT);
        if (!RESOLUTIONS.contains(res)) {
            throw new IllegalArgumentException("Choose what to do with the returned device.");
        }
        String reason = f.reason() == null ? "" : f.reason().trim();
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("Please write the reason for the return.");
        }
        if (reason.length() > 1000) {
            throw new IllegalArgumentException("Reason is too long (max 1000 characters).");
        }
        LocalDate today = LocalDate.now();
        LocalDate day = f.returnDate() == null ? today : f.returnDate();
        if (day.isAfter(today)) {
            throw new IllegalArgumentException("Return date cannot be in the future.");
        }
        if (f.stockId() == null) {
            throw new IllegalArgumentException("Unit not found.");
        }

        String cond = null;
        if (RESTOCK.equals(res)) {
            cond = f.conditionAfter() == null ? "" : f.conditionAfter().trim().toUpperCase(Locale.ROOT);
            if (!GOOD_CONDITIONS.contains(cond)) {
                throw new IllegalArgumentException("Choose the condition of the device (REFURBISHED or NEW).");
            }
        }

        StockInward unit = stockRepo.findByIdForUpdate(f.stockId())
                .orElseThrow(() -> new IllegalArgumentException("Unit not found."));
        if (!isSuperAdmin(actor) && !unit.getCompanyName().equals(me.getCompanyName())) {
            throw new AccessDeniedException("Not your company's unit.");
        }
        if (!"ISSUED".equals(unit.getStatus())) {
            throw new IllegalStateException("This unit is not with a customer (status " + unit.getStatus()
                    + "). It may already have been returned.");
        }

        SaleInfo sale = saleInfoOf(unit);
        if (sale != null && sale.soldAt() != null && day.isBefore(sale.soldAt().toLocalDate())) {
            throw new IllegalArgumentException("Return date cannot be before the sale date ("
                    + sale.soldAt().toLocalDate().format(DATE_FMT) + ").");
        }
        WarrantyInfo warranty = warrantyOf(unit, day);

        // ---- exchange: the NEW device that goes to the customer (checked before anything is changed) ----
        String exchangeImei = clean(f.exchangeImei(), 60);
        StockInward newUnit = null;
        if (exchangeImei != null) {
            if (!hasExchangeAccess(actor)) {
                throw new AccessDeniedException("Exchange is not enabled for your role.");
            }
            if (exchangeImei.equals(unit.getImeiNumber())) {
                throw new IllegalArgumentException("The new device must be a different IMEI from the returned one.");
            }
            StockInward found = stockRepo.findByImeiNumberAndCompanyName(exchangeImei, unit.getCompanyName())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "No device with IMEI \"" + f.exchangeImei().trim() + "\" was found in your stock."));
            newUnit = stockRepo.findByIdForUpdate(found.getId())
                    .orElseThrow(() -> new IllegalArgumentException("The new device was not found."));
            if (!"AVAILABLE".equals(newUnit.getStatus())) {
                throw new IllegalStateException("The new device (IMEI " + exchangeImei + ") is not AVAILABLE (status "
                        + newUnit.getStatus() + "). Choose another device.");
            }
            if (!sameProduct(unit, newUnit)) {
                throw new IllegalArgumentException("The new device must be the same product ("
                        + unit.getProductName() + ").");
            }
        }
        final String exText = newUnit == null ? ""
                : " Exchange: replacement IMEI " + newUnit.getImeiNumber() + " given to the customer.";

        // ---- vendor details (only when the device is being sent for repair) ----
        Vendor vendor = null;
        LocalDate sentDate = null;
        LocalDate expectedBack = null;
        String challan = null;
        String vendorIssue = null;
        if (VENDOR.equals(res)) {
            if (f.vendorId() == null) {
                throw new IllegalArgumentException("Choose the vendor the device is being sent to.");
            }
            final Long wantedVendor = f.vendorId();
            vendor = vendorService.getVendorsForCompany(unit.getCompanyName()).stream()
                    .filter(v -> v.getId().equals(wantedVendor))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Selected vendor is not in your vendor list."));
            sentDate = f.sentDate() == null ? day : f.sentDate();
            if (sentDate.isBefore(day)) {
                throw new IllegalArgumentException("Sent-to-vendor date cannot be before the return date.");
            }
            if (sentDate.isAfter(today)) {
                throw new IllegalArgumentException("Sent-to-vendor date cannot be in the future.");
            }
            expectedBack = f.expectedBackDate();
            if (expectedBack != null && expectedBack.isBefore(sentDate)) {
                throw new IllegalArgumentException("Expected-back date cannot be before the sent date.");
            }
            challan = clean(f.challanNo(), 100);
            vendorIssue = clean(f.vendorIssue(), 1000);
            if (vendorIssue == null) vendorIssue = clean(reason, 1000);
        }

        // ---- 1) the unit (same row, same IMEI) ----
        String newStatus;
        String condAfter;
        if (RESTOCK.equals(res)) {
            newStatus = "AVAILABLE";
            condAfter = cond;
            unit.setCondition(cond);
        } else if (DAMAGED.equals(res)) {
            newStatus = "DAMAGED";
            condAfter = "DAMAGED";
            unit.setRemarks(reason.length() > 500 ? reason.substring(0, 500) : reason);
        } else {
            newStatus = "AT_VENDOR";
            condAfter = unit.getCondition() == null ? "NEW" : unit.getCondition();
        }
        unit.setStatus(newStatus);
        stockRepo.save(unit);

        // ---- 2) the return case ----
        StockReturn r = new StockReturn();
        r.setCompanyName(unit.getCompanyName());
        r.setStockId(unit.getId());
        r.setImeiNumber(unit.getImeiNumber());
        r.setProductName(unit.getProductName());
        if (sale != null) {
            r.setRequestId(sale.requestId());
            r.setRequestNo(sale.requestNo());
            r.setCustomerName(sale.customerName());
            r.setSoldAt(sale.soldAt());
            r.setSoldPrice(sale.price());
        }
        r.setReturnDate(day);
        r.setReason(reason);
        r.setConditionAfter(condAfter);
        r.setStatusAfter(newStatus);
        r.setWarrantyEndDate(warranty.endDate());
        r.setInWarranty("UNKNOWN".equals(warranty.state()) ? null : "IN".equals(warranty.state()));
        r.setCreatedById(me.getId());
        r.setCreatedBy(actorName);
        r.setResolution(res);
        if (newUnit != null) {
            r.setExchangeStockId(newUnit.getId());
            r.setExchangeImei(newUnit.getImeiNumber());
        }
        if (VENDOR.equals(res)) {
            r.setCaseStatus(WITH_VENDOR);
            r.setVendorId(vendor.getId());
            r.setVendorName(vendor.getVendorName());
            r.setChallanNo(challan);
            r.setVendorIssue(vendorIssue);
            r.setSentDate(sentDate);
            r.setExpectedBackDate(expectedBack);
        } else {
            r.setCaseStatus(CLOSED);
            r.setClosedAt(LocalDateTime.now());
            r.setClosedBy(actorName);
        }
        r = returnRepo.save(r);
        r.setCaseNo(String.format("RC-%d-%06d", today.getYear(), r.getId()));
        r = returnRepo.save(r);

        // ---- 3) Device History ----
        Long reqId = sale == null ? null : sale.requestId();
        String reqNo = sale == null ? null : sale.requestNo();
        String cust = sale == null ? null : sale.customerName();

        // ---- 2b) exchange: the new device goes to the same customer ----
        if (newUnit != null) {
            newUnit.setStatus("ISSUED");
            newUnit.setOutwardRequestId(reqId);
            newUnit.setIssuedAt(LocalDateTime.now());
            stockRepo.save(newUnit);
            historyService.record(newUnit, StockUnitHistoryService.SOLD, "AVAILABLE", "ISSUED",
                    reqId, reqNo, cust, null,
                    "Given in exchange for IMEI " + unit.getImeiNumber() + " (case " + r.getCaseNo() + ")",
                    me.getId(), actorName, day);
        }

        if (VENDOR.equals(res)) {
            historyService.record(unit, StockUnitHistoryService.RETURNED, "ISSUED", "RETURNED",
                    reqId, reqNo, cust, null,
                    "Case " + r.getCaseNo() + ": returned by customer. Warranty: " + warranty.label()
                            + ". Reason: " + reason + "." + exText,
                    me.getId(), actorName, day);
            historyService.record(unit, StockUnitHistoryService.SENT_TO_VENDOR, "RETURNED", "AT_VENDOR",
                    null, null, null, null,
                    "Sent to vendor " + vendor.getVendorName()
                            + (challan != null ? ", challan " + challan : "")
                            + ". Problem: " + vendorIssue
                            + (expectedBack != null ? ". Expected back: " + expectedBack.format(DATE_FMT) : ""),
                    me.getId(), actorName, sentDate);
        } else {
            boolean damaged = DAMAGED.equals(res);
            historyService.record(unit, StockUnitHistoryService.RETURNED, "ISSUED", newStatus,
                    reqId, reqNo, cust, null,
                    "Case " + r.getCaseNo() + ": returned by customer. After return: " + condAfter
                            + (damaged ? " (marked DAMAGED)" : " - back in stock (AVAILABLE)")
                            + ". Warranty: " + warranty.label() + ". Reason: " + reason + "." + exText,
                    me.getId(), actorName, day);
        }

        // ---- 4) audit log (never blocks the return) ----
        audit(me, ip, newUnit == null ? "RETURN" : "EXCHANGE",
                "Customer " + (newUnit == null ? "return " : "exchange ") + r.getCaseNo() + " of IMEI "
                        + unit.getImeiNumber() + " -> " + newStatus
                        + (newUnit != null ? ", new IMEI " + newUnit.getImeiNumber() + " issued" : "")
                        + (reqNo != null ? " (sale " + reqNo + ")" : ""),
                unit.getId());
        return r;
    }

    // ===================== 2) the vendor gives the device back =====================

    @Transactional
    public StockReturn receiveFromVendor(ReceiveForm f, CustomUserDetails actor, String ip) {
        requireAccess(actor);
        UserTable me = actor.getUser();
        String actorName = ApprovalChainService.fullName(me);

        if (f.caseId() == null) {
            throw new IllegalArgumentException("Case not found.");
        }
        String result = f.result() == null ? "" : f.result().trim().toUpperCase(Locale.ROOT);
        if (!VENDOR_RESULTS.contains(result)) {
            throw new IllegalArgumentException("Choose what the vendor did (repaired / replaced / not repairable).");
        }
        String notes = clean(f.notes(), 1000);
        if ("NOT_REPAIRABLE".equals(result) && notes == null) {
            throw new IllegalArgumentException("Write the vendor's reason in Notes when the device is not repairable.");
        }
        if (f.repairCost() != null && f.repairCost().signum() < 0) {
            throw new IllegalArgumentException("Repair cost cannot be negative.");
        }
        if (f.repairCost() != null && f.repairCost().compareTo(new BigDecimal("9999999.99")) > 0) {
            throw new IllegalArgumentException("Repair cost is too large.");
        }

        StockReturn r = returnRepo.findById(f.caseId())
                .orElseThrow(() -> new IllegalArgumentException("Case not found."));
        if (!isSuperAdmin(actor) && !r.getCompanyName().equals(me.getCompanyName())) {
            throw new AccessDeniedException("Not your company's case.");
        }

        StockInward unit = stockRepo.findByIdForUpdate(r.getStockId())
                .orElseThrow(() -> new IllegalArgumentException("Unit not found."));
        if (!WITH_VENDOR.equals(r.getCaseStatus()) || !"AT_VENDOR".equals(unit.getStatus())) {
            throw new IllegalStateException("This device is not with a vendor any more. It may already have been received.");
        }

        LocalDate today = LocalDate.now();
        LocalDate day = f.receivedDate() == null ? today : f.receivedDate();
        if (day.isAfter(today)) {
            throw new IllegalArgumentException("Received date cannot be in the future.");
        }
        if (r.getSentDate() != null && day.isBefore(r.getSentDate())) {
            throw new IllegalArgumentException("Received date cannot be before the sent date ("
                    + r.getSentDate().format(DATE_FMT) + ").");
        }

        String cond = f.conditionAfter() == null || f.conditionAfter().isBlank()
                ? "REFURBISHED" : f.conditionAfter().trim().toUpperCase(Locale.ROOT);
        if (!"NOT_REPAIRABLE".equals(result) && !GOOD_CONDITIONS.contains(cond)) {
            throw new IllegalArgumentException("Choose the condition of the device (REFURBISHED or NEW).");
        }

        String vendorLabel = r.getVendorName() == null ? "vendor" : r.getVendorName();
        String costText = f.repairCost() == null ? ""
                : ". Repair cost: Rs. " + String.format("%,.2f", f.repairCost());
        String notesText = notes == null ? "" : ". Notes: " + notes;
        String oldImei = unit.getImeiNumber();

        if ("REPAIRED".equals(result)) {
            unit.setStatus("AVAILABLE");
            unit.setCondition(cond);
            stockRepo.save(unit);

            r.setStatusAfter("AVAILABLE");
            r.setConditionAfter(cond);
            historyService.record(unit, StockUnitHistoryService.RECEIVED_FROM_VENDOR, "AT_VENDOR", "AVAILABLE",
                    null, null, null, null,
                    "Case " + r.getCaseNo() + ": repaired by " + vendorLabel + ". Back in stock as " + cond
                            + costText + notesText,
                    me.getId(), actorName, day);

        } else if ("NOT_REPAIRABLE".equals(result)) {
            unit.setStatus("DAMAGED");
            unit.setRemarks(notes.length() > 500 ? notes.substring(0, 500) : notes);
            stockRepo.save(unit);

            r.setStatusAfter("DAMAGED");
            r.setConditionAfter("DAMAGED");
            historyService.record(unit, StockUnitHistoryService.RECEIVED_FROM_VENDOR, "AT_VENDOR", "DAMAGED",
                    null, null, null, null,
                    "Case " + r.getCaseNo() + ": " + vendorLabel + " could not repair it. Marked DAMAGED"
                            + costText + notesText,
                    me.getId(), actorName, day);

        } else { // REPLACED
            String newImei = f.replacementImei() == null ? "" : f.replacementImei().trim();
            if (newImei.isEmpty()) {
                throw new IllegalArgumentException("Enter the IMEI of the replacement device the vendor gave.");
            }
            if (newImei.equals(oldImei)) {
                throw new IllegalArgumentException("The replacement IMEI must be different from the old one.");
            }
            if (stockRepo.existsByImeiNumberAndCompanyName(newImei, unit.getCompanyName())) {
                throw new IllegalArgumentException("A unit with IMEI \"" + newImei + "\" already exists.");
            }

            // the new device = a new stock row (it really is a different physical unit)
            StockInward n = new StockInward();
            n.setCompanyName(unit.getCompanyName());
            n.setVendorId(r.getVendorId() != null ? r.getVendorId() : unit.getVendorId());
            n.setCategoryId(unit.getCategoryId());
            n.setProductId(unit.getProductId());
            n.setImeiNumber(newImei);
            n.setCondition(cond);
            n.setQuantity(1);
            n.setUnitPrice(unit.getUnitPrice());
            n.setTaxPercent(unit.getTaxPercent());
            if (unit.getUnitPrice() != null) {
                BigDecimal total = unit.getUnitPrice().setScale(2, RoundingMode.HALF_UP);
                BigDecimal grand = total;
                if (unit.getTaxPercent() != null) {
                    grand = total.add(total.multiply(unit.getTaxPercent())
                            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
                }
                n.setTotalAmount(total);
                n.setGrandTotal(grand.setScale(2, RoundingMode.HALF_UP));
            }
            n.setPurchaseDate(day);
            n.setInvoiceNumber(r.getChallanNo());
            n.setWarehouse(unit.getWarehouse());
            n.setStatus("AVAILABLE");
            n.setRemarks("Replacement for IMEI " + oldImei + " (case " + r.getCaseNo() + ")");
            n.setCreatedBy(me.getUsername());
            n = stockRepo.save(n);

            // the old device is gone (kept by the vendor)
            unit.setStatus("REPLACED");
            unit.setRemarks("Replaced by vendor. New IMEI " + newImei + " (case " + r.getCaseNo() + ")");
            stockRepo.save(unit);

            r.setStatusAfter("REPLACED");
            r.setConditionAfter(cond);
            r.setReplacementStockId(n.getId());
            r.setReplacementImei(newImei);

            historyService.record(unit, StockUnitHistoryService.RECEIVED_FROM_VENDOR, "AT_VENDOR", "REPLACED",
                    null, null, null, null,
                    "Case " + r.getCaseNo() + ": " + vendorLabel + " replaced this device with IMEI " + newImei
                            + costText + notesText,
                    me.getId(), actorName, day);
            historyService.recordInward(n, me.getUsername());
            historyService.record(n, StockUnitHistoryService.RECEIVED_FROM_VENDOR, null, "AVAILABLE",
                    null, null, null, null,
                    "Replacement from " + vendorLabel + " for IMEI " + oldImei + " (case " + r.getCaseNo() + "). Condition: " + cond,
                    me.getId(), actorName, day);
        }

        r.setVendorResult(result);
        r.setVendorReceivedDate(day);
        r.setVendorNotes(notes);
        r.setRepairCost(f.repairCost());
        r.setCaseStatus(CLOSED);
        r.setClosedAt(LocalDateTime.now());
        r.setClosedBy(actorName);
        r = returnRepo.save(r);

        audit(me, ip, "VENDOR_RECEIVE",
                "Received from vendor: case " + r.getCaseNo() + ", IMEI " + oldImei + " -> " + result,
                unit.getId());
        return r;
    }

    // ===================== helpers =====================

    /** Who bought this unit last: from Device History, or from the outward request for older units. */
    private SaleInfo saleInfoOf(StockInward unit) {
        Optional<StockUnitHistory> sold = historyRepo
                .findFirstByStockIdAndEventTypeOrderByCreatedAtDescIdDesc(unit.getId(), StockUnitHistoryService.SOLD);
        if (sold.isPresent()) {
            StockUnitHistory h = sold.get();
            return new SaleInfo(h.getRequestId(), h.getRequestNo(), h.getCustomerName(),
                    h.getAmount(), h.getCreatedAt(), h.getActorName());
        }
        if (unit.getOutwardRequestId() != null) {
            return requestRepo.findById(unit.getOutwardRequestId())
                    .map(r -> new SaleInfo(r.getId(), r.getRequestNo(), customerLabel(r),
                            null, r.getCompletedAt(), null))
                    .orElse(null);
        }
        return null;
    }

    private String customerLabel(StockOutwardRequest r) {
        String company = r.getCustomerCompany();
        if (company == null || company.isBlank()) return r.getCustomerName();
        return company + " (" + r.getCustomerName() + ")";
    }

    /** Warranty as recorded at inward (end date). Customer-specific warranty comes in a later step. */
    private WarrantyInfo warrantyOf(StockInward unit, LocalDate asOf) {
        LocalDate end = unit.getWarrantyEndDate();
        if (end == null) {
            return new WarrantyInfo(null, "Not recorded", "UNKNOWN");
        }
        long days = ChronoUnit.DAYS.between(asOf, end);
        if (days >= 0) {
            return new WarrantyInfo(end,
                    "In warranty - " + days + (days == 1 ? " day" : " days") + " left (till " + end.format(DATE_FMT) + ")",
                    "IN");
        }
        long ago = -days;
        return new WarrantyInfo(end,
                "Expired " + ago + (ago == 1 ? " day" : " days") + " ago (on " + end.format(DATE_FMT) + ")",
                "EXPIRED");
    }

    private String clean(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }

    private void audit(UserTable me, String ip, String action, String description, Long stockId) {
        try {
            auditLogService.log(me.getId(), me.getUsername(), action, "Stock", "Customer Return",
                    description, ip, stockId, "STOCK_RETURN");
        } catch (Exception e) {
            log.warn("Audit log failed ({}): {}", action, e.getMessage());
        }
    }

    /** All product rows of this company with the same product name (same idea as Stock Outward). */
    private List<Long> sameProductIds(StockInward unit) {
        if (unit.getProductId() == null) return List.of();
        String name = categoryRepo.findById(unit.getProductId())
                .map(ProductCategory::getProductName).orElse(null);
        if (name == null || name.isBlank()) return List.of(unit.getProductId());
        String wanted = name.trim();
        List<Long> ids = categoryRepo.findByCompanyName(unit.getCompanyName()).stream()
                .filter(p -> p.getProductName() != null && p.getProductName().trim().equalsIgnoreCase(wanted))
                .map(ProductCategory::getId)
                .collect(Collectors.toList());
        if (!ids.contains(unit.getProductId())) ids.add(unit.getProductId());
        return ids;
    }

    private boolean sameProduct(StockInward a, StockInward b) {
        if (a.getProductId() == null || b.getProductId() == null) return false;
        return sameProductIds(a).contains(b.getProductId());
    }

    /** IMEIs of AVAILABLE units of the same product (up to 50) for the exchange drop-down. */
    private List<String> availableSameProduct(StockInward unit) {
        List<Long> ids = sameProductIds(unit);
        if (ids.isEmpty()) return List.of();
        return stockRepo.findByCompanyNameAndStatusAndProductIdInOrderByCreatedAtAscIdAsc(
                        unit.getCompanyName(), "AVAILABLE", ids, PageRequest.of(0, 50))
                .stream().map(StockInward::getImeiNumber).collect(Collectors.toList());
    }

    private boolean hasExchangeAccess(CustomUserDetails actor) {
        return permissionService.hasPermission(actor.getUserId(), EXCHANGE_PERMISSION)
                || permissionService.hasPermission(actor.getUserId(), "SUPER_ADMIN");
    }

    private boolean isSuperAdmin(CustomUserDetails actor) {
        return permissionService.hasPermission(actor.getUserId(), "SUPER_ADMIN");
    }

    private void requireAccess(CustomUserDetails actor) {
        if (!permissionService.hasPermission(actor.getUserId(), PERMISSION)
                && !permissionService.hasPermission(actor.getUserId(), "SUPER_ADMIN")) {
            throw new AccessDeniedException("Customer Return is not enabled for your role.");
        }
    }
}
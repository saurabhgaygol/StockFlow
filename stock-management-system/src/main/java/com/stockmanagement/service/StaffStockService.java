package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StaffStockMovement;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.StockReturn;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.StaffStockMovementRepository;
import com.stockmanagement.repository.StockInwardRepository;
import com.stockmanagement.repository.StockReturnRepository;
import com.stockmanagement.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * FIELD STAFF STOCK.
 *
 * Staff = active users ticked as "Field staff" in Settings > User. A device given to staff keeps its row in
 * stock_inward (same IMEI) with status WITH_STAFF and holder_user_id = the staff member, so every
 * existing screen / query that looks for AVAILABLE units simply never sees it.
 *
 *   office  -> staff    issue()
 *   staff   -> office   takeBack()          (AVAILABLE, or DAMAGED when faulty)
 *   staff   -> staff    transfer()
 *   staff   -> customer applyExchange()     (Customer Return > Exchange, TEMPORARY or PERMANENT)
 *                       markSold()          (Stock Outward final approval)
 *   temporary fit:      makePermanent() / tempTakenBack()
 */
@Service
public class StaffStockService {

    private static final Logger log = LoggerFactory.getLogger(StaffStockService.class);

    public static final String ISSUE_PERMISSION = "STAFF_ISSUE";
    public static final String VIEW_PERMISSION = "STAFF_STOCK_VIEW";
    public static final String WITH_STAFF = "WITH_STAFF";
    public static final String TEMPORARY = "TEMPORARY";
    public static final String PERMANENT = "PERMANENT";

    // Device History event names (the timeline falls back to a generic style for these)
    public static final String EV_ISSUED = "ISSUED_TO_STAFF";
    public static final String EV_RETURNED = "STAFF_RETURNED";
    public static final String EV_TRANSFER = "STAFF_TRANSFER";
    public static final String EV_FITTED_TEMP = "STAFF_FITTED_TEMP";
    public static final String EV_TEMP_BACK = "STAFF_TEMP_BACK";

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final StockInwardRepository stockRepo;
    private final UserRepository userRepo;
    private final ProductCategoryRepository categoryRepo;
    private final StaffStockMovementRepository moveRepo;
    private final StockReturnRepository returnRepo;
    private final StockUnitHistoryService historyService;
    private final UserPermissionService permissionService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    public StaffStockService(StockInwardRepository stockRepo, UserRepository userRepo,
                             ProductCategoryRepository categoryRepo, StaffStockMovementRepository moveRepo,
                             StockReturnRepository returnRepo, StockUnitHistoryService historyService,
                             UserPermissionService permissionService, NotificationService notificationService,
                             AuditLogService auditLogService) {
        this.stockRepo = stockRepo;
        this.userRepo = userRepo;
        this.categoryRepo = categoryRepo;
        this.moveRepo = moveRepo;
        this.returnRepo = returnRepo;
        this.historyService = historyService;
        this.permissionService = permissionService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
    }

    // ===================== what the screens receive =====================

    public record StaffOption(Long id, String name, String city, long inHand, long temporary) {
        public Long getId() { return id; }
        public String getName() { return name; }
        public String getCity() { return city; }
        public long getInHand() { return inHand; }
        public long getTemporary() { return temporary; }
        public long getTotal() { return inHand + temporary; }
    }

    /** One device in a pick-list popup (serialised to JSON). */
    public record UnitOption(Long id, String category, String product, String imei, String since) {}

    /** One device in the Staff Stock report. */
    public record HeldUnit(Long id, String category, String product, String imei, String since,
                           String kind, String customer, String vehicle, String installedOn, Long returnId) {
        public Long getId() { return id; }
        public String getCategory() { return category; }
        public String getProduct() { return product; }
        public String getImei() { return imei; }
        public String getSince() { return since; }
        public String getKind() { return kind; }
        public String getCustomer() { return customer; }
        public String getVehicle() { return vehicle; }
        public String getInstalledOn() { return installedOn; }
        public Long getReturnId() { return returnId; }
        public boolean getTemporary() { return TEMPORARY.equals(kind); }
    }

    public record StaffBlock(StaffOption staff, List<HeldUnit> units) {
        public StaffOption getStaff() { return staff; }
        public List<HeldUnit> getUnits() { return units; }
    }

    public record Result(String batchNo, int count) {}

    /** Customer Return > Exchange from staff stock (what the screen sent). */
    public record StaffExchange(Long staffId, String type, String vehicle) {}

    // ===================== permissions =====================

    private boolean isSuper(CustomUserDetails a) {
        return permissionService.hasPermission(a.getUserId(), "SUPER_ADMIN");
    }

    public boolean canIssue(CustomUserDetails a) {
        return permissionService.hasPermission(a.getUserId(), ISSUE_PERMISSION) || isSuper(a);
    }

    public boolean canView(CustomUserDetails a) {
        return permissionService.hasPermission(a.getUserId(), VIEW_PERMISSION) || canIssue(a);
    }

    public void requireIssue(CustomUserDetails a) {
        if (!canIssue(a)) throw new AccessDeniedException("Staff stock is not enabled for your role.");
    }

    public void requireView(CustomUserDetails a) {
        if (!canView(a)) throw new AccessDeniedException("Staff stock report is not enabled for your role.");
    }

    // ===================== lists =====================

    /** Field staff of the user's company with how many devices each one holds. */
    @Transactional(readOnly = true)
    public List<StaffOption> staffList(CustomUserDetails actor) {
        String company = actor.getUser().getCompanyName();
        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : stockRepo.countWithStaffGrouped(company)) {
            Long uid = (Long) row[0];
            boolean temp = TEMPORARY.equals(row[1]);
            long n = ((Number) row[2]).longValue();
            long[] c = counts.computeIfAbsent(uid, k -> new long[2]);
            if (temp) c[1] += n; else c[0] += n;
        }
        List<StaffOption> out = new ArrayList<>();
        for (UserTable u : userRepo.findFieldStaff(company)) {
            long[] c = counts.getOrDefault(u.getId(), new long[2]);
            out.add(new StaffOption(u.getId(), ApprovalChainService.fullName(u), u.getCity(), c[0], c[1]));
        }
        return out;
    }

    /** Office AVAILABLE devices, to choose from when issuing to staff. */
    @Transactional(readOnly = true)
    public List<UnitOption> officeUnits(CustomUserDetails actor) {
        requireIssue(actor);
        String company = actor.getUser().getCompanyName();
        Map<Long, ProductCategory> products = productMap(company);
        return stockRepo.findByCompanyNameAndStatusOrderByCreatedAtAscIdAsc(company, "AVAILABLE").stream()
                .map(s -> option(s, products)).collect(Collectors.toList());
    }

    /** Devices a staff member has IN HAND (not fitted at a customer). */
    @Transactional(readOnly = true)
    public List<UnitOption> staffUnits(CustomUserDetails actor, Long staffId) {
        String company = actor.getUser().getCompanyName();
        Map<Long, ProductCategory> products = productMap(company);
        return stockRepo.findWithStaff(company, staffId).stream()
                .filter(s -> s.getInstallType() == null)
                .map(s -> option(s, products)).collect(Collectors.toList());
    }

    /** Full report: every staff member with every device he holds. */
    @Transactional(readOnly = true)
    public List<StaffBlock> report(CustomUserDetails actor) {
        requireView(actor);
        String company = actor.getUser().getCompanyName();
        Map<Long, ProductCategory> products = productMap(company);
        Map<Long, List<HeldUnit>> byStaff = new LinkedHashMap<>();
        for (StockInward s : stockRepo.findAllWithStaff(company)) {
            ProductCategory p = s.getProductId() == null ? null : products.get(s.getProductId());
            boolean temp = TEMPORARY.equals(s.getInstallType());
            byStaff.computeIfAbsent(s.getHolderUserId(), k -> new ArrayList<>()).add(new HeldUnit(
                    s.getId(), p == null ? null : p.getCategoryName(), p == null ? null : p.getProductName(),
                    s.getImeiNumber(), s.getHolderSince() == null ? "" : s.getHolderSince().format(DT),
                    temp ? TEMPORARY : "IN_HAND", s.getInstallCustomer(), s.getInstallVehicle(),
                    s.getInstallAt() == null ? null : s.getInstallAt().format(DT), s.getInstallReturnId()));
        }
        List<StaffBlock> out = new ArrayList<>();
        Map<Long, StaffOption> seen = new LinkedHashMap<>();
        for (StaffOption o : staffList(actor)) seen.put(o.id(), o);
        // staff who are no longer "Field Staff" but still hold devices must not disappear
        for (Long uid : byStaff.keySet()) {
            if (!seen.containsKey(uid)) {
                UserTable u = userRepo.findById(uid).orElse(null);
                seen.put(uid, new StaffOption(uid, u == null ? ("User #" + uid) : ApprovalChainService.fullName(u),
                        u == null ? null : u.getCity(), 0, 0));
            }
        }
        for (StaffOption o : seen.values()) {
            List<HeldUnit> units = byStaff.getOrDefault(o.id(), List.of());
            long temp = units.stream().filter(HeldUnit::getTemporary).count();
            out.add(new StaffBlock(new StaffOption(o.id(), o.name(), o.city(), units.size() - temp, temp), units));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<StaffStockMovement> recentMovements(CustomUserDetails actor) {
        requireView(actor);
        return isSuper(actor) ? moveRepo.findTop300ByOrderByCreatedAtDescIdDesc()
                : moveRepo.findTop300ByCompanyNameOrderByCreatedAtDescIdDesc(actor.getUser().getCompanyName());
    }

    /** Fills "holderName" for the Stock Inward list. Never throws. */
    @Transactional(readOnly = true)
    public void fillHolderNames(List<StockInward> list) {
        try {
            Map<Long, String> names = new HashMap<>();
            for (StockInward s : list) {
                if (s.getHolderUserId() != null && WITH_STAFF.equals(s.getStatus())) {
                    s.setHolderName(names.computeIfAbsent(s.getHolderUserId(), id ->
                            userRepo.findById(id).map(ApprovalChainService::fullName).orElse("staff")));
                }
            }
        } catch (Exception e) {
            log.warn("Could not fill staff names: {}", e.getMessage());
        }
    }

    // ===================== office -> staff =====================

    @Transactional
    public Result issue(CustomUserDetails actor, String ip, Long staffId, List<Long> stockIds, String remarks) {
        requireIssue(actor);
        UserTable me = actor.getUser();
        UserTable staff = requireStaff(me, staffId);
        List<StockInward> units = lock(me, stockIds);
        for (StockInward s : units) {
            if (!"AVAILABLE".equals(s.getStatus())) {
                throw new IllegalStateException(label(s) + " is not AVAILABLE in the office (status " + s.getStatus() + ").");
            }
        }
        String staffName = ApprovalChainService.fullName(staff);
        String actorName = ApprovalChainService.fullName(me);
        String batch = null;
        LocalDateTime now = LocalDateTime.now();
        for (StockInward s : units) {
            s.setStatus(WITH_STAFF);
            s.setHolderUserId(staff.getId());
            s.setHolderSince(now);
            clearInstall(s);
            stockRepo.save(s);
            historyService.record(s, EV_ISSUED, "AVAILABLE", WITH_STAFF, null, null, null, null,
                    "Given to " + staffName + (staff.getCity() == null ? "" : " (" + staff.getCity() + ")")
                            + (isBlank(remarks) ? "" : ". " + remarks.trim()),
                    me.getId(), actorName);
            batch = move(batch, "SI", me, s, StaffStockMovement.ISSUE, null, null, staff.getId(), staffName,
                    null, null, null, remarks);
        }
        notifyStaff(staff, "Stock received - " + units.size() + " device(s)",
                actorName + " gave you " + units.size() + " device(s). Batch " + batch + ".");
        audit(me, ip, "STAFF_ISSUE", "Issued " + units.size() + " device(s) to " + staffName + " (" + batch + ")");
        return new Result(batch, units.size());
    }

    // ===================== staff -> office =====================

    /** backTo = AVAILABLE or DAMAGED (faulty device brought back). */
    @Transactional
    public Result takeBack(CustomUserDetails actor, String ip, List<Long> stockIds, String backTo, String remarks) {
        requireIssue(actor);
        UserTable me = actor.getUser();
        String to = "DAMAGED".equalsIgnoreCase(backTo) ? "DAMAGED" : "AVAILABLE";
        if ("DAMAGED".equals(to) && isBlank(remarks)) {
            throw new IllegalArgumentException("Write the damage details in Remarks.");
        }
        List<StockInward> units = lock(me, stockIds);
        for (StockInward s : units) requireInHand(s);
        String actorName = ApprovalChainService.fullName(me);
        String batch = null;
        Map<Long, Integer> perStaff = new LinkedHashMap<>();
        for (StockInward s : units) {
            Long fromId = s.getHolderUserId();
            String fromName = nameOf(fromId);
            s.setStatus(to);
            if ("DAMAGED".equals(to)) s.setRemarks(trim(remarks.trim(), 500));
            s.setHolderUserId(null);
            s.setHolderSince(null);
            clearInstall(s);
            stockRepo.save(s);
            historyService.record(s, EV_RETURNED, WITH_STAFF, to, null, null, null, null,
                    "Taken back from " + fromName + " to office" + ("DAMAGED".equals(to) ? " as DAMAGED" : "")
                            + (isBlank(remarks) ? "" : ". " + remarks.trim()),
                    me.getId(), actorName);
            batch = move(batch, "SR", me, s, StaffStockMovement.RETURN_TO_OFFICE, fromId, fromName, null, null,
                    null, null, null, remarks);
            perStaff.merge(fromId, 1, Integer::sum);
        }
        for (Map.Entry<Long, Integer> e : perStaff.entrySet()) {
            userRepo.findById(e.getKey()).ifPresent(u -> notifyStaff(u, "Stock taken back",
                    e.getValue() + " device(s) were taken back to the office by " + actorName + "."));
        }
        audit(me, ip, "STAFF_RETURN", "Took back " + units.size() + " device(s) from staff (" + batch + ")");
        return new Result(batch, units.size());
    }

    // ===================== staff -> staff =====================

    @Transactional
    public Result transfer(CustomUserDetails actor, String ip, Long toStaffId, List<Long> stockIds, String remarks) {
        requireIssue(actor);
        UserTable me = actor.getUser();
        UserTable to = requireStaff(me, toStaffId);
        List<StockInward> units = lock(me, stockIds);
        for (StockInward s : units) {
            requireInHand(s);
            if (Objects.equals(s.getHolderUserId(), to.getId())) {
                throw new IllegalArgumentException(label(s) + " is already with " + ApprovalChainService.fullName(to) + ".");
            }
        }
        String toName = ApprovalChainService.fullName(to);
        String actorName = ApprovalChainService.fullName(me);
        String batch = null;
        LocalDateTime now = LocalDateTime.now();
        for (StockInward s : units) {
            Long fromId = s.getHolderUserId();
            String fromName = nameOf(fromId);
            s.setHolderUserId(to.getId());
            s.setHolderSince(now);
            stockRepo.save(s);
            historyService.record(s, EV_TRANSFER, WITH_STAFF, WITH_STAFF, null, null, null, null,
                    "Moved from " + fromName + " to " + toName + (isBlank(remarks) ? "" : ". " + remarks.trim()),
                    me.getId(), actorName);
            batch = move(batch, "ST", me, s, StaffStockMovement.TRANSFER, fromId, fromName, to.getId(), toName,
                    null, null, null, remarks);
        }
        notifyStaff(to, "Stock received - " + units.size() + " device(s)",
                actorName + " moved " + units.size() + " device(s) to you. Batch " + batch + ".");
        audit(me, ip, "STAFF_TRANSFER", "Moved " + units.size() + " device(s) to " + toName + " (" + batch + ")");
        return new Result(batch, units.size());
    }

    // ===================== staff -> customer =====================

    /** Checks (inside the return transaction, unit already locked) that the unit may be used for an exchange. */
    public void validateForExchange(StockInward unit, StaffExchange sx, UserTable me) {
        if (sx.staffId() == null) throw new IllegalArgumentException("Choose the staff member.");
        requireStaff(me, sx.staffId());
        if (!TEMPORARY.equals(sx.type()) && !PERMANENT.equals(sx.type())) {
            throw new IllegalArgumentException("Choose Temporary or Permanent for the staff exchange.");
        }
        if (!WITH_STAFF.equals(unit.getStatus()) || unit.getInstallType() != null
                || !Objects.equals(unit.getHolderUserId(), sx.staffId())) {
            throw new IllegalStateException("The new device (IMEI " + unit.getImeiNumber()
                    + ") is not in the selected staff member's hand (status " + unit.getStatus() + "). Choose another device.");
        }
    }

    /**
     * Fits the staff device at the customer. PERMANENT: it is now sold to the customer (ISSUED).
     * TEMPORARY: it stays on the staff's list, marked as fitted at the customer.
     */
    @Transactional
    public void applyExchange(StockInward newUnit, StaffExchange sx, StockReturn kase, StockInward oldUnit,
                              Long saleReqId, String saleReqNo, String customer, UserTable me,
                              java.time.LocalDate day) {
        String actorName = ApprovalChainService.fullName(me);
        Long staffId = newUnit.getHolderUserId();
        String staffName = nameOf(staffId);
        String vehicle = clean(sx.vehicle(), 60);
        LocalDateTime now = LocalDateTime.now();
        String note = "Exchange for IMEI " + oldUnit.getImeiNumber() + " (case " + kase.getCaseNo() + ") from "
                + staffName + "'s stock";
        if (PERMANENT.equals(sx.type())) {
            newUnit.setStatus("ISSUED");
            newUnit.setOutwardRequestId(saleReqId);
            newUnit.setIssuedAt(now);
            newUnit.setHolderUserId(null);
            newUnit.setHolderSince(null);
            clearInstall(newUnit);
            stockRepo.save(newUnit);
            historyService.record(newUnit, StockUnitHistoryService.SOLD, WITH_STAFF, "ISSUED", saleReqId, saleReqNo,
                    customer, null, "Given permanently in " + note + (vehicle == null ? "" : ", vehicle " + vehicle),
                    me.getId(), actorName, day);
            move(null, "SX", me, newUnit, StaffStockMovement.EXCHANGE_PERM, staffId, staffName, null, null,
                    customer, vehicle, kase.getCaseNo(), null);
        } else {
            newUnit.setInstallType(TEMPORARY);
            newUnit.setInstallCustomer(customer);
            newUnit.setInstallVehicle(vehicle);
            newUnit.setInstallReturnId(kase.getId());
            newUnit.setInstallAt(now);
            stockRepo.save(newUnit);
            historyService.record(newUnit, EV_FITTED_TEMP, WITH_STAFF, WITH_STAFF, saleReqId, saleReqNo,
                    customer, null, "Fitted temporarily by " + staffName + " - " + note
                            + (vehicle == null ? "" : ", vehicle " + vehicle),
                    me.getId(), actorName, day);
            move(null, "SX", me, newUnit, StaffStockMovement.EXCHANGE_TEMP, staffId, staffName, null, null,
                    customer, vehicle, kase.getCaseNo(), null);
        }
        if (staffId != null) {
            userRepo.findById(staffId).ifPresent(u -> notifyStaff(u,
                    (PERMANENT.equals(sx.type()) ? "Device fitted (permanent) - " : "Device fitted (temporary) - ")
                            + newUnit.getImeiNumber(),
                    "IMEI " + newUnit.getImeiNumber() + " was recorded as fitted at "
                            + (customer == null ? "the customer" : customer) + " (" + kase.getCaseNo() + ")."));
        }
    }

    /** Temporary device stays at the customer for good: it becomes a sale (ISSUED) against the original sale. */
    @Transactional
    public void makePermanent(CustomUserDetails actor, String ip, Long stockId) {
        requireIssue(actor);
        UserTable me = actor.getUser();
        StockInward s = lockOne(me, stockId);
        if (!WITH_STAFF.equals(s.getStatus()) || !TEMPORARY.equals(s.getInstallType())) {
            throw new IllegalStateException("This device is not a temporary fit.");
        }
        StockReturn kase = s.getInstallReturnId() == null ? null : returnRepo.findById(s.getInstallReturnId()).orElse(null);
        Long staffId = s.getHolderUserId();
        String staffName = nameOf(staffId);
        String customer = s.getInstallCustomer();
        String vehicle = s.getInstallVehicle();
        s.setStatus("ISSUED");
        s.setOutwardRequestId(kase == null ? null : kase.getRequestId());
        s.setIssuedAt(LocalDateTime.now());
        s.setHolderUserId(null);
        s.setHolderSince(null);
        clearInstall(s);
        stockRepo.save(s);
        String actorName = ApprovalChainService.fullName(me);
        historyService.record(s, StockUnitHistoryService.SOLD, WITH_STAFF, "ISSUED",
                kase == null ? null : kase.getRequestId(), kase == null ? null : kase.getRequestNo(), customer, null,
                "Temporary device made permanent (was with " + staffName + ")", me.getId(), actorName);
        move(null, "SX", me, s, StaffStockMovement.EXCHANGE_PERM, staffId, staffName, null, null, customer, vehicle,
                kase == null ? null : kase.getCaseNo(), "Temporary converted to permanent");
        audit(me, ip, "STAFF_PERMANENT", "Temporary device " + s.getImeiNumber() + " made permanent at " + customer);
    }

    /** Temporary device removed from the customer vehicle: it is back in the staff member's hand. */
    @Transactional
    public void tempTakenBack(CustomUserDetails actor, String ip, Long stockId, String remarks) {
        requireIssue(actor);
        UserTable me = actor.getUser();
        StockInward s = lockOne(me, stockId);
        if (!WITH_STAFF.equals(s.getStatus()) || !TEMPORARY.equals(s.getInstallType())) {
            throw new IllegalStateException("This device is not a temporary fit.");
        }
        Long staffId = s.getHolderUserId();
        String staffName = nameOf(staffId);
        String customer = s.getInstallCustomer();
        String vehicle = s.getInstallVehicle();
        String refNo = null;
        if (s.getInstallReturnId() != null) {
            refNo = returnRepo.findById(s.getInstallReturnId()).map(StockReturn::getCaseNo).orElse(null);
        }
        clearInstall(s);
        stockRepo.save(s);
        historyService.record(s, EV_TEMP_BACK, WITH_STAFF, WITH_STAFF, null, null, customer, null,
                "Temporary device removed from " + (customer == null ? "customer" : customer)
                        + ", back with " + staffName + (isBlank(remarks) ? "" : ". " + remarks.trim()),
                me.getId(), ApprovalChainService.fullName(me));
        move(null, "SX", me, s, StaffStockMovement.TEMP_BACK, null, null, staffId, staffName, customer, vehicle, refNo, remarks);
        audit(me, ip, "STAFF_TEMP_BACK", "Temporary device " + s.getImeiNumber() + " taken back from " + customer);
    }

    // ===================== Stock Outward: sold from staff stock =====================

    /** True when the unit is in the hand of exactly this staff member (not fitted anywhere). */
    public boolean isInHandOf(StockInward s, Long staffId) {
        return WITH_STAFF.equals(s.getStatus()) && s.getInstallType() == null
                && staffId != null && Objects.equals(s.getHolderUserId(), staffId);
    }

    /** Called by Stock Outward right after it set the unit to ISSUED. Clears the holder + writes the register. */
    public void markSold(StockInward s, Long staffId, String requestNo, String customer, UserTable me) {
        String staffName = nameOf(staffId);
        s.setHolderUserId(null);
        s.setHolderSince(null);
        clearInstall(s);
        stockRepo.save(s);
        move(null, "SX", me, s, StaffStockMovement.SOLD, staffId, staffName, null, null, customer, null, requestNo,
                "Sold from " + staffName + "'s stock");
    }

    /** Staff-side check used by Stock Outward. */
    public UserTable requireStaffFor(CustomUserDetails actor, Long staffId) {
        requireIssue(actor);
        return requireStaff(actor.getUser(), staffId);
    }

    /** Stock Outward popup: in-hand units of one staff member for the given products. */
    @Transactional(readOnly = true)
    public List<StockInward> inHandForProducts(String company, Long staffId, java.util.Collection<Long> productIds) {
        return stockRepo.findWithStaff(company, staffId).stream()
                .filter(s -> s.getInstallType() == null && s.getProductId() != null && productIds.contains(s.getProductId()))
                .collect(Collectors.toList());
    }

    public String nameOf(Long userId) {
        if (userId == null) return "office";
        return userRepo.findById(userId).map(ApprovalChainService::fullName).orElse("staff");
    }

    // ===================== helpers =====================

    private UserTable requireStaff(UserTable me, Long staffId) {
        if (staffId == null) throw new IllegalArgumentException("Choose a staff member.");
        UserTable u = userRepo.findById(staffId).orElseThrow(() -> new IllegalArgumentException("Staff member not found."));
        boolean ok = userRepo.findFieldStaff(me.getCompanyName()).stream().anyMatch(x -> x.getId().equals(staffId));
        if (!ok) throw new IllegalArgumentException(ApprovalChainService.fullName(u)
                + " is not an active field staff member of your company.");
        return u;
    }

    private void requireInHand(StockInward s) {
        if (!WITH_STAFF.equals(s.getStatus())) {
            throw new IllegalStateException(label(s) + " is not with any staff member (status " + s.getStatus() + ").");
        }
        if (s.getInstallType() != null) {
            throw new IllegalStateException(label(s) + " is fitted at a customer (temporary). Use \"Taken back\" or \"Make permanent\" first.");
        }
    }

    private List<StockInward> lock(UserTable me, List<Long> ids) {
        if (ids == null || ids.stream().noneMatch(Objects::nonNull)) {
            throw new IllegalArgumentException("Select at least one device.");
        }
        List<Long> sorted = ids.stream().filter(Objects::nonNull).distinct().sorted().collect(Collectors.toList());
        List<StockInward> out = new ArrayList<>();
        for (Long id : sorted) out.add(lockOne(me, id));
        return out;
    }

    private StockInward lockOne(UserTable me, Long id) {
        StockInward s = stockRepo.findByIdForUpdate(id).orElseThrow(() -> new IllegalArgumentException("Device not found."));
        if (!Objects.equals(s.getCompanyName(), me.getCompanyName())) {
            throw new AccessDeniedException("Not your company's device.");
        }
        return s;
    }

    private String move(String batch, String prefix, UserTable me, StockInward s, String type,
                        Long fromId, String fromName, Long toId, String toName,
                        String customer, String vehicle, String refNo, String remarks) {
        StaffStockMovement m = new StaffStockMovement();
        m.setCompanyName(s.getCompanyName());
        m.setStockId(s.getId());
        m.setImeiNumber(s.getImeiNumber());
        m.setProductName(productName(s));
        m.setMovementType(type);
        m.setFromUserId(fromId);
        m.setFromName(fromId == null ? "Office" : fromName);
        m.setToUserId(toId);
        m.setToName(toId == null ? (customer != null ? customer : "Office") : toName);
        m.setCustomerName(customer);
        m.setVehicleNo(vehicle);
        m.setRefNo(refNo);
        m.setRemarks(isBlank(remarks) ? null : trim(remarks.trim(), 500));
        m.setCreatedById(me.getId());
        m.setCreatedBy(ApprovalChainService.fullName(me));
        m.setBatchNo(batch);
        m = moveRepo.save(m);
        if (batch == null) {
            String b = String.format("%s-%d-%06d", prefix, LocalDateTime.now().getYear(), m.getId());
            m.setBatchNo(b);
            moveRepo.save(m);
            return b;
        }
        return batch;
    }

    private String productName(StockInward s) {
        if (s.getProductId() == null) return null;
        return categoryRepo.findById(s.getProductId()).map(ProductCategory::getProductName).orElse(null);
    }

    private Map<Long, ProductCategory> productMap(String company) {
        return categoryRepo.findByCompanyName(company).stream()
                .collect(Collectors.toMap(ProductCategory::getId, p -> p, (a, b) -> a));
    }

    private UnitOption option(StockInward s, Map<Long, ProductCategory> products) {
        ProductCategory p = s.getProductId() == null ? null : products.get(s.getProductId());
        return new UnitOption(s.getId(), p == null ? null : p.getCategoryName(),
                p == null ? null : p.getProductName(), s.getImeiNumber(),
                s.getHolderSince() == null ? "" : s.getHolderSince().format(DT));
    }

    private void clearInstall(StockInward s) {
        s.setInstallType(null);
        s.setInstallCustomer(null);
        s.setInstallVehicle(null);
        s.setInstallReturnId(null);
        s.setInstallAt(null);
    }

    private void notifyStaff(UserTable u, String title, String message) {
        try {
            notificationService.sendNotification(u.getId(), u.getUsername(), title, message, "/reports/staff-stock");
        } catch (Exception e) {
            log.warn("Notification to {} failed: {}", u.getUsername(), e.getMessage());
        }
    }

    private void audit(UserTable me, String ip, String action, String description) {
        try {
            auditLogService.log(me.getId(), me.getUsername(), action, "Stock", "Staff Stock", description, ip, null, "STAFF_STOCK");
        } catch (Exception e) {
            log.warn("Audit log failed ({}): {}", action, e.getMessage());
        }
    }

    private String label(StockInward s) {
        return s.getImeiNumber() != null ? s.getImeiNumber() : s.getSerialNumber();
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String clean(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        return t.length() <= max ? t : t.substring(0, max);
    }

    private String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max); }
}
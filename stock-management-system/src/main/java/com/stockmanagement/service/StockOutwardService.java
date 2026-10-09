package com.stockmanagement.service;

import com.stockmanagement.entity.ApprovalLevel;
import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.Role;
import com.stockmanagement.entity.StockCustomer;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.StockOutwardActivity;
import com.stockmanagement.entity.StockOutwardApproval;
import com.stockmanagement.entity.StockOutwardItem;
import com.stockmanagement.entity.StockOutwardRequest;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.RoleRepository;
import com.stockmanagement.repository.StockInwardRepository;
import com.stockmanagement.repository.StockOutwardActivityRepository;
import com.stockmanagement.repository.StockOutwardApprovalRepository;
import com.stockmanagement.repository.StockOutwardItemRepository;
import com.stockmanagement.repository.StockOutwardRequestRepository;
import com.stockmanagement.repository.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StockOutwardService {

    private static final Logger log = LoggerFactory.getLogger(StockOutwardService.class);

    public static final String PENDING = "PENDING";
    public static final String ON_HOLD = "ON_HOLD";
    public static final String ISSUED = "ISSUED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";

    private static final String WAITING = "WAITING";
    private static final String APPROVED = "APPROVED";
    private static final String WITH_STAFF_STATUS = "WITH_STAFF";
    private static final int LIST_LIMIT = 500;

    private final StockOutwardRequestRepository requestRepo;
    private final StockOutwardItemRepository itemRepo;
    private final StockOutwardApprovalRepository approvalRepo;
    private final StockOutwardActivityRepository activityRepo;
    private final StockInwardRepository stockRepo;
    private final ProductCategoryRepository categoryRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final ApprovalChainService chainService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;
    private final UserPermissionService permissionService;
    private final StockUnitHistoryService historyService;
    private final StockCustomerService customerService;
    private final StaffStockService staffStockService;

    public StockOutwardService(StockOutwardRequestRepository requestRepo,
                               StockOutwardItemRepository itemRepo,
                               StockOutwardApprovalRepository approvalRepo,
                               StockOutwardActivityRepository activityRepo,
                               StockInwardRepository stockRepo,
                               ProductCategoryRepository categoryRepo,
                               UserRepository userRepo,
                               RoleRepository roleRepo,
                               ApprovalChainService chainService,
                               NotificationService notificationService,
                               AuditLogService auditLogService,
                               UserPermissionService permissionService,
                               StockUnitHistoryService historyService,
                               StockCustomerService customerService,
                               StaffStockService staffStockService) {
        this.requestRepo = requestRepo;
        this.itemRepo = itemRepo;
        this.approvalRepo = approvalRepo;
        this.activityRepo = activityRepo;
        this.stockRepo = stockRepo;
        this.categoryRepo = categoryRepo;
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.chainService = chainService;
        this.notificationService = notificationService;
        this.auditLogService = auditLogService;
        this.permissionService = permissionService;
        this.historyService = historyService;
        this.customerService = customerService;
        this.staffStockService = staffStockService;
    }
    
    
    public static String detailUrl(Long requestId) {
        return "/settings/outward/" + requestId;
    }

    // ===================== screen ke liye chhote data-dibbe =====================

    public record ItemInput(Long productId, Integer quantity, BigDecimal unitPrice) {}

    public record ProductOption(Long id, String label, long available) {
        public Long getId() { return id; }
        public String getLabel() { return label; }
        public long getAvailable() { return available; }
    }

    public record RequestRow(StockOutwardRequest request, String itemsSummary, String levelLabel,
                             String waiting, boolean mine, boolean awaitingMe) {
        public StockOutwardRequest getRequest() { return request; }
        public String getItemsSummary() { return itemsSummary; }
        public String getLevelLabel() { return levelLabel; }
        public String getWaiting() { return waiting; }
        public boolean getMine() { return mine; }
        public boolean getAwaitingMe() { return awaitingMe; }
    }

    public record Stats(long pending, long onHold, long issuedToday, long rejected,
                        long awaitingMe, long approvedByMeToday, long total) {
        public long getPending() { return pending; }
        public long getOnHold() { return onHold; }
        public long getIssuedToday() { return issuedToday; }
        public long getRejected() { return rejected; }
        public long getAwaitingMe() { return awaitingMe; }
        public long getApprovedByMeToday() { return approvedByMeToday; }
        public long getTotal() { return total; }
    }

    public record ListResult(List<RequestRow> rows, Stats stats) {}

    public record ItemView(StockOutwardItem item, long available, boolean enough) {
        public StockOutwardItem getItem() { return item; }
        public long getAvailable() { return available; }
        public boolean getEnough() { return enough; }
        public BigDecimal getLineTotal() {
            return item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        }
    }

    public record Detail(StockOutwardRequest request, List<ItemView> items,
                         List<StockOutwardApproval> approvals, List<StockOutwardActivity> activities,
                         List<StockInward> issuedUnits, StockOutwardApproval currentApproval,
                         boolean canApprove, boolean canReject, boolean canComment,
                         boolean canResubmit, boolean canCancel, boolean stockShort) {
        public StockOutwardRequest getRequest() { return request; }
        public List<ItemView> getItems() { return items; }
        public List<StockOutwardApproval> getApprovals() { return approvals; }
        public List<StockOutwardActivity> getActivities() { return activities; }
        public List<StockInward> getIssuedUnits() { return issuedUnits; }
        public StockOutwardApproval getCurrentApproval() { return currentApproval; }
        public boolean getCanApprove() { return canApprove; }
        public boolean getCanReject() { return canReject; }
        public boolean getCanComment() { return canComment; }
        public boolean getCanResubmit() { return canResubmit; }
        public boolean getCanCancel() { return canCancel; }
        public boolean getStockShort() { return stockShort; }
    }

    /** Final approval popup: ek available device (IMEI) jo approver chun sakta hai. */
    public record UnitOption(Long id, String imei, String serial, String condition,
                             String warehouse, String batch, String purchaseDate) {}

    /** Final approval popup: ek requested product aur uske available devices. */
    public record ItemUnits(Long itemId, String productName, int quantity, List<UnitOption> units) {}

    // ===================== panel ki list aur counts =====================

    @Transactional(readOnly = true)
    public ListResult list(CustomUserDetails actor) {
        UserTable me = actor.getUser();
        Set<String> codes = codes(actor);
        boolean superAdmin = codes.contains("SUPER_ADMIN");
        boolean viewAll = superAdmin || codes.contains("STOCK_OUT_VIEW_ALL");

        List<StockOutwardRequest> requests;
        if (superAdmin) {
            requests = requestRepo.findAllByOrderByCreatedAtDesc();
        } else if (viewAll) {
            requests = requestRepo.findByCompanyNameOrderByCreatedAtDesc(me.getCompanyName());
        } else {
            requests = requestRepo.findInvolving(me.getCompanyName(), me.getId(), me.getRoleId());
        }
        if (requests.size() > LIST_LIMIT) requests = requests.subList(0, LIST_LIMIT);

        List<Long> ids = requests.stream().map(StockOutwardRequest::getId).collect(Collectors.toList());
        Map<Long, List<StockOutwardItem>> itemsBy = ids.isEmpty() ? new HashMap<>()
                : itemRepo.findByRequestIdIn(ids).stream().collect(Collectors.groupingBy(StockOutwardItem::getRequestId));
        Map<Long, List<StockOutwardApproval>> approvalsBy = ids.isEmpty() ? new HashMap<>()
                : approvalRepo.findByRequestIdIn(ids).stream().collect(Collectors.groupingBy(StockOutwardApproval::getRequestId));

        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long pending = 0, onHold = 0, issuedToday = 0, rejected = 0, awaitingMe = 0;
        List<RequestRow> rows = new ArrayList<>();

        for (StockOutwardRequest r : requests) {
            List<StockOutwardApproval> approvals = approvalsBy.getOrDefault(r.getId(), List.of());
            StockOutwardApproval cur = approvals.stream()
                    .filter(a -> a.getLevelNo().equals(r.getCurrentLevel())).findFirst().orElse(null);

            boolean mine = r.getRequestedById().equals(me.getId());
            boolean awaiting = PENDING.equals(r.getStatus()) && cur != null && !mine && assignedTo(me, cur);

            switch (r.getStatus()) {
                case PENDING -> pending++;
                case ON_HOLD -> onHold++;
                case REJECTED -> rejected++;
                case ISSUED -> {
                    if (r.getCompletedAt() != null && !r.getCompletedAt().isBefore(startOfToday)) issuedToday++;
                }
                default -> { }
            }
            if (awaiting) awaitingMe++;

            rows.add(new RequestRow(r,
                    summarize(itemsBy.getOrDefault(r.getId(), List.of())),
                    levelLabel(r, cur),
                    isOpen(r) ? age(r.getUpdatedAt()) : "",
                    mine, awaiting));
        }

        long approvedByMeToday = approvalRepo
                .countByActedByIdAndStatusAndActedAtGreaterThanEqual(me.getId(), APPROVED, startOfToday);

        return new ListResult(rows,
                new Stats(pending, onHold, issuedToday, rejected, awaitingMe, approvedByMeToday, rows.size()));
    }

    /** Sales ke dropdown ke liye products, "in stock: N" ke saath. */
    @Transactional(readOnly = true)
    public List<ProductOption> productOptions(String companyName) {
        if (companyName == null) return List.of();

        Map<Long, Long> availByProductId = new HashMap<>();
        for (Object[] row : stockRepo.countAvailableGroupedByProduct(companyName)) {
            if (row[0] != null) availByProductId.put((Long) row[0], ((Number) row[1]).longValue());
        }

        Map<String, List<ProductCategory>> byName = new LinkedHashMap<>();
        for (ProductCategory p : categoryRepo.findByCompanyNameAndStatus(companyName, "ACTIVE")) {
            if (p.getProductName() == null || p.getProductName().isBlank()) continue;
            byName.computeIfAbsent(p.getProductName().trim().toLowerCase(), k -> new ArrayList<>()).add(p);
        }

        List<ProductOption> options = new ArrayList<>();
        for (List<ProductCategory> group : byName.values()) {
            ProductCategory rep = group.get(0);
            long available = group.stream().mapToLong(p -> availByProductId.getOrDefault(p.getId(), 0L)).sum();
            String cat = rep.getCategoryName() == null ? "" : rep.getCategoryName() + " \u203A ";
            options.add(new ProductOption(rep.getId(), cat + rep.getProductName(), available));
        }
        options.sort(Comparator.comparing(ProductOption::label, String.CASE_INSENSITIVE_ORDER));
        return options;
    }

    // ===================== request ka detail page =====================

    @Transactional(readOnly = true)
    public Detail getDetail(Long id, CustomUserDetails actor) {
        StockOutwardRequest r = requestRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));
        UserTable me = actor.getUser();
        Set<String> codes = codes(actor);
        boolean superAdmin = codes.contains("SUPER_ADMIN");
        boolean viewAll = superAdmin || codes.contains("STOCK_OUT_VIEW_ALL");

        if (!superAdmin && !r.getCompanyName().equals(me.getCompanyName())) {
            throw new AccessDeniedException("Not your company's request.");
        }

        List<StockOutwardApproval> approvals = approvalRepo.findByRequestIdOrderByLevelNoAsc(id);
        boolean isRequester = r.getRequestedById().equals(me.getId());
        boolean involved = isRequester || approvals.stream().anyMatch(a -> assignedTo(me, a));
        if (!viewAll && !involved) {
            throw new AccessDeniedException("You are not part of this request.");
        }

        StockOutwardApproval cur = approvals.stream()
                .filter(a -> a.getLevelNo().equals(r.getCurrentLevel())).findFirst().orElse(null);

        boolean open = isOpen(r);
        List<ItemView> items = new ArrayList<>();
        boolean stockShort = false;
        for (StockOutwardItem it : itemRepo.findByRequestId(id)) {
            long available = open ? availableFor(r.getCompanyName(), it.getProductId()) : 0;
            boolean enough = available >= it.getQuantity();
            if (open && !enough) stockShort = true;
            items.add(new ItemView(it, available, enough));
        }

        boolean iAmCurrentApprover = cur != null && !isRequester && assignedTo(me, cur);

        return new Detail(r, items, approvals,
                activityRepo.findByRequestIdOrderByCreatedAtAsc(id),
                ISSUED.equals(r.getStatus()) ? stockRepo.findByOutwardRequestId(id) : List.of(),
                cur,
                PENDING.equals(r.getStatus()) && iAmCurrentApprover,
                open && iAmCurrentApprover,
                open && (isRequester || involved),
                ON_HOLD.equals(r.getStatus()) && isRequester,
                open && (isRequester || superAdmin),
                stockShort);
    }

    // ===================== kaam: request banana, approve, hold, reject... =====================

    /** Sales request banata hai. Ye chain ke Level 1 se shuru hoti hai. */
    @Transactional
    public StockOutwardRequest create(CustomUserDetails actor, String ip,
                                      StockCustomerService.CustomerInput customer, String dealName,
                                      String remarks, List<ItemInput> inputs) {
        UserTable me = actor.getUser();
        Set<String> codes = codes(actor);
        if (!codes.contains("STOCK_OUT_REQUEST") && !codes.contains("SUPER_ADMIN")) {
            throw new AccessDeniedException("You are not allowed to raise stock outward requests.");
        }
        String company = me.getCompanyName();
        if (company == null || company.isBlank()) {
            throw new IllegalStateException("Your user is not linked to a company.");
        }
        if (dealName != null && dealName.trim().length() > 200) {
            throw new IllegalArgumentException("Deal name is too long (max 200 characters).");
        }
        if (remarks != null && remarks.length() > 1000) {
            throw new IllegalArgumentException("Remarks are too long (max 1000 characters).");
        }

        List<ApprovalLevel> chain = chainService.getLevels(company);
        if (chain.isEmpty()) {
            throw new IllegalStateException("Approval chain is not configured for your company. "
                    + "Ask an administrator to set it up under Settings \u203A Approval Chain.");
        }
        for (ApprovalLevel l : chain) {
            boolean anyApprover = resolveApprovers(l.getApproverType(), l.getApproverUserId(),
                    l.getApproverRoleId(), company).stream().anyMatch(u -> !u.getId().equals(me.getId()));
            if (!anyApprover) {
                throw new IllegalStateException("Level " + l.getLevelNo() + " (" + l.getLevelName()
                        + ") has no active approver other than you. Please contact an administrator.");
            }
        }

        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Add at least one product.");
        }
        Map<String, StockOutwardItem> merged = new LinkedHashMap<>();
        for (ItemInput in : inputs) {
            if (in.productId() == null) continue;
            int qty = in.quantity() == null ? 0 : in.quantity();
            if (qty < 1 || qty > 10000) {
                throw new IllegalArgumentException("Quantity must be between 1 and 10000.");
            }
            if (in.unitPrice() == null || in.unitPrice().signum() < 0) {
                throw new IllegalArgumentException("Enter the selling price for every product.");
            }
            if (in.unitPrice().compareTo(new BigDecimal("9999999.99")) > 0) {
                throw new IllegalArgumentException("Selling price is too large.");
            }
            BigDecimal price = in.unitPrice().setScale(2, RoundingMode.HALF_UP);
            ProductCategory p = categoryRepo.findById(in.productId())
                    .orElseThrow(() -> new IllegalArgumentException("Selected product does not exist."));
            if (!company.equals(p.getCompanyName())) {
                throw new IllegalArgumentException("Selected product does not belong to your company.");
            }
            String key = p.getProductName().trim().toLowerCase();
            StockOutwardItem item = merged.get(key);
            if (item == null) {
                item = new StockOutwardItem();
                item.setProductId(p.getId());
                item.setProductName(p.getProductName().trim());
                item.setCategoryName(p.getCategoryName());
                item.setQuantity(qty);
                item.setUnitPrice(price);
                merged.put(key, item);
            } else {
                if (item.getUnitPrice().compareTo(price) != 0) {
                    throw new IllegalArgumentException("\"" + item.getProductName()
                            + "\" is added twice with different prices. Use one line with one price.");
                }
                item.setQuantity(item.getQuantity() + qty);
            }
        }
        if (merged.isEmpty()) throw new IllegalArgumentException("Add at least one product.");

        // customer: purana mile to wahi, nahi to naya customer save ho jayega
        StockCustomer cust = customerService.resolve(company, customer, ApprovalChainService.fullName(me));

        StockOutwardRequest r = new StockOutwardRequest();
        r.setCompanyName(company);
        r.setRequestedById(me.getId());
        r.setRequestedByName(ApprovalChainService.fullName(me));
        r.setCustomerId(cust.getId());
        r.setCustomerName(cust.getCustomerName());
        r.setCustomerMobile(cust.getMobile());
        r.setCustomerCompany(cust.getBusinessName());
        r.setCustomerGst(cust.getGstNumber());
        r.setCustomerEmail(cust.getEmail());
        r.setCustomerAddress(cust.getAddressLine());
        r.setCustomerCity(cust.getCity());
        r.setCustomerState(cust.getState());
        r.setCustomerPincode(cust.getPincode());
        r.setDealName(dealName == null || dealName.isBlank() ? null : dealName.trim());
        r.setRemarks(remarks == null || remarks.isBlank() ? null : remarks.trim());
        r.setStatus(PENDING);
        r.setCurrentLevel(1);
        r.setTotalLevels(chain.size());
        BigDecimal total = BigDecimal.ZERO;
        for (StockOutwardItem it : merged.values()) {
            total = total.add(it.getUnitPrice().multiply(BigDecimal.valueOf(it.getQuantity())));
        }
        r.setTotalAmount(total);
        r = requestRepo.save(r);
        r.setRequestNo(String.format("SO-%d-%06d", LocalDate.now().getYear(), r.getId()));
        r = requestRepo.save(r);

        for (StockOutwardItem item : merged.values()) {
            item.setRequestId(r.getId());
            itemRepo.save(item);
        }

        // chain ki copy: baad mein chain badle to ye request purani chain pe hi chalegi
        Map<Long, Role> roles = roleRepo.findAll().stream().collect(Collectors.toMap(Role::getId, x -> x));
        StockOutwardApproval first = null;
        for (ApprovalLevel l : chain) {
            StockOutwardApproval a = new StockOutwardApproval();
            a.setRequestId(r.getId());
            a.setLevelNo(l.getLevelNo());
            a.setLevelName(l.getLevelName());
            a.setApproverType(l.getApproverType());
            a.setApproverUserId(l.getApproverUserId());
            a.setApproverRoleId(l.getApproverRoleId());
            a.setApproverLabel(approverLabel(l, roles));
            a.setStatus(l.getLevelNo() == 1 ? PENDING : WAITING);
            a = approvalRepo.save(a);
            if (l.getLevelNo() == 1) first = a;
        }

        activity(r, 1, me, "CREATED", r.getRemarks() != null ? r.getRemarks() : "Request raised.");
        notifyApprovers(r, first, "Approval required \u2013 " + r.getRequestNo(),
                r.getRequestedByName() + " requested " + summarize(new ArrayList<>(merged.values()))
                        + " for " + who(r) + " (Rs. " + money(total) + "). Your approval is needed (Level 1 of "
                        + r.getTotalLevels() + ").", me.getId());
        audit(me, ip, "CREATE", "Raised stock outward request " + r.getRequestNo(), r);
        return r;
    }

    /**
     * Current level ka approver approve karta hai. Aakhri approval stock issue karta hai.
     * Aakhri level par stockIds mein approver ki chuni hui IMEI aati hain (auto FIFO nahi).
     */
    @Transactional
    public StockOutwardRequest approve(Long id, CustomUserDetails actor, String ip, Integer expectedLevel,
                                       String comment, List<Long> stockIds) {
        return approve(id, actor, ip, expectedLevel, comment, stockIds, null);
    }

    /**
     * Same as above. sourceStaffId != null = at the final level the approver picked devices from that
     * FIELD STAFF member's stock (instead of / together with office stock). null = old behaviour.
     */
    @Transactional
    public StockOutwardRequest approve(Long id, CustomUserDetails actor, String ip, Integer expectedLevel,
                                       String comment, List<Long> stockIds, Long sourceStaffId) {
        UserTable me = actor.getUser();
        StockOutwardRequest r = lockOwned(id, me, actor);
        requireStatus(r, PENDING, "Only pending requests can be approved.");
        requireLevel(r, expectedLevel);
        StockOutwardApproval cur = currentApproval(r);
        requireCanAct(me, r, cur);

        cur.setStatus(APPROVED);
        stamp(cur, me, comment);
        approvalRepo.save(cur);
        activity(r, cur.getLevelNo(), me, "APPROVED",
                blank(comment) ? "Approved (" + cur.getLevelName() + ")." : comment.trim());

        if (r.getCurrentLevel() < r.getTotalLevels()) {
            StockOutwardApproval next = approvalRepo
                    .findByRequestIdAndLevelNo(r.getId(), r.getCurrentLevel() + 1)
                    .orElseThrow(() -> new IllegalStateException("Approval chain is corrupted for " + r.getRequestNo()));
            next.setStatus(PENDING);
            approvalRepo.save(next);
            r.setCurrentLevel(next.getLevelNo());
            requestRepo.save(r);

            notifyApprovers(r, next, "Approval required \u2013 " + r.getRequestNo(),
                    "Level " + cur.getLevelNo() + " approved by " + ApprovalChainService.fullName(me)
                            + ". Now waiting for your approval (Level " + next.getLevelNo() + " of "
                            + r.getTotalLevels() + ") \u2013 " + who(r) + ".", me.getId());
            notifyUser(r.getRequestedById(), "Request progressed \u2013 " + r.getRequestNo(),
                    "Level " + cur.getLevelNo() + " (" + cur.getLevelName() + ") approved. Waiting for Level "
                            + next.getLevelNo() + " (" + next.getLevelName() + ").", r);
        } else {
            if (sourceStaffId != null) staffStockService.requireStaffFor(actor, sourceStaffId);
            int units = issueStock(r, me, stockIds, sourceStaffId); // approver ne jo IMEI chune wahi issue honge; galat ho to sab wapas
            r.setStatus(ISSUED);
            r.setCompletedAt(LocalDateTime.now());
            requestRepo.save(r);
            activity(r, cur.getLevelNo(), me, "ISSUED", "All levels approved. " + units + " unit(s) issued from stock.");
            notifyUser(r.getRequestedById(), "Stock issued \u2013 " + r.getRequestNo(),
                    "All approvals are complete. " + units + " unit(s) have been issued for "
                            + who(r) + ".", r);
        }
        audit(me, ip, "APPROVE", "Approved level " + cur.getLevelNo() + " of " + r.getRequestNo(), r);
        return r;
    }

    /** Approver request ko rokta hai (jaise "payment nahi aaya" / "abhi stock nahi hai"). */
    @Transactional
    public void hold(Long id, CustomUserDetails actor, String ip, Integer expectedLevel, String reason) {
        requireText(reason, "Please write the reason for putting the request on hold.");
        UserTable me = actor.getUser();
        StockOutwardRequest r = lockOwned(id, me, actor);
        requireStatus(r, PENDING, "Only pending requests can be put on hold.");
        requireLevel(r, expectedLevel);
        StockOutwardApproval cur = currentApproval(r);
        requireCanAct(me, r, cur);

        cur.setStatus(ON_HOLD);
        stamp(cur, me, reason);
        approvalRepo.save(cur);
        r.setStatus(ON_HOLD);
        requestRepo.save(r);

        activity(r, cur.getLevelNo(), me, "ON_HOLD", reason.trim());
        notifyUser(r.getRequestedById(), "On hold \u2013 " + r.getRequestNo(),
                ApprovalChainService.fullName(me) + " (" + cur.getLevelName() + "): " + shorten(reason, 200), r);
        audit(me, ip, "HOLD", "Put " + r.getRequestNo() + " on hold at level " + cur.getLevelNo(), r);
    }

    /** Approver reject karta hai. Request hamesha ke liye band. */
    @Transactional
    public void reject(Long id, CustomUserDetails actor, String ip, Integer expectedLevel, String reason) {
        requireText(reason, "Please write the reason for rejecting the request.");
        UserTable me = actor.getUser();
        StockOutwardRequest r = lockOwned(id, me, actor);
        if (!isOpen(r)) throw new IllegalStateException("This request is already closed.");
        requireLevel(r, expectedLevel);
        StockOutwardApproval cur = currentApproval(r);
        requireCanAct(me, r, cur);

        cur.setStatus(REJECTED);
        stamp(cur, me, reason);
        approvalRepo.save(cur);
        r.setStatus(REJECTED);
        r.setCompletedAt(LocalDateTime.now());
        requestRepo.save(r);

        activity(r, cur.getLevelNo(), me, "REJECTED", reason.trim());
        notifyUser(r.getRequestedById(), "Rejected \u2013 " + r.getRequestNo(),
                ApprovalChainService.fullName(me) + " (" + cur.getLevelName() + "): " + shorten(reason, 200), r);
        audit(me, ip, "REJECT", "Rejected " + r.getRequestNo() + " at level " + cur.getLevelNo(), r);
    }

    /** Sales aur approvers ki baatcheet. */
    @Transactional
    public void comment(Long id, CustomUserDetails actor, String ip, String message) {
        requireText(message, "Please type a message.");
        if (message.length() > 1000) throw new IllegalArgumentException("Message is too long (max 1000 characters).");
        UserTable me = actor.getUser();
        StockOutwardRequest r = lockOwned(id, me, actor);
        if (!isOpen(r)) throw new IllegalStateException("This request is closed - replies are disabled.");

        List<StockOutwardApproval> approvals = approvalRepo.findByRequestIdOrderByLevelNoAsc(id);
        boolean isRequester = r.getRequestedById().equals(me.getId());
        boolean involved = approvals.stream().anyMatch(a -> assignedTo(me, a));
        if (!isRequester && !involved) throw new AccessDeniedException("You are not part of this request.");

        activity(r, r.getCurrentLevel(), me, "COMMENT", message.trim());

        String title = "New reply \u2013 " + r.getRequestNo();
        String body = ApprovalChainService.fullName(me) + ": " + shorten(message, 200);
        if (isRequester) {
            notifyApprovers(r, currentApproval(r), title, body, me.getId());
        } else {
            notifyUser(r.getRequestedById(), title, body, r);
        }
    }

    /** Sales ne hold ka jawab de diya, request usi level pe wapas jaati hai. */
    @Transactional
    public void resubmit(Long id, CustomUserDetails actor, String ip, String message) {
        UserTable me = actor.getUser();
        StockOutwardRequest r = lockOwned(id, me, actor);
        requireStatus(r, ON_HOLD, "Only requests on hold can be resubmitted.");
        if (!r.getRequestedById().equals(me.getId())) {
            throw new AccessDeniedException("Only the requester can resubmit.");
        }
        StockOutwardApproval cur = currentApproval(r);
        cur.setStatus(PENDING);
        cur.setActedById(null);
        cur.setActedByName(null);
        cur.setActedAt(null);
        cur.setComment(null);
        approvalRepo.save(cur);
        r.setStatus(PENDING);
        requestRepo.save(r);

        String text = blank(message) ? "Resubmitted for approval." : message.trim();
        activity(r, cur.getLevelNo(), me, "RESUBMITTED", text);
        notifyApprovers(r, cur, "Resubmitted \u2013 " + r.getRequestNo(),
                r.getRequestedByName() + " resubmitted the request: " + shorten(text, 200), me.getId());
        audit(me, ip, "RESUBMIT", "Resubmitted " + r.getRequestNo(), r);
    }

    @Transactional
    public void cancel(Long id, CustomUserDetails actor, String ip, String reason) {
        UserTable me = actor.getUser();
        StockOutwardRequest r = lockOwned(id, me, actor);
        if (!isOpen(r)) throw new IllegalStateException("This request is already closed.");
        boolean superAdmin = codes(actor).contains("SUPER_ADMIN");
        if (!r.getRequestedById().equals(me.getId()) && !superAdmin) {
            throw new AccessDeniedException("Only the requester can cancel this request.");
        }
        StockOutwardApproval cur = currentApproval(r);
        r.setStatus(CANCELLED);
        r.setCompletedAt(LocalDateTime.now());
        requestRepo.save(r);

        String text = blank(reason) ? "Request cancelled." : reason.trim();
        activity(r, r.getCurrentLevel(), me, "CANCELLED", text);
        notifyApprovers(r, cur, "Cancelled \u2013 " + r.getRequestNo(),
                ApprovalChainService.fullName(me) + " cancelled the request: " + shorten(text, 200), me.getId());
        audit(me, ip, "CANCEL", "Cancelled " + r.getRequestNo(), r);
    }

    // ===================== final approval: IMEI chunna aur issue karna =====================

    /**
     * Final approval popup ke liye: har requested product ki available IMEI list.
     * Sirf us approver ko milti hai jiske paas request abhi pending hai.
     */
    @Transactional(readOnly = true)
    public List<ItemUnits> availableUnits(Long id, CustomUserDetails actor) {
        return availableUnits(id, actor, null);
    }

    /** staffId != null = the devices that staff member holds in hand (instead of office stock). */
    @Transactional(readOnly = true)
    public List<ItemUnits> availableUnits(Long id, CustomUserDetails actor, Long staffId) {
        UserTable me = actor.getUser();
        StockOutwardRequest r = requestRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));
        if (!codes(actor).contains("SUPER_ADMIN") && !r.getCompanyName().equals(me.getCompanyName())) {
            throw new AccessDeniedException("Not your company's request.");
        }
        if (!PENDING.equals(r.getStatus())) {
            throw new IllegalStateException("This request is not waiting for approval.");
        }
        requireCanAct(me, r, currentApproval(r));

        List<ItemUnits> out = new ArrayList<>();
        for (StockOutwardItem it : itemRepo.findByRequestId(id)) {
            List<Long> productIds = resolveProductIds(r.getCompanyName(), it.getProductId());
            if (staffId != null) staffStockService.requireStaffFor(actor, staffId);
            List<StockInward> pool = staffId == null
                    ? stockRepo.findAvailableForPick(r.getCompanyName(), productIds)
                    : staffStockService.inHandForProducts(r.getCompanyName(), staffId, productIds);
            List<UnitOption> units = pool.stream()
                    .map(s -> new UnitOption(s.getId(), s.getImeiNumber(), s.getSerialNumber(), s.getCondition(),
                            s.getWarehouse(), s.getBatchNumber(),
                            s.getPurchaseDate() == null ? null : s.getPurchaseDate().toString()))
                    .collect(Collectors.toList());
            out.add(new ItemUnits(it.getId(), it.getProductName(), it.getQuantity(), units));
        }
        return out;
    }

    /**
     * Final approver jo IMEI chunta hai wahi issue hote hain (auto FIFO nahi).
     * Har product ke liye bilkul utni hi IMEI chuni honi chahiye jitni quantity request mein hai.
     */
    private int issueStock(StockOutwardRequest r, UserTable actor, List<Long> selectedIds, Long sourceStaffId) {
        List<StockOutwardItem> items = itemRepo.findByRequestId(r.getId());
        List<Long> given = selectedIds == null ? List.of()
                : selectedIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toList());
        if (given.isEmpty()) {
            throw new IllegalStateException("Select the IMEI of every device to issue before the final approval.");
        }
        List<Long> ids = given.stream().distinct().sorted().collect(Collectors.toList());
        if (ids.size() != given.size()) {
            throw new IllegalArgumentException("The same device was selected twice.");
        }

        // har item ke product ids (same naam ke saare variants), taaki chuni hui unit sahi item se jud sake
        Map<Long, Set<Long>> productIdsByItem = new LinkedHashMap<>();
        for (StockOutwardItem it : items) {
            productIdsByItem.put(it.getId(),
                    new java.util.HashSet<>(resolveProductIds(r.getCompanyName(), it.getProductId())));
        }

        // sorted id order mein lock, taaki do approvers ke beech deadlock na ho
        Map<Long, List<StockInward>> pickedByItem = new HashMap<>();
        List<String> problems = new ArrayList<>();
        for (Long sid : ids) {
            StockInward s = stockRepo.findByIdForUpdate(sid).orElse(null);
            if (s == null || !r.getCompanyName().equals(s.getCompanyName())) {
                problems.add("A selected device was not found.");
                continue;
            }
            String label = s.getImeiNumber() != null ? s.getImeiNumber() : s.getSerialNumber();
            boolean fromStaff = sourceStaffId != null && staffStockService.isInHandOf(s, sourceStaffId);
            if (!"AVAILABLE".equals(s.getStatus()) && !fromStaff) {
                problems.add(label + " is no longer available (status " + s.getStatus() + ").");
                continue;
            }
            Long itemId = null;
            for (Map.Entry<Long, Set<Long>> e : productIdsByItem.entrySet()) {
                if (s.getProductId() != null && e.getValue().contains(s.getProductId())) {
                    itemId = e.getKey();
                    break;
                }
            }
            if (itemId == null) {
                problems.add(label + " is not a product of this request.");
                continue;
            }
            pickedByItem.computeIfAbsent(itemId, k -> new ArrayList<>()).add(s);
        }
        for (StockOutwardItem it : items) {
            int got = pickedByItem.getOrDefault(it.getId(), List.of()).size();
            if (got != it.getQuantity()) {
                problems.add(it.getProductName() + ": select exactly " + it.getQuantity()
                        + " device(s), you selected " + got + ".");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" ", problems));
        }

        LocalDateTime now = LocalDateTime.now();
        int total = 0;
        for (StockOutwardItem it : items) {
            for (StockInward s : pickedByItem.get(it.getId())) {
                final boolean soldFromStaff = WITH_STAFF_STATUS.equals(s.getStatus());
                final Long staffHolder = s.getHolderUserId();
                final String fromStatus = soldFromStaff ? WITH_STAFF_STATUS : "AVAILABLE";
                s.setStatus(ISSUED);
                s.setOutwardRequestId(r.getId());
                s.setIssuedAt(now);
                stockRepo.save(s);
                // is IMEI ko kisne khareeda, kis price pe
                historyService.record(s, StockUnitHistoryService.SOLD, fromStatus, ISSUED,
                        r.getId(), r.getRequestNo(), who(r), it.getUnitPrice(),
                        "Issued through " + r.getRequestNo()
                                + (soldFromStaff ? " (sold from " + staffStockService.nameOf(staffHolder) + "'s stock)" : ""),
                        actor.getId(), ApprovalChainService.fullName(actor));
                if (soldFromStaff) {
                    staffStockService.markSold(s, staffHolder, r.getRequestNo(), who(r), actor);
                }
                total++;
            }
        }
        return total;
    }

    private List<Long> resolveProductIds(String company, Long representativeId) {
        ProductCategory rep = categoryRepo.findById(representativeId)
                .orElseThrow(() -> new IllegalStateException("Product no longer exists."));
        String name = rep.getProductName() == null ? "" : rep.getProductName().trim();
        List<Long> ids = categoryRepo.findByCompanyName(company).stream()
                .filter(p -> p.getProductName() != null && p.getProductName().trim().equalsIgnoreCase(name))
                .map(ProductCategory::getId)
                .collect(Collectors.toList());
        if (!ids.contains(representativeId)) ids.add(representativeId);
        return ids;
    }

    private long availableFor(String company, Long representativeId) {
        return stockRepo.countByCompanyNameAndStatusAndProductIdIn(
                company, "AVAILABLE", resolveProductIds(company, representativeId));
    }

    // ===================== chhote helper =====================

    private Set<String> codes(CustomUserDetails actor) {
        return permissionService.getAllowedPermissionDetails(actor.getUserId()).stream()
                .map(p -> p.getPermissionCode()).collect(Collectors.toSet());
    }

    private StockOutwardRequest lockOwned(Long id, UserTable me, CustomUserDetails actor) {
        StockOutwardRequest r = requestRepo.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Request not found."));
        boolean superAdmin = codes(actor).contains("SUPER_ADMIN");
        if (!superAdmin && !r.getCompanyName().equals(me.getCompanyName())) {
            throw new AccessDeniedException("Not your company's request.");
        }
        return r;
    }

    private StockOutwardApproval currentApproval(StockOutwardRequest r) {
        return approvalRepo.findByRequestIdAndLevelNo(r.getId(), r.getCurrentLevel())
                .orElseThrow(() -> new IllegalStateException("Approval chain is corrupted for " + r.getRequestNo()));
    }

    private void requireCanAct(UserTable me, StockOutwardRequest r, StockOutwardApproval cur) {
        if (r.getRequestedById().equals(me.getId())) {
            throw new IllegalStateException("You cannot approve, hold or reject your own request.");
        }
        if (!assignedTo(me, cur)) {
            throw new AccessDeniedException("This request is not waiting for your approval.");
        }
    }

    /** Kya ye user is level ka approver hai? */
    private boolean assignedTo(UserTable me, StockOutwardApproval a) {
        if (ApprovalChainService.TYPE_USER.equals(a.getApproverType())) {
            return me.getId().equals(a.getApproverUserId());
        }
        return me.getRoleId() != null && me.getRoleId().equals(a.getApproverRoleId());
    }

    private List<UserTable> resolveApprovers(String type, Long userId, Long roleId, String company) {
        if (ApprovalChainService.TYPE_USER.equals(type)) {
            List<UserTable> out = new ArrayList<>();
            if (userId != null) {
                userRepo.findById(userId).ifPresent(u -> {
                    if ("ACTIVE".equalsIgnoreCase(u.getStatus()) && company.equals(u.getCompanyName())) out.add(u);
                });
            }
            return out;
        }
        if (roleId == null) return List.of();
        return userRepo.findByRoleIdAndCompanyNameAndStatus(roleId, company, "ACTIVE");
    }

    private String approverLabel(ApprovalLevel l, Map<Long, Role> roles) {
        if (ApprovalChainService.TYPE_USER.equals(l.getApproverType())) {
            return userRepo.findById(l.getApproverUserId()).map(u -> {
                String dept = blank(u.getDepartment()) ? "" : " (" + u.getDepartment() + ")";
                return ApprovalChainService.fullName(u) + dept;
            }).orElse("Unknown user");
        }
        Role role = roles.get(l.getApproverRoleId());
        return "Role: " + (role == null ? "Unknown" : role.getRoleName());
    }

    private void stamp(StockOutwardApproval a, UserTable me, String comment) {
        a.setActedById(me.getId());
        a.setActedByName(ApprovalChainService.fullName(me));
        a.setActedAt(LocalDateTime.now());
        a.setComment(blank(comment) ? null : comment.trim());
    }

    private void activity(StockOutwardRequest r, Integer level, UserTable actor, String action, String message) {
        StockOutwardActivity a = new StockOutwardActivity();
        a.setRequestId(r.getId());
        a.setLevelNo(level);
        a.setActorId(actor.getId());
        a.setActorName(ApprovalChainService.fullName(actor));
        a.setAction(action);
        a.setMessage(message);
        activityRepo.save(a);
    }

    private void audit(UserTable me, String ip, String action, String description, StockOutwardRequest r) {
        try {
            auditLogService.log(me.getId(), me.getUsername(), action, "Stock", "Stock Outward",
                    description, ip, r.getId(), "STOCK_OUTWARD");
        } catch (Exception e) {
            log.warn("Audit log failed for {}: {}", r.getRequestNo(), e.getMessage());
        }
    }

    // ---- notifications: DB mein save + live push; push fail ho to workflow nahi rukta ----

    private void notifyApprovers(StockOutwardRequest r, StockOutwardApproval level,
                                 String title, String message, Long excludeUserId) {
        if (level == null) return;
        for (UserTable u : resolveApprovers(level.getApproverType(), level.getApproverUserId(),
                level.getApproverRoleId(), r.getCompanyName())) {
            if (u.getId().equals(excludeUserId)) continue;
            safeNotify(u, title, message, r);
        }
    }

    private void notifyUser(Long userId, String title, String message, StockOutwardRequest r) {
        userRepo.findById(userId).ifPresent(u -> safeNotify(u, title, message, r));
    }

    private void safeNotify(UserTable u, String title, String message, StockOutwardRequest r) {
        try {
            notificationService.sendNotification(u.getId(), u.getUsername(), title,
                    shorten(message, 480), detailUrl(r.getId()));
        } catch (Exception e) {
            log.warn("Notification to {} failed: {}", u.getUsername(), e.getMessage());
        }
    }

  

    /** "DPS School (Ramesh Patil)" ya sirf naam, agar company nahi likhi. */
    private String who(StockOutwardRequest r) {
        if (blank(r.getCustomerCompany())) return r.getCustomerName();
        return r.getCustomerCompany() + " (" + r.getCustomerName() + ")";
    }

    private boolean isOpen(StockOutwardRequest r) {
        return PENDING.equals(r.getStatus()) || ON_HOLD.equals(r.getStatus());
    }

    /** Approver ka page purana ho sakta hai (kisi aur ne pehle hi decide kar diya) - andha kaam nahi. */
    private void requireLevel(StockOutwardRequest r, Integer expectedLevel) {
        if (expectedLevel != null && !expectedLevel.equals(r.getCurrentLevel())) {
            throw new IllegalStateException("This request has already moved on (it is now at level "
                    + r.getCurrentLevel() + "). Please review it again.");
        }
    }

    private void requireStatus(StockOutwardRequest r, String expected, String message) {
        if (!expected.equals(r.getStatus())) throw new IllegalStateException(message);
    }

    private void requireText(String s, String message) {
        if (blank(s)) throw new IllegalArgumentException(message);
    }

    private String money(BigDecimal v) {
        return v == null ? "0.00" : String.format("%,.2f", v);
    }

    private boolean blank(String s) { return s == null || s.isBlank(); }

    private String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "\u2026";
    }

    private String summarize(Collection<StockOutwardItem> items) {
        List<String> parts = new ArrayList<>();
        int shown = 0;
        for (StockOutwardItem it : items) {
            if (shown++ < 2) parts.add(it.getQuantity() + " \u00D7 " + it.getProductName());
        }
        String s = String.join(", ", parts);
        if (items.size() > 2) s += " +" + (items.size() - 2) + " more";
        return s;
    }

    private String levelLabel(StockOutwardRequest r, StockOutwardApproval cur) {
        switch (r.getStatus()) {
            case ISSUED:    return "Completed";
            case CANCELLED: return "Cancelled";
            case REJECTED:  return "Rejected at L" + r.getCurrentLevel();
            default:
                if (cur == null) return "-";
                return "L" + r.getCurrentLevel() + "/" + r.getTotalLevels() + " \u00B7 " + cur.getLevelName();
        }
    }

    private String age(LocalDateTime from) {
        if (from == null) return "";
        long minutes = Duration.between(from, LocalDateTime.now()).toMinutes();
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h " + (minutes % 60) + "m";
        return (hours / 24) + "d " + (hours % 24) + "h";
    }
}
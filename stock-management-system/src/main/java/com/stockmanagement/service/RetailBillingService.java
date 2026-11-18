package com.stockmanagement.service;

import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.RetailBill;
import com.stockmanagement.entity.RetailBillCounter;
import com.stockmanagement.entity.RetailBillLine;
import com.stockmanagement.entity.RetailBillPayment;
import com.stockmanagement.entity.RetailBirthdayCode;
import com.stockmanagement.entity.RetailCustomer;
import com.stockmanagement.entity.RetailCustomerCoupon;
import com.stockmanagement.entity.RetailOffer;
import com.stockmanagement.entity.RetailPointsLedger;
import com.stockmanagement.entity.RetailShopProfile;
import com.stockmanagement.entity.RetailVariant;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.RetailBillCounterRepository;
import com.stockmanagement.repository.RetailBillLineRepository;
import com.stockmanagement.repository.RetailBillPaymentRepository;
import com.stockmanagement.repository.RetailBillRepository;
import com.stockmanagement.repository.RetailBirthdayCodeRepository;
import com.stockmanagement.repository.RetailCustomerCouponRepository;
import com.stockmanagement.repository.RetailCustomerRepository;
import com.stockmanagement.repository.RetailOfferRepository;
import com.stockmanagement.repository.RetailPointsLedgerRepository;
import com.stockmanagement.repository.RetailShopProfileRepository;
import com.stockmanagement.repository.RetailVariantRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Billing / POS: prices a cart, saves the bill, takes stock out, gives loyalty points and cancels a bill.
 *
 * Prices INCLUDE GST (see BillCalculator). Everything is company-wise: the company always comes from the
 * logged-in user. A bill is saved in ONE transaction: if anything fails (stock, points, number), nothing is kept.
 */
@Service
public class RetailBillingService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final SecureRandom RANDOM = new SecureRandom();

    // ------------------------------------------------------------------ what comes in

    /** one cart line. offerChoice: null = best offer automatically, 0 = no offer on this item. */
    public record CartLine(String barcode, BigDecimal qty, Long offerChoice,
                           String manualType, BigDecimal manualValue) {}

    public record PayIn(String mode, BigDecimal amount, String reference) {}

    /**
     * mobile and name are required. billOfferId = a bill offer chosen by the cashier (optional).
     * pointsToUse = whole points the customer agreed to use (0 = none; the cashier asks first).
     */
    public record BillIn(List<CartLine> lines, String mobile, String name, LocalDate dob, String email,
                         Long billOfferId, String birthdayCode, long pointsToUse,
                         String manualReason, List<PayIn> payments, String couponCode) {}

    // ------------------------------------------------------------------ what goes out

    public record QuoteLine(String barcode, Long variantId, String label, String hsnCode, BigDecimal gstRate,
                            BigDecimal qty, BigDecimal unitPrice, Long offerId, String offerName, boolean offerLocked,
                            BigDecimal manualDiscount, BillCalculator.LineOut calc) {}

    public record Quote(List<QuoteLine> lines, BillCalculator.Result calc, String billOfferName,
                        BigDecimal billOfferDiscount, BigDecimal birthdayDiscount,
                        long pointsUsed, long pointsEarned, long pointsAfter,
                        BigDecimal manualPercentMax, boolean needsManager, List<String> warnings) {}

    public record Saved(Long billId, String billNo, BigDecimal grandTotal, long pointsEarned, long pointsBalance, String token) {}

    public record CustomerView(Long id, String mobile, String name, LocalDate dob, String email, long points,
                               int totalBills, BigDecimal totalSpent, LocalDateTime lastVisit, boolean birthdayToday) {}

    // ------------------------------------------------------------------ wiring

    private final RetailVariantRepository variantRepo;
    private final ProductCategoryRepository productRepo;
    private final RetailOfferRepository offerRepo;
    private final RetailOfferService offerService;
    private final RetailInwardService inwardService;
    private final RetailBillRepository billRepo;
    private final RetailBillLineRepository lineRepo;
    private final RetailBillPaymentRepository payRepo;
    private final RetailBillCounterRepository counterRepo;
    private final RetailCustomerRepository customerRepo;
    private final RetailPointsLedgerRepository ledgerRepo;
    private final RetailBirthdayCodeRepository codeRepo;
    private final RetailCustomerCouponRepository custCouponRepo;
    private final RetailShopProfileRepository profileRepo;
    private final AuditLogService auditLogService;

    public RetailBillingService(RetailVariantRepository variantRepo, ProductCategoryRepository productRepo,
                                RetailOfferRepository offerRepo, RetailOfferService offerService,
                                RetailInwardService inwardService, RetailBillRepository billRepo,
                                RetailBillLineRepository lineRepo, RetailBillPaymentRepository payRepo,
                                RetailBillCounterRepository counterRepo, RetailCustomerRepository customerRepo,
                                RetailPointsLedgerRepository ledgerRepo, RetailBirthdayCodeRepository codeRepo,
                                RetailShopProfileRepository profileRepo, AuditLogService auditLogService,
                                RetailCustomerCouponRepository custCouponRepo) {
        this.variantRepo = variantRepo;
        this.productRepo = productRepo;
        this.offerRepo = offerRepo;
        this.offerService = offerService;
        this.inwardService = inwardService;
        this.billRepo = billRepo;
        this.lineRepo = lineRepo;
        this.payRepo = payRepo;
        this.counterRepo = counterRepo;
        this.customerRepo = customerRepo;
        this.ledgerRepo = ledgerRepo;
        this.codeRepo = codeRepo;
        this.custCouponRepo = custCouponRepo;
        this.profileRepo = profileRepo;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------ customers

    /** search box: mobile number or name (at least 2 characters) */
    @Transactional(readOnly = true)
    public List<CustomerView> searchCustomers(String company, String q) {
        String s = q == null ? "" : q.trim();
        if (s.length() < 2) return List.of();
        return customerRepo.search(company, s, PageRequest.of(0, 8)).stream().map(RetailBillingService::view).toList();
    }

    @Transactional(readOnly = true)
    public Optional<CustomerView> customerByMobile(String company, String mobile) {
        return customerRepo.findByCompanyNameAndMobile(company, cleanMobile(mobile)).map(RetailBillingService::view);
    }

    private static CustomerView view(RetailCustomer c) {
        LocalDate today = LocalDate.now();
        // "birthdayToday" now means: it is the customer's birthday MONTH (the offer is valid all month)
        boolean bday = c.getDob() != null && c.getDob().getMonth() == today.getMonth();
        return new CustomerView(c.getId(), c.getMobile(), c.getName(), c.getDob(), c.getEmail(),
                c.getPointsBalance() == null ? 0 : c.getPointsBalance(),
                c.getTotalBills() == null ? 0 : c.getTotalBills(),
                c.getTotalSpent() == null ? BigDecimal.ZERO : c.getTotalSpent(), c.getLastVisit(), bday);
    }

    static String cleanMobile(String raw) {
        String m = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (m.length() == 12 && m.startsWith("91")) m = m.substring(2);
        if (m.length() == 11 && m.startsWith("0")) m = m.substring(1);
        if (m.length() != 10) throw new IllegalArgumentException("Enter a valid 10 digit mobile number.");
        return m;
    }

    // ------------------------------------------------------------------ birthday code

    /**
     * Gives the birthday-offer code. It works in the customer's whole birthday month, once. Asking again in that
     * month returns the same unused code (so a customer who lost the message can be given it again); after it is
     * used, no second code is made for that month.
     */
    @Transactional
    public String birthdayCode(String company, Long customerId) {
        RetailCustomer c = customerRepo.findByIdAndCompanyName(customerId, company)
                .orElseThrow(() -> new IllegalArgumentException("Customer not found."));
        if (!view(c).birthdayToday()) throw new IllegalArgumentException("This is not the customer's birthday month.");
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate monthEnd = today.withDayOfMonth(today.lengthOfMonth());
        List<RetailBirthdayCode> thisMonth = codeRepo
                .findByCompanyNameAndCustomerIdAndValidOnBetween(company, c.getId(), monthStart, monthEnd);
        for (RetailBirthdayCode x : thisMonth) {
            if (x.getUsedBillId() != null) throw new IllegalArgumentException("The birthday offer is already used this month.");
        }
        if (!thisMonth.isEmpty()) return thisMonth.get(0).getCode();
        for (int tries = 0; tries < 20; tries++) {
            String code = "BDAY-" + (1000 + RANDOM.nextInt(9000));
            if (codeRepo.findByCompanyNameAndCode(company, code).isPresent()) continue;
            RetailBirthdayCode b = new RetailBirthdayCode();
            b.setCompanyName(company);
            b.setCustomerId(c.getId());
            b.setCode(code);
            b.setValidOn(today);
            codeRepo.save(b);
            return code;
        }
        throw new IllegalStateException("Could not make a birthday code. Please try again.");
    }

    public record BirthdayInfo(String name, String mobile, String code, BigDecimal percent, LocalDate validTill) {}

    /** owner side: the birthday code of the customer with this mobile number (same code every time in the birthday month) */
    @Transactional
    public BirthdayInfo birthdayCodeByMobile(String company, String mobile) {
        RetailCustomer c = customerRepo.findByCompanyNameAndMobile(company, cleanMobile(mobile))
                .orElseThrow(() -> new IllegalArgumentException("No customer found with this mobile number."));
        if (c.getDob() == null) throw new IllegalArgumentException("This customer has no date of birth saved.");
        String code = birthdayCode(company, c.getId());
        LocalDate today = LocalDate.now();
        RetailShopProfile p = profileRepo.findByCompanyName(company).orElseGet(RetailShopProfile::new);
        return new BirthdayInfo(c.getName(), c.getMobile(), code, p.getBirthdayDiscountPercent(),
                today.withDayOfMonth(today.lengthOfMonth()));
    }

    // ------------------------------------------------------------------ price the cart (nothing is saved)

    @Transactional(readOnly = true)
    public Quote quote(String company, BillIn in, boolean canOverride) {
        return build(company, in, canOverride, false).quote;
    }

    // ------------------------------------------------------------------ save the bill

    @Transactional
    public Saved save(String company, Long userId, String userName, BillIn in, boolean canOverride,
                      String approvedBy, HttpServletRequest request) {
        String mobile = cleanMobile(in.mobile());
        String name = in.name() == null ? "" : in.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Customer name is required.");

        Built b = build(company, in, canOverride, true);
        Quote q = b.quote;
        BillCalculator.Result calc = q.calc();

        // payments must add up to the total payable (cash change is not a payment)
        List<PayIn> pays = in.payments() == null ? List.of() : in.payments();
        BigDecimal paid = BigDecimal.ZERO;
        for (PayIn p : pays) {
            if (p.amount() == null || p.amount().signum() <= 0) throw new IllegalArgumentException("Payment amount must be more than zero.");
            paid = paid.add(p.amount());
        }
        if (calc.grandTotal().signum() > 0 && paid.compareTo(calc.grandTotal()) != 0) {
            throw new IllegalArgumentException("Payments (" + paid.toPlainString() + ") must add up to the total payable ("
                    + calc.grandTotal().toPlainString() + ").");
        }

        // the customer (created with the bill when new)
        RetailCustomer cust = customerRepo.findByCompanyNameAndMobile(company, mobile).orElse(null);
        if (cust == null) {
            cust = new RetailCustomer();
            cust.setCompanyName(company);
            cust.setMobile(mobile);
            cust.setName(name);
            cust.setDob(in.dob());
            cust.setEmail(blankToNull(in.email()));
            cust = customerRepo.saveAndFlush(cust);
        }
        cust = customerRepo.findLocked(cust.getId(), company).orElseThrow();   // lock: points change safely
        cust.setName(name);
        if (in.dob() != null) cust.setDob(in.dob());
        if (blankToNull(in.email()) != null) cust.setEmail(in.email().trim());

        long redeem = q.pointsUsed();
        if (redeem > 0 && redeem > nz(cust.getPointsBalance())) {
            throw new IllegalArgumentException("Customer has only " + nz(cust.getPointsBalance()) + " points.");
        }

        // birthday code: check again inside the transaction, then mark used
        RetailBirthdayCode code = null;
        if (q.birthdayDiscount().signum() > 0) {
            code = b.code;
        }

        // invoice number: taken once, here, from the locked counter
        int year = LocalDate.now().getYear();
        RetailBillCounter counter = counterRepo.findLocked(company, year).orElse(null);
        if (counter == null) {
            RetailBillCounter c = new RetailBillCounter();
            c.setCompanyName(company);
            c.setBillYear(year);
            c.setLastNo(0L);
            counterRepo.saveAndFlush(c);
            counter = counterRepo.findLocked(company, year).orElseThrow();
        }
        counter.setLastNo(counter.getLastNo() + 1);
        counterRepo.save(counter);
        String billNo = String.format("INV-%d-%06d", year, counter.getLastNo());

        // the bill
        long earned = BillCalculator.pointsEarned(calc.grandTotal(), b.profile.getPointsRatePercent());
        RetailBill bill = new RetailBill();
        bill.setCompanyName(company);
        bill.setBillNo(billNo);
        bill.setShareToken(java.util.UUID.randomUUID().toString().replace("-", ""));
        bill.setBillDate(LocalDateTime.now());
        bill.setCustomerId(cust.getId());
        bill.setCustomerName(name);
        bill.setCustomerMobile(mobile);
        bill.setGrossAmount(calc.gross());
        bill.setItemDiscount(calc.itemDiscount());
        bill.setManualDiscount(calc.manualDiscount());
        bill.setManualReason(calc.manualDiscount().signum() > 0 ? in.manualReason().trim() : null);
        bill.setManualApprovedBy(b.manualAboveLimit ? approvedBy : null);
        bill.setBillOfferId(b.billOffer == null ? null : b.billOffer.getId());
        bill.setBillOfferName(q.billOfferName());
        bill.setBillDiscount(calc.billDiscount());
        bill.setPointsRedeemed(redeem);
        bill.setPointsEarned(earned);
        bill.setTaxableAmount(calc.taxable());
        bill.setCgstAmount(calc.cgst());
        bill.setSgstAmount(calc.sgst());
        bill.setRoundOff(calc.roundOff());
        bill.setGrandTotal(calc.grandTotal());
        bill.setStatus(RetailBill.COMPLETED);
        bill.setCreatedById(userId);
        bill.setCreatedByName(userName);
        bill = billRepo.saveAndFlush(bill);

        // lines (+ stock)
        List<RetailInwardService.LineData> outs = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (int i = 0; i < q.lines().size(); i++) {
            QuoteLine ql = q.lines().get(i);
            BillCalculator.LineOut lo = ql.calc();
            RetailBillLine bl = new RetailBillLine();
            bl.setCompanyName(company);
            bl.setBillId(bill.getId());
            bl.setVariantId(ql.variantId());
            bl.setItemLabel(ql.label());
            bl.setBarcode(ql.barcode());
            bl.setHsnCode(ql.hsnCode());
            bl.setGstRate(ql.gstRate());
            bl.setQty(ql.qty());
            bl.setUnitPrice(ql.unitPrice());
            bl.setGrossAmount(lo.grossAmount());
            bl.setOfferId(ql.offerId());
            bl.setOfferName(ql.offerName());
            bl.setDiscount(lo.itemDiscount());
            bl.setManualDiscount(lo.manualDiscount());
            bl.setBillDiscount(lo.billShare());
            bl.setTaxableAmount(lo.taxable());
            bl.setCgstAmount(lo.cgst());
            bl.setSgstAmount(lo.sgst());
            bl.setLineTotal(lo.lineTotal());
            lineRepo.save(bl);

            RetailVariant v = b.variants.get(ql.variantId());
            v.setQty(v.getQty().subtract(ql.qty()));
            variantRepo.save(v);
            ProductCategory p = b.products.get(v.getProductId());
            outs.add(new RetailInwardService.LineData(v.getId(), p == null ? ql.label() : p.getProductName(),
                    v.getStyleCode(), v.getSizeLabel(), v.getColour(), v.getBarcode(), v.getUnit(), ql.qty(),
                    v.getMrp(), v.getSalePrice()));
            reasons.add("Sale " + billNo);
        }
        inwardService.recordOutBatch(company, userId, userName, outs, reasons);

        for (PayIn p : pays) {
            RetailBillPayment bp = new RetailBillPayment();
            bp.setCompanyName(company);
            bp.setBillId(bill.getId());
            bp.setPayMode(normMode(p.mode()));
            bp.setAmount(p.amount());
            bp.setReference(blankToNull(p.reference()));
            payRepo.save(bp);
        }

        // customer totals and points
        cust.setTotalBills(nz(cust.getTotalBills()) + 1);
        cust.setTotalSpent(cust.getTotalSpent().add(calc.grandTotal()));
        cust.setLastVisit(bill.getBillDate());
        cust.setPointsBalance(nz(cust.getPointsBalance()) - redeem + earned);
        customerRepo.save(cust);
        if (redeem > 0) ledger(company, cust.getId(), bill.getId(), -redeem, RetailPointsLedger.REDEEMED);
        if (earned > 0) ledger(company, cust.getId(), bill.getId(), earned, RetailPointsLedger.EARNED);
        if (code != null) {
            code.setUsedBillId(bill.getId());
            codeRepo.save(code);
        }
        if (b.custCoupon != null) {
            // customer coupon: lock it again, make sure nobody used it meanwhile, then mark it used
            RetailCustomerCoupon cc = custCouponRepo.findLockedByCode(company, b.custCoupon.getCode())
                    .orElseThrow(() -> new IllegalArgumentException("This coupon code is not valid."));
            if (cc.getUsedBillId() != null) throw new IllegalArgumentException("This coupon is already used and has expired.");
            cc.setUsedBillId(bill.getId());
            custCouponRepo.save(cc);
        }

        audit(userId, userName, "CREATE", "Bill " + billNo + " for " + name + " (" + mobile + "), total "
                + calc.grandTotal().toPlainString() + (b.manualAboveLimit ? ", manual discount approved by " + approvedBy : ""), request);
        return new Saved(bill.getId(), billNo, calc.grandTotal(), earned, nz(cust.getPointsBalance()), bill.getShareToken());
    }

    // ------------------------------------------------------------------ cancel a bill

    /** Puts the stock back and reverses the points. The bill stays in the list as CANCELLED. */
    @Transactional
    public void cancel(String company, Long userId, String userName, Long billId, String reason, HttpServletRequest request) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Please give a reason for cancelling.");
        RetailBill bill = billRepo.findByIdAndCompanyName(billId, company)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        if (!RetailBill.COMPLETED.equals(bill.getStatus())) throw new IllegalArgumentException("This bill is already cancelled.");

        List<RetailBillLine> lines = lineRepo.findByCompanyNameAndBillIdOrderByIdAsc(company, billId);
        List<Long> ids = lines.stream().map(RetailBillLine::getVariantId).filter(x -> x != null).distinct().toList();
        Map<Long, RetailVariant> vs = variantRepo.findLockedByCompanyNameAndIdIn(company, ids).stream()
                .collect(Collectors.toMap(RetailVariant::getId, v -> v));
        for (RetailBillLine l : lines) {
            RetailVariant v = l.getVariantId() == null ? null : vs.get(l.getVariantId());
            if (v == null) continue;
            v.setQty(v.getQty().add(l.getQty()));
            variantRepo.save(v);
        }

        if (bill.getCustomerId() != null) {
            RetailCustomer c = customerRepo.findLocked(bill.getCustomerId(), company).orElse(null);
            if (c != null) {
                long earned = nz(bill.getPointsEarned()), used = nz(bill.getPointsRedeemed());
                long take = Math.min(earned, nz(c.getPointsBalance()) + used);   // never below zero
                c.setPointsBalance(nz(c.getPointsBalance()) - take + used);
                c.setTotalBills(Math.max(0, nz(c.getTotalBills()) - 1));
                c.setTotalSpent(c.getTotalSpent().subtract(bill.getGrandTotal()).max(BigDecimal.ZERO));
                customerRepo.save(c);
                if (take > 0) ledger(company, c.getId(), billId, -take, RetailPointsLedger.CANCEL_EARNED);
                if (used > 0) ledger(company, c.getId(), billId, used, RetailPointsLedger.CANCEL_REDEEMED);
            }
        }

        // a customer coupon used on this bill works again
        for (RetailCustomerCoupon cc : custCouponRepo.findByCompanyNameAndUsedBillId(company, billId)) {
            cc.setUsedBillId(null);
            custCouponRepo.save(cc);
        }

        bill.setStatus(RetailBill.CANCELLED);
        bill.setCancelledBy(userName);
        bill.setCancelledAt(LocalDateTime.now());
        bill.setCancelReason(reason.trim());
        billRepo.save(bill);
        audit(userId, userName, "CANCEL", "Cancelled bill " + bill.getBillNo() + ": " + reason.trim(), request);
    }

    // ------------------------------------------------------------------ the pricing engine

    private static final class Built {
        Quote quote;
        RetailShopProfile profile;
        RetailOffer billOffer;
        RetailBirthdayCode code;
        RetailCustomerCoupon custCoupon;
        boolean manualAboveLimit;
        Map<Long, RetailVariant> variants = new HashMap<>();
        Map<Long, ProductCategory> products = new HashMap<>();
    }

    private Built build(String company, BillIn in, boolean canOverride, boolean lock) {
        if (in == null || in.lines() == null || in.lines().isEmpty()) throw new IllegalArgumentException("Add at least one item.");
        Built b = new Built();
        b.profile = profileRepo.findByCompanyName(company).orElseGet(RetailShopProfile::new);
        List<String> warnings = new ArrayList<>();

        // same barcode scanned twice = one line with the total quantity
        Map<String, CartLine> merged = new LinkedHashMap<>();
        for (CartLine cl : in.lines()) {
            String code = cl.barcode() == null ? "" : cl.barcode().trim();
            if (code.isEmpty()) throw new IllegalArgumentException("Barcode is required.");
            if (cl.qty() == null || cl.qty().signum() <= 0) throw new IllegalArgumentException("Quantity must be more than zero.");
            String key = code.toLowerCase(Locale.ROOT);
            CartLine old = merged.get(key);
            merged.put(key, old == null ? new CartLine(code, cl.qty(), cl.offerChoice(), cl.manualType(), cl.manualValue())
                    : new CartLine(code, old.qty().add(cl.qty()), old.offerChoice(), old.manualType(), old.manualValue()));
        }

        List<String> codes = merged.values().stream().map(CartLine::barcode).toList();
        List<RetailVariant> found = lock ? variantRepo.findLockedByCompanyNameAndBarcodeIn(company, codes)
                : variantRepo.findByCompanyNameAndBarcodeIn(company, codes);
        Map<String, RetailVariant> byCode = new HashMap<>();
        for (RetailVariant v : found) if (v.getBarcode() != null) byCode.put(v.getBarcode().toLowerCase(Locale.ROOT), v);
        for (RetailVariant v : found) b.variants.put(v.getId(), v);
        for (ProductCategory p : productRepo.findAllById(found.stream().map(RetailVariant::getProductId).distinct().toList())) {
            if (company.equals(p.getCompanyName())) b.products.put(p.getId(), p);
        }

        // offers each variant gets right now
        List<RetailVariantService.Line> forOffers = new ArrayList<>();
        for (RetailVariant v : found) {
            ProductCategory p = b.products.get(v.getProductId());
            forOffers.add(new RetailVariantService.Line(v, p == null ? "-" : p.getCategoryName(), p == null ? "-" : p.getProductName()));
        }
        Map<Long, RetailOfferService.Applied> applied = offerService.appliedFor(company, forOffers);

        List<BillCalculator.LineIn> calcIn = new ArrayList<>();
        List<Object[]> meta = new ArrayList<>();   // variant, label, offerId, offerName, locked
        BigDecimal limit = b.profile.getCashierDiscountLimit() == null ? BigDecimal.ZERO : b.profile.getCashierDiscountLimit();
        BigDecimal maxPct = BigDecimal.ZERO;
        boolean anyManual = false;

        for (CartLine cl : merged.values()) {
            RetailVariant v = byCode.get(cl.barcode().toLowerCase(Locale.ROOT));
            if (v == null) throw new IllegalArgumentException("Barcode " + cl.barcode() + " is not in your stock.");
            if (!"ACTIVE".equals(v.getStatus())) throw new IllegalArgumentException("Barcode " + cl.barcode() + " is inactive.");
            if (v.getQty().compareTo(cl.qty()) < 0) {
                throw new IllegalArgumentException("Only " + v.getQty().stripTrailingZeros().toPlainString() + " available for "
                        + cl.barcode() + " (asked for " + cl.qty().stripTrailingZeros().toPlainString() + ").");
            }
            BigDecimal price = v.getSalePrice() != null ? v.getSalePrice() : v.getMrp();
            if (price == null || price.signum() <= 0) throw new IllegalArgumentException("No price set for " + cl.barcode() + ".");
            ProductCategory p = b.products.get(v.getProductId());
            // GST %: this variant's own rate, else its product's rate, else the shop's default (18 unless the shop changed it)
            BigDecimal shopGst = b.profile.getDefaultGstRate() == null ? new BigDecimal("18.00") : b.profile.getDefaultGstRate();
            BigDecimal rate = v.getGstRate() != null ? v.getGstRate()
                    : (p != null && p.getGstRate() != null ? p.getGstRate() : shopGst);

            // item offer: best one automatically; offerChoice 0 = the cashier removed it
            RetailOfferService.Applied ap = applied.get(v.getId());
            BigDecimal itemDisc = BigDecimal.ZERO;
            Long offerId = null;
            String offerName = null;
            boolean locked = false;
            if (ap != null) {
                RetailOffer o = offerRepo.findByIdAndCompanyName(ap.offerId(), company).orElse(null);
                locked = o != null && Boolean.TRUE.equals(o.getLockedForCashier());
                boolean removed = cl.offerChoice() != null && cl.offerChoice() == 0L;
                if (removed && locked && !canOverride) {
                    throw new IllegalArgumentException("The offer on " + label(p, v) + " is locked. A manager must approve removing it.");
                }
                if (!removed) {
                    offerId = ap.offerId();
                    offerName = ap.name();
                    if (ap.offerPrice() != null) {
                        itemDisc = price.subtract(ap.offerPrice()).multiply(cl.qty());
                    } else if (o != null && o.getBuyQty() != null && o.getGetQty() != null) {
                        itemDisc = BillCalculator.buyXGetYDiscount(price, cl.qty(), o.getBuyQty(), o.getGetQty());
                    }
                }
            }

            // manual discount (after the item offer)
            BigDecimal manual = BigDecimal.ZERO;
            if (cl.manualType() != null && !cl.manualType().isBlank() && cl.manualValue() != null && cl.manualValue().signum() > 0) {
                anyManual = true;
                BigDecimal base = price.multiply(cl.qty()).subtract(itemDisc).setScale(2, RoundingMode.HALF_UP);
                BigDecimal pct;
                if ("PERCENT".equalsIgnoreCase(cl.manualType())) {
                    if (cl.manualValue().compareTo(HUNDRED) > 0) throw new IllegalArgumentException("Discount cannot be more than 100%.");
                    manual = base.multiply(cl.manualValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP);
                    pct = cl.manualValue();
                } else if ("FLAT".equalsIgnoreCase(cl.manualType())) {
                    manual = cl.manualValue().min(base).setScale(2, RoundingMode.HALF_UP);
                    pct = base.signum() == 0 ? BigDecimal.ZERO : manual.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
                } else {
                    throw new IllegalArgumentException("Discount type must be PERCENT or FLAT.");
                }
                if (pct.compareTo(maxPct) > 0) maxPct = pct;
            }

            calcIn.add(new BillCalculator.LineIn(cl.barcode(), price, cl.qty(), rate, itemDisc, manual));
            meta.add(new Object[]{v, label(p, v), offerId, offerName, locked, p == null ? null : p.getHsnCode(), rate});
        }

        // manual discounts need a reason, and a manager above the cashier limit
        if (anyManual) {
            if (in.manualReason() == null || in.manualReason().isBlank()) throw new IllegalArgumentException("Please give a reason for the discount.");
            if (maxPct.compareTo(limit) > 0) {
                b.manualAboveLimit = true;
                if (!canOverride) {
                    throw new IllegalArgumentException("A discount above " + limit.stripTrailingZeros().toPlainString()
                            + "% needs a manager's approval.");
                }
            }
        }

        // first pass without bill-level discounts, to know what the bill is worth
        BillCalculator.Result base = BillCalculator.compute(new BillCalculator.Input(calcIn, BigDecimal.ZERO, BigDecimal.ZERO));
        BigDecimal subtotal = base.gross().subtract(base.itemDiscount()).subtract(base.manualDiscount());

        // bill offer (optional, chosen by the cashier)
        BigDecimal billOfferDisc = BigDecimal.ZERO;
        String billOfferName = null;
        String coupon = in.couponCode() == null ? "" : in.couponCode().trim();
        RetailOffer chosen = null;
        if (!coupon.isEmpty()) {
            // a typed coupon code wins over the list
            RetailCustomerCoupon mine = custCouponRepo.findByCompanyNameAndCode(company, coupon.toUpperCase(Locale.ROOT)).orElse(null);
            if (mine != null) {
                // a one-time code: used already -> expired. A code tied to a customer also needs that mobile number
                if (mine.getUsedBillId() != null) throw new IllegalArgumentException("This coupon is already used and has expired.");
                if (mine.getMobile() != null) {
                    if (in.mobile() == null || in.mobile().isBlank()) {
                        throw new IllegalArgumentException("This coupon is for one customer. Enter the customer's mobile number first.");
                    }
                    if (!mine.getMobile().equals(cleanMobile(in.mobile()))) {
                        throw new IllegalArgumentException("This coupon belongs to another customer.");
                    }
                }
                chosen = offerRepo.findByIdAndCompanyName(mine.getOfferId(), company)
                        .orElseThrow(() -> new IllegalArgumentException("This coupon code is not valid."));
                if (!RetailOfferService.isBill(chosen) || !RetailOfferService.isLive(chosen, LocalDate.now())) {
                    throw new IllegalArgumentException("This coupon has ended or is switched off.");
                }
                b.custCoupon = mine;
            } else {
                chosen = offerRepo.findByCompanyNameOrderByIdDesc(company).stream()
                        .filter(x -> !RetailOfferService.isCustomerCoupon(x) && coupon.equalsIgnoreCase(x.getCouponCode()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("This coupon code is not valid."));
                if (!RetailOfferService.isBill(chosen) || !RetailOfferService.isLive(chosen, LocalDate.now())) {
                    throw new IllegalArgumentException("This coupon has ended or is switched off.");
                }
            }
        } else if (in.billOfferId() != null) {
            chosen = offerRepo.findByIdAndCompanyName(in.billOfferId(), company)
                    .orElseThrow(() -> new IllegalArgumentException("Bill offer not found."));
            if (chosen.getCouponCode() != null && !chosen.getCouponCode().isBlank()) {
                throw new IllegalArgumentException("This offer needs its coupon code.");
            }
        }
        if (chosen != null) {
            RetailOffer o = chosen;
            if (!RetailOfferService.isBill(o) || !RetailOfferService.isLive(o, LocalDate.now())) {
                throw new IllegalArgumentException("This bill offer is not running now.");
            }
            if (o.getMinBill() != null && subtotal.compareTo(o.getMinBill()) < 0) {
                throw new IllegalArgumentException("This offer needs a bill of " + o.getMinBill().stripTrailingZeros().toPlainString() + " or more.");
            }
            billOfferDisc = RetailOffer.BILLPCT.equals(o.getOfferType())
                    ? subtotal.multiply(o.getValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP) : o.getValue();
            billOfferDisc = billOfferDisc.min(subtotal);
            billOfferName = o.getName();
            b.billOffer = o;
        }

        // birthday offer (needs a valid one-time code of THIS customer, in THIS month)
        BigDecimal bdayDisc = BigDecimal.ZERO;
        if (in.birthdayCode() != null && !in.birthdayCode().isBlank()) {
            String mobile = cleanMobile(in.mobile());
            RetailCustomer c = customerRepo.findByCompanyNameAndMobile(company, mobile)
                    .orElseThrow(() -> new IllegalArgumentException("Birthday code is for an existing customer only."));
            RetailBirthdayCode bc = codeRepo.findByCompanyNameAndCode(company, in.birthdayCode().trim().toUpperCase(Locale.ROOT))
                    .orElseThrow(() -> new IllegalArgumentException("This birthday code is not valid."));
            LocalDate nowD = LocalDate.now();
            if (!bc.getCustomerId().equals(c.getId()) || bc.getValidOn().getYear() != nowD.getYear()
                    || bc.getValidOn().getMonth() != nowD.getMonth()) {
                throw new IllegalArgumentException("This birthday code is not valid for this customer this month.");
            }
            if (bc.getUsedBillId() != null) throw new IllegalArgumentException("This birthday code is already used.");
            BigDecimal pct = b.profile.getBirthdayDiscountPercent();
            if (pct == null || pct.signum() <= 0) throw new IllegalArgumentException("The birthday discount is not set up yet.");
            bdayDisc = subtotal.subtract(billOfferDisc).multiply(pct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            b.code = bc;
            billOfferName = billOfferName == null ? "Birthday offer" : billOfferName + " + Birthday offer";
        }

        // loyalty points the customer chose to use (1 point = Rs 1)
        long wantPoints = Math.max(0, in.pointsToUse());
        BigDecimal room = subtotal.subtract(billOfferDisc).subtract(bdayDisc).max(BigDecimal.ZERO);
        long pointsUsed = Math.min(wantPoints, room.setScale(0, RoundingMode.DOWN).longValue());
        long balance = 0;
        if (in.mobile() != null && !in.mobile().isBlank()) {
            try {
                balance = customerRepo.findByCompanyNameAndMobile(company, cleanMobile(in.mobile()))
                        .map(c -> nz(c.getPointsBalance())).orElse(0L);
            } catch (IllegalArgumentException ignored) { /* mobile is checked when the bill is saved */ }
        }
        if (pointsUsed > balance) throw new IllegalArgumentException("Customer has only " + balance + " points.");

        BillCalculator.Result calc = BillCalculator.compute(new BillCalculator.Input(
                calcIn, billOfferDisc.add(bdayDisc), BigDecimal.valueOf(pointsUsed)));

        List<QuoteLine> out = new ArrayList<>();
        for (int i = 0; i < calcIn.size(); i++) {
            Object[] m = meta.get(i);
            RetailVariant v = (RetailVariant) m[0];
            BillCalculator.LineIn li = calcIn.get(i);
            out.add(new QuoteLine(v.getBarcode(), v.getId(), (String) m[1], (String) m[5], (BigDecimal) m[6], li.qty(),
                    li.unitPrice(), (Long) m[2], (String) m[3], (Boolean) m[4], li.manualDiscount(), calc.lines().get(i)));
        }
        long earned = BillCalculator.pointsEarned(calc.grandTotal(), b.profile.getPointsRatePercent());
        b.quote = new Quote(out, calc, billOfferName, billOfferDisc, bdayDisc, pointsUsed, earned,
                balance - pointsUsed + earned, maxPct, b.manualAboveLimit, warnings);
        return b;
    }

    // ------------------------------------------------------------------ screens: bill offers, list, one bill

    /** bill offers (and coupons) running today: the cashier can pick one for the whole bill */
    @Transactional(readOnly = true)
    public List<RetailOffer> liveBillOffers(String company) {
        LocalDate today = LocalDate.now();
        return offerRepo.findByCompanyNameOrderByIdDesc(company).stream()
                .filter(o -> RetailOfferService.isBill(o) && RetailOfferService.isLive(o, today))
                .filter(o -> o.getCouponCode() == null || o.getCouponCode().isBlank())   // coupon offers need the code
                .toList();
    }

    /** bills of a date range (newest first); q = part of the bill number, customer name or mobile */
    @Transactional(readOnly = true)
    public List<RetailBill> list(String company, LocalDate from, LocalDate to, String q, String status) {
        LocalDate f = from == null ? LocalDate.now() : from;
        LocalDate t2 = to == null ? f : to;
        if (t2.isBefore(f)) { LocalDate x = f; f = t2; t2 = x; }
        List<RetailBill> all = billRepo.findByCompanyNameAndBillDateBetweenOrderByIdDesc(
                company, f.atStartOfDay(), t2.plusDays(1).atStartOfDay().minusNanos(1));
        String s = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return all.stream()
                .filter(b -> status == null || status.isBlank() || status.equalsIgnoreCase(b.getStatus()))
                .filter(b -> s.isEmpty()
                        || (b.getBillNo() != null && b.getBillNo().toLowerCase(Locale.ROOT).contains(s))
                        || (b.getCustomerName() != null && b.getCustomerName().toLowerCase(Locale.ROOT).contains(s))
                        || (b.getCustomerMobile() != null && b.getCustomerMobile().contains(s)))
                .toList();
    }

    /** totals of the completed bills in a date range: how much came in by cash / UPI / card */
    public record DaySummary(int bills, int cancelled, BigDecimal total, BigDecimal cash, BigDecimal upi, BigDecimal card) {}

    @Transactional(readOnly = true)
    public DaySummary summary(String company, LocalDate from, LocalDate to) {
        List<RetailBill> done = list(company, from, to, null, RetailBill.COMPLETED);
        int cancelled = list(company, from, to, null, RetailBill.CANCELLED).size();
        BigDecimal total = BigDecimal.ZERO, cash = BigDecimal.ZERO, upi = BigDecimal.ZERO, card = BigDecimal.ZERO;
        for (RetailBill b : done) total = total.add(nz2(b.getGrandTotal()));
        if (!done.isEmpty()) {
            List<Long> ids = done.stream().map(RetailBill::getId).toList();
            for (RetailBillPayment p : payRepo.findByCompanyNameAndBillIdIn(company, ids)) {
                BigDecimal a = nz2(p.getAmount());
                if ("CASH".equals(p.getPayMode())) cash = cash.add(a);
                else if ("UPI".equals(p.getPayMode())) upi = upi.add(a);
                else if ("CARD".equals(p.getPayMode())) card = card.add(a);
            }
        }
        return new DaySummary(done.size(), cancelled, total, cash, upi, card);
    }

    private static BigDecimal nz2(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** one bill with everything needed to print it (GST summary is grouped by GST %) */
    public record BillDetail(RetailBill bill, List<RetailBillLine> lines, List<RetailBillPayment> payments,
                             List<BillCalculator.RateRow> rateRows, RetailShopProfile profile, BigDecimal pointsBalance) {}

    /** the bill behind a customer link (no login). Bills made before links existed have no token and cannot be opened. */
    @Transactional(readOnly = true)
    public BillDetail detailByToken(String token) {
        if (token == null || !token.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Bill not found.");
        RetailBill b = billRepo.findByShareToken(token).orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        return detail(b.getCompanyName(), b.getId());
    }

    @Transactional(readOnly = true)
    public BillDetail detail(String company, Long billId) {
        RetailBill bill = billRepo.findByIdAndCompanyName(billId, company)
                .orElseThrow(() -> new IllegalArgumentException("Bill not found."));
        List<RetailBillLine> lines = lineRepo.findByCompanyNameAndBillIdOrderByIdAsc(company, billId);
        List<RetailBillPayment> pays = payRepo.findByCompanyNameAndBillIdOrderByIdAsc(company, billId);
        List<BillCalculator.RateRow> rates = new ArrayList<>();
        for (RetailBillLine l : lines) {
            BigDecimal r = l.getGstRate() == null ? BigDecimal.ZERO : l.getGstRate();
            boolean done = false;
            for (int i = 0; i < rates.size(); i++) {
                BillCalculator.RateRow x = rates.get(i);
                if (x.gstRate().compareTo(r) == 0) {
                    rates.set(i, new BillCalculator.RateRow(r, x.taxable().add(l.getTaxableAmount()),
                            x.cgst().add(l.getCgstAmount()), x.sgst().add(l.getSgstAmount())));
                    done = true;
                    break;
                }
            }
            if (!done) rates.add(new BillCalculator.RateRow(r, l.getTaxableAmount(), l.getCgstAmount(), l.getSgstAmount()));
        }
        BigDecimal balance = bill.getCustomerId() == null ? null
                : customerRepo.findByIdAndCompanyName(bill.getCustomerId(), company)
                        .map(c -> BigDecimal.valueOf(nz(c.getPointsBalance()))).orElse(null);
        return new BillDetail(bill, lines, pays, rates, profile(company), balance);
    }

    // ------------------------------------------------------------------ shop details (printed on every bill)

    public record ProfileIn(String shopName, String address, String phone, String email, String website,
                            String gstin, String stateName,
                            String footerNote, BigDecimal pointsRatePercent, BigDecimal birthdayDiscountPercent,
                            BigDecimal cashierDiscountLimit, BigDecimal defaultGstRate, String upiId) {}

    @Transactional
    public void saveProfile(String company, Long userId, String userName, ProfileIn in, HttpServletRequest request) {
        // shop name, address, phone and e-mail are compulsory; the website is optional
        String shopName = blankToNull(in.shopName());
        String address = blankToNull(in.address());
        String phone = blankToNull(in.phone());
        String email = blankToNull(in.email());
        String website = blankToNull(in.website());
        if (shopName == null) throw new IllegalArgumentException("Shop name is required.");
        if (address == null) throw new IllegalArgumentException("Address is required.");
        if (phone == null) throw new IllegalArgumentException("Phone number is required.");
        if (phone.replaceAll("[^0-9]", "").length() < 10 || !phone.matches("[0-9+()\\- ]{10,20}"))
            throw new IllegalArgumentException("Enter a valid phone number (at least 10 digits).");
        if (email == null) throw new IllegalArgumentException("E-mail is required.");
        if (email.length() > 150 || !email.matches("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"))
            throw new IllegalArgumentException("Enter a valid e-mail address, e.g. shop@example.com.");
        email = email.toLowerCase(Locale.ROOT);
        if (website != null) {
            website = website.replaceFirst("(?i)^https?://", "");
            if (website.length() > 200 || !website.matches("[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}(/\\S*)?"))
                throw new IllegalArgumentException("Enter a valid website, e.g. www.example.com.");
        }
        String gstin = blankToNull(in.gstin());
        if (gstin != null) {
            gstin = gstin.toUpperCase(Locale.ROOT);
            if (!gstin.matches("[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]"))
                throw new IllegalArgumentException("GSTIN looks wrong. It has 15 letters and digits, e.g. 27ABCDE1234F1Z5.");
        }
        // a field that is not sent (null) keeps its saved value; the Shop Features panel sends only the shop details
        RetailShopProfile p = profileRepo.findByCompanyName(company).orElseGet(RetailShopProfile::new);
        BigDecimal rate = in.pointsRatePercent() != null ? in.pointsRatePercent()
                : (p.getPointsRatePercent() != null ? p.getPointsRatePercent() : new BigDecimal("0.50"));
        // no fixed default for these two: they stay blank until the shop sets them
        BigDecimal bday = in.birthdayDiscountPercent() != null ? in.birthdayDiscountPercent() : p.getBirthdayDiscountPercent();
        BigDecimal limit = in.cashierDiscountLimit() != null ? in.cashierDiscountLimit() : p.getCashierDiscountLimit();
        BigDecimal dgst = in.defaultGstRate() != null ? in.defaultGstRate()
                : (p.getDefaultGstRate() != null ? p.getDefaultGstRate() : new BigDecimal("18.00"));
        if (dgst.signum() < 0 || dgst.compareTo(new BigDecimal("40")) > 0) throw new IllegalArgumentException("Default GST % must be between 0 and 40.");
        if (rate.signum() < 0 || rate.compareTo(HUNDRED) > 0) throw new IllegalArgumentException("Points rate must be between 0 and 100.");
        if (bday != null && (bday.signum() < 0 || bday.compareTo(HUNDRED) > 0)) throw new IllegalArgumentException("Birthday discount must be between 0 and 100.");
        if (limit != null && (limit.signum() < 0 || limit.compareTo(HUNDRED) > 0)) throw new IllegalArgumentException("Cashier discount limit must be between 0 and 100.");
        p.setCompanyName(company);
        p.setShopName(shopName);
        p.setAddress(address);
        p.setPhone(phone);
        p.setEmail(email);
        p.setWebsite(website);
        p.setGstin(gstin);
        p.setStateName(blankToNull(in.stateName()));
        if (in.footerNote() != null) p.setFooterNote(blankToNull(in.footerNote()));
        p.setPointsRatePercent(rate.setScale(2, RoundingMode.HALF_UP));
        p.setBirthdayDiscountPercent(bday == null ? null : bday.setScale(2, RoundingMode.HALF_UP));
        p.setCashierDiscountLimit(limit == null ? null : limit.setScale(2, RoundingMode.HALF_UP));
        p.setDefaultGstRate(dgst.setScale(2, RoundingMode.HALF_UP));
        if (in.upiId() != null) {
            String upi = blankToNull(in.upiId());
            if (upi != null) {
                upi = upi.trim();
                if (!upi.matches("[A-Za-z0-9._\\-]{2,80}@[A-Za-z][A-Za-z0-9.\\-]{1,40}"))
                    throw new IllegalArgumentException("UPI ID looks wrong. It looks like shopname@bank.");
            }
            p.setUpiId(upi);
        }
        profileRepo.save(p);
        audit(userId, userName, "UPDATE", "Shop details updated", request);
    }

    // ------------------------------------------------------------------ small helpers

    private void ledger(String company, Long customerId, Long billId, long points, String reason) {
        RetailPointsLedger l = new RetailPointsLedger();
        l.setCompanyName(company);
        l.setCustomerId(customerId);
        l.setBillId(billId);
        l.setPoints(points);
        l.setReason(reason);
        ledgerRepo.save(l);
    }

    private void audit(Long userId, String userName, String action, String text, HttpServletRequest request) {
        try {
            auditLogService.log(userId, userName, action, "Retail", "Billing", text, request);
        } catch (RuntimeException ignored) {
            // audit failure must not block billing
        }
    }

    private static String label(ProductCategory p, RetailVariant v) {
        StringBuilder sb = new StringBuilder(p == null ? "Item" : p.getProductName());
        if (v.getSizeLabel() != null && !v.getSizeLabel().isBlank()) sb.append(" / ").append(v.getSizeLabel());
        if (v.getColour() != null && !v.getColour().isBlank()) sb.append(" / ").append(v.getColour());
        return sb.toString();
    }

    private static String normMode(String m) {
        String s = m == null ? "" : m.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CASH", "UPI", "CARD" -> s;
            default -> "OTHER";
        };
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static long nz(Long v) { return v == null ? 0L : v; }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    /** kept for the screens: companies with the billing feature on can also read this */
    public RetailShopProfile profile(String company) {
        return profileRepo.findByCompanyName(company).orElseGet(RetailShopProfile::new);
    }

    @SuppressWarnings("unused")
    private static <T> T first(Collection<T> c) { return c.iterator().next(); }
}
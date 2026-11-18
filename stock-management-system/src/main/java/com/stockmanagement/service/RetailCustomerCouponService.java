package com.stockmanagement.service;

import com.stockmanagement.entity.RetailCustomer;
import com.stockmanagement.entity.RetailCustomerCoupon;
import com.stockmanagement.entity.RetailOffer;
import com.stockmanagement.repository.RetailCustomerCouponRepository;
import com.stockmanagement.repository.RetailCustomerRepository;
import com.stockmanagement.repository.RetailOfferRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Coupons that belong to one customer. The shop gives each customer their own code (tied to the mobile number).
 * A code works once and only when the bill is made for that mobile number. The ordinary "one code for everyone"
 * coupon of an offer is not touched by this service.
 */
@Service
public class RetailCustomerCouponService {

    /** most codes made in one go */
    public static final int MAX_PER_ISSUE = 1000;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0/O/1/I

    public record Issued(int created, int already, List<String> invalid) {}
    public record Row(String mobile, String name, String code, boolean used) {}

    private final RetailCustomerCouponRepository repo;
    private final RetailOfferRepository offerRepo;
    private final RetailCustomerRepository customerRepo;
    private final AuditLogService auditLogService;
    private final SecureRandom random = new SecureRandom();

    public RetailCustomerCouponService(RetailCustomerCouponRepository repo, RetailOfferRepository offerRepo,
                                       RetailCustomerRepository customerRepo, AuditLogService auditLogService) {
        this.repo = repo;
        this.offerRepo = offerRepo;
        this.customerRepo = customerRepo;
        this.auditLogService = auditLogService;
    }

    /** makes a code for every mobile number (pasted list and / or all customers); a customer who already has one is skipped */
    @Transactional
    public Issued issue(String company, Long userId, String userName, Long offerId, String mobilesText,
                        boolean allCustomers, Integer count, HttpServletRequest request) {
        RetailOffer offer = offerRepo.findByIdAndCompanyName(offerId, company)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        if (!RetailOfferService.isCustomerCoupon(offer)) {
            throw new IllegalArgumentException("This offer is not set up for coupon codes. Edit the offer and choose a one-time code option.");
        }
        if ("SINGLE".equals(offer.getCouponMode())) {
            // open one-time codes: not tied to any mobile number
            int n = count == null ? 0 : count;
            if (n < 1) throw new IllegalArgumentException("Enter how many codes you need.");
            if (n > MAX_PER_ISSUE) throw new IllegalArgumentException("Up to " + MAX_PER_ISSUE + " codes at a time.");
            String pre = offer.getCouponCode() == null || offer.getCouponCode().isBlank() ? "CPN" : offer.getCouponCode();
            for (int i = 0; i < n; i++) {
                RetailCustomerCoupon c = new RetailCustomerCoupon();
                c.setCompanyName(company);
                c.setOfferId(offerId);
                c.setCode(freshCode(company, pre));
                repo.save(c);
            }
            audit(userId, userName, "CREATE", "Made " + n + " one-time coupon code(s) for offer \"" + offer.getName() + "\"", request);
            return new Issued(n, 0, new ArrayList<>());
        }

        Set<String> mobiles = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        if (mobilesText != null) {
            for (String part : mobilesText.split("[\\s,;]+")) {
                if (part.isBlank()) continue;
                String m = cleanMobile(part);
                if (m == null) invalid.add(part.length() > 20 ? part.substring(0, 20) : part);
                else mobiles.add(m);
            }
        }
        Map<String, String> names = new HashMap<>();
        if (allCustomers) {
            for (RetailCustomer c : customerRepo.findByCompanyNameOrderByIdAsc(company)) {
                mobiles.add(c.getMobile());
                names.put(c.getMobile(), c.getName());
            }
        }
        if (mobiles.isEmpty()) throw new IllegalArgumentException("Add at least one mobile number, or choose all customers.");
        if (mobiles.size() > MAX_PER_ISSUE) {
            throw new IllegalArgumentException("Up to " + MAX_PER_ISSUE + " customers at a time. You gave " + mobiles.size() + ".");
        }

        for (RetailCustomer c : customerRepo.findByCompanyNameAndMobileIn(company, mobiles)) names.put(c.getMobile(), c.getName());
        Set<String> have = new HashSet<>();
        for (RetailCustomerCoupon x : repo.findByCompanyNameAndOfferIdAndMobileIn(company, offerId, mobiles)) have.add(x.getMobile());

        String prefix = offer.getCouponCode() == null || offer.getCouponCode().isBlank() ? "CPN" : offer.getCouponCode();
        int created = 0, already = 0;
        for (String m : mobiles) {
            if (have.contains(m)) { already++; continue; }
            RetailCustomerCoupon c = new RetailCustomerCoupon();
            c.setCompanyName(company);
            c.setOfferId(offerId);
            c.setMobile(m);
            c.setCustomerName(names.get(m));
            c.setCode(freshCode(company, prefix));
            repo.save(c);
            created++;
        }
        audit(userId, userName, "CREATE", "Made " + created + " customer coupon(s) for offer \"" + offer.getName() + "\" ("
                + already + " customer(s) already had one)", request);
        return new Issued(created, already, invalid);
    }

    @Transactional(readOnly = true)
    public List<Row> list(String company, Long offerId) {
        offerRepo.findByIdAndCompanyName(offerId, company).orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        List<Row> out = new ArrayList<>();
        for (RetailCustomerCoupon c : repo.findByCompanyNameAndOfferIdOrderByIdAsc(company, offerId)) {
            out.add(new Row(c.getMobile() == null ? "" : c.getMobile(), c.getCustomerName() == null ? "" : c.getCustomerName(), c.getCode(), c.getUsedBillId() != null));
        }
        return out;
    }

    /** the same list as a CSV file (opens in Excel) */
    @Transactional(readOnly = true)
    public String csv(String company, Long offerId) {
        RetailOffer offer = offerRepo.findByIdAndCompanyName(offerId, company)
                .orElseThrow(() -> new IllegalArgumentException("Offer not found."));
        StringBuilder sb = new StringBuilder("Mobile,Customer name,Coupon code,Status,Offer\r\n");
        for (Row r : list(company, offerId)) {
            sb.append(r.mobile().isEmpty() ? "" : r.mobile()).append(',').append(q(r.name())).append(',').append(r.code()).append(',')
              .append(r.used() ? "Used" : "Not used").append(',').append(q(offer.getName())).append("\r\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ helpers

    private String freshCode(String company, String prefix) {
        for (int tries = 0; tries < 50; tries++) {
            StringBuilder sb = new StringBuilder(prefix).append('-');
            for (int i = 0; i < 5; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            String code = sb.toString();
            if (repo.findByCompanyNameAndCode(company, code).isEmpty()) return code;
        }
        throw new IllegalStateException("Could not make a unique coupon code. Please try again.");
    }

    /** 10 digit mobile, or null when it is not one */
    static String cleanMobile(String raw) {
        String m = raw == null ? "" : raw.replaceAll("[^0-9]", "");
        if (m.length() == 12 && m.startsWith("91")) m = m.substring(2);
        if (m.length() == 11 && m.startsWith("0")) m = m.substring(1);
        return m.length() == 10 ? m : null;
    }

    private static String q(String s) {
        String v = s == null ? "" : s.replace("\"", "\"\"");
        return "\"" + v + "\"";
    }

    private void audit(Long userId, String userName, String action, String text, HttpServletRequest request) {
        try {
            auditLogService.log(userId, userName, action, "Retail", "Offers & Discounts", text, request);
        } catch (RuntimeException ignored) {
            // audit failure must not block coupon work
        }
    }
}
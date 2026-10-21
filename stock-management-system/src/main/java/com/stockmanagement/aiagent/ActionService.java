package com.stockmanagement.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockmanagement.entity.ProductCategory;
import com.stockmanagement.entity.StockInward;
import com.stockmanagement.entity.Vendor;
import com.stockmanagement.repository.ProductCategoryRepository;
import com.stockmanagement.repository.StockInwardRepository;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.StockCustomerService;
import com.stockmanagement.service.StockInwardService;
import com.stockmanagement.service.StockOutwardService;
import com.stockmanagement.service.StockOutwardService.ItemInput;
import com.stockmanagement.service.UserPermissionService;
import com.stockmanagement.service.VendorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI se ADD / EDIT. DELETE ka koi raasta nahi hai.
 *
 * Flow: AI sirf "propose" JSON deta hai -> server permission + data check karta hai -> chat me Confirm card ->
 * user "Confirm" dabata hai -> wahi purani services (jo screen use karti hai) chalti hain, logged-in user ke naam se
 * (same permission, same approval chain, same validation) -> audit log "(via AI)".
 * Token: ek baar ka, sirf usi user ka, 10 minute me expire.
 *
 * MODULE MAP (har module ka code "[NAME] ... START/END" ke beech hai; naya module bhi isi tarah alag section me judega):
 *   [VENDOR]  -> OPS lines, propose block, execute block, helpers block, title lines
 *   [STOCK_INWARD], [STOCK_OUTWARD] -> abhi purane tareeke me; agle step me inke bhi section banenge
 */
@Service
public class ActionService {

    private static final Logger log = LoggerFactory.getLogger(ActionService.class);
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_ROWS = 50;
    private static final int MAX_PENDING_PER_USER = 10;
    private static final SecureRandom RND = new SecureRandom();

    /** operation -> permission jo user ke paas honi chahiye. Delete yahan kabhi nahi aayega. */
    public static final Map<String, String> OPS = new LinkedHashMap<>();
    static {
        OPS.put("inward_add", "STOCK_INWARD_ADD");
        OPS.put("inward_edit", "STOCK_INWARD_EDIT");
        OPS.put("vendor_add", "VENDOR_ADD");      // [VENDOR]
        OPS.put("vendor_edit", "VENDOR_EDIT");    // [VENDOR]
        OPS.put("outward_create", "STOCK_OUT_REQUEST");
    }

    public static class ActionException extends RuntimeException {
        public ActionException(String m) { super(m); }
    }

    /** Chat me dikhne wala Confirm card. */
    public record ProposalView(String token, String op, String title, List<String> headers,
                               List<List<String>> rows, int count, List<String> notes) {}

    public record ConfirmResult(boolean ok, String message, List<String> lines) {}

    private record Op(String type, Map<String, Object> d) {}

    private record Pending(Long userId, String type, List<Op> ops, long expires) {}

    private final boolean enabled;
    private final UserPermissionService perms;
    private final VendorService vendorService;
    private final StockInwardService inwardService;
    private final StockOutwardService outwardService;
    private final StockInwardRepository inwardRepo;
    private final ProductCategoryRepository categoryRepo;
    private final AuditLogService audit;
    private final Map<String, Pending> store = new ConcurrentHashMap<>();

    public ActionService(@Value("${ai.agent.actions:true}") boolean enabled,
                         UserPermissionService perms, VendorService vendorService, StockInwardService inwardService,
                         StockOutwardService outwardService, StockInwardRepository inwardRepo,
                         ProductCategoryRepository categoryRepo, AuditLogService audit) {
        this.enabled = enabled;
        this.perms = perms;
        this.vendorService = vendorService;
        this.inwardService = inwardService;
        this.outwardService = outwardService;
        this.inwardRepo = inwardRepo;
        this.categoryRepo = categoryRepo;
        this.audit = audit;
    }

    /** Is user ko kaun-kaun se add/edit allowed hain (global switch band ho to khali). */
    public List<String> allowedOps(AgentUser u) {
        List<String> out = new ArrayList<>();
        if (!enabled) return out;
        for (Map.Entry<String, String> e : OPS.entrySet()) {
            if (u.has("SUPER_ADMIN") || u.has(e.getValue())) out.add(e.getKey());
        }
        return out;
    }

    /* ======================= PROPOSE ======================= */

    public ProposalView propose(AgentUser u, JsonNode msg) {
        String type = msg.path("op").asText("").trim();
        if (!OPS.containsKey(type)) throw new ActionException("Unknown operation. Allowed: " + allowedOps(u));
        if (!allowedOps(u).contains(type)) throw new ActionException("The user does not have permission for this operation.");
        JsonNode rows = msg.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) throw new ActionException("rows[] is required.");
        if (rows.size() > MAX_ROWS) throw new ActionException("At most " + MAX_ROWS + " rows per proposal. Split it.");

        purge();
        long mine = store.values().stream().filter(p -> p.userId().equals(u.userId())).count();
        if (mine >= MAX_PENDING_PER_USER) throw new ActionException("Too many unconfirmed proposals. Ask the user to confirm or cancel the earlier ones.");

        List<Op> ops = new ArrayList<>();
        List<List<String>> table = new ArrayList<>();
        List<String> headers;
        List<String> notes = new ArrayList<>();
        int n = 0;
        Set<String> seen = new HashSet<>();
        switch (type) {
            // ============ [VENDOR] propose START ============
            // ----- [VENDOR] ADD (propose) START: zaruri fields (GST chhod kar), phone/email/GST format, duplicate check, card -----
            case "vendor_add" -> {
                headers = List.of("#", "Vendor", "Contact", "Phone", "Email", "GST");
                Set<String> existing = new HashSet<>();
                for (Vendor v : vendorService.getVendorsForCompany(u.company())) existing.add(low(v.getVendorName()));
                for (JsonNode r : rows) {
                    n++;
                    String name = req(r, "name", n);
                    if (!seen.add(low(name))) throw new ActionException("Row " + n + ": vendor \"" + name + "\" is listed twice.");
                    if (existing.contains(low(name))) throw new ActionException("Row " + n + ": vendor \"" + name + "\" is already in the vendor list.");
                    // GST ke alawa sab zaruri (screen jaisa hi rule)
                    String phone = req(r, "phone", n);
                    String email = req(r, "email", n);
                    int digits = phone.replaceAll("\\D", "").length();
                    if (digits < 7 || digits > 15)
                        throw new ActionException("Row " + n + ": phone number looks invalid. Ask the user for the correct phone number.");
                    if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                        throw new ActionException("Row " + n + ": email looks invalid. Ask the user for the correct email.");
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("name", name);
                    d.put("contact", req(r, "contact_person", n));
                    d.put("phone", phone);
                    d.put("email", email);
                    d.put("address", req(r, "address", n));
                    d.put("gst", gst(r, n));   // optional
                    ops.add(new Op(type, d));
                    table.add(List.of(String.valueOf(n), name, nz(d.get("contact")), nz(d.get("phone")), nz(d.get("email")), nz(d.get("gst"))));
                }
            }
            // ----- [VENDOR] ADD (propose) END -----
            // ----- [VENDOR] EDIT (propose) START: vendor dhundho (FIND), nayi values check, owner check, khali details ka note -----
            case "vendor_edit" -> {
                headers = List.of("#", "Vendor", "Change");
                for (JsonNode r : rows) {
                    n++;
                    // Vendor ko name, GST, phone ya email me se kisi se bhi dhundh sakte hain ("find"); purana "name" bhi chalta hai
                    Vendor v = findVendorBy(u.company(), r, n);
                    if (!u.has("SUPER_ADMIN") && !u.company().equals(v.getAddedByCompany()))
                        throw new ActionException("Row " + n + ": vendor \"" + v.getVendorName() + "\" was added by another company, so it cannot be edited here.");
                    if (!seen.add(String.valueOf(v.getId()))) throw new ActionException("Row " + n + ": vendor \"" + v.getVendorName() + "\" is listed twice.");
                    String name = v.getVendorName();
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", v.getId());
                    String newPhone = s(r, "phone");
                    if (newPhone != null) {
                        int pd = newPhone.replaceAll("\\D", "").length();
                        if (pd < 7 || pd > 15) throw new ActionException("Row " + n + ": the new phone number looks invalid. Ask the user for the correct phone number.");
                    }
                    String newEmail = s(r, "email");
                    if (newEmail != null && !newEmail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))
                        throw new ActionException("Row " + n + ": the new email looks invalid. Ask the user for the correct email.");
                    List<String> diff = new ArrayList<>();
                    put(d, diff, "new_name", s(r, "new_name"), v.getVendorName(), "Name");
                    put(d, diff, "contact", s(r, "contact_person"), v.getContactPerson(), "Contact");
                    put(d, diff, "phone", s(r, "phone"), v.getPhone(), "Phone");
                    put(d, diff, "email", s(r, "email"), v.getEmail(), "Email");
                    put(d, diff, "address", s(r, "address"), v.getAddress(), "Address");
                    put(d, diff, "gst", gst(r, n), v.getGstNumber(), "GST");
                    if (diff.isEmpty()) throw new ActionException("Row " + n + ": nothing to change for \"" + name + "\".");
                    // Edit ke baad bhi jo details is vendor me khali rahengi, unka note (professional: kya baaki hai wo dikhe)
                    List<String> gaps = new ArrayList<>();
                    if (isEmptyAfter(d, "contact", v.getContactPerson())) gaps.add("Contact person");
                    if (isEmptyAfter(d, "phone", v.getPhone())) gaps.add("Phone");
                    if (isEmptyAfter(d, "email", v.getEmail())) gaps.add("Email");
                    if (isEmptyAfter(d, "address", v.getAddress())) gaps.add("Address");
                    if (isEmptyAfter(d, "gst", v.getGstNumber())) gaps.add("GST (optional)");
                    if (!gaps.isEmpty()) notes.add("\"" + v.getVendorName() + "\" still has no: " + String.join(", ", gaps) + ".");
                    ops.add(new Op(type, d));
                    table.add(List.of(String.valueOf(n), v.getVendorName(), String.join("; ", diff)));
                }
            }
            // ----- [VENDOR] EDIT (propose) END -----
            // ============ [VENDOR] propose END ============
            case "inward_add" -> {
                headers = List.of("#", "Product", "Vendor", "IMEI", "Price", "Tax %", "Date", "Status");
                Map<String, Vendor> vendors = vendorMap(u.company());
                for (JsonNode r : rows) {
                    n++;
                    String imei = req(r, "imei", n);
                    if (!seen.add(imei)) throw new ActionException("Row " + n + ": IMEI " + imei + " is listed twice.");
                    if (inwardRepo.existsByImeiNumberAndCompanyName(imei, u.company()))
                        throw new ActionException("Row " + n + ": a unit with IMEI " + imei + " already exists.");
                    ProductCategory p = findProduct(u.company(), req(r, "product", n), s(r, "category"), n);
                    String vname = req(r, "vendor", n);
                    Vendor v = vendors.get(low(vname));
                    if (v == null) throw new ActionException("Row " + n + ": vendor \"" + vname + "\" is not in the vendor list (add the vendor first).");
                    BigDecimal price = dec(r, "unit_price", n, true);
                    Map<String, Object> d = unitFields(r, n);
                    d.put("imei", imei);
                    d.put("productId", p.getId());
                    d.put("vendorId", v.getId());
                    d.put("unit_price", price);
                    String status = up(s(r, "status"));
                    d.put("status", status == null ? "AVAILABLE" : status);
                    if (!Set.of("AVAILABLE", "DAMAGED").contains(d.get("status")))
                        throw new ActionException("Row " + n + ": a new unit can only be AVAILABLE or DAMAGED.");
                    ops.add(new Op(type, d));
                    table.add(List.of(String.valueOf(n), p.getProductName(), v.getVendorName(), imei, price.toPlainString(),
                            nz(d.get("tax_percent")), nz(d.get("purchase_date")), String.valueOf(d.get("status"))));
                }
            }
            case "inward_edit" -> {
                headers = List.of("#", "IMEI", "Product", "Change");
                Map<String, Vendor> vendors = null;
                for (JsonNode r : rows) {
                    n++;
                    String imei = req(r, "imei", n);
                    if (!seen.add(imei)) throw new ActionException("Row " + n + ": IMEI " + imei + " is listed twice.");
                    StockInward cur = inwardRepo.findByImeiNumberAndCompanyName(imei, u.company()).orElse(null);
                    if (cur == null) throw new ActionException("Row " + n + ": no unit with IMEI " + imei + " in this company.");
                    String st = cur.getStatus();
                    if (Set.of("ISSUED", "WITH_STAFF", "AT_VENDOR", "REPLACED").contains(st))
                        throw new ActionException("Row " + n + ": IMEI " + imei + " is " + st + " and can no longer be edited.");
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("id", cur.getId());
                    List<String> diff = new ArrayList<>();
                    if (has(r, "new_imei")) {
                        String ni = s(r, "new_imei");
                        if (ni != null && !ni.equals(cur.getImeiNumber())) {
                            if (inwardRepo.existsByImeiNumberAndCompanyName(ni, u.company()))
                                throw new ActionException("Row " + n + ": IMEI " + ni + " already exists.");
                            d.put("imei", ni);
                            diff.add("IMEI " + cur.getImeiNumber() + " → " + ni);
                        }
                    }
                    if (has(r, "product")) {
                        ProductCategory p = findProduct(u.company(), s(r, "product"), s(r, "category"), n);
                        if (!p.getId().equals(cur.getProductId())) {
                            d.put("productId", p.getId());
                            diff.add("Product → " + p.getProductName());
                        }
                    }
                    if (has(r, "vendor")) {
                        if (vendors == null) vendors = vendorMap(u.company());
                        Vendor v = vendors.get(low(s(r, "vendor")));
                        if (v == null) throw new ActionException("Row " + n + ": vendor \"" + s(r, "vendor") + "\" is not in the vendor list.");
                        if (!v.getId().equals(cur.getVendorId())) {
                            d.put("vendorId", v.getId());
                            diff.add("Vendor → " + v.getVendorName());
                        }
                    }
                    if (has(r, "unit_price")) {
                        BigDecimal p = dec(r, "unit_price", n, true);
                        if (cur.getUnitPrice() == null || cur.getUnitPrice().compareTo(p) != 0) {
                            d.put("unit_price", p);
                            diff.add("Price " + nz(cur.getUnitPrice()) + " → " + p.toPlainString());
                        }
                    }
                    Map<String, Object> f = unitFields(r, n);
                    for (Map.Entry<String, Object> e : f.entrySet()) {
                        d.put(e.getKey(), e.getValue());
                        diff.add(e.getKey().replace('_', ' ') + " → " + e.getValue());
                    }
                    if (has(r, "status")) {
                        String ns = up(s(r, "status"));
                        if (ns != null && !ns.equals(st)) {
                            if (!Set.of("AVAILABLE", "DAMAGED").contains(ns))
                                throw new ActionException("Row " + n + ": status can only be changed to AVAILABLE or DAMAGED.");
                            d.put("status", ns);
                            diff.add("Status " + st + " → " + ns);
                        }
                    }
                    if (diff.isEmpty()) throw new ActionException("Row " + n + ": nothing to change for IMEI " + imei + ".");
                    ops.add(new Op(type, d));
                    table.add(List.of(String.valueOf(n), cur.getImeiNumber(), productName(cur.getProductId()), String.join("; ", diff)));
                }
            }
            case "outward_create" -> {
                headers = List.of("#", "Customer", "Items", "Value");
                for (JsonNode r : rows) {
                    n++;
                    JsonNode c = r.path("customer");
                    String cname = req(c, "name", n);
                    String mobile = req(c, "mobile", n);
                    JsonNode items = r.path("items");
                    if (!items.isArray() || items.isEmpty()) throw new ActionException("Row " + n + ": items[] is required.");
                    Map<String, Object> d = new LinkedHashMap<>();
                    d.put("c_name", cname);
                    d.put("c_mobile", mobile);
                    d.put("c_company", s(c, "company"));
                    d.put("c_gst", s(c, "gst"));
                    d.put("c_email", s(c, "email"));
                    d.put("c_address", s(c, "address"));
                    d.put("c_city", s(c, "city"));
                    d.put("c_state", s(c, "state"));
                    d.put("c_pincode", s(c, "pincode"));
                    d.put("deal", s(r, "deal_name"));
                    d.put("remarks", s(r, "remarks"));
                    List<Object[]> its = new ArrayList<>();
                    BigDecimal total = BigDecimal.ZERO;
                    List<String> desc = new ArrayList<>();
                    for (JsonNode it : items) {
                        ProductCategory p = findProduct(u.company(), req(it, "product", n), s(it, "category"), n);
                        int qty = it.path("quantity").asInt(0);
                        if (qty < 1 || qty > 10000) throw new ActionException("Row " + n + ": quantity must be 1 to 10000.");
                        BigDecimal price = dec(it, "unit_price", n, true);
                        its.add(new Object[]{p.getId(), qty, price});
                        total = total.add(price.multiply(BigDecimal.valueOf(qty)));
                        desc.add(p.getProductName() + " × " + qty + " @ " + price.toPlainString());
                    }
                    d.put("items", its);
                    ops.add(new Op(type, d));
                    table.add(List.of(String.valueOf(n), cname + " (" + mobile + ")", String.join("; ", desc), total.toPlainString()));
                }
                notes.add("This only raises a request. It still goes through your normal approval chain; nothing is issued yet.");
            }
            default -> throw new ActionException("Unknown operation.");
        }

        String token = newToken();
        store.put(token, new Pending(u.userId(), type, ops, System.currentTimeMillis() + TTL_MS));
        String title = switch (type) {
            case "inward_add" -> "Add " + ops.size() + " stock unit(s)";
            case "inward_edit" -> "Edit " + ops.size() + " stock unit(s)";
            case "vendor_add" -> "Add " + ops.size() + " vendor(s)";    // [VENDOR]
            case "vendor_edit" -> "Edit " + ops.size() + " vendor(s)";  // [VENDOR]
            default -> "Raise " + ops.size() + " stock-out request(s)";
        };
        return new ProposalView(token, type, title, headers, table, ops.size(), notes);
    }

    /* ======================= CONFIRM / CANCEL ======================= */

    public ConfirmResult confirm(CustomUserDetails me, String token, String ip) {
        purge();
        Pending p = token == null ? null : store.remove(token);   // ek baar ka
        if (p == null) return new ConfirmResult(false, "This confirmation has expired or was already used. Please ask again.", List.of());
        if (!p.userId().equals(me.getUserId())) return new ConfirmResult(false, "This confirmation belongs to another user.", List.of());
        if (!enabled) return new ConfirmResult(false, "AI actions are switched off.", List.of());
        Set<String> codes = new HashSet<>();
        perms.getAllowedPermissionDetails(me.getUserId()).forEach(x -> codes.add(x.getPermissionCode()));
        String need = OPS.get(p.type());
        if (need == null || !(codes.contains(need) || codes.contains("SUPER_ADMIN")))
            return new ConfirmResult(false, "You no longer have permission for this action.", List.of());

        String company = me.getUser().getCompanyName();
        boolean superAdmin = codes.contains("SUPER_ADMIN");
        String actor = me.getUsername() + " (via AI)";   // audit, created_by aur history me yahi naam dikhega
        List<String> lines = new ArrayList<>();
        int ok = 0, fail = 0, i = 0;
        for (Op op : p.ops()) {
            i++;
            try {
                lines.add("Row " + i + ": " + execute(op, me, company, actor, superAdmin, ip));
                ok++;
            } catch (RuntimeException e) {
                fail++;
                lines.add("Row " + i + ": NOT done - " + friendly(e));
            }
        }
        try {
            audit.log(me.getUserId(), actor, "AI_ACTION", "AI", "Assistant",
                    p.type() + ": " + ok + " done, " + fail + " failed", ip);
        } catch (RuntimeException e) {
            log.warn("AI action audit failed: {}", e.getMessage());
        }
        String msg = fail == 0 ? "Done. " + ok + " item(s) saved." : ok + " saved, " + fail + " could not be saved.";
        return new ConfirmResult(fail == 0, msg, lines);
    }

    public void cancel(Long userId, String token) {
        Pending p = token == null ? null : store.get(token);
        if (p != null && p.userId().equals(userId)) store.remove(token);
    }

    @SuppressWarnings("unchecked")
    private String execute(Op op, CustomUserDetails me, String company, String actor, boolean superAdmin, String ip) {
        Map<String, Object> d = op.d();
        switch (op.type()) {
            // ============ [VENDOR] execute START ============
            // ----- [VENDOR] ADD (execute) START: naya vendor VendorService se save, user "(via AI)" -----
            case "vendor_add" -> {
                Vendor v = new Vendor();
                v.setVendorName((String) d.get("name"));
                v.setContactPerson((String) d.get("contact"));
                v.setPhone((String) d.get("phone"));
                v.setEmail((String) d.get("email"));
                v.setAddress((String) d.get("address"));
                v.setGstNumber((String) d.get("gst"));
                VendorService.AddVendorResult r = vendorService.addVendorForCompany(v, company, actor);
                if (r.alreadyInYourList()) throw new IllegalArgumentException("already in your vendor list");
                return "vendor \"" + r.vendor().getVendorName() + "\" added";
            }
            // ----- [VENDOR] ADD (execute) END -----
            // ----- [VENDOR] EDIT (execute) START: purane vendor me sirf badli hui fields save (VendorService ke check se) -----
            case "vendor_edit" -> {
                Vendor cur = vendorService.getVendorById((Long) d.get("id"));
                Vendor in = new Vendor();
                in.setVendorName(d.containsKey("new_name") ? (String) d.get("new_name") : cur.getVendorName());
                in.setContactPerson(d.containsKey("contact") ? (String) d.get("contact") : cur.getContactPerson());
                in.setPhone(d.containsKey("phone") ? (String) d.get("phone") : cur.getPhone());
                in.setEmail(d.containsKey("email") ? (String) d.get("email") : cur.getEmail());
                in.setAddress(d.containsKey("address") ? (String) d.get("address") : cur.getAddress());
                in.setGstNumber(d.containsKey("gst") ? (String) d.get("gst") : cur.getGstNumber());
                vendorService.updateVendor(cur.getId(), in, company, superAdmin);
                return "vendor \"" + in.getVendorName() + "\" updated";
            }
            // ----- [VENDOR] EDIT (execute) END -----
            // ============ [VENDOR] execute END ============
            case "inward_add" -> {
                StockInward s = new StockInward();
                s.setVendorId((Long) d.get("vendorId"));
                s.setProductId((Long) d.get("productId"));
                s.setCategoryId((Long) d.get("productId"));
                s.setImeiNumber((String) d.get("imei"));
                s.setSerialNumber((String) d.get("serial"));
                s.setUnitPrice((BigDecimal) d.get("unit_price"));
                s.setStatus((String) d.get("status"));
                applyUnit(s, d);
                if (s.getCondition() == null) s.setCondition("NEW");
                inwardService.addStock(s, company, actor, superAdmin);
                return "IMEI " + s.getImeiNumber() + " added";
            }
            case "inward_edit" -> {
                StockInward cur = inwardService.getStockById((Long) d.get("id"));
                if (!superAdmin && !company.equals(cur.getCompanyName())) throw new IllegalArgumentException("unit not found");
                StockInward in = new StockInward();
                in.setVendorId(d.containsKey("vendorId") ? (Long) d.get("vendorId") : cur.getVendorId());
                in.setProductId(d.containsKey("productId") ? (Long) d.get("productId") : cur.getProductId());
                in.setCategoryId(d.containsKey("productId") ? (Long) d.get("productId") : cur.getCategoryId());
                in.setImeiNumber(d.containsKey("imei") ? (String) d.get("imei") : cur.getImeiNumber());
                in.setUnitPrice(d.containsKey("unit_price") ? (BigDecimal) d.get("unit_price") : cur.getUnitPrice());
                in.setStatus(d.containsKey("status") ? (String) d.get("status") : cur.getStatus());
                in.setCondition(cur.getCondition());
                in.setTaxPercent(cur.getTaxPercent());
                in.setPurchaseDate(cur.getPurchaseDate());
                in.setWarrantyPeriodMonths(cur.getWarrantyPeriodMonths());
                in.setWarrantyStartDate(cur.getWarrantyStartDate());
                in.setInvoiceNumber(cur.getInvoiceNumber());
                in.setPoNumber(cur.getPoNumber());
                in.setBatchNumber(cur.getBatchNumber());
                in.setWarehouse(cur.getWarehouse());
                in.setRemarks(cur.getRemarks());
                applyUnit(in, d);
                inwardService.updateStock(cur.getId(), in, actor, me);
                return "IMEI " + in.getImeiNumber() + " updated";
            }
            default -> {
                List<ItemInput> items = new ArrayList<>();
                for (Object o : (List<Object>) d.get("items")) {
                    Object[] a = (Object[]) o;
                    items.add(new ItemInput((Long) a[0], (Integer) a[1], (BigDecimal) a[2]));
                }
                StockCustomerService.CustomerInput c = new StockCustomerService.CustomerInput(null,
                        (String) d.get("c_name"), (String) d.get("c_mobile"), (String) d.get("c_company"),
                        (String) d.get("c_gst"), (String) d.get("c_email"), (String) d.get("c_address"),
                        (String) d.get("c_city"), (String) d.get("c_state"), (String) d.get("c_pincode"));
                var r = outwardService.create(me, ip, c, (String) d.get("deal"), (String) d.get("remarks"), items);
                return "request " + r.getRequestNo() + " raised (waiting for Level 1 approval)";
            }
        }
    }

    /* ======================= helpers ======================= */

    private static void applyUnit(StockInward s, Map<String, Object> d) {
        if (d.containsKey("serial")) s.setSerialNumber((String) d.get("serial"));
        if (d.containsKey("condition")) s.setCondition((String) d.get("condition"));
        if (d.containsKey("tax_percent")) s.setTaxPercent((BigDecimal) d.get("tax_percent"));
        if (d.containsKey("purchase_date")) s.setPurchaseDate(LocalDate.parse((String) d.get("purchase_date")));
        if (d.containsKey("warranty_months")) s.setWarrantyPeriodMonths((Integer) d.get("warranty_months"));
        if (d.containsKey("warranty_start")) s.setWarrantyStartDate(LocalDate.parse((String) d.get("warranty_start")));
        if (d.containsKey("invoice")) s.setInvoiceNumber((String) d.get("invoice"));
        if (d.containsKey("po")) s.setPoNumber((String) d.get("po"));
        if (d.containsKey("batch")) s.setBatchNumber((String) d.get("batch"));
        if (d.containsKey("warehouse")) s.setWarehouse((String) d.get("warehouse"));
        if (d.containsKey("remarks")) s.setRemarks((String) d.get("remarks"));
    }

    /** Optional unit fields jo row me diye gaye hon (sirf wahi jo diye). */
    private Map<String, Object> unitFields(JsonNode r, int n) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (has(r, "serial")) d.put("serial", s(r, "serial"));
        if (has(r, "condition")) d.put("condition", s(r, "condition"));
        if (has(r, "tax_percent")) d.put("tax_percent", dec(r, "tax_percent", n, false));
        if (has(r, "purchase_date")) d.put("purchase_date", date(r, "purchase_date", n));
        if (has(r, "warranty_months")) {
            int m = r.path("warranty_months").asInt(-1);
            if (m < 0 || m > 600) throw new ActionException("Row " + n + ": warranty_months must be 0 to 600.");
            d.put("warranty_months", m);
        }
        if (has(r, "warranty_start")) d.put("warranty_start", date(r, "warranty_start", n));
        if (has(r, "invoice")) d.put("invoice", s(r, "invoice"));
        if (has(r, "po")) d.put("po", s(r, "po"));
        if (has(r, "batch")) d.put("batch", s(r, "batch"));
        if (has(r, "warehouse")) d.put("warehouse", s(r, "warehouse"));
        if (has(r, "remarks")) d.put("remarks", s(r, "remarks"));
        return d;
    }

    // ===================== [VENDOR] helpers START =====================

    // ----- [VENDOR] FIND START: vendor ko name / contact person / GST / phone / email / address se dhundhna; ek se zyada mile to AI user se poochta hai -----
    /** Vendor dhundho: "find":{name|gst|phone|email} (jitne diye sab milne chahiye) ya purana "name". Ek hi vendor milna chahiye. */
    private Vendor findVendorBy(String company, JsonNode r, int n) {
        JsonNode f = r.has("find") && r.get("find").isObject() ? r.get("find") : null;
        String fName = s(f, "name"), fGst = s(f, "gst"), fPhone = s(f, "phone"), fEmail = s(f, "email");
        String fContact = s(f, "contact_person"), fAddr = s(f, "address");
        if (f == null && has(r, "name")) fName = s(r, "name");
        if (fName == null && fGst == null && fPhone == null && fEmail == null && fContact == null && fAddr == null)
            throw new ActionException("Row " + n + ": tell which vendor to edit (its name, contact person, GST, phone, email or address). Ask the user.");
        String gstKey = fGst == null ? null : fGst.replace(" ", "").toUpperCase();
        String phoneKey = fPhone == null ? null : fPhone.replaceAll("\\D", "");
        List<Vendor> hit = new ArrayList<>();
        for (Vendor v : vendorService.getVendorsForCompany(company)) {
            if (fName != null && !low(v.getVendorName()).equals(low(fName))) continue;
            if (gstKey != null && !gstKey.equals(v.getGstNumber() == null ? "" : v.getGstNumber().replace(" ", "").toUpperCase())) continue;
            if (phoneKey != null) {
                String vp = v.getPhone() == null ? "" : v.getPhone().replaceAll("\\D", "");
                boolean same = !vp.isEmpty() && !phoneKey.isEmpty() && (vp.equals(phoneKey) || (vp.length() >= 10 && phoneKey.length() >= 10 && vp.endsWith(phoneKey.substring(phoneKey.length() - 10))));
                if (!same) continue;
            }
            if (fEmail != null && !low(v.getEmail()).equals(low(fEmail))) continue;
            if (fContact != null && !low(v.getContactPerson()).contains(low(fContact))) continue;
            if (fAddr != null && !low(v.getAddress()).contains(low(fAddr))) continue;
            hit.add(v);
        }
        if (hit.isEmpty()) throw new ActionException("Row " + n + ": no vendor matching that detail was found in this company's vendor list.");
        if (hit.size() > 1) {
            List<String> names = new ArrayList<>();
            for (Vendor v : hit) names.add(v.getVendorName() + " (contact: " + nz(v.getContactPerson()) + ", phone: " + nz(v.getPhone()) + ")");
            throw new ActionException("Row " + n + ": " + hit.size() + " vendors match: " + names + ". Show the user this list and ask which one they mean (they can reply with the name or phone). Do not guess.");
        }
        return hit.get(0);
    }

    private Vendor findVendor(String company, String name) {
        for (Vendor v : vendorService.getVendorsForCompany(company)) {
            if (low(v.getVendorName()).equals(low(name))) return v;
        }
        return null;
    }
    // ----- [VENDOR] FIND END -----

    // ----- [VENDOR] LOOKUP START: company ke saare vendors naam se (stock inward bhi isi ko use karta hai) -----
    private Map<String, Vendor> vendorMap(String company) {
        Map<String, Vendor> m = new HashMap<>();
        for (Vendor v : vendorService.getVendorsForCompany(company)) m.put(low(v.getVendorName()), v);
        return m;
    }
    // ----- [VENDOR] LOOKUP END -----

    // ----- [VENDOR] EDIT-GAPS START: edit ke baad bhi kaun si details khali rahengi (card me note + AI user se poochta hai) -----
    /** Is edit ke baad bhi field khali rahegi? (nayi value di ho to bhari maani jaayegi) */
    private static boolean isEmptyAfter(Map<String, Object> d, String key, String current) {
        Object v = d.containsKey(key) ? d.get(key) : current;
        return v == null || String.valueOf(v).isBlank();
    }
    // ----- [VENDOR] EDIT-GAPS END -----

    // ----- [VENDOR] GST-CHECK START: GST optional, par diya ho to 15 letters/digits -----
    /** GST optional hai, par diya ho to 15 letters/digits ka hona chahiye. */
    private static String gst(JsonNode r, int n) {
        String g = s(r, "gst");
        if (g == null) return null;
        g = g.replace(" ", "").toUpperCase();
        if (!g.matches("^[A-Z0-9]{15}$"))
            throw new ActionException("Row " + n + ": GST number must be 15 letters/digits. Ask the user for the correct GST number.");
        return g;
    }
    // ----- [VENDOR] GST-CHECK END -----

    // ===================== [VENDOR] helpers END ======================

    private ProductCategory findProduct(String company, String product, String category, int n) {
        List<ProductCategory> hit = new ArrayList<>();
        for (ProductCategory p : categoryRepo.findByCompanyNameAndStatus(company, "ACTIVE")) {
            if (p.getProductName() != null && low(p.getProductName()).equals(low(product))
                    && (category == null || low(p.getCategoryName()).equals(low(category)))) hit.add(p);
        }
        if (hit.isEmpty()) throw new ActionException("Row " + n + ": product \"" + product + "\" was not found in this company's products.");
        Set<String> cats = new TreeSet<>();
        for (ProductCategory p : hit) cats.add(p.getCategoryName());
        if (cats.size() > 1) throw new ActionException("Row " + n + ": product \"" + product + "\" exists in several categories " + cats + "; give the category.");
        return hit.get(0);
    }

    private String productName(Long id) {
        if (id == null) return "";
        return categoryRepo.findById(id).map(ProductCategory::getProductName).orElse("");
    }

    private static void put(Map<String, Object> d, List<String> diff, String key, String val, String old, String label) {
        if (val == null) return;
        if (val.equals(old == null ? "" : old)) return;
        if (key.equals("new_name") && val.isBlank()) return;
        d.put(key, val);
        diff.add(label + ": " + (old == null || old.isBlank() ? "(empty)" : old) + " → " + val);
    }

    private static boolean has(JsonNode r, String f) {
        return r != null && r.has(f) && !r.get(f).isNull();
    }

    private static String s(JsonNode r, String f) {
        if (!has(r, f)) return null;
        String v = r.get(f).asText("").trim();
        if (v.length() > 300) throw new ActionException("Field " + f + " is too long.");
        if (isPlaceholder(v)) throw new ActionException("Field " + f + " has a placeholder value (\"" + v + "\"), not a real value. Ask the user for the real value.");
        return v.isEmpty() ? null : v;
    }

    private static String req(JsonNode r, String f, int n) {
        String v = s(r, f);
        if (v == null) throw new ActionException("Row " + n + ": \"" + f + "\" is required. Ask the user for it.");
        return v;
    }

    private static BigDecimal dec(JsonNode r, String f, int n, boolean required) {
        if (!has(r, f)) {
            if (required) throw new ActionException("Row " + n + ": \"" + f + "\" is required. Ask the user for it.");
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(r.get(f).asText("").replace(",", "").trim());
            if (v.signum() < 0 || v.compareTo(new BigDecimal("99999999.99")) > 0) throw new NumberFormatException();
            return v;
        } catch (NumberFormatException e) {
            throw new ActionException("Row " + n + ": \"" + f + "\" is not a valid amount.");
        }
    }

    private static String date(JsonNode r, String f, int n) {
        try {
            return LocalDate.parse(r.get(f).asText("").trim()).toString();
        } catch (RuntimeException e) {
            throw new ActionException("Row " + n + ": \"" + f + "\" must be a date like 2026-10-16.");
        }
    }

    /** AI kabhi "ADD_GST_OR_UPDATE" jaisi dummy value bhej de to use asli value nahi maanna. */
    private static boolean isPlaceholder(String v) {
        String t = v.trim();
        if (t.isEmpty()) return false;
        String u = t.toUpperCase();
        if (u.matches("^(N/?A|NONE|NULL|NIL|TBD|UNKNOWN|EMPTY|\\(EMPTY\\)|\\?+|-+)$")) return true;
        if (t.matches("^<.*>$") || t.matches("^\\[.*\\]$")) return true;
        if (u.contains("PLACEHOLDER")) return true;
        return t.equals(u) && t.matches("^(ADD|ENTER|YOUR|UPDATE|NEW|INSERT|PUT)_[A-Z0-9_]+$|^[A-Z0-9]+(_[A-Z0-9]+)*_OR_[A-Z0-9_]+$");
    }

    private static String low(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    private static String up(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String friendly(RuntimeException e) {
        String m = e.getMessage();
        if (e instanceof org.springframework.security.access.AccessDeniedException) return "not allowed for your account";
        return m == null || m.isBlank() ? "could not be saved" : (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private static String newToken() {
        byte[] b = new byte[24];
        RND.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void purge() {
        long now = System.currentTimeMillis();
        store.values().removeIf(p -> p.expires() < now);
    }
}
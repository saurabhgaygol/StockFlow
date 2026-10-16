package com.stockmanagement.aiagent;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * AI ko sirf yahi "menu" dikhta hai. Table/column ke asli naam AI ko kabhi nahi bheje jaate;
 * AI sirf business naam (logical) use karta hai. Yahan jo nahi hai, wo AI ke liye exist hi nahi karta.
 * Sensitive columns (password, mobile, email, address, GST) yahan jaanbujh kar nahi hain.
 */
@Component
public class DataCatalog {

    public enum Type { STRING, NUMBER, DATE, DATETIME, BOOL }

    /** expr = SQL expression (sirf yahi file me likha hota hai, AI ka text kabhi nahi). */
    public record Field(String name, String expr, Type type, String join, String help) {}

    public record Dataset(String name, String help, String from, String companyExpr,
                          Set<String> anyPermission, Map<String, String> joins,
                          Map<String, Field> fields, String scopeKind) {}

    private final Map<String, Dataset> datasets = new LinkedHashMap<>();

    public DataCatalog() {
        // ---------- Stock inward ----------
        Map<String, String> inJ = new LinkedHashMap<>();
        inJ.put("cat", "LEFT JOIN product_category cat ON cat.id = t.category_id");
        inJ.put("prod", "LEFT JOIN product_category prod ON prod.id = t.product_id");
        inJ.put("ven", "LEFT JOIN vendors ven ON ven.id = t.vendor_id");
        inJ.put("hold", "LEFT JOIN user_table hold ON hold.id = t.holder_user_id AND hold.company_name = t.company_name");
        add(new Dataset("inward_stock", "Har stock unit/lot ki purchase (inward) entry: kya, kis vendor se, kab, kitne ka, abhi status/kiske paas.",
                "stock_inward t", "t.company_name", Set.of("STOCK_INWARD_VIEW"), inJ, fields(
                f("product_name", "prod.product_name", Type.STRING, "prod", "Product ka naam"),
                f("category_name", "cat.category_name", Type.STRING, "cat", "Category"),
                f("vendor_name", "ven.vendor_name", Type.STRING, "ven", "Vendor"),
                f("serial_number", "t.serial_number", Type.STRING, null, ""),
                f("imei_number", "t.imei_number", Type.STRING, null, ""),
                f("condition", "t.item_condition", Type.STRING, null, "New/Used etc."),
                f("quantity", "t.quantity", Type.NUMBER, null, ""),
                f("unit_price", "t.unit_price", Type.NUMBER, null, ""),
                f("total_amount", "t.total_amount", Type.NUMBER, null, "tax se pehle"),
                f("tax_percent", "t.tax_percent", Type.NUMBER, null, ""),
                f("grand_total", "t.grand_total", Type.NUMBER, null, "tax ke saath"),
                f("purchase_date", "t.purchase_date", Type.DATE, null, "Kharidne ki date"),
                f("warranty_end_date", "t.warranty_end_date", Type.DATE, null, ""),
                f("invoice_number", "t.invoice_number", Type.STRING, null, ""),
                f("batch_number", "t.batch_number", Type.STRING, null, ""),
                f("warehouse", "t.warehouse", Type.STRING, null, ""),
                f("status", "t.status", Type.STRING, null, "Stock ki halat (available/issued/installed...)"),
                f("created_by", "t.created_by", Type.STRING, null, "Entry kisne ki"),
                f("created_at", "t.created_at", Type.DATETIME, null, ""),
                f("issued_at", "t.issued_at", Type.DATETIME, null, "Kab issue hua"),
                f("holder_name", "CONCAT_WS(' ', hold.first_name, hold.last_name)", Type.STRING, "hold", "Abhi kis employee ke paas"),
                f("holder_since", "t.holder_since", Type.DATETIME, null, ""),
                f("install_type", "t.install_type", Type.STRING, null, ""),
                f("install_customer", "t.install_customer", Type.STRING, null, ""),
                f("install_vehicle", "t.install_vehicle", Type.STRING, null, ""),
                f("install_at", "t.install_at", Type.DATETIME, null, "")), "NONE"));

        // ---------- Outward requests ----------
        add(new Dataset("outward_requests", "Stock outward (issue) requests: customer, deal, status, amount.",
                "stock_outward_request t", "t.company_name", Set.of("STOCK_OUT_VIEW"), Map.of(), fields(
                f("request_no", "t.request_no", Type.STRING, null, ""),
                f("requested_by_name", "t.requested_by_name", Type.STRING, null, "Request kisne ki"),
                f("customer_name", "t.customer_name", Type.STRING, null, ""),
                f("customer_company", "t.customer_company", Type.STRING, null, ""),
                f("customer_city", "t.customer_city", Type.STRING, null, ""),
                f("customer_state", "t.customer_state", Type.STRING, null, ""),
                f("deal_name", "t.deal_name", Type.STRING, null, ""),
                f("status", "t.status", Type.STRING, null, "PENDING/ISSUED/REJECTED/ON_HOLD..."),
                f("total_amount", "t.total_amount", Type.NUMBER, null, ""),
                f("current_level", "t.current_level", Type.NUMBER, null, ""),
                f("total_levels", "t.total_levels", Type.NUMBER, null, ""),
                f("completed_at", "t.completed_at", Type.DATETIME, null, ""),
                f("created_at", "t.created_at", Type.DATETIME, null, "")), "OUTWARD"));

        // ---------- Outward items ----------
        Map<String, String> oiJ = new LinkedHashMap<>();
        oiJ.put("r", "JOIN stock_outward_request r ON r.id = t.request_id");
        add(new Dataset("outward_items", "Outward requests ke andar ke items (kaunsa product, kitna, kis rate pe, kis customer ko).",
                "stock_outward_item t", "r.company_name", Set.of("STOCK_OUT_VIEW"), oiJ, fields(
                f("product_name", "t.product_name", Type.STRING, "r", ""),
                f("category_name", "t.category_name", Type.STRING, "r", ""),
                f("quantity", "t.quantity", Type.NUMBER, "r", ""),
                f("unit_price", "t.unit_price", Type.NUMBER, "r", ""),
                f("line_total", "t.quantity * t.unit_price", Type.NUMBER, "r", "quantity x unit_price"),
                f("request_no", "r.request_no", Type.STRING, "r", ""),
                f("customer_name", "r.customer_name", Type.STRING, "r", ""),
                f("requested_by_name", "r.requested_by_name", Type.STRING, "r", ""),
                f("status", "r.status", Type.STRING, "r", "request ka status"),
                f("created_at", "r.created_at", Type.DATETIME, "r", "request ki date"),
                f("completed_at", "r.completed_at", Type.DATETIME, "r", "issue hone ki date")), "OUTWARD_ITEM"));

        // ---------- Staff movements ----------
        add(new Dataset("staff_movements", "Employees ke beech / customer ko stock ki movement (kisne kisko kitna stock diya).",
                "staff_stock_movement t", "t.company_name", Set.of("STAFF_STOCK_VIEW", "STAFF_ISSUE"), Map.of(), fields(
                f("batch_no", "t.batch_no", Type.STRING, null, ""),
                f("imei_number", "t.imei_number", Type.STRING, null, ""),
                f("product_name", "t.product_name", Type.STRING, null, ""),
                f("movement_type", "t.movement_type", Type.STRING, null, ""),
                f("from_name", "t.from_name", Type.STRING, null, "Kisne diya"),
                f("to_name", "t.to_name", Type.STRING, null, "Kisko mila (employee)"),
                f("customer_name", "t.customer_name", Type.STRING, null, ""),
                f("vehicle_no", "t.vehicle_no", Type.STRING, null, ""),
                f("ref_no", "t.ref_no", Type.STRING, null, ""),
                f("created_by", "t.created_by", Type.STRING, null, ""),
                f("created_at", "t.created_at", Type.DATETIME, null, "")), "STAFF"));

        // ---------- Returns ----------
        add(new Dataset("stock_returns", "Customer returns / warranty cases / vendor repair-replace tracking.",
                "stock_return t", "t.company_name", Set.of("STOCK_RETURN"), Map.of(), fields(
                f("imei_number", "t.imei_number", Type.STRING, null, ""),
                f("product_name", "t.product_name", Type.STRING, null, ""),
                f("request_no", "t.request_no", Type.STRING, null, ""),
                f("customer_name", "t.customer_name", Type.STRING, null, ""),
                f("sold_at", "t.sold_at", Type.DATETIME, null, ""),
                f("sold_price", "t.sold_price", Type.NUMBER, null, ""),
                f("return_date", "t.return_date", Type.DATE, null, ""),
                f("reason", "t.reason", Type.STRING, null, ""),
                f("condition_after", "t.condition_after", Type.STRING, null, ""),
                f("status_after", "t.status_after", Type.STRING, null, ""),
                f("in_warranty", "t.in_warranty", Type.BOOL, null, ""),
                f("case_no", "t.case_no", Type.STRING, null, ""),
                f("case_status", "t.case_status", Type.STRING, null, ""),
                f("resolution", "t.resolution", Type.STRING, null, ""),
                f("vendor_name", "t.vendor_name", Type.STRING, null, ""),
                f("vendor_issue", "t.vendor_issue", Type.STRING, null, ""),
                f("sent_date", "t.sent_date", Type.DATE, null, ""),
                f("expected_back_date", "t.expected_back_date", Type.DATE, null, ""),
                f("vendor_received_date", "t.vendor_received_date", Type.DATE, null, ""),
                f("vendor_result", "t.vendor_result", Type.STRING, null, ""),
                f("repair_cost", "t.repair_cost", Type.NUMBER, null, ""),
                f("created_by", "t.created_by", Type.STRING, null, ""),
                f("created_at", "t.created_at", Type.DATETIME, null, ""),
                f("closed_at", "t.closed_at", Type.DATETIME, null, "")), "NONE"));

        // ---------- Unit history ----------
        add(new Dataset("unit_history", "Har device/unit ki life history (kab kya hua, kisne kiya).",
                "stock_unit_history t", "t.company_name", Set.of("DEVICE_HISTORY_VIEW"), Map.of(), fields(
                f("imei_number", "t.imei_number", Type.STRING, null, ""),
                f("product_name", "t.product_name", Type.STRING, null, ""),
                f("event_type", "t.event_type", Type.STRING, null, ""),
                f("from_status", "t.from_status", Type.STRING, null, ""),
                f("to_status", "t.to_status", Type.STRING, null, ""),
                f("request_no", "t.request_no", Type.STRING, null, ""),
                f("customer_name", "t.customer_name", Type.STRING, null, ""),
                f("amount", "t.amount", Type.NUMBER, null, ""),
                f("reason", "t.reason", Type.STRING, null, ""),
                f("actor_name", "t.actor_name", Type.STRING, null, "Kisne kiya"),
                f("created_at", "t.created_at", Type.DATETIME, null, "")), "NONE"));

        // ---------- Vendors ----------
        add(new Dataset("vendors", "Vendors ki list (naam, contact person, status). Phone/email/GST AI ko nahi milta.",
                "vendors t", "t.added_by_company", Set.of("VENDOR_VIEW"), Map.of(), fields(
                f("vendor_name", "t.vendor_name", Type.STRING, null, ""),
                f("contact_person", "t.contact_person", Type.STRING, null, ""),
                f("status", "t.status", Type.STRING, null, ""),
                f("created_at", "t.created_at", Type.DATETIME, null, "")), "NONE"));

        // ---------- Employees ----------
        add(new Dataset("employees", "Employees ki list (naam, department, city, status). Password/mobile/email AI ko nahi milta.",
                "user_table t", "t.company_name", Set.of("USER_VIEW"), Map.of(), fields(
                f("full_name", "CONCAT_WS(' ', t.first_name, t.last_name)", Type.STRING, null, ""),
                f("employee_id", "t.employee_id", Type.STRING, null, ""),
                f("department", "t.department", Type.STRING, null, ""),
                f("city", "t.city", Type.STRING, null, ""),
                f("field_staff", "t.field_staff", Type.BOOL, null, ""),
                f("status", "t.status", Type.STRING, null, ""),
                f("created_at", "t.created_at", Type.DATETIME, null, "")), "NONE"));

        // ---------- Audit logs (login/logout aur baaki activity) ----------
        Map<String, String> alJ = new LinkedHashMap<>();
        alJ.put("u", "JOIN user_table u ON u.id = t.user_id");
        add(new Dataset("audit_logs", "Audit trail: kaun user kab login/logout hua aur kisne kaunsa kaam kiya (action, module, description, IP).",
                "audit_logs t", "u.company_name", Set.of("AUDIT_LOG_VIEW"), alJ, fields(
                f("user_name", "t.user_name", Type.STRING, null, "User ka naam (AI se hue kaam 'via AI' ke saath)"),
                f("action", "t.action", Type.STRING, null, "LOGIN, LOGOUT, CREATE, UPDATE, DELETE, APPROVE ... jaisi activity"),
                f("module", "t.module", Type.STRING, null, "Software ka hissa (jaise Stock Inward)"),
                f("sub_module", "t.sub_module", Type.STRING, null, ""),
                f("description", "t.description", Type.STRING, null, "Kya hua, chhota vivaran"),
                f("ip_address", "t.ip_address", Type.STRING, null, ""),
                f("created_at", "t.created_at", Type.DATETIME, null, "Activity ka samay (din-wise ke liye created_at:day)")), "NONE"));
    }

    private void add(Dataset d) { datasets.put(d.name(), d); }

    private static Field f(String name, String expr, Type type, String join, String help) {
        return new Field(name, expr, type, join, help);
    }

    private static Map<String, Field> fields(Field... fs) {
        Map<String, Field> m = new LinkedHashMap<>();
        for (Field x : fs) m.put(x.name(), x);
        return Collections.unmodifiableMap(m);
    }

    public Dataset get(String name) { return name == null ? null : datasets.get(name); }

    /** Sirf wahi datasets jinki permission user ke paas hai. */
    public List<Dataset> allowedFor(AgentUser u) {
        List<Dataset> out = new ArrayList<>();
        for (Dataset d : datasets.values()) if (u.hasAny(d.anyPermission()) || u.has("SUPER_ADMIN")) out.add(d);
        return out;
    }

    public boolean isAllowed(AgentUser u, Dataset d) {
        return d != null && (u.has("SUPER_ADMIN") || u.hasAny(d.anyPermission()));
    }

    /** Topics jinki permission user ke paas nahi hai (sirf naam + chhota label, koi data/field nahi). */
    public String lockedFor(AgentUser u) {
        StringBuilder sb = new StringBuilder();
        for (Dataset d : datasets.values()) {
            if (u.has("SUPER_ADMIN") || u.hasAny(d.anyPermission())) continue;
            String h = d.help() == null ? "" : d.help().replaceAll("\\s+", " ").trim();
            if (h.length() > 60) h = h.substring(0, 60);
            sb.append("- ").append(d.name()).append(h.isEmpty() ? "" : " (" + h + ")").append('\n');
        }
        return sb.toString();
    }

    /** AI ke system prompt ke liye chhota menu (token bachane ke liye compact). */
    public String menuFor(AgentUser u) {
        StringBuilder sb = new StringBuilder();
        for (Dataset d : allowedFor(u)) {
            sb.append("- ").append(d.name()).append(": ").append(d.help()).append("\n  fields: ");
            boolean first = true;
            for (Field f : d.fields().values()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(f.name()).append('(').append(switch (f.type()) {
                    case STRING -> "text"; case NUMBER -> "num"; case DATE -> "date";
                    case DATETIME -> "datetime"; case BOOL -> "bool"; }).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
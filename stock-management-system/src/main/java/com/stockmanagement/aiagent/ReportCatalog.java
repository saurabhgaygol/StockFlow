package com.stockmanagement.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verified (pre-tested) reports. Pakke hisaab wale sawalon (last N issued, month-wise, customer-wise...)
 * me AI sirf naam + date/limit deta hai; query ka asli dhancha yahan fixed hai, isliye hisaab har baar sahi.
 * Ye bhi wahi QueryEngine se chalte hain: company, permission, row-scope sab waise hi forced.
 * Jo sawal inme na aaye, AI free "query" use karta hai.
 */
@Component
public class ReportCatalog {

    private final ObjectMapper mapper;
    private final Map<String, String> help = new LinkedHashMap<>();

    public ReportCatalog(ObjectMapper mapper) {
        this.mapper = mapper;
        help.put("recent_issued_items", "Last N stock items actually issued (outward ISSUED) with date, product, qty, customer, request no. params: limit(default 10), from, to");
        help.put("issued_by_month", "Month-wise issued (used) stock: units, value, requests. params: from, to");
        help.put("issued_by_customer", "Customer-wise issued stock units & value (top first). params: from, to, limit(default 20)");
        help.put("issued_by_product", "Product-wise issued stock units & value (top first). params: from, to, limit(default 20)");
        help.put("inward_by_month", "Month-wise stock inward (purchase): units and value. params: from, to");
        help.put("inward_by_vendor", "Vendor-wise stock inward units and value (top first). params: from, to, limit(default 20)");
        help.put("stock_by_status", "Current stock count by status (AVAILABLE, ISSUED, WITH_STAFF, DAMAGED...). params: none");
        help.put("stock_by_product_status", "Product-wise stock count by status. params: none");
        help.put("staff_movement_by_employee", "Employee-wise stock movement count by type. params: from, to");
    }

    public String menu() {
        StringBuilder sb = new StringBuilder();
        help.forEach((k, v) -> sb.append("- ").append(k).append(": ").append(v).append('\n'));
        return sb.toString();
    }

    /** null = unknown report. Dates YYYY-MM-DD; galat format QueryEngine reject karega. */
    public ObjectNode build(String name, JsonNode p) {
        if (name == null || !help.containsKey(name)) return null;
        String from = str(p, "from"), to = str(p, "to");
        int limit = lim(p, name.equals("recent_issued_items") ? 10 : 20);
        ObjectNode q = mapper.createObjectNode();
        ArrayNode filters = q.putArray("filters");
        switch (name) {
            case "recent_issued_items" -> {
                q.put("dataset", "outward_items");
                cols(q, "completed_at", "product_name", "quantity", "customer_name", "request_no", "requested_by_name");
                eq(filters, "status", "ISSUED"); range(filters, "completed_at", from, to);
                order(q, "completed_at", "desc"); q.put("limit", limit);
            }
            case "issued_by_month" -> {
                q.put("dataset", "outward_items");
                q.putArray("group_by").add("completed_at:month");
                metric(q, "sum", "quantity", "units"); metric(q, "sum", "line_total", "value"); metric(q, "count_distinct", "request_no", "requests");
                eq(filters, "status", "ISSUED"); range(filters, "completed_at", from, to);
                order(q, "completed_at_month", "asc"); q.put("limit", 120);
            }
            case "issued_by_customer" -> {
                q.put("dataset", "outward_items");
                q.putArray("group_by").add("customer_name");
                metric(q, "sum", "quantity", "units"); metric(q, "sum", "line_total", "value");
                eq(filters, "status", "ISSUED"); range(filters, "completed_at", from, to);
                order(q, "units", "desc"); q.put("limit", limit);
            }
            case "issued_by_product" -> {
                q.put("dataset", "outward_items");
                q.putArray("group_by").add("product_name");
                metric(q, "sum", "quantity", "units"); metric(q, "sum", "line_total", "value");
                eq(filters, "status", "ISSUED"); range(filters, "completed_at", from, to);
                order(q, "units", "desc"); q.put("limit", limit);
            }
            case "inward_by_month" -> {
                q.put("dataset", "inward_stock");
                q.putArray("group_by").add("purchase_date:month");
                metric(q, "sum", "quantity", "units"); metric(q, "sum", "grand_total", "value");
                range(filters, "purchase_date", from, to);
                order(q, "purchase_date_month", "asc"); q.put("limit", 120);
            }
            case "inward_by_vendor" -> {
                q.put("dataset", "inward_stock");
                q.putArray("group_by").add("vendor_name");
                metric(q, "sum", "quantity", "units"); metric(q, "sum", "grand_total", "value");
                range(filters, "purchase_date", from, to);
                order(q, "units", "desc"); q.put("limit", limit);
            }
            case "stock_by_status" -> {
                q.put("dataset", "inward_stock");
                q.putArray("group_by").add("status");
                metric(q, "sum", "quantity", "units");
                order(q, "units", "desc"); q.put("limit", 50);
            }
            case "stock_by_product_status" -> {
                q.put("dataset", "inward_stock");
                q.putArray("group_by").add("product_name").add("status");
                metric(q, "sum", "quantity", "units");
                order(q, "product_name", "asc"); q.put("limit", 500);
            }
            case "staff_movement_by_employee" -> {
                q.put("dataset", "staff_movements");
                q.putArray("group_by").add("to_name").add("movement_type");
                metric(q, "count", null, "movements");
                range(filters, "created_at", from, to);
                order(q, "movements", "desc"); q.put("limit", 200);
            }
            default -> { return null; }
        }
        return q;
    }

    private static String str(JsonNode p, String k) {
        JsonNode v = p == null ? null : p.get(k);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText().trim();
    }

    private static int lim(JsonNode p, int def) {
        JsonNode v = p == null ? null : p.get("limit");
        int n = v == null ? def : v.asInt(def);
        return Math.max(1, Math.min(n, 200));
    }

    private void cols(ObjectNode q, String... c) { ArrayNode a = q.putArray("columns"); for (String s : c) a.add(s); }

    private void metric(ObjectNode q, String fn, String field, String as) {
        ArrayNode a = q.has("metrics") ? (ArrayNode) q.get("metrics") : q.putArray("metrics");
        ObjectNode m = a.addObject(); m.put("fn", fn); if (field != null) m.put("field", field); m.put("as", as);
    }

    private void order(ObjectNode q, String f, String dir) { q.putArray("order_by").addObject().put("field", f).put("dir", dir); }

    private void eq(ArrayNode filters, String f, String v) { filters.addObject().put("field", f).put("op", "=").put("value", v); }

    /** from/to inclusive: 'to' ke din ka poora din aaye isliye +1 din se chhota. */
    private void range(ArrayNode filters, String field, String from, String to) {
        if (from != null) filters.addObject().put("field", field).put("op", ">=").put("value", from);
        if (to != null) {
            String next = to;
            try { next = LocalDate.parse(to).plusDays(1).toString(); } catch (RuntimeException ignored) { }
            filters.addObject().put("field", field).put("op", "<").put("value", next);
        }
    }
}
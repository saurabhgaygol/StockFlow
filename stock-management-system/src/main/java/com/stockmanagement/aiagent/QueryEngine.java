package com.stockmanagement.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.stockmanagement.aiagent.DataCatalog.Dataset;
import com.stockmanagement.aiagent.DataCatalog.Field;
import com.stockmanagement.aiagent.DataCatalog.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI ka "form" (JSON) -> safe SQL -> result. AI SQL kabhi nahi likhta:
 *  - table/column sirf DataCatalog se aate hain (whitelist), values sirf PreparedStatement parameters.
 *  - company filter har query me server khud lagata hai (AI ka bheja company ignore hota hai).
 *  - sirf SELECT, read-only connection, 15s timeout, row cap. Delete/update ka koi raasta hi nahi.
 *  - bade data ke liye GROUP BY database me hota hai, AI ko sirf chhota summary milta hai.
 */
@Component
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);
    public static final int MAX_ROWS = 500;
    public static final int DEFAULT_ROWS = 100;
    public static final int LIST_MODE_ROWS = 200;
    private static final int TIMEOUT_SEC = 15;
    private static final Pattern ALIAS = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    public record Result(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    /** Validation / user-facing error: message safe hai, AI ko wapas jaata hai. */
    public static class QueryException extends RuntimeException {
        public QueryException(String m) { super(m); }
    }

    private final DataSource dataSource;
    private final DataCatalog catalog;

    public QueryEngine(AiReadOnlyDataSourceConfig dsConfig, DataCatalog catalog) {
        this.dataSource = dsConfig.get();
        this.catalog = catalog;
    }

    public Result run(AgentUser user, JsonNode q) {
        Dataset ds = catalog.get(text(q, "dataset"));
        if (ds == null || !catalog.isAllowed(user, ds)) {
            throw new QueryException("Ye data aapke liye available nahi hai.");
        }
        if (user.company() == null || user.company().isBlank()) {
            throw new QueryException("Aapka user kisi company se linked nahi hai.");
        }

        List<String> outNames = new ArrayList<>();
        List<String> selectSql = new ArrayList<>();
        List<String> groupSql = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        used.add(ds.companyExpr());

        // ---- dimensions (group by) ----
        JsonNode gb = q.get("group_by");
        if (gb != null && !gb.isArray()) throw new QueryException("group_by list honi chahiye.");
        if (gb != null) {
            if (gb.size() > 4) throw new QueryException("Max 4 group_by allowed.");
            for (JsonNode g : gb) {
                String spec = g.asText("");
                String fname = spec, bucket = null;
                int c = spec.indexOf(':');
                if (c > 0) { fname = spec.substring(0, c); bucket = spec.substring(c + 1); }
                Field f = field(ds, fname);
                String expr = f.expr();
                String outName = f.name();
                if (bucket != null) {
                    if (f.type() != Type.DATE && f.type() != Type.DATETIME) throw new QueryException(fname + " pe bucket nahi lag sakta.");
                    String fmt = switch (bucket) {
                        case "day" -> "%Y-%m-%d"; case "month" -> "%Y-%m"; case "year" -> "%Y";
                        default -> throw new QueryException("bucket sirf day/month/year ho sakta hai.");
                    };
                    expr = "DATE_FORMAT(" + f.expr() + ", '" + fmt + "')";
                    outName = f.name() + "_" + bucket;
                }
                used.add(f.expr());
                selectSql.add(expr + " AS `" + outName + "`");
                groupSql.add(expr);
                outNames.add(outName);
            }
        }

        // ---- metrics ----
        JsonNode ms = q.get("metrics");
        if (ms != null && !ms.isArray()) throw new QueryException("metrics list honi chahiye.");
        if (ms != null) {
            if (ms.size() > 6) throw new QueryException("Max 6 metrics allowed.");
            int i = 0;
            for (JsonNode m : ms) {
                i++;
                String fn = text(m, "fn");
                String fname = text(m, "field");
                String as = text(m, "as");
                if (as == null || !ALIAS.matcher(as).matches()) as = (fn == null ? "m" : fn) + "_" + (fname == null ? "rows" : fname);
                if (!ALIAS.matcher(as).matches()) as = "metric" + i;
                if (outNames.contains(as)) as = as + "_" + i;
                String expr;
                if ("count".equals(fn) && (fname == null || fname.isBlank())) {
                    expr = "COUNT(*)";
                } else {
                    Field f = field(ds, fname);
                    used.add(f.expr());
                    expr = switch (fn == null ? "" : fn) {
                        case "count" -> "COUNT(" + f.expr() + ")";
                        case "count_distinct" -> "COUNT(DISTINCT " + f.expr() + ")";
                        case "sum", "avg" -> {
                            if (f.type() != Type.NUMBER) throw new QueryException(fn + " sirf number field pe chalta hai.");
                            yield fn.toUpperCase() + "(" + f.expr() + ")";
                        }
                        case "min", "max" -> {
                            if (f.type() == Type.BOOL) throw new QueryException(fn + " is field pe nahi chalta.");
                            yield fn.toUpperCase() + "(" + f.expr() + ")";
                        }
                        default -> throw new QueryException("fn sirf count/count_distinct/sum/avg/min/max ho sakta hai.");
                    };
                }
                selectSql.add(expr + " AS `" + as + "`");
                outNames.add(as);
            }
        }

        boolean listMode = selectSql.isEmpty();
        if (listMode) {
            JsonNode cols = q.get("columns");
            if (cols == null || !cols.isArray() || cols.isEmpty()) {
                throw new QueryException("Ya to group_by/metrics do, ya detail ke liye columns list do.");
            }
            if (cols.size() > 12) throw new QueryException("Max 12 columns.");
            for (JsonNode c : cols) {
                Field f = field(ds, c.asText(""));
                used.add(f.expr());
                selectSql.add(f.expr() + " AS `" + f.name() + "`");
                outNames.add(f.name());
            }
        } else if (!groupSql.isEmpty() && ms == null) {
            // group_by without metrics -> distinct values; fine
        }

        // ---- filters ----
        List<Object> params = new ArrayList<>();
        StringBuilder where = new StringBuilder(ds.companyExpr() + " = ?");
        params.add(user.company());                      // server-forced company
        String scope = scope(ds, user, params);          // server-forced row scope
        if (!scope.isEmpty()) where.append(" AND ").append(scope);
        JsonNode fs = q.get("filters");
        if (fs != null && !fs.isArray()) throw new QueryException("filters list honi chahiye.");
        if (fs != null) {
            if (fs.size() > 10) throw new QueryException("Max 10 filters.");
            for (JsonNode fl : fs) {
                Field f = field(ds, text(fl, "field"));
                used.add(f.expr());
                where.append(" AND ").append(filter(f, text(fl, "op"), fl.get("value"), params));
            }
        }

        // ---- order / limit ----
        StringBuilder order = new StringBuilder();
        JsonNode ob = q.get("order_by");
        if (ob != null && ob.isArray()) {
            for (JsonNode o : ob) {
                String n = text(o, "field");
                if (n == null || !outNames.contains(n)) throw new QueryException("order_by sirf result ke column pe ho sakta hai.");
                if (order.length() > 0) order.append(", ");
                String dir = text(o, "dir");
                boolean asc = dir != null ? "asc".equalsIgnoreCase(dir) : n.matches(".*_(day|month|year)$");
                order.append('`').append(n).append('`').append(asc ? " ASC" : " DESC");
            }
        }
        int limit = q.has("limit") ? q.get("limit").asInt(DEFAULT_ROWS) : DEFAULT_ROWS;
        limit = Math.max(1, Math.min(limit, listMode ? LIST_MODE_ROWS : MAX_ROWS));

        // ---- joins (sirf jo expression me use hue) ----
        StringBuilder joins = new StringBuilder();
        String scopeText = scope;
        for (Map.Entry<String, String> j : ds.joins().entrySet()) {
            String a = j.getKey() + ".";
            boolean need = scopeText.contains(a);
            for (String e : used) if (e.contains(a)) { need = true; break; }
            if (need) joins.append(' ').append(j.getValue());
        }

        String sql = "SELECT /*+ MAX_EXECUTION_TIME(" + (TIMEOUT_SEC * 1000) + ") */ " + String.join(", ", selectSql)
                + " FROM " + ds.from() + joins
                + " WHERE " + where
                + (groupSql.isEmpty() ? "" : " GROUP BY " + String.join(", ", groupSql))
                + (order.length() > 0 ? " ORDER BY " + order : "")
                + " LIMIT " + (limit + 1);
        return execute(sql, params, limit);
    }

    /** Row-level visibility: jo user normal app me dekh sakta hai, AI bhi wahi dekhe. */
    private String scope(Dataset ds, AgentUser u, List<Object> params) {
        if (u.viewAll()) return "";
        switch (ds.scopeKind()) {
            case "OUTWARD" -> {
                params.add(u.userId()); params.add(u.userId()); params.add(u.roleId() == null ? -1L : u.roleId());
                return "(t.requested_by_id = ? OR EXISTS (SELECT 1 FROM stock_outward_approval a WHERE a.request_id = t.id AND "
                        + "((a.approver_type = 'USER' AND a.approver_user_id = ?) OR (a.approver_type = 'ROLE' AND a.approver_role_id = ?))))";
            }
            case "OUTWARD_ITEM" -> {
                params.add(u.userId()); params.add(u.userId()); params.add(u.roleId() == null ? -1L : u.roleId());
                return "(r.requested_by_id = ? OR EXISTS (SELECT 1 FROM stock_outward_approval a WHERE a.request_id = r.id AND "
                        + "((a.approver_type = 'USER' AND a.approver_user_id = ?) OR (a.approver_type = 'ROLE' AND a.approver_role_id = ?))))";
            }
            case "STAFF" -> {
                params.add(u.userId()); params.add(u.userId());
                return "(t.from_user_id = ? OR t.to_user_id = ?)";
            }
            default -> { return ""; }
        }
    }

    private String filter(Field f, String op, JsonNode v, List<Object> params) {
        if (op == null) throw new QueryException("filter me op chahiye.");
        String e = f.expr();
        switch (op) {
            case "=", "!=", ">", ">=", "<", "<=" -> {
                if (f.type() == Type.BOOL && !op.equals("=") && !op.equals("!=")) throw new QueryException("bool pe sirf = / != chalta hai.");
                params.add(value(f, v));
                return "(" + e + " " + (op.equals("!=") ? "<>" : op) + " ?)";
            }
            case "in" -> {
                if (v == null || !v.isArray() || v.isEmpty() || v.size() > 50) throw new QueryException("in ke liye 1-50 values ki list do.");
                StringBuilder sb = new StringBuilder("(" + e + " IN (");
                for (int i = 0; i < v.size(); i++) { if (i > 0) sb.append(','); sb.append('?'); params.add(value(f, v.get(i))); }
                return sb.append("))").toString();
            }
            case "contains" -> {
                if (f.type() != Type.STRING) throw new QueryException("contains sirf text field pe chalta hai.");
                String s = v == null ? "" : v.asText("");
                if (s.isBlank() || s.length() > 100) throw new QueryException("contains value 1-100 chars ki ho.");
                params.add("%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
                return "(" + e + " LIKE ?)";
            }
            case "between" -> {
                if (v == null || !v.isArray() || v.size() != 2) throw new QueryException("between ke liye [from, to] do.");
                params.add(value(f, v.get(0))); params.add(value(f, v.get(1)));
                return "(" + e + " BETWEEN ? AND ?)";
            }
            case "is_null" -> { return "(" + e + " IS NULL)"; }
            case "not_null" -> { return "(" + e + " IS NOT NULL)"; }
            default -> throw new QueryException("op sirf =, !=, >, >=, <, <=, in, contains, between, is_null, not_null ho sakta hai.");
        }
    }

    private Object value(Field f, JsonNode v) {
        if (v == null || v.isNull() || v.isContainerNode()) throw new QueryException(f.name() + " ki value galat hai.");
        try {
            switch (f.type()) {
                case NUMBER: return new BigDecimal(v.asText());
                case BOOL: return v.asBoolean() ? 1 : 0;
                case DATE: return java.sql.Date.valueOf(LocalDate.parse(v.asText()));
                case DATETIME: {
                    String s = v.asText();
                    if (s.length() == 10) return Timestamp.valueOf(LocalDate.parse(s).atStartOfDay());
                    return Timestamp.valueOf(java.time.LocalDateTime.parse(s.replace(' ', 'T')));
                }
                default: {
                    String s = v.asText();
                    if (s.length() > 200) throw new QueryException(f.name() + " ki value bahut lambi hai.");
                    return s;
                }
            }
        } catch (QueryException qe) {
            throw qe;
        } catch (RuntimeException ex) {
            throw new QueryException(f.name() + " ki value ka format galat hai (date: YYYY-MM-DD).");
        }
    }

    private Field field(Dataset ds, String name) {
        Field f = name == null ? null : ds.fields().get(name);
        if (f == null) throw new QueryException("Field '" + (name == null ? "" : name.replaceAll("[^A-Za-z0-9_]", "")) + "' is dataset me nahi hai.");
        return f;
    }

    private static String text(JsonNode n, String k) {
        JsonNode v = n == null ? null : n.get(k);
        return v == null || v.isNull() ? null : v.asText();
    }

    private Result execute(String sql, List<Object> params, int limit) {
        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setQueryTimeout(TIMEOUT_SEC);
                ps.setMaxRows(limit + 1);
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<String> cols = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) cols.add(md.getColumnLabel(i));
                    List<List<Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= limit) { truncated = true; break; }
                        List<Object> r = new ArrayList<>(cols.size());
                        for (int i = 1; i <= cols.size(); i++) {
                            Object o = rs.getObject(i);
                            if (o instanceof BigDecimal bd) o = new BigDecimal(bd.stripTrailingZeros().toPlainString());   // number rahe (chart/totals ke liye), plain format
                            else if (o instanceof java.util.Date || o instanceof java.time.temporal.TemporalAccessor) o = String.valueOf(o);
                            r.add(o);
                        }
                        rows.add(r);
                    }
                    return new Result(cols, rows, truncated);
                }
            }
        } catch (SQLException ex) {
            log.warn("AI query failed: {}", ex.getMessage());   // SQL/DB detail user ya AI ko nahi jaati
            throw new QueryException("Ye data abhi nikal nahi paya. Filter chhota karke dobara try karein.");
        }
    }
}
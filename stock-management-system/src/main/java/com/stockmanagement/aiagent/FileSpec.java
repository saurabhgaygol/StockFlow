package com.stockmanagement.aiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * File (Excel / PDF / PowerPoint) ka design AI runtime par tay karta hai, lekin sirf "spec" ke roop me.
 * Server har cheez validate karta hai: data sirf server ke verified query results se aata hai,
 * colours sirf 6-digit hex, formats/chart types whitelist me, sizes limited. Browser spec se file banata hai.
 */
public final class FileSpec {

    private FileSpec() {}

    public record Chart(String type, int label, int value) {}

    public record Section(String heading, List<String> columns, List<List<Object>> rows, boolean truncated,
                          String show, Chart chart, List<Object> totals, String slide) {}

    public record FilePayload(String title, String subtitle, String primary, String accent, String orientation,
                              List<String> summary, List<Section> sections, List<String> formats) {}

    private static final Set<String> FORMATS = Set.of("xlsx", "pdf", "pptx");
    private static final Set<String> CHARTS = Set.of("bar", "line", "pie");
    private static final Pattern HEX = Pattern.compile("^[0-9A-Fa-f]{6}$");
    private static final int MAX_SECTIONS = 10;
    private static final int MAX_SUMMARY = 8;
    private static final Pattern CTRL = Pattern.compile("[\\p{Cntrl}&&[^\\n]]");

    /** User ne file maangi hai ya nahi (server-side deewar; AI khud se file nahi bana sakta). */
    public static final Pattern ASKED = Pattern.compile(
            "(?i)(excel|xlsx|\\bxls\\b|spreadsheet|\\bpdf\\b|\\bppt|powerpoint|presentation|slides?\\b|\\bsheet\\b|"
            + "\\bfile\\b|download|export|report)");

    public static FilePayload build(JsonNode msg, List<QueryEngine.Result> results, int defaultResultNo) {
        List<String> formats = new ArrayList<>();
        JsonNode f = msg.get("formats");
        if (f != null && f.isArray()) f.forEach(x -> addFormat(formats, x.asText("")));
        addFormat(formats, msg.path("format").asText(""));
        if (formats.isEmpty()) throw new IllegalArgumentException("formats missing. Use xlsx, pdf and/or pptx.");

        String title = clean(msg.path("title").asText("Report"), 100);
        if (title.isBlank()) title = "Report";
        String subtitle = clean(msg.path("subtitle").asText(""), 160);

        JsonNode theme = msg.path("theme");
        String primary = hex(theme.path("primary").asText(""), "1F3A5F");
        String accent = hex(theme.path("accent").asText(""), "2E7D9A");
        String orient = msg.path("orientation").asText("auto").toLowerCase(Locale.ROOT);
        if (!orient.equals("portrait") && !orient.equals("landscape")) orient = "auto";

        List<Section> sections = new ArrayList<>();
        JsonNode sec = msg.get("sections");
        if (sec == null || !sec.isArray() || sec.isEmpty()) {
            Defaults.addDefault(sections, results, defaultResultNo, title);
        } else {
            if (sec.size() > MAX_SECTIONS) throw new IllegalArgumentException("Too many sections (max " + MAX_SECTIONS + ").");
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (JsonNode s : sec) { sections.add(section(s, results, defaultResultNo)); used.add(s.path("result").asInt(defaultResultNo)); }
            // AI ne jo data nikala par file me nahi dala (kamzor model): wo bhi jod do, kuch waste na ho
            for (int i = 1; i <= results.size() && sections.size() < MAX_SECTIONS; i++) {
                if (used.contains(i) || results.get(i - 1).rows().isEmpty()) continue;
                ObjectNode extra = new ObjectMapper().createObjectNode();
                extra.put("result", i);
                extra.put("heading", headingFor(results.get(i - 1)));
                sections.add(section(extra, results, defaultResultNo));
            }
        }
        if (sections.isEmpty()) throw new IllegalArgumentException("No data to put in the file. Run a query first.");

        List<String> summary = new ArrayList<>();
        JsonNode sm = msg.get("summary");
        if (sm != null && sm.isArray()) {
            for (JsonNode b : sm) {
                String t = clean(b.asText(""), 300);
                if (!t.isBlank() && summary.size() < MAX_SUMMARY) summary.add(t);
            }
        }
        return new FilePayload(title, subtitle, primary, accent, orient, summary, sections, formats);
    }

    private static String headingFor(QueryEngine.Result r) {
        List<String> n = new ArrayList<>();
        for (String c : r.columns()) { if (n.size() < 2) n.add(pretty(c)); }
        return String.join(" / ", n);
    }

    /** Model file action nahi de paya: nikle hue sab data se server khud saaf file banata hai (chart ke saath). */
    public static FilePayload buildDefault(List<QueryEngine.Result> results, String userText, String title) {
        List<String> formats = new ArrayList<>();
        String t = userText == null ? "" : userText.toLowerCase(Locale.ROOT);
        if (t.matches("(?s).*(ppt|powerpoint|presentation|slide).*")) formats.add("pptx");
        if (t.matches("(?s).*(excel|xlsx|\\bxls\\b|spreadsheet|\\bsheet\\b).*")) formats.add("xlsx");
        if (t.matches("(?s).*\\bpdf\\b.*")) formats.add("pdf");
        if (formats.isEmpty()) { formats.add("pptx"); formats.add("xlsx"); formats.add("pdf"); }
        ObjectMapper om = new ObjectMapper();
        ObjectNode spec = om.createObjectNode();
        ArrayNode f = spec.putArray("formats");
        formats.forEach(f::add);
        spec.put("title", title == null || title.isBlank() ? "Report" : title);
        ArrayNode secs = spec.putArray("sections");
        for (int i = 1; i <= results.size() && secs.size() < MAX_SECTIONS; i++) {
            if (results.get(i - 1).rows().isEmpty()) continue;
            ObjectNode o = secs.addObject();
            o.put("result", i);
            o.put("heading", headingFor(results.get(i - 1)));
        }
        return build(spec, results, 1);
    }

    private static Section section(JsonNode s, List<QueryEngine.Result> results, int defaultNo) {
        int no = s.path("result").asInt(defaultNo);
        if (no < 1 || no > results.size()) throw new IllegalArgumentException("Section result number is invalid. Results available: 1.." + results.size());
        QueryEngine.Result r = results.get(no - 1);
        List<String> all = r.columns();

        // columns: subset + order (sirf wahi jo result me hain)
        List<Integer> idx = new ArrayList<>();
        JsonNode cols = s.get("columns");
        if (cols != null && cols.isArray() && !cols.isEmpty()) {
            for (JsonNode c : cols) {
                int i = all.indexOf(c.asText(""));
                if (i < 0) throw new IllegalArgumentException("Unknown column '" + clean(c.asText(""), 40) + "'. Available: " + all);
                if (!idx.contains(i)) idx.add(i);
            }
        } else {
            for (int i = 0; i < all.size(); i++) idx.add(i);
        }

        JsonNode labels = s.path("labels");
        List<String> names = new ArrayList<>();
        for (int i : idx) {
            String lab = labels.isObject() && labels.has(all.get(i)) ? clean(labels.path(all.get(i)).asText(""), 60) : "";
            names.add(lab.isBlank() ? pretty(all.get(i)) : lab);
        }

        List<List<Object>> rows = new ArrayList<>();
        for (List<Object> row : r.rows()) {
            List<Object> o = new ArrayList<>();
            for (int i : idx) o.add(i < row.size() ? row.get(i) : null);
            rows.add(o);
        }

        JsonNode sort = s.path("sort");
        if (sort.isObject()) {
            int si = all.indexOf(sort.path("col").asText(""));
            int pos = si < 0 ? -1 : idx.indexOf(si);
            if (pos < 0) throw new IllegalArgumentException("sort column must be one of the section columns.");
            boolean desc = "desc".equalsIgnoreCase(sort.path("dir").asText("asc"));
            final int p = pos;
            Comparator<List<Object>> cmp = (a, b) -> compare(a.get(p), b.get(p));
            rows.sort(desc ? cmp.reversed() : cmp);
        }

        String show = s.path("show").asText("table").toLowerCase(Locale.ROOT);
        if (!show.equals("chart") && !show.equals("both")) show = "table";

        boolean unpivoted = false;
        Chart chart = null;
        JsonNode ch = s.get("chart");
        if (ch != null && ch.isObject()) {
            String type = ch.path("type").asText("bar").toLowerCase(Locale.ROOT);
            if (!CHARTS.contains(type)) throw new IllegalArgumentException("chart type must be bar, line or pie.");
            int li = idx.indexOf(all.indexOf(ch.path("label").asText("")));
            int vi = idx.indexOf(all.indexOf(ch.path("value").asText("")));
            if (li < 0 || vi < 0) throw new IllegalArgumentException("chart label/value must be columns of the section.");
            if (!isNumeric(rows, vi)) throw new IllegalArgumentException("chart value column must be numeric.");
            chart = new Chart(type, li, vi);
            if (show.equals("table") && s.path("show").isMissingNode()) show = "both";
        } else {
            // chart nahi diya: category + number mile to khud bar chart laga do (report dull na rahe)
            // ek hi row aur kai number columns (jaise Available / Issued / Total): Metric-Value table banao taaki chart ban sake
            if (rows.size() == 1 && names.size() >= 2 && names.size() <= 12) {
                int numCols = 0;
                for (int c = 0; c < names.size(); c++) if (isNumeric(rows, c)) numCols++;
                if (numCols >= 2) {
                    List<List<Object>> flat = new ArrayList<>();
                    for (int c = 0; c < names.size(); c++) {
                        if (isNumeric(rows, c)) flat.add(new ArrayList<>(Arrays.asList(names.get(c), rows.get(0).get(c))));
                    }
                    names = new ArrayList<>(List.of("Metric", "Value"));
                    rows = flat;
                    unpivoted = true;
                }
            }
            Chart auto = autoChart(rows, names.size());
            if (auto != null) { chart = auto; show = "both"; }
            else if (show.equals("chart") || show.equals("both")) show = "table";
        }

        List<Object> totals = null;
        if (s.path("totals").asBoolean(false) && !unpivoted) {
            totals = new ArrayList<>();
            boolean any = false;
            for (int c = 0; c < names.size(); c++) {
                if (isNumeric(rows, c)) {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (List<Object> row : rows) if (row.get(c) instanceof Number n) sum = sum.add(new BigDecimal(n.toString()));
                    totals.add(sum);
                    any = true;
                } else {
                    totals.add(c == 0 ? "Total" : null);
                }
            }
            if (!any) totals = null;
        }
        String heading = clean(s.path("heading").asText(""), 80);
        return new Section(heading, names, rows, r.truncated(), show, chart, totals, clean(s.path("slide").asText(""), 20));
    }

    /** Spec me section nahi diya: last (ya diya hua) result poora. */
    private static final class Defaults {
        static void addDefault(List<Section> out, List<QueryEngine.Result> results, int no, String title) {
            if (results.isEmpty()) return;
            int n = Math.min(Math.max(no, 1), results.size());
            QueryEngine.Result r = results.get(n - 1);
            List<String> names = new ArrayList<>();
            for (String c : r.columns()) names.add(pretty(c));
            List<List<Object>> rows = new ArrayList<>();
            for (List<Object> row : r.rows()) rows.add(new ArrayList<>(row));
            out.add(new Section(title, names, rows, r.truncated(), "table", null, null, ""));
        }
    }

    /** 2..25 rows, ek text column + ek numeric column ho to bar chart (last numeric column value banta hai). */
    private static Chart autoChart(List<List<Object>> rows, int cols) {
        if (rows.size() < 2 || rows.size() > 25 || cols < 2) return null;
        int label = -1, value = -1;
        for (int c = 0; c < cols; c++) {
            if (isNumeric(rows, c)) value = c;
            else if (label < 0) label = c;
        }
        return label >= 0 && value >= 0 ? new Chart("bar", label, value) : null;
    }

    private static boolean isNumeric(List<List<Object>> rows, int c) {
        boolean any = false;
        for (List<Object> r : rows) {
            Object v = r.get(c);
            if (v == null) continue;
            if (!(v instanceof Number)) return false;
            any = true;
        }
        return any;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof Number x && b instanceof Number y) return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()));
        return String.valueOf(a).compareToIgnoreCase(String.valueOf(b));
    }

    private static void addFormat(List<String> out, String raw) {
        String f = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (f.equals("excel") || f.equals("xls")) f = "xlsx";
        if (f.equals("ppt") || f.equals("powerpoint") || f.equals("slides")) f = "pptx";
        if (FORMATS.contains(f) && !out.contains(f)) out.add(f);
    }

    private static String hex(String v, String dflt) {
        String s = v == null ? "" : v.trim().replace("#", "");
        return HEX.matcher(s).matches() ? s.toUpperCase(Locale.ROOT) : dflt;
    }

    private static String clean(String s, int max) {
        String t = s == null ? "" : CTRL.matcher(s).replaceAll(" ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String pretty(String col) {
        String s = col == null ? "" : col.replace('_', ' ');
        StringBuilder b = new StringBuilder();
        boolean up = true;
        for (char c : s.toCharArray()) {
            b.append(up ? Character.toUpperCase(c) : c);
            up = c == ' ';
        }
        return b.toString();
    }

    // ---------------- AI-likha code (browser ke sandbox me chalta hai) ----------------

    public record Dataset(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    public record CodePayload(String title, List<String> formats, String code, List<Dataset> data) {}

    private static final int MAX_CODE = 40_000;
    private static final int MAX_CELLS = 150_000;
    /** Defense in depth: asli suraksha browser sandbox + CSP hai (network band, page/cookies tak pahunch nahi). */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)(\\bfetch\\s*\\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|importScripts|\\bimport\\s*\\(|"
            + "\\b(self|window|globalThis|parent|top|opener)\\s*(\\.|\\[)|localStorage|sessionStorage|indexedDB|document\\s*\\.|"
            + "\\beval\\s*\\(|\\bFunction\\s*\\(|\\bpostMessage\\b|\\blocation\\s*[.=\\[]|\\bWorker\\s*\\(|"
            + "navigator\\s*\\.|<script|__proto__)");

    public static CodePayload buildCode(JsonNode msg, List<QueryEngine.Result> results) {
        List<String> formats = new ArrayList<>();
        JsonNode f = msg.get("formats");
        if (f != null && f.isArray()) f.forEach(x -> addFormat(formats, x.asText("")));
        addFormat(formats, msg.path("format").asText(""));
        if (formats.isEmpty()) throw new IllegalArgumentException("formats missing. Use xlsx, pdf and/or pptx.");
        String code = msg.path("code").asText("");
        if (code.isBlank()) throw new IllegalArgumentException("code missing.");
        if (code.length() > MAX_CODE) throw new IllegalArgumentException("code too long (max " + MAX_CODE + " characters). Make it shorter.");
        java.util.regex.Matcher m = FORBIDDEN.matcher(code);
        if (m.find()) throw new IllegalArgumentException("code uses something not allowed: '" + m.group().trim() + "'. Use only the data argument and the libs argument.");
        if (results.isEmpty()) throw new IllegalArgumentException("No data to put in the file. Run a query first.");
        String title = clean(msg.path("title").asText("Report"), 100);
        List<Dataset> data = new ArrayList<>();
        long cells = 0;
        for (QueryEngine.Result r : results) {
            cells += (long) r.rows().size() * Math.max(1, r.columns().size());
            if (cells > MAX_CELLS) break;
            data.add(new Dataset(r.columns(), r.rows(), r.truncated()));
        }
        return new CodePayload(title.isBlank() ? "Report" : title, formats, code, data);
    }
}
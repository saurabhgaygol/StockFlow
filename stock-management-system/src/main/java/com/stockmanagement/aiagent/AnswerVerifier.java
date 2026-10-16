package com.stockmanagement.aiagent;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fake number rokne ki deewar: AI ke jawab me jo bhi sankhya hai wo server ke nikale data se match honi chahiye
 * (data me ya data ke column-total / row-count me). Match na ho to jawab user tak nahi jaata.
 * Sirf saal (2000-2100), list numbering, dates aur 'last 5 / top 10' jaise shabd bina match ke allowed hain.
 */
@Component
public class AnswerVerifier {

    private static final Pattern NUM = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    public record Check(boolean ok, List<String> unknown) {}

    public Check verify(String answer, List<QueryEngine.Result> results) {
        if (answer == null) return new Check(true, List.of());
        Set<String> allowed = allowedNumbers(results);
        List<String> bad = new ArrayList<>();
        Matcher m = NUM.matcher(cleaned(answer));
        while (m.find()) {
            String raw = m.group();
            String norm = norm(raw);
            if (norm == null) continue;
            if (isYear(norm) || allowed.contains(norm)) continue;
            if (!bad.contains(raw)) bad.add(raw);
        }
        return new Check(bad.isEmpty(), bad);
    }

    private Set<String> allowedNumbers(List<QueryEngine.Result> results) {
        Set<String> set = new HashSet<>();
        int totalRows = 0;
        for (QueryEngine.Result r : results) {
            totalRows += r.rows().size();
            set.add(String.valueOf(r.rows().size()));
            int cols = r.columns().size();
            BigDecimal[] sums = new BigDecimal[cols];
            boolean[] numeric = new boolean[cols];
            Arrays.fill(numeric, true);
            for (List<Object> row : r.rows()) {
                for (int i = 0; i < cols && i < row.size(); i++) {
                    Object v = row.get(i);
                    if (v == null) continue;
                    String s = String.valueOf(v);
                    // Tarikh ke tukde (10, 09) ko 'data ki sankhya' na maano, warna nakli 10 bhi pass ho jaata
                    Matcher m = NUM.matcher(cleaned(s));
                    while (m.find()) { String n = norm(m.group()); if (n != null) set.add(n); }
                    BigDecimal bd = toDecimal(v);
                    if (bd == null) { numeric[i] = false; continue; }
                    sums[i] = sums[i] == null ? bd : sums[i].add(bd);
                }
            }
            for (int i = 0; i < cols; i++) {
                if (numeric[i] && sums[i] != null) set.add(sums[i].stripTrailingZeros().toPlainString());
            }
        }
        set.add(String.valueOf(totalRows));
        return set;
    }

    private static BigDecimal toDecimal(Object v) {
        try {
            String s = String.valueOf(v).trim();
            if (!s.matches("-?\\d+(\\.\\d+)?")) return null;
            return new BigDecimal(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String norm(String raw) {
        try {
            return new BigDecimal(raw.replace(",", "")).stripTrailingZeros().toPlainString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isYear(String norm) {
        try {
            BigDecimal b = new BigDecimal(norm);
            if (b.scale() > 0) return false;
            long v = b.longValue();
            return v >= 2000 && v <= 2100;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Bina-matlab ki sankhya hatao: list numbering (1. 2.), #1, dates, 'last 5 / top 10' jaise shabd. */
    private static String cleaned(String t) {
        String s = t;
        s = s.replaceAll("(?m)^\\s*(?:[-*]\\s*)?\\d{1,2}[.)]\\s", " ");
        s = s.replaceAll("#\\d{1,2}\\b", " ");
        s = s.replaceAll("\\b\\d{4}-\\d{2}-\\d{2}\\b", " ");
        s = s.replaceAll("\\b\\d{4}-\\d{2}\\b", " ");
        s = s.replaceAll("(?i)\\b\\d{1,2}\\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\b", " ");
        s = s.replaceAll("(?i)\\b(last|latest|top|first|recent|pichle|pehle)\\s+\\d{1,3}\\b", " ");
        return s;
    }

    /** Fallback: AI ka jawab bharosemand nahi to seedha server ka data table ke roop me. */
    public String tableOf(QueryEngine.Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", r.columns())).append(" |\n|");
        for (int i = 0; i < r.columns().size(); i++) sb.append("---|");
        sb.append('\n');
        int n = 0;
        for (List<Object> row : r.rows()) {
            if (n++ >= 50) break;
            sb.append("| ");
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(" | ");
                String c = row.get(i) == null ? "" : String.valueOf(row.get(i));
                sb.append(c.replace("|", "/").replace("\n", " "));
            }
            sb.append(" |\n");
        }
        if (r.truncated() || r.rows().size() > 50) sb.append("\n(Only the first rows are shown.)");
        return sb.toString();
    }
}
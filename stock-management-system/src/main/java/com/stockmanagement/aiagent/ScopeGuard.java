package com.stockmanagement.aiagent;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Pehli deewar: AI/model tak jaane se pehle hi user ka message check hota hai.
 * Block: software ke internals puchna, system prompt/instructions nikalwana, "tumhe puri permission hai" jaise
 * dabav, delete/drop jaisi maang, jailbreak. Baar-baar koshish par thodi der ke liye AI band.
 * (Doosri deewar: system prompt, teesri: QueryEngine whitelist + permission + company, chauthi: DB ka read-only user.)
 */
@Component
public class ScopeGuard {

    public enum Verdict { OK, INTERNALS, DESTRUCTIVE, PRESSURE, LOCKED }

    public record Decision(Verdict verdict, String reply) {
        public boolean allowed() { return verdict == Verdict.OK; }
    }

    private static final int MAX_STRIKES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static final Pattern INTERNALS = Pattern.compile(
            "system\\s*prompt|your\\s+(prompt|instructions?|rules?|code)|source\\s*code|(show|reveal|print|tell|dikha|bata)\\w*\\s+.{0,20}(prompt|instruction|code|schema)"
            + "|database\\s+(schema|structure|design)|db\\s+(schema|structure)|table\\s*(name|list|structure)|column\\s*name|which\\s+tables?"
            + "|kaun\\s*se\\s*table|kon\\s*se\\s*table|\\bschema\\b|\\bsql\\b|\\bquery\\s+(likh|show|dikha)|how\\s+(do|does)\\s+(you|this\\s+software|stockflow)\\s+work"
            + "|internal(ly)?\\s+(work|logic|working)|andar\\s+se\\s+kaise|kaise\\s+kaam\\s+karta|api\\s*key|secret|\\bpassword\\b|\\btoken\\b|\\.java\\b|\\bcontroller\\b|\\brepository\\b|spring\\s*boot|hibernate"
            + "|ignore\\s+(all\\s+|the\\s+|your\\s+)?(previous|above|prior|earlier)|forget\\s+(all\\s+|your\\s+)?(rules|instructions)|developer\\s+mode|jailbreak|\\bdan\\b|act\\s+as|pretend|role\\s*play"
            + "|neeche\\s+ke\\s+niyam\\s+bhool|pehle\\s+ke\\s+(niyam|instructions?)\\s+bhool",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\b(delete|drop|truncate|erase|wipe|purge|destroy)\\b|(mita|mitao|hata|hatao)\\s*(do|de|dena|dijiye|kar)?\\b|delete\\s*kar|\\bremove\\s+(all|every|the\\s+data)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern PRESSURE = Pattern.compile(
            "(puri|poori|full|saari|sari|sab)\\s+(permission|access|authority)|tumhe\\s+(sab|puri|poori)|you\\s+(have|are\\s+given)\\s+(full|all|every)\\s+(permission|access)"
            + "|i\\s+(am|m)\\s+(the\\s+)?(admin|owner|developer|super\\s*admin)\\s+(so|,)|override|bypass|sudo|authorized\\s+you|maine\\s+permission\\s+de|no\\s+restrictions?|without\\s+(any\\s+)?(check|restriction|permission)",
            Pattern.CASE_INSENSITIVE);

    public static final String REPLY_INTERNALS =
            "I can only help with your StockFlow business data (stock, outward, returns, vendors, employees). "
            + "I can't discuss how the software works internally or anything outside it.";
    public static final String REPLY_DESTRUCTIVE =
            "AI can never delete or remove anything in StockFlow. I can only read and analyse the data you are permitted to see.";
    public static final String REPLY_PRESSURE =
            "I only work within your actual permissions in StockFlow. Claiming extra permission doesn't change what I can access.";
    public static final String REPLY_LOCKED =
            "Too many requests outside the allowed scope. AI is paused for you for a few minutes. Please try again later.";

    private final ConcurrentHashMap<Long, Deque<Instant>> strikes = new ConcurrentHashMap<>();

    public Decision check(Long userId, String text) {
        if (isLocked(userId)) return new Decision(Verdict.LOCKED, REPLY_LOCKED);
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        if (DESTRUCTIVE.matcher(t).find()) { strike(userId); return new Decision(Verdict.DESTRUCTIVE, REPLY_DESTRUCTIVE); }
        if (PRESSURE.matcher(t).find())    { strike(userId); return new Decision(Verdict.PRESSURE, REPLY_PRESSURE); }
        if (INTERNALS.matcher(t).find())   { strike(userId); return new Decision(Verdict.INTERNALS, REPLY_INTERNALS); }
        return new Decision(Verdict.OK, null);
    }

    private void strike(Long userId) {
        Deque<Instant> d = strikes.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (d) { d.addLast(Instant.now()); prune(d); }
    }

    private boolean isLocked(Long userId) {
        Deque<Instant> d = strikes.get(userId);
        if (d == null) return false;
        synchronized (d) { prune(d); return d.size() >= MAX_STRIKES; }
    }

    private void prune(Deque<Instant> d) {
        Instant cut = Instant.now().minus(WINDOW);
        while (!d.isEmpty() && d.peekFirst().isBefore(cut)) d.removeFirst();
    }
}
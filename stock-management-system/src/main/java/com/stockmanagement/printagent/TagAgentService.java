package com.stockmanagement.printagent;

import com.stockmanagement.service.TagBatchService;
import com.stockmanagement.service.TagDesigns;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Printer agent: pairing, printers, routes (tag size -> printer) and the print job queue.
 * Everything is per company; the agent only ever sees the jobs of its own computer.
 */
@Service
public class TagAgentService {

    /** the agent calls at least every 30 s (long poll); a bit more than that = still online */
    static final int ONLINE_SECONDS = 70;
    static final int PAIR_MINUTES = 15;
    /** a job nobody picked up for this long is failed, so a shut-down computer does not print old tags later */
    static final int JOB_WAIT_SECONDS = 120;
    /** a bill the agent took but never answered for (computer switched off while printing) is shown as failed */
    static final int BILL_SENT_SECONDS = 180;
    static final int DEFAULT_DPI = 203;
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";   // no 0 O 1 I
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TagAgentRepository agents;
    private final TagAgentPrinterRepository printers;
    private final TagPrinterRouteRepository routes;
    private final TagPrintJobRepository jobs;
    private final TagBatchService batchService;

    public TagAgentService(TagAgentRepository agents, TagAgentPrinterRepository printers,
                           TagPrinterRouteRepository routes, TagPrintJobRepository jobs, TagBatchService batchService) {
        this.batchService = batchService;
        this.agents = agents;
        this.printers = printers;
        this.routes = routes;
        this.jobs = jobs;
    }

    // ------------------------------------------------------------------ pairing

    /** a new one-time code for this shop, shown in Tag Studio ("ABCD-EFGH"); expired unused codes are dropped */
    @Transactional
    public String newPairCode(String company, Long userId, String userName) {
        // only old (expired) codes are removed: two computers of the same shop can be connected at the same time
        agents.deleteAll(agents.findByTokenHashIsNullAndPairExpiresAtBefore(LocalDateTime.now()));
        agents.flush();
        String code;
        do {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(CODE_CHARS.charAt(RANDOM.nextInt(CODE_CHARS.length())));
            code = sb.toString();
        } while (agents.findByPairCode(code).isPresent());
        TagAgent a = new TagAgent();
        a.setCompanyName(company);
        a.setPairCode(code);
        a.setPairExpiresAt(LocalDateTime.now().plusMinutes(PAIR_MINUTES));
        a.setCreatedByUserId(userId);
        a.setCreatedByName(userName);
        agents.save(a);
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    public record Paired(String token, String company) {}

    /** exchanges a valid code for the agent's secret token (shown once, only its hash is kept) */
    @Transactional
    public Optional<Paired> pair(String code, String computer, String version, List<PrinterIn> found) {
        String c = code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
        if (c.length() != 8) return Optional.empty();
        Optional<TagAgent> opt = agents.findByPairCode(c);
        if (opt.isEmpty()) return Optional.empty();
        TagAgent a = opt.get();
        if (a.getTokenHash() != null || a.getPairExpiresAt() == null || a.getPairExpiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = "sfa_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        a.setTokenHash(hash(token));
        a.setPairCode(null);
        a.setPairExpiresAt(null);
        a.setComputerName(clip(computer, 120));
        a.setAgentVersion(clip(version, 20));
        a.setPairedAt(LocalDateTime.now());
        a.setLastSeenAt(LocalDateTime.now());
        agents.save(a);
        replacePrinters(a, found);
        return Optional.of(new Paired(token, a.getCompanyName()));
    }

    /** the agent a token belongs to (null token or unknown = empty) */
    @Transactional
    public Optional<TagAgent> authenticate(String token) {
        if (token == null || !token.startsWith("sfa_") || token.length() > 100) return Optional.empty();
        Optional<TagAgent> a = agents.findByTokenHash(hash(token));
        a.ifPresent(x -> {
            if (x.getLastSeenAt() == null || x.getLastSeenAt().isBefore(LocalDateTime.now().minusSeconds(10))) {
                x.setLastSeenAt(LocalDateTime.now());
                agents.save(x);
            }
        });
        return a;
    }

    /** the agent is alive (WebSocket ping) */
    @Transactional
    public void touch(Long agentId) {
        agents.findById(agentId).ifPresent(a -> { a.setLastSeenAt(LocalDateTime.now()); agents.save(a); });
    }

    /** the WebSocket closed: show the computer as offline at once */
    @Transactional
    public void markOffline(Long agentId) {
        agents.findById(agentId).ifPresent(a -> { a.setLastSeenAt(LocalDateTime.now().minusMinutes(5)); agents.save(a); });
    }

    /** removes a computer: its token stops working and its printers and routes go */
    @Transactional
    public boolean disconnect(String company, Long agentId) {
        Optional<TagAgent> a = agents.findByIdAndCompanyName(agentId, company);
        if (a.isEmpty()) return false;
        routes.deleteAll(routes.findByAgentId(agentId));
        printers.deleteByAgent(agentId);
        agents.delete(a.get());
        return true;
    }

    // ------------------------------------------------------------------ printers

    public record PrinterIn(String name, boolean isDefault, String status) {}

    @Transactional
    public void reportPrinters(TagAgent agent, String computer, String version, List<PrinterIn> found) {
        if (computer != null && !computer.isBlank()) agent.setComputerName(clip(computer, 120));
        if (version != null && !version.isBlank()) agent.setAgentVersion(clip(version, 20));
        agent.setLastSeenAt(LocalDateTime.now());
        agents.save(agent);
        replacePrinters(agent, found);
    }

    private void replacePrinters(TagAgent agent, List<PrinterIn> found) {
        printers.deleteByAgent(agent.getId());
        printers.flush();
        if (found == null) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (PrinterIn p : found) {
            // the name is kept exactly as Windows has it (spaces too): the agent prints by this name
            String n = p.name();
            if (n == null || n.isBlank() || n.length() > 200 || !seen.add(n)) continue;
            TagAgentPrinter row = new TagAgentPrinter();
            row.setAgentId(agent.getId());
            row.setCompanyName(agent.getCompanyName());
            row.setPrinterName(n);
            row.setDefaultPrinter(p.isDefault());
            row.setStatus(clip(p.status(), 40));
            printers.save(row);
            if (seen.size() >= 50) break;
        }
    }

    // ------------------------------------------------------------------ what Tag Studio shows

    public record AgentView(Long id, String computer, boolean online, String lastSeen, String version,
                            List<Map<String, Object>> printers) {}

    @Transactional(readOnly = true)
    public List<AgentView> agentViews(String company) {
        List<AgentView> out = new ArrayList<>();
        for (TagAgent a : agents.findByCompanyNameAndTokenHashIsNotNullOrderByIdDesc(company)) {
            List<Map<String, Object>> ps = new ArrayList<>();
            for (TagAgentPrinter p : printers.findByAgentIdOrderByPrinterNameAsc(a.getId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", p.getPrinterName());
                m.put("isDefault", p.isDefaultPrinter());
                m.put("status", p.getStatus() == null ? "" : p.getStatus());
                ps.add(m);
            }
            out.add(new AgentView(a.getId(), a.getComputerName() == null ? "Computer" : a.getComputerName(), isOnline(a),
                    a.getLastSeenAt() == null ? "" : a.getLastSeenAt().toString(), a.getAgentVersion() == null ? "" : a.getAgentVersion(), ps));
        }
        return out;
    }

    /** tag size code -> {agentId, printer} the shop chose */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> routeViews(String company) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (TagPrinterRoute r : routes.findByCompanyName(company)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("agentId", r.getAgentId());
            m.put("printer", r.getPrinterName());
            out.put(r.getPrinterCode(), m);
        }
        return out;
    }

    /** printer = null or blank clears the choice */
    @Transactional
    public void setRoute(String company, String printerCode, Long agentId, String printerName, String userName) {
        Optional<TagPrinterRoute> cur = routes.findByCompanyNameAndPrinterCode(company, printerCode);
        if (agentId == null || printerName == null || printerName.isBlank()) {
            cur.ifPresent(routes::delete);
            return;
        }
        TagAgent a = agents.findByIdAndCompanyName(agentId, company)
                .orElseThrow(() -> new IllegalArgumentException("This computer is not connected any more."));
        boolean known = printers.findByAgentIdOrderByPrinterNameAsc(a.getId()).stream()
                .anyMatch(p -> p.getPrinterName().equals(printerName));
        if (!known) throw new IllegalArgumentException("This printer was not found on that computer.");
        TagPrinterRoute r = cur.orElseGet(() -> {
            TagPrinterRoute n = new TagPrinterRoute();
            n.setCompanyName(company);
            n.setPrinterCode(printerCode);
            return n;
        });
        r.setAgentId(a.getId());
        r.setPrinterName(printerName);
        r.setUpdatedByName(userName);
        r.setUpdatedAt(LocalDateTime.now());
        routes.save(r);
    }

    static boolean isOnline(TagAgent a) {
        return a.getLastSeenAt() != null && a.getLastSeenAt().isAfter(LocalDateTime.now().minusSeconds(ONLINE_SECONDS));
    }

    // ------------------------------------------------------------------ print jobs

    /**
     * Queues a print of some tags of a batch on the printer chosen for that tag size. Throws IllegalArgumentException
     * with a message the user can read (no printer chosen, computer offline, nothing to print).
     */
    @Transactional
    public TagPrintJob createJob(String company, TagBatchService.BatchDetail d, List<Long> tagIds, List<Integer> copies,
                                 TagDesigns.Printer size, Long userId, String userName) {
        if (size.sheet()) throw new IllegalArgumentException("A4 sheets are printed from the PDF. Direct printing is for label and hang tag sizes.");
        TagPrinterRoute r = routes.findByCompanyNameAndPrinterCode(company, size.code())
                .orElseThrow(() -> new IllegalArgumentException("Choose a printer for " + size.name() + " first."));
        TagAgent a = agents.findByIdAndCompanyName(r.getAgentId(), company)
                .orElseThrow(() -> new IllegalArgumentException("The computer of this printer is not connected any more."));
        if (!isOnline(a)) {
            throw new IllegalArgumentException("The printer agent on " + (a.getComputerName() == null ? "the shop computer" : a.getComputerName())
                    + " is not running. Start it and try again.");
        }
        // same picking rules as the PDF (Qty, or the copies the user set); counted per tag so each picture is fetched once
        Map<Long, Integer> count = new LinkedHashMap<>();
        for (TagBatchService.TagView t : batchService.pick(d, tagIds, copies)) count.merge(t.id(), 1, Integer::sum);
        if (count.isEmpty()) throw new IllegalArgumentException("There are no tags to print.");
        StringBuilder items = new StringBuilder();
        int total = 0;
        for (Map.Entry<Long, Integer> e : count.entrySet()) {
            if (items.length() > 0) items.append(';');
            items.append(e.getKey()).append(':').append(e.getValue());
            total += e.getValue();
        }
        if (items.length() > 7900) throw new IllegalArgumentException("Too many different tags for one print. Print fewer products at a time.");

        TagPrintJob j = new TagPrintJob();
        j.setCompanyName(company);
        j.setAgentId(a.getId());
        j.setBatchId(d.batch().getId());
        j.setBatchNo(d.batch().getBatchNo());
        j.setPrinterCode(size.code());
        j.setPrinterName(r.getPrinterName());
        j.setWidthMm(size.w());
        j.setHeightMm(size.h());
        j.setDpi(DEFAULT_DPI);
        j.setItems(items.toString());
        j.setTotalTags(total);
        j.setCreatedByUserId(userId);
        j.setCreatedByName(userName);
        return jobs.save(j);
    }

    /** route code of the bill printer for one receipt width: BILL80 or BILL58 (kept in the same table as the tag sizes) */
    public static String billCode(String size) { return "BILL" + size; }

    public static boolean isBillCode(String code) { return code != null && code.startsWith("BILL"); }

    /** Queues one receipt (a picture of the bill, made when the agent asks for it) on the shop's bill printer. */
    @Transactional
    public TagPrintJob createBillJob(String company, Long billId, String billNo, String size, int heightMm,
                                     Long userId, String userName) {
        String code = billCode(size);
        TagPrinterRoute r = routes.findByCompanyNameAndPrinterCode(company, code)
                .orElseThrow(() -> new IllegalArgumentException("Choose the bill printer first."));
        TagAgent a = agents.findByIdAndCompanyName(r.getAgentId(), company)
                .orElseThrow(() -> new IllegalArgumentException("The computer of this printer is not connected any more."));
        if (!isOnline(a)) {
            throw new IllegalArgumentException("The printer agent on " + (a.getComputerName() == null ? "the shop computer" : a.getComputerName())
                    + " is not running. Start it and try again.");
        }
        TagPrintJob j = new TagPrintJob();
        j.setCompanyName(company);
        j.setAgentId(a.getId());
        j.setBatchId(billId);
        j.setBatchNo(billNo);
        j.setPrinterCode(code);
        j.setPrinterName(r.getPrinterName());
        j.setWidthMm("58".equals(size) ? 58f : 80f);
        j.setHeightMm((float) heightMm);
        j.setDpi(DEFAULT_DPI);
        j.setItems("1:1");
        j.setTotalTags(1);
        j.setCreatedByUserId(userId);
        j.setCreatedByName(userName);
        return jobs.save(j);
    }

    @Transactional
    public Optional<TagPrintJob> job(String company, Long id) {
        return jobs.findByIdAndCompanyName(id, company).map(j -> {
            String before = j.getStatus();
            expireIfStale(j);
            if (!before.equals(j.getStatus())) jobs.save(j);
            return j;
        });
    }

    /** a job that was never picked up (or a bill that was taken but never answered) is shown as failed */
    private TagPrintJob expireIfStale(TagPrintJob j) {
        LocalDateTime now = LocalDateTime.now();
        if (TagPrintJob.QUEUED.equals(j.getStatus()) && j.getCreatedAt().isBefore(now.minusSeconds(JOB_WAIT_SECONDS))) {
            j.setStatus(TagPrintJob.FAILED);
            j.setMessage("The printer agent did not respond. Check that it is running on the shop computer.");
            j.setDoneAt(now);
        } else if (TagPrintJob.SENT.equals(j.getStatus()) && isBillCode(j.getPrinterCode())
                && j.getSentAt() != null && j.getSentAt().isBefore(now.minusSeconds(BILL_SENT_SECONDS))) {
            j.setStatus(TagPrintJob.FAILED);
            j.setMessage("The shop computer did not say whether the bill printed. Check the printer.");
            j.setDoneAt(now);
        }
        return j;
    }

    /**
     * The next waiting job of this agent, as the small JSON the agent needs, or empty. The job becomes SENT at once,
     * so it is printed only once even if the agent calls twice.
     */
    @Transactional
    public Optional<Map<String, Object>> claimNext(Long agentId) {
        for (TagPrintJob j : jobs.lockQueued(agentId, PageRequest.of(0, 5))) {
            if (expireIfStale(j).getStatus().equals(TagPrintJob.FAILED)) { jobs.save(j); continue; }
            j.setStatus(TagPrintJob.SENT);
            j.setSentAt(LocalDateTime.now());
            jobs.save(j);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId());
            m.put("batchNo", j.getBatchNo());
            m.put("printer", j.getPrinterName());
            m.put("widthMm", j.getWidthMm());
            m.put("heightMm", j.getHeightMm());
            m.put("dpi", j.getDpi());
            m.put("totalTags", j.getTotalTags());
            List<Map<String, Object>> items = new ArrayList<>();
            for (String part : j.getItems().split(";")) {
                String[] kv = part.split(":");
                Map<String, Object> it = new LinkedHashMap<>();
                it.put("tagId", Long.parseLong(kv[0]));
                it.put("copies", Integer.parseInt(kv[1]));
                items.add(it);
            }
            m.put("items", items);
            return Optional.of(m);
        }
        return Optional.empty();
    }

    /** is a job waiting for this agent (does not claim it) */
    @Transactional(readOnly = true)
    public boolean hasQueued(Long agentId) {
        return jobs.existsByAgentIdAndStatus(agentId, TagPrintJob.QUEUED);
    }

    /** the job of this agent, for fetching its tag pictures (only tags that are in the job) */
    @Transactional(readOnly = true)
    public Optional<TagPrintJob> jobOfAgent(Long agentId, Long jobId) {
        return jobs.findByIdAndAgentId(jobId, agentId);
    }

    public static boolean jobHasTag(TagPrintJob j, Long tagId) {
        for (String part : j.getItems().split(";")) {
            if (part.startsWith(tagId + ":")) return true;
        }
        return false;
    }

    @Transactional
    public boolean finish(Long agentId, Long jobId, boolean ok, String message) {
        Optional<TagPrintJob> o = jobs.findByIdAndAgentId(jobId, agentId);
        if (o.isEmpty()) return false;
        TagPrintJob j = o.get();
        if (TagPrintJob.DONE.equals(j.getStatus())) return true;
        j.setStatus(ok ? TagPrintJob.DONE : TagPrintJob.FAILED);
        j.setMessage(clip(message, 300));
        j.setDoneAt(LocalDateTime.now());
        jobs.save(j);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
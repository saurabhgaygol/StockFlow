package com.stockmanagement.printagent;

import com.stockmanagement.service.RetailBillImageService;
import com.stockmanagement.service.TagBatchService;
import com.stockmanagement.service.TagPdfService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the Printer Agent (the small program on the shop computer) calls. Security: TagAgentSecurityConfig.
 * The agent can reach only its own shop's data: its own jobs and the pictures of the tags in those jobs.
 */
@RestController
public class TagAgentController {

    private static final Logger log = LoggerFactory.getLogger(TagAgentController.class);
    private static final long TEN_MIN = 10 * 60_000L;
    private static final int MAX_PAIR_FAILS = 10;

    private final TagAgentService service;
    private final TagAgentHub hub;
    private final TagBatchService batchService;
    private final TagPdfService pdfService;
    private final RetailBillImageService billImages;
    private final Map<String, Deque<Long>> pairFails = new ConcurrentHashMap<>();
    private final boolean requireHttps;

    public TagAgentController(TagAgentService service, TagAgentHub hub, TagBatchService batchService, TagPdfService pdfService,
                              RetailBillImageService billImages,
                              @Value("${app.tag-agent.require-https:false}") boolean requireHttps) {
        this.requireHttps = requireHttps;
        this.service = service;
        this.hub = hub;
        this.batchService = batchService;
        this.pdfService = pdfService;
        this.billImages = billImages;
    }

    public record PrinterBody(String name, Boolean isDefault, String status) {}
    public record PairBody(String code, String computer, String version, List<PrinterBody> printers) {}
    public record PrintersBody(String computer, String version, List<PrinterBody> printers) {}
    public record ResultBody(Boolean ok, String message) {}

    private static List<TagAgentService.PrinterIn> printersOf(List<PrinterBody> in) {
        List<TagAgentService.PrinterIn> out = new ArrayList<>();
        if (in != null) for (PrinterBody p : in) out.add(new TagAgentService.PrinterIn(p.name(), Boolean.TRUE.equals(p.isDefault()), p.status()));
        return out;
    }

    private static TagAgent agent(HttpServletRequest req) {
        return (TagAgent) req.getAttribute(TagAgentAuthFilter.ATTR_AGENT);
    }

    /**
     * The caller's address for the wrong-code limit. Behind a reverse proxy on the same machine (nginx) every request
     * arrives from 127.0.0.1, so then the first X-Forwarded-For address is used. A caller that is not a local proxy
     * cannot choose its own address this way.
     */
    private static String clientIp(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        boolean proxy = remote != null && (remote.equals("127.0.0.1") || remote.equals("0:0:0:0:0:0:0:1") || remote.equals("::1"));
        String xff = req.getHeader("X-Forwarded-For");
        if (proxy && xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 45) return first;
        }
        return remote == null ? "unknown" : remote;
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return ResponseEntity.status(status).body(m);
    }

    /** code from Tag Studio -> the agent's secret token (shown once). Wrong codes are limited per IP. */
    @PostMapping("/api/tag-agent/pair")
    public ResponseEntity<Map<String, Object>> pair(@RequestBody PairBody body, HttpServletRequest req) {
        if (!TagAgentAuthFilter.secureEnough(req, requireHttps)) return error(403, "HTTPS is required.");
        String ip = clientIp(req);
        long now = System.currentTimeMillis();
        // old addresses with no recent wrong codes are forgotten (the list must not grow forever)
        if (pairFails.size() > 1000) {
            pairFails.entrySet().removeIf(e -> {
                Deque<Long> d = e.getValue();
                synchronized (d) {
                    while (!d.isEmpty() && now - d.peekFirst() > TEN_MIN) d.pollFirst();
                    return d.isEmpty();
                }
            });
        }
        Deque<Long> fails = pairFails.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (fails) {
            while (!fails.isEmpty() && now - fails.peekFirst() > TEN_MIN) fails.pollFirst();
            if (fails.size() >= MAX_PAIR_FAILS) return error(429, "Too many wrong codes. Wait 10 minutes and try again.");
        }
        Optional<TagAgentService.Paired> p = service.pair(body.code(), body.computer(), body.version(), printersOf(body.printers()));
        if (p.isEmpty()) {
            synchronized (fails) { fails.addLast(now); }
            return error(400, "This code is wrong or has expired. Make a new one in StockFlow (Add a printer / Connect a computer).");
        }
        log.info("Printer agent paired for company {} from {}", p.get().company(), ip);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", p.get().token());
        m.put("company", p.get().company());
        return ResponseEntity.ok(m);
    }

    /** the agent reports the printers of its computer (at start, and when the list changes) */
    @PostMapping("/api/tag-agent/printers")
    public ResponseEntity<Map<String, Object>> printers(@RequestBody PrintersBody body, HttpServletRequest req) {
        service.reportPrinters(agent(req), body.computer(), body.version(), printersOf(body.printers()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return ResponseEntity.ok(m);
    }

    /** long poll: answers as soon as a job is waiting, or 204 after about 25 s (then the agent asks again) */
    @GetMapping("/api/tag-agent/jobs/next")
    public DeferredResult<ResponseEntity<Map<String, Object>>> next(HttpServletRequest req,
                                                                     @RequestParam(value = "wait", defaultValue = "25") int wait) {
        return hub.await(agent(req).getId(), Math.max(1, Math.min(wait, 30)));
    }

    /** one tag of a job as a picture at the printer's resolution */
    @GetMapping("/api/tag-agent/jobs/{jobId}/tags/{tagId}.png")
    public ResponseEntity<byte[]> tag(HttpServletRequest req, @PathVariable("jobId") Long jobId, @PathVariable("tagId") Long tagId) {
        TagAgent a = agent(req);
        Optional<TagPrintJob> job = service.jobOfAgent(a.getId(), jobId);
        if (job.isEmpty() || !TagPrintJob.SENT.equals(job.get().getStatus()) || !TagAgentService.jobHasTag(job.get(), tagId)) {
            return ResponseEntity.notFound().build();      // pictures are handed out only while the job is being printed
        }
        TagPrintJob j = job.get();
        if (TagAgentService.isBillCode(j.getPrinterCode())) {      // a bill receipt, not a tag
            try {
                byte[] png = billImages.png(j.getCompanyName(), j.getBatchId(), j.getPrinterCode().substring(4), j.getDpi());
                return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(png);
            } catch (IOException | RuntimeException e) {
                log.error("Bill picture failed for job {}", jobId, e);
                return ResponseEntity.internalServerError().build();
            }
        }
        Optional<TagBatchService.BatchDetail> d = batchService.detail(j.getCompanyName(), j.getBatchId());
        if (d.isEmpty()) return ResponseEntity.notFound().build();
        TagBatchService.TagView t = d.get().tags().stream().filter(x -> x.id().equals(tagId)).findFirst().orElse(null);
        if (t == null) return ResponseEntity.notFound().build();
        try {
            byte[] png = pdfService.tagPng(j.getCompanyName(), d.get(), t, j.getPrinterCode(), j.getDpi());
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(png);
        } catch (IOException | RuntimeException e) {
            log.error("Tag picture failed for job {} tag {}", jobId, tagId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /** the agent says how the print went */
    @PostMapping("/api/tag-agent/jobs/{jobId}/result")
    public ResponseEntity<Map<String, Object>> result(HttpServletRequest req, @PathVariable("jobId") Long jobId, @RequestBody ResultBody body) {
        boolean found = service.finish(agent(req).getId(), jobId, Boolean.TRUE.equals(body.ok()), body.message());
        if (!found) return error(404, "Job not found.");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return ResponseEntity.ok(m);
    }
}
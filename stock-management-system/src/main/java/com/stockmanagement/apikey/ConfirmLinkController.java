package com.stockmanagement.apikey;

import com.stockmanagement.aiagent.ActionService;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import java.util.List;
import java.util.Optional;

/**
 * Bahar (ChatGPT / Claude) se aaye add / edit ka Confirm page: /ai-confirm/{link}
 *
 *  - Ye page normal StockFlow login (purani SecurityConfig) ke peeche hai: link khulte hi login maangta hai.
 *  - Link sirf usi user ke liye jiski API key se request aayi thi; doosre user ko "not found".
 *  - 10 minute me expire, ek baar hi chalta hai.
 *  - Confirm dabane par maujooda ActionService.confirm(...) chalta hai: wahi permission dobara check,
 *    wahi VendorService / StockInwardService / StockOutwardService ke rules, wahi audit. Koi naya save logic nahi.
 */
@Controller
public class ConfirmLinkController {

    private final ApiKeyService keys;
    private final ActionService actions;
    private final AuditLogService audit;

    public ConfirmLinkController(ApiKeyService keys, ActionService actions, AuditLogService audit) {
        this.keys = keys;
        this.actions = actions;
        this.audit = audit;
    }

    @GetMapping("/ai-confirm/{link}")
    public String view(@PathVariable String link, @AuthenticationPrincipal CustomUserDetails me, Model model) {
        Optional<ApiKeyService.PendingConfirm> c = keys.peekConfirm(link, me.getUserId());
        model.addAttribute("link", link);
        if (c.isEmpty()) {
            model.addAttribute("state", "MISSING");
        } else {
            model.addAttribute("state", "PENDING");
            model.addAttribute("p", c.get().view());
            model.addAttribute("keyName", c.get().keyName());
            long left = Math.max(1, (c.get().expiresAtMs() - System.currentTimeMillis()) / 60000);
            model.addAttribute("minutesLeft", left);
        }
        return "ai/ai-confirm";
    }

    @PostMapping("/ai-confirm/{link}")
    public String confirm(@PathVariable String link, @AuthenticationPrincipal CustomUserDetails me,
                          HttpServletRequest req, Model model) {
        Optional<ApiKeyService.PendingConfirm> c = keys.takeConfirm(link, me.getUserId());
        model.addAttribute("link", link);
        if (c.isEmpty()) {
            model.addAttribute("state", "MISSING");
            return "ai/ai-confirm";
        }
        ActionService.ConfirmResult r = actions.confirm(me, c.get().proposalToken(), audit.getClientIp(req));
        model.addAttribute("state", r.ok() ? "DONE" : "FAILED");
        model.addAttribute("resultMessage", r.message());
        model.addAttribute("resultLines", r.lines() == null ? List.of() : r.lines());
        return "ai/ai-confirm";
    }

    @PostMapping("/ai-confirm/{link}/cancel")
    public String cancel(@PathVariable String link, @AuthenticationPrincipal CustomUserDetails me, Model model) {
        keys.takeConfirm(link, me.getUserId()).ifPresent(c -> actions.cancel(me.getUserId(), c.proposalToken()));
        model.addAttribute("link", link);
        model.addAttribute("state", "CANCELLED");
        return "ai/ai-confirm";
    }
}
package com.stockmanagement.apikey;

import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Settings > API Keys tab (AI Connections tab ke bagal me).
 * Har login user ye page dekh sakta hai; key sirf API_KEY_MANAGE / SUPER_ADMIN wala bana sakta hai.
 * Saare niyam ApiKeyService me hain, yahan sirf request / response.
 *
 *  GET  /settings/api-keys              -> page
 *  POST /settings/api-keys/create       -> nayi key (poori key sirf ek baar, flash se)
 *  POST /settings/api-keys/{id}/delete  -> key delete (usi second se band)
 *  POST /settings/api-keys/{id}/expiry  -> expiry date badalna / hatana
 */
@Controller
@RequestMapping("/settings/api-keys")
public class ApiKeyController {

    private static final String PAGE = "redirect:/settings/api-keys";

    private final ApiKeyService keys;
    private final AuditLogService audit;
    private final boolean externalEnabled;

    public ApiKeyController(ApiKeyService keys, AuditLogService audit,
                            @Value("${api.external.enabled:false}") boolean externalEnabled) {
        this.keys = keys;
        this.audit = audit;
        this.externalEnabled = externalEnabled;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal CustomUserDetails me, Model model) {
        boolean sa = keys.isSuperAdmin(me);
        LocalDateTime now = LocalDateTime.now();
        ApiKey mine = keys.myKey(me.getUserId()).orElse(null);
        model.addAttribute("isSuperAdmin", sa);
        model.addAttribute("canManage", keys.canManage(me));
        model.addAttribute("myKey", mine);
        model.addAttribute("myKeyExpired", mine != null && mine.isExpired(now));
        model.addAttribute("externalEnabled", externalEnabled);
        model.addAttribute("today", LocalDate.now().toString());
        model.addAttribute("askUrl", ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/ask").toUriString());
        model.addAttribute("meUrl", ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/me").toUriString());
        model.addAttribute("curlExample", "curl -X POST " + model.getAttribute("askUrl") + " \\\n"
                + "  -H \"Authorization: Bearer sf_live_YOUR_KEY\" \\\n"
                + "  -H \"Content-Type: application/json\" \\\n"
                + "  -d '{\"message\": \"How many vendors do we have?\"}'");
        if (sa) {
            List<ApiKey> all = keys.allKeys(me);
            model.addAttribute("allKeys", all);
            model.addAttribute("userNames", keys.userNames(all));
            model.addAttribute("expiredIds", all.stream().filter(k -> k.isExpired(now)).map(ApiKey::getId)
                    .collect(java.util.stream.Collectors.toSet()));
        }
        return "ai/api-keys";
    }

    @PostMapping("/create")
    public String create(@AuthenticationPrincipal CustomUserDetails me, HttpServletRequest req, RedirectAttributes ra,
                         @RequestParam("name") String name,
                         @RequestParam(value = "expiryDate", required = false) String expiryDate,
                         @RequestParam(value = "canWrite", required = false) String canWrite,
                         @RequestParam(value = "allowedIps", required = false) String allowedIps) {
        try {
            ApiKeyService.Created c = keys.create(me, name, parseDate(expiryDate), "on".equals(canWrite) || "true".equals(canWrite),
                    allowedIps, audit.getClientIp(req));
            ra.addFlashAttribute("newKey", c.plainKey());   // sirf agle ek page load par, phir kabhi nahi
            ra.addFlashAttribute("successMessage", "API key \"" + c.key().getName() + "\" created. Copy it now - it will not be shown again.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails me,
                         HttpServletRequest req, RedirectAttributes ra) {
        try {
            ApiKey k = keys.delete(me, id, audit.getClientIp(req));
            ra.addFlashAttribute("successMessage", "API key \"" + k.getName() + "\" deleted. It stopped working immediately.");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/{id}/expiry")
    public String expiry(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails me,
                         HttpServletRequest req, RedirectAttributes ra,
                         @RequestParam(value = "expiryDate", required = false) String expiryDate) {
        try {
            LocalDate d = parseDate(expiryDate);
            keys.changeExpiry(me, id, d, audit.getClientIp(req));
            ra.addFlashAttribute("successMessage", d == null ? "Expiry removed. The key now never expires."
                    : "Expiry set to " + d + ".");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Enter a valid expiry date.");
        }
    }
}
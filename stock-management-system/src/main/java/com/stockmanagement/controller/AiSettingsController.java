package com.stockmanagement.controller;

import com.stockmanagement.service.AiAccessService;
import com.stockmanagement.service.AiConnectionService;
import com.stockmanagement.service.AiConnectionService.AccessInfo;
import com.stockmanagement.service.AiConnectionService.SaveForm;
import com.stockmanagement.service.AiProvider;
import com.stockmanagement.service.AiProviderException;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.entity.AiConnection;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Settings > AI Connections (profile menu se khulta hai).
 * Har login user apni personal connection bana sakta hai (company policy allow kare to);
 * company-wide connection super admin banata hai, aur access Use / Share / Manage levels se diya jaata hai.
 * Access ke rules AiConnectionService / AiAccessService me enforce hote hain, yahan sirf request/response hai.
 */
@Controller
@RequestMapping("/settings/ai-connections")
public class AiSettingsController {

    private static final String PAGE = "redirect:/settings/ai-connections";

    private final AiConnectionService service;
    private final AuditLogService auditLogService;
    private final AiAccessService accessService;

    public AiSettingsController(AiConnectionService service, AuditLogService auditLogService,
                                AiAccessService accessService) {
        this.service = service;
        this.auditLogService = auditLogService;
        this.accessService = accessService;
    }

    /* ================= PAGE ================= */

    @GetMapping
    public String page(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        boolean sa = service.isSuperAdmin(user);
        model.addAttribute("isSuperAdmin", sa);
        model.addAttribute("canPersonal", service.canAddPersonal(user));
        model.addAttribute("cryptoReady", service.isCryptoReady());
        model.addAttribute("providers", Arrays.asList(AiProvider.values()));
        model.addAttribute("connections", service.listFor(user));
        model.addAttribute("myCompany", user.getUser().getCompanyName());
        if (sa) {
            model.addAttribute("companies", service.companies());
            model.addAttribute("policies", service.policies());
        }
        return "ai/ai-connections";
    }

    /* ================= JSON (popups) ================= */

    @GetMapping("/{id}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> detail(@PathVariable Long id,
                                                      @AuthenticationPrincipal CustomUserDetails user) {
        return json(() -> service.detail(user, id));
    }

    @PostMapping("/test")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> test(@AuthenticationPrincipal CustomUserDetails user,
                                                    @RequestParam("provider") String provider,
                                                    @RequestParam(value = "baseUrl", required = false) String baseUrl,
                                                    @RequestParam(value = "apiKey", required = false) String apiKey,
                                                    @RequestParam(value = "connectionId", required = false) Long connectionId) {
        return json(() -> {
            List<String> models = service.testAndFetchModels(user, provider, baseUrl, apiKey, connectionId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("models", models);
            return m;
        });
    }

    @GetMapping("/{id}/access")
    @ResponseBody
    public ResponseEntity<AccessInfo> accessInfo(@PathVariable Long id,
                                                 @AuthenticationPrincipal CustomUserDetails user) {
        try {
            return ResponseEntity.ok(service.accessInfo(user, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    /* ================= LEVELS & SHARING (Use / Share / Manage) ================= */

    @GetMapping("/{id}/grants")
    @ResponseBody
    public ResponseEntity<?> grants(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        try {
            return ResponseEntity.ok(accessService.view(user, id));
        } catch (IllegalArgumentException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
        }
    }

    @PostMapping("/{id}/grant")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> grant(@PathVariable Long id,
                                                     @AuthenticationPrincipal CustomUserDetails user,
                                                     HttpServletRequest request,
                                                     @RequestParam(value = "targetType", defaultValue = "USER") String targetType,
                                                     @RequestParam(value = "targetIds", required = false) List<Long> targetIds,
                                                     @RequestParam(value = "level", defaultValue = "USE") String level,
                                                     @RequestParam(value = "expiresOn", required = false) String expiresOn,
                                                     @RequestParam(value = "dailyLimit", required = false) Integer dailyLimit) {
        return json(() -> {
            String summary = accessService.grant(user, id, targetType, targetIds, level, expiresOn, dailyLimit);
            audit(user, request, "UPDATE", "AI connection " + id + " access: " + targetType + " "
                    + (targetIds == null ? "[]" : targetIds) + " -> " + summary, id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("message", summary);
            return m;
        });
    }

    @PostMapping("/{id}/revoke")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> revoke(@PathVariable Long id,
                                                      @AuthenticationPrincipal CustomUserDetails user,
                                                      HttpServletRequest request,
                                                      @RequestParam("rowId") Long rowId) {
        return json(() -> {
            String summary = accessService.revoke(user, id, rowId);
            audit(user, request, "UPDATE", "AI connection " + id + " access entry " + rowId + " removed", id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("message", summary);
            return m;
        });
    }

    /* ================= ACTIONS ================= */

    @PostMapping("/save")
    public String save(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                       RedirectAttributes ra,
                       @RequestParam(value = "id", required = false) Long id,
                       @RequestParam(value = "scope", required = false) String scope,
                       @RequestParam(value = "companyName", required = false) String companyName,
                       @RequestParam("name") String name,
                       @RequestParam("provider") String provider,
                       @RequestParam(value = "baseUrl", required = false) String baseUrl,
                       @RequestParam(value = "apiKey", required = false) String apiKey,
                       @RequestParam(value = "modelIds", required = false) List<String> modelIds,
                       @RequestParam(value = "defaultModel", required = false) String defaultModel) {
        try {
            AiConnection c = service.save(user, new SaveForm(id, scope, companyName, name, provider, baseUrl,
                    apiKey, modelIds, defaultModel));
            ra.addFlashAttribute("successMessage",
                    id == null ? "Connection \"" + c.getName() + "\" added." : "Connection updated.");
            audit(user, request, id == null ? "CREATE" : "UPDATE",
                    (id == null ? "Added " : "Updated ") + c.getScope() + " AI connection '" + c.getName()
                            + "' (" + c.getProvider() + ")", c.getId());
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request, RedirectAttributes ra) {
        try {
            AiConnection c = service.toggle(user, id);
            ra.addFlashAttribute("successMessage", "Connection is now " + c.getStatus().toLowerCase() + ".");
            audit(user, request, "UPDATE", "AI connection '" + c.getName() + "' set to " + c.getStatus(), id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request, RedirectAttributes ra) {
        try {
            AiConnection c = service.delete(user, id);
            ra.addFlashAttribute("successMessage", "Connection \"" + c.getName() + "\" deleted.");
            audit(user, request, "DELETE", "Deleted AI connection '" + c.getName() + "'", id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/{id}/access")
    public String setAccess(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                            HttpServletRequest request, RedirectAttributes ra,
                            @RequestParam("mode") String mode,
                            @RequestParam(value = "userIds", required = false) List<Long> userIds,
                            @RequestParam(value = "roleIds", required = false) List<Long> roleIds) {
        try {
            String summary = service.setAccess(user, id, mode, userIds, roleIds);
            ra.addFlashAttribute("successMessage", "Access updated: " + summary + ".");
            audit(user, request, "UPDATE", "AI connection " + id + " access set to " + summary, id);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    @PostMapping("/policy")
    public String policy(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                         RedirectAttributes ra,
                         @RequestParam("company") String company,
                         @RequestParam(value = "allowPersonal", defaultValue = "false") boolean allowPersonal) {
        try {
            service.setPersonalPolicy(user, company, allowPersonal);
            ra.addFlashAttribute("successMessage", "Policy saved for " + company + ".");
            audit(user, request, "UPDATE", "Personal AI keys " + (allowPersonal ? "allowed" : "blocked")
                    + " for " + company, null);
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        return PAGE;
    }

    /* ================= helpers ================= */

    private ResponseEntity<Map<String, Object>> json(Supplier<Map<String, Object>> work) {
        try {
            return ResponseEntity.ok(work.get());
        } catch (AiProviderException | IllegalArgumentException | IllegalStateException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(m);
        }
    }

    private void audit(CustomUserDetails user, HttpServletRequest request, String action, String text, Long refId) {
        auditLogService.log(user.getUserId(), user.getUsername(), action, "Settings", "AI Connections",
                text, auditLogService.getClientIp(request), refId, "AI_CONNECTION");
    }
}
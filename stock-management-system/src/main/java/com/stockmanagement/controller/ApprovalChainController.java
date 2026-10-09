package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.repository.RoleRepository;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.ApprovalChainService;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Settings screen: admin tay karta hai ki Stock Outward kaun approve karega aur kis order mein.
 * Badlav sirf naye banne wali requests pe lagta hai.
 */
@Controller
@RequestMapping("/settings/approval-chain")
@RequirePermission("APPROVAL_CHAIN_MANAGE")
public class ApprovalChainController {

    private final ApprovalChainService chainService;
    private final UserPermissionService permissionService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final AuditLogService auditLogService;

    public ApprovalChainController(ApprovalChainService chainService,
                                   UserPermissionService permissionService,
                                   UserRepository userRepository,
                                   RoleRepository roleRepository,
                                   AuditLogService auditLogService) {
        this.chainService = chainService;
        this.permissionService = permissionService;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal CustomUserDetails user,
                       @RequestParam(value = "company", required = false) String company,
                       Model model) {
        Set<String> codes = codes(user);
        requireManage(codes);
        boolean superAdmin = codes.contains("SUPER_ADMIN");
        String target = targetCompany(user, company, superAdmin);

        model.addAttribute("isSuperAdmin", superAdmin);
        model.addAttribute("companies", superAdmin ? userRepository.findDistinctCompanyNames() : null);
        model.addAttribute("selectedCompany", target);
        model.addAttribute("levels", chainService.getLevelViews(target));
        model.addAttribute("users", userRepository.findByCompanyNameAndStatusOrderByFirstNameAsc(target, "ACTIVE"));
        model.addAttribute("roles", roleRepository.findByStatusOrderByRoleNameAsc("ACTIVE"));
        return "settings/approval-chain";
    }

    @PostMapping("/add")
    public String add(@AuthenticationPrincipal CustomUserDetails user, HttpServletRequest request,
                      @RequestParam(value = "company", required = false) String company,
                      @RequestParam("levelName") String levelName,
                      @RequestParam("approverType") String approverType,
                      @RequestParam(value = "approverUserId", required = false) Long approverUserId,
                      @RequestParam(value = "approverRoleId", required = false) Long approverRoleId,
                      RedirectAttributes ra) {
        return run(user, company, ra, request, "Approval level added.", "Added approval level \"" + levelName + "\"",
                target -> chainService.addLevel(target, levelName, approverType, approverUserId,
                        approverRoleId, user.getUsername()));
    }

    @PostMapping("/update/{id}")
    public String update(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request,
                         @RequestParam(value = "company", required = false) String company,
                         @RequestParam("levelName") String levelName,
                         @RequestParam("approverType") String approverType,
                         @RequestParam(value = "approverUserId", required = false) Long approverUserId,
                         @RequestParam(value = "approverRoleId", required = false) Long approverRoleId,
                         RedirectAttributes ra) {
        return run(user, company, ra, request, "Approval level updated.", "Updated approval level " + id,
                target -> chainService.updateLevel(id, target, levelName, approverType,
                        approverUserId, approverRoleId));
    }

    @PostMapping("/delete/{id}")
    public String delete(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                         HttpServletRequest request,
                         @RequestParam(value = "company", required = false) String company,
                         RedirectAttributes ra) {
        return run(user, company, ra, request, "Approval level removed.", "Removed approval level " + id,
                target -> chainService.deleteLevel(id, target));
    }

    @PostMapping("/move/{id}")
    public String move(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user,
                       HttpServletRequest request,
                       @RequestParam(value = "company", required = false) String company,
                       @RequestParam("direction") String direction,
                       RedirectAttributes ra) {
        return run(user, company, ra, request, "Order updated.", "Moved approval level " + id + " " + direction,
                target -> chainService.move(id, target, direction));
    }

    // ------------------------------------------------------------------

    private String run(CustomUserDetails user, String company, RedirectAttributes ra,
                       HttpServletRequest request, String okMessage, String auditText,
                       java.util.function.Consumer<String> action) {
        Set<String> codes = codes(user);
        requireManage(codes);
        String target = targetCompany(user, company, codes.contains("SUPER_ADMIN"));
        try {
            action.accept(target);
            ra.addFlashAttribute("successMessage", okMessage);
            auditLogService.log(user.getUserId(), user.getUsername(), "UPDATE", "Settings", "Approval Chain",
                    auditText + " (" + target + ")", auditLogService.getClientIp(request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            ra.addFlashAttribute("errorMessage", e.getMessage());
        }
        ra.addAttribute("company", target);
        return "redirect:/settings/approval-chain";
    }

    private String targetCompany(CustomUserDetails user, String requested, boolean superAdmin) {
        if (superAdmin && requested != null && !requested.isBlank()) {
            return requested;
        }
        return user.getUser().getCompanyName();
    }

    private Set<String> codes(CustomUserDetails user) {
        return permissionService.getAllowedPermissionDetails(user.getUserId()).stream()
                .map(p -> p.getPermissionCode()).collect(Collectors.toSet());
    }

    private void requireManage(Set<String> codes) {
        if (!codes.contains("APPROVAL_CHAIN_MANAGE") && !codes.contains("SUPER_ADMIN")) {
            throw new AccessDeniedException("Approval chain management is not enabled for your role.");
        }
    }
}
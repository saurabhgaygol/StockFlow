package com.stockmanagement.controller;

import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Apna password badalna. Sirf login chahiye (koi permission nahi), kyunki har user ko ye karna aana chahiye.
 * - /change-password      : full page (forced change ke time)
 * - /change-password/api  : popup (topbar) ke liye JSON
 */
@Controller
public class PasswordChangeController {

    private static final int MIN_LENGTH = 8;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public PasswordChangeController(UserRepository userRepository,
                                    PasswordEncoder passwordEncoder,
                                    AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/change-password")
    public String page(@AuthenticationPrincipal CustomUserDetails user, Model model) {
        model.addAttribute("forced", isForced(user));
        return "change-password";
    }

    @PostMapping("/change-password")
    @Transactional
    public String change(@AuthenticationPrincipal CustomUserDetails user,
                         @RequestParam("currentPassword") String currentPassword,
                         @RequestParam("newPassword") String newPassword,
                         @RequestParam("confirmPassword") String confirmPassword,
                         HttpServletRequest request,
                         HttpServletResponse response,
                         Model model) {

        UserTable dbUser = userRepository.findById(user.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        String error = validate(dbUser, currentPassword, newPassword, confirmPassword);
        if (error != null) {
            model.addAttribute("forced", Boolean.TRUE.equals(dbUser.getForcePasswordChange()));
            model.addAttribute("error", error);
            return "change-password";
        }

        applyChange(dbUser, newPassword, request, response);
        return "redirect:/login?passwordChanged=true";
    }

    /** Popup se call hota hai (fetch). JSON: {ok:true} ya {ok:false, message:"..."} */
    @PostMapping("/change-password/api")
    @Transactional
    public ResponseEntity<Map<String, Object>> changeApi(@AuthenticationPrincipal CustomUserDetails user,
                                                         @RequestParam("currentPassword") String currentPassword,
                                                         @RequestParam("newPassword") String newPassword,
                                                         @RequestParam("confirmPassword") String confirmPassword,
                                                         HttpServletRequest request,
                                                         HttpServletResponse response) {

        UserTable dbUser = userRepository.findById(user.getUserId())
                .orElseThrow(() -> new IllegalStateException("User not found"));

        String error = validate(dbUser, currentPassword, newPassword, confirmPassword);
        if (error != null) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", error));
        }

        applyChange(dbUser, newPassword, request, response);
        return ResponseEntity.ok(Map.of("ok", true, "redirect", "/login?passwordChanged=true"));
    }

    private void applyChange(UserTable dbUser, String newPassword,
                             HttpServletRequest request, HttpServletResponse response) {
        dbUser.setPassword(passwordEncoder.encode(newPassword));
        dbUser.setForcePasswordChange(false);
        dbUser.setUpdatedBy(dbUser.getUsername());
        dbUser.setUpdatedAt(LocalDateTime.now());
        userRepository.save(dbUser);

        try {
            auditLogService.log(dbUser.getId(), dbUser.getUsername(), "UPDATE", "AUTHENTICATION",
                    "PASSWORD_CHANGE", "User changed own password", request);
        } catch (Exception ignored) {
            // audit fail hone se password change nahi rukna chahiye
        }

        // Purana session khatam; naye password se dobara login
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(request, response, auth);
    }

    private String validate(UserTable dbUser, String current, String next, String confirm) {
        if (!passwordEncoder.matches(current, dbUser.getPassword())) {
            return "Current password is incorrect.";
        }
        if (next == null || next.length() < MIN_LENGTH) {
            return "New password must be at least " + MIN_LENGTH + " characters.";
        }
        if (!next.matches(".*[A-Za-z].*") || !next.matches(".*\\d.*")) {
            return "New password must contain at least one letter and one number.";
        }
        if (next.equals(current)) {
            return "New password must be different from the current password.";
        }
        if (!next.equals(confirm)) {
            return "New password and confirmation do not match.";
        }
        return null;
    }

    private boolean isForced(CustomUserDetails user) {
        return userRepository.findById(user.getUserId())
                .map(u -> Boolean.TRUE.equals(u.getForcePasswordChange()))
                .orElse(false);
    }
}
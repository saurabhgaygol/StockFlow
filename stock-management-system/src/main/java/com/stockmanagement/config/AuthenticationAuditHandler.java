package com.stockmanagement.config;



import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class AuthenticationAuditHandler implements AuthenticationSuccessHandler, LogoutSuccessHandler {

    private final AuditLogService auditLogService;

    public AuthenticationAuditHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    // =========================
    // LOGIN SUCCESS
    // =========================

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException, ServletException {

        if (authentication != null) {

            Object principal = authentication.getPrincipal();

            if (principal instanceof CustomUserDetails userDetails) {

                Long userId = userDetails.getUserId();
                String userName=userDetails.getUsername();

                auditLogService.log(
                        userId,
                        userName,
                        "LOGIN",
                        "AUTHENTICATION",
                        "LOGIN",
                        "User logged in successfully",
                        request
                );
            }
        }

        response.sendRedirect("/dashboard");
    }

    // =========================
    // LOGOUT SUCCESS
    // =========================

    @Override
    public void onLogoutSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication)
            throws IOException, ServletException {

        if (authentication != null) {

            Object principal = authentication.getPrincipal();

            if (principal instanceof CustomUserDetails userDetails) {

                Long userId = userDetails.getUserId();
                String userName=userDetails.getUsername();

                auditLogService.log(
                        userId,
                        userName,
                        "LOGOUT",
                        "AUTHENTICATION",
                        "LOGOUT",
                        "User logged out successfully",
                        request
                );
            }
        }

        response.sendRedirect("/login?logout=true");
    }
}


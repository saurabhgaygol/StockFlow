package com.stockmanagement.config;

import com.stockmanagement.service.LoginAttemptService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Locked username ka login POST authenticate hone se pehle hi rok deta hai
 * (isse lock ke dauran sahi password bhi kaam nahi karta).
 */
public class LoginLockoutFilter extends OncePerRequestFilter {

    private final LoginAttemptService loginAttemptService;

    public LoginLockoutFilter(LoginAttemptService loginAttemptService) {
        this.loginAttemptService = loginAttemptService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/login".equals(request.getServletPath())
                && loginAttemptService.isLocked(request.getParameter("username"))) {

            response.sendRedirect(request.getContextPath() + "/login?locked=true");
            return;
        }

        chain.doFilter(request, response);
    }
}
package com.stockmanagement.config;

import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.CustomUserDetails;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * forcePasswordChange=true wala user /change-password ke alawa kisi page par nahi ja sakta.
 * Flag har request par DB se padha jata hai, isliye session me purani value atakne ka darr nahi.
 */
public class ForcePasswordChangeFilter extends OncePerRequestFilter {

    public static final String CHANGE_PATH = "/change-password";

    private final UserRepository userRepository;

    public ForcePasswordChangeFilter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        if (isAllowedPath(request.getServletPath())) {
            chain.doFilter(request, response);
            return;
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails user) {
            boolean mustChange = userRepository.findById(user.getUserId())
                    .map(u -> Boolean.TRUE.equals(u.getForcePasswordChange()))
                    .orElse(false);

            if (mustChange) {
                if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
                    response.setHeader("HX-Redirect", CHANGE_PATH);
                    response.setStatus(HttpServletResponse.SC_OK);
                } else {
                    response.sendRedirect(request.getContextPath() + CHANGE_PATH);
                }
                return;
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isAllowedPath(String path) {
        return path.equals(CHANGE_PATH)
                || path.equals("/login")
                || path.equals("/logout")
                || path.equals("/error")
                || path.startsWith("/css/")
                || path.startsWith("/js/")
                || path.startsWith("/images/");
    }
}
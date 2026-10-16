package com.stockmanagement.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * AI-report sandbox worker ko "network band" policy ke saath serve karta hai.
 * Is policy ke saath worker kahin connect nahi kar sakta (fetch/XHR/WebSocket/import sab block),
 * isliye AI ka likha code data bahar nahi bhej sakta. Browser JS bhi is header ko check karta hai.
 */
@Component
public class SandboxWorkerHeaderFilter extends OncePerRequestFilter {

    private static final String PATH = "/js/ai-sandbox-worker.js";
    static final String POLICY = "default-src 'none'; script-src 'self' 'unsafe-eval'; connect-src 'none'; worker-src 'none'; img-src 'none'";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().endsWith(PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", POLICY);
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
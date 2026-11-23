package com.stockmanagement.printagent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Guards /api/tag-agent/** (not a @Component on purpose: it runs only in TagAgentSecurityConfig's chain).
 * Every call except pairing needs "Authorization: Bearer sfa_..." of a paired agent. The agent found is put in a
 * request attribute for the controller. The agent token works nowhere else in the app.
 */
public class TagAgentAuthFilter extends OncePerRequestFilter {

    public static final String ATTR_AGENT = "stockflow.tagAgent";

    private final TagAgentService service;
    private final boolean requireHttps;

    public TagAgentAuthFilter(TagAgentService service, boolean requireHttps) {
        this.service = service;
        this.requireHttps = requireHttps;
    }

    /** true when the call came over HTTPS (directly, or through a proxy that says so), or when HTTPS is not required */
    public static boolean secureEnough(HttpServletRequest req, boolean requireHttps) {
        if (!requireHttps) return true;
        String h = req.getHeader("X-Forwarded-Proto");
        return req.isSecure() || (h != null && h.trim().equalsIgnoreCase("https"));
    }

    /** the long-poll answer comes in an async dispatch; check again there so the context is set for it too */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().endsWith("/api/tag-agent/pair");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (!secureEnough(req, requireHttps)) {
            res.setStatus(403);
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"HTTPS is required.\"}");
            return;
        }
        res.setHeader("Cache-Control", "no-store");
        TagAgent agent = (TagAgent) req.getAttribute(ATTR_AGENT);
        if (agent == null) {
            String h = req.getHeader("Authorization");
            String token = h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
            Optional<TagAgent> a = service.authenticate(token);
            if (a.isEmpty()) {
                res.setStatus(401);
                res.setContentType("application/json");
                res.getWriter().write("{\"error\":\"Unauthorized.\"}");
                return;
            }
            agent = a.get();
            req.setAttribute(ATTR_AGENT, agent);
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tag-agent:" + agent.getId(), null, List.of(new SimpleGrantedAuthority("ROLE_TAG_AGENT"))));
        chain.doFilter(req, res);
    }
}
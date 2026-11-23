package com.stockmanagement.printagent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Separate security chain for the Printer Agent (/api/tag-agent/**): no login form, no session, no CSRF cookie;
 * every call carries the agent's own token. All other URLs keep using the normal login chain, unchanged.
 * Set app.tag-agent.require-https=true in production: the agent token is then refused over plain HTTP.
 */
@Configuration
public class TagAgentSecurityConfig {

    @Bean
    @Order(2)
    public SecurityFilterChain tagAgentFilterChain(HttpSecurity http, TagAgentService service,
                                                   @Value("${app.tag-agent.require-https:false}") boolean requireHttps) throws Exception {
        http
            .securityMatcher("/api/tag-agent/**")
            .csrf(c -> c.disable())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .requestCache(rc -> rc.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/tag-agent/pair").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Unauthorized.\"}");
                })
                .accessDeniedHandler((req, res, ex) -> {
                    res.setStatus(403);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Forbidden.\"}");
                }))
            .addFilterBefore(new TagAgentAuthFilter(service, requireHttps), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
package com.stockmanagement.apikey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Bahar ke darwaze (/api/v1/**) ki ALAG security chain.
 * Purani SecurityConfig (login, form, CSRF, baaki saare pages) bilkul nahi badli; ye chain sirf /api/v1/** sambhalti hai
 * aur @Order(1) ki wajah se un URLs par pehle lagti hai. Baaki har URL purani chain par hi jaata hai.
 *
 *  - Login form / session / cookie nahi: har request apni API key se (ApiKeyAuthFilter).
 *  - CSRF ki zarurat nahi (cookie hi nahi hai, browser se chori-chhupe request ka khatra nahi).
 *  - Key ke bina koi bhi /api/v1 URL nahi khulta.
 *  - Ye key StockFlow ke web pages, /api/ai/** ya kisi aur URL par kaam nahi karti (wahan purani login chain hai).
 *
 * Settings (application.properties):
 *  api.external.enabled           = poora bahari darwaza on/off (default OFF)
 *  api.external.require-https     = HTTPS zaruri (default true; localhost chhod kar)
 *  api.external.trust-proxy       = nginx/load-balancer ke peeche ho to true (X-Forwarded-For / -Proto maanega)
 *  api.external.per-key-per-minute, per-ip-per-minute, max-failed-per-ip = limits
 */
@Configuration
public class ApiKeySecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain apiKeyFilterChain(HttpSecurity http, ApiKeyService keys,
                                                 @Value("${api.external.enabled:false}") boolean enabled,
                                                 @Value("${api.external.require-https:true}") boolean requireHttps,
                                                 @Value("${api.external.trust-proxy:false}") boolean trustProxy,
                                                 @Value("${api.external.per-key-per-minute:30}") int perKey,
                                                 @Value("${api.external.per-ip-per-minute:60}") int perIp,
                                                 @Value("${api.external.max-failed-per-ip:10}") int maxFails) throws Exception {

        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(keys, enabled, requireHttps, trustProxy, perKey, perIp, maxFails);

        http
            .securityMatcher("/api/v1/**")
            .csrf(c -> c.disable())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .requestCache(rc -> rc.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.anyRequest().authenticated())
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
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
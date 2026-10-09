package com.stockmanagement.config;

import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.LoginAttemptService;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class SecurityConfig {

    private final UserDetailsService userDetailsService;
    private final AuthenticationAuditHandler authenticationAuditHandler;
    private final UserRepository userRepository;
    private final LoginAttemptService loginAttemptService;

    public SecurityConfig(
            UserDetailsService userDetailsService,
            AuthenticationAuditHandler authenticationAuditHandler,
            UserRepository userRepository,
            LoginAttemptService loginAttemptService) {

        this.userDetailsService = userDetailsService;
        this.authenticationAuditHandler = authenticationAuditHandler;
        this.userRepository = userRepository;
        this.loginAttemptService = loginAttemptService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
            .userDetailsService(userDetailsService)

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/login",
                    "/css/**",
                    "/js/**",
                    "/images/**"
                ).permitAll()
                .anyRequest().authenticated()
            )

            // Referrer me poora URL dusri site ko leak na ho (X-Frame-Options/nosniff/no-cache Spring default deta hai)
            .headers(h -> h
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
            )

            // Locked username ka login attempt authenticate hone se pehle hi band
            .addFilterBefore(new LoginLockoutFilter(loginAttemptService), UsernamePasswordAuthenticationFilter.class)

            // forcePasswordChange=true wale user ko sirf /change-password tak access
            .addFilterBefore(new ForcePasswordChangeFilter(userRepository), AuthorizationFilter.class)

            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(authenticationAuditHandler)
                .failureUrl("/login?error=true")
                .permitAll()
            )

            .logout(logout -> logout
                .logoutSuccessHandler(authenticationAuditHandler)
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            );

        return http.build();
    }
}
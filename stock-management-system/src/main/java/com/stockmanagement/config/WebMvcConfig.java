package com.stockmanagement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final NotificationReadInterceptor notificationReadInterceptor;
    private final PermissionInterceptor permissionInterceptor;

    public WebMvcConfig(NotificationReadInterceptor notificationReadInterceptor,
                        PermissionInterceptor permissionInterceptor) {
        this.notificationReadInterceptor = notificationReadInterceptor;
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .order(0)
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/error",
                        PermissionInterceptor.DENIED_PATH, "/login", "/ws/**");

        registry.addInterceptor(notificationReadInterceptor)
                .order(1)
                .excludePathPatterns("/api/**", "/ws/**", "/css/**", "/js/**", "/images/**", "/error");
    }
}
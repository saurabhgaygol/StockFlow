package com.stockmanagement.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final NotificationReadInterceptor notificationReadInterceptor;

    public WebMvcConfig(NotificationReadInterceptor notificationReadInterceptor) {
        this.notificationReadInterceptor = notificationReadInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(notificationReadInterceptor)
                .excludePathPatterns("/api/**", "/ws/**", "/css/**", "/js/**", "/images/**", "/error");
    }
}
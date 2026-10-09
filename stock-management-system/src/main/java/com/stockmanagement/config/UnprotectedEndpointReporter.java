package com.stockmanagement.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Map;

/**
 * Startup pe un endpoints ki list WARN me dikhata hai jin pe @RequirePermission nahi hai,
 * taaki naya endpoint banake annotation lagana bhoolne par turant pata chale.
 */
@Component
public class UnprotectedEndpointReporter {

    private static final Logger log = LoggerFactory.getLogger(UnprotectedEndpointReporter.class);

    private final RequestMappingHandlerMapping mapping;

    public UnprotectedEndpointReporter(RequestMappingHandlerMapping requestMappingHandlerMapping) {
        this.mapping = requestMappingHandlerMapping;
    }

    @EventListener(ContextRefreshedEvent.class)
    public void report() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            HandlerMethod hm = e.getValue();
            if (!hm.getBeanType().getName().startsWith("com.stockmanagement.controller")) continue;
            boolean annotated = hm.hasMethodAnnotation(RequirePermission.class)
                    || AnnotatedElementUtils.hasAnnotation(hm.getBeanType(), RequirePermission.class);
            if (!annotated) {
                log.warn("No @RequirePermission on {} -> {}#{}", e.getKey(),
                        hm.getBeanType().getSimpleName(), hm.getMethod().getName());
            }
        }
    }
}
package com.stockmanagement.controller;

import com.stockmanagement.config.PermissionInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.io.IOException;

@ControllerAdvice
public class AccessDeniedAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    public void handle(HttpServletRequest request, HttpServletResponse response) throws IOException {
        PermissionInterceptor.respondDenied(request, response);
    }
}
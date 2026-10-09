package com.stockmanagement.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
public class AccessDeniedController {

    @GetMapping("/access-denied")
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public String accessDenied() {
        return "access-denied";
    }
}
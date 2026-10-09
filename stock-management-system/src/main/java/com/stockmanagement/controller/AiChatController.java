package com.stockmanagement.controller;

import com.stockmanagement.service.CustomUserDetails;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * AI chat page (abhi sirf UI). Login wala har user dekh sakta hai.
 * Kuch users tak limit karna ho to @RequirePermission lagao.
 */
@Controller
public class AiChatController {

    @GetMapping("/ai-chat")
    public String aiChatPage(@AuthenticationPrincipal CustomUserDetails userDetails, Model model) {
        model.addAttribute("userId", userDetails.getUserId());
        model.addAttribute("username", userDetails.getUsername());
        return "ai/ai-chat";
    }
}
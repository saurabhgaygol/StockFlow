package com.stockmanagement.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the login page only. Spring Security itself handles the POST to
 * /login (see {@code SecurityConfig}) - this controller never touches
 * credentials.
 */
@Controller
public class LoginController {

	 @GetMapping("/login")
	    public String home() {
	        return "login";
	    }
}

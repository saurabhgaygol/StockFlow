package com.stockmanagement.controller;


import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;
import java.util.stream.Collectors;

@Controller
public class UserController {

    private final UserPermissionService userPermissionService;
    private final UserRepository userRepository;

    public UserController(UserPermissionService userPermissionService, UserRepository userRepository) {
        this.userPermissionService = userPermissionService;
        this.userRepository = userRepository;
    }

    @GetMapping("/settings/user")
    public String userPage(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            Model model) {

        Long userId = userDetails.getUserId();
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> permissionCodes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream()
                .map(p -> p.getPermissionCode())
                .collect(Collectors.toList());
        model.addAttribute("permissions", permissionCodes);

        List<UserTable> users = userRepository.findAll();
        model.addAttribute("users", users);

        return "settings/user";
    }
}
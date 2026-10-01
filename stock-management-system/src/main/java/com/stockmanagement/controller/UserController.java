package com.stockmanagement.controller;

import com.stockmanagement.entity.Permission;
import com.stockmanagement.entity.UserPermission;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.PermissionRepository;
import com.stockmanagement.repository.UserPermissionRepository;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class UserController {

    private static final int PAGE_SIZE = 100;

    private final UserPermissionService userPermissionService;
    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final UserPermissionRepository userPermissionRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserPermissionService userPermissionService,
                          UserRepository userRepository,
                          PermissionRepository permissionRepository,
                          UserPermissionRepository userPermissionRepository,
                          PasswordEncoder passwordEncoder) {
        this.userPermissionService = userPermissionService;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.passwordEncoder = passwordEncoder;
    }

    // ===================== MAIN PAGE =====================
    @GetMapping("/settings/user")
    public String userPage(@AuthenticationPrincipal CustomUserDetails userDetails,
                           @RequestParam(value = "page", defaultValue = "0") int page,
                           @RequestParam(value = "q", required = false) String q,
                           @RequestHeader(value = "HX-Target", required = false) String hxTarget,
                           Model model) {

        Long userId = userDetails.getUserId();
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> permissionCodes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream().map(Permission::getPermissionCode).collect(Collectors.toList());
        model.addAttribute("permissions", permissionCodes);

        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        Page<UserTable> usersPage = userRepository.searchUsers(q, pageable);
        model.addAttribute("users", usersPage.getContent());
        model.addAttribute("currentPage", usersPage.getNumber());
        model.addAttribute("totalPages", usersPage.getTotalPages());
        model.addAttribute("totalItems", usersPage.getTotalElements());
        model.addAttribute("pageSize", PAGE_SIZE);
        int startIdx = usersPage.getTotalElements() == 0 ? 0 : usersPage.getNumber() * PAGE_SIZE + 1;
        int endIdx = (int) Math.min((usersPage.getNumber() + 1) * (long) PAGE_SIZE, usersPage.getTotalElements());
        model.addAttribute("startIndex", startIdx);
        model.addAttribute("endIndex", endIdx);
        model.addAttribute("searchQuery", q == null ? "" : q);

        boolean isTableRequest = "userTableArea".equals(hxTarget);
        if (isTableRequest) {
            return "settings/user :: userTableArea";
        }

        List<Permission> assignable = permissionRepository.findAssignablePermissions(permissionCodes);
        Map<String, Map<String, List<Permission>>> tree = new LinkedHashMap<>();
        for (Permission p : assignable) {
            String mod = (p.getModule() == null || p.getModule().isBlank()) ? "General" : p.getModule();
            String sub = (p.getSubModule() == null || p.getSubModule().isBlank()) ? "" : p.getSubModule();
            tree.computeIfAbsent(mod, k -> new LinkedHashMap<>())
                .computeIfAbsent(sub, k -> new ArrayList<>())
                .add(p);
        }
        model.addAttribute("permTree", tree);

        return "settings/user";
    }

    // ===================== ASSIGNED PERMISSIONS JSON =====================
    @GetMapping("/settings/user/assigned/{userId}")
    @ResponseBody
    public List<Long> getAssignedPermissions(@PathVariable Long userId) {
        return userPermissionRepository.findByUserIdAndAllowedTrue(userId)
                .stream().map(UserPermission::getPermissionId).collect(Collectors.toList());
    }

    // ===================== SAVE USER (ADD + UPDATE) =====================
    @PostMapping("/settings/user/save")
    @Transactional
    public String saveUser(@ModelAttribute UserTable formUser,
                           @RequestParam(value = "permissionIds", required = false) List<Long> permissionIds,
                           @AuthenticationPrincipal CustomUserDetails userDetails,
                           Model model) {

        Long loginUserId = userDetails.getUserId();
        boolean isEdit = formUser.getId() != null;

        // Master ke saare permission IDs — SECURITY FILTER
        Set<Long> masterIds = userPermissionService.getAllowedPermissionDetails(loginUserId)
                .stream().map(Permission::getId).collect(Collectors.toSet());

        // Sirf wahi permissions allow karo jo master ke paas hain (duplicates hata ke)
        Set<Long> finalIds = new LinkedHashSet<>();
        if (permissionIds != null) {
            for (Long pid : permissionIds) {
                if (masterIds.contains(pid)) finalIds.add(pid);
            }
        }

        if (isEdit) {
            // ==================== UPDATE ====================
            UserTable existing = userRepository.findById(formUser.getId())
                    .orElseThrow(() -> new IllegalArgumentException("User not found: " + formUser.getId()));

            if (!existing.getUsername().equals(formUser.getUsername())
                    && userRepository.existsByUsername(formUser.getUsername())) {
                return reloadWithError(userDetails, model, "Username already exists: " + formUser.getUsername());
            }
            if (!existing.getEmployeeId().equals(formUser.getEmployeeId())
                    && userRepository.existsByEmployeeId(formUser.getEmployeeId())) {
                return reloadWithError(userDetails, model, "Employee ID already exists: " + formUser.getEmployeeId());
            }

            existing.setCompanyName(formUser.getCompanyName());
            existing.setEmployeeId(formUser.getEmployeeId());
            existing.setUsername(formUser.getUsername());
            existing.setFirstName(formUser.getFirstName());
            existing.setLastName(formUser.getLastName());
            existing.setEmail(formUser.getEmail());
            existing.setMobile(formUser.getMobile());
            existing.setDepartment(formUser.getDepartment());

            if (formUser.getPassword() != null && !formUser.getPassword().isBlank()) {
                existing.setPassword(passwordEncoder.encode(formUser.getPassword()));
                existing.setForcePasswordChange(true);
            }
            userRepository.save(existing);

            // ✅ STEP 1: Direct DELETE query se sirf master ke jurisdiction wali permissions delete karo
            //    (Hibernate queue me nahi jaayega — turant DB me execute hoga, duplicate error nahi aayega)
            if (!masterIds.isEmpty()) {
                userPermissionRepository.deleteByUserIdAndPermissionIdIn(
                    existing.getId(),
                    new ArrayList<>(masterIds)
                );
            }

            // ✅ STEP 2: Nayi checked permissions save karo
            for (Long pid : finalIds) {
                UserPermission up = new UserPermission();
                up.setUserId(existing.getId());
                up.setPermissionId(pid);
                up.setAllowed(true);
                up.setCreatedBy(loginUserId);
                userPermissionRepository.save(up);
            }

        } else {
            // ==================== ADD ====================
            if (userRepository.existsByUsername(formUser.getUsername())) {
                return reloadWithError(userDetails, model, "Username already exists: " + formUser.getUsername());
            }
            if (userRepository.existsByEmployeeId(formUser.getEmployeeId())) {
                return reloadWithError(userDetails, model, "Employee ID already exists: " + formUser.getEmployeeId());
            }

            if (formUser.getSystemGeneratedId() == null || formUser.getSystemGeneratedId().isBlank()) {
                formUser.setSystemGeneratedId("SYS-" + System.currentTimeMillis());
            }
            formUser.setPassword(passwordEncoder.encode(formUser.getPassword()));
            formUser.setCreatedBy(loginUserId);
            if (formUser.getStatus() == null || formUser.getStatus().isBlank()) {
                formUser.setStatus("ACTIVE");
            }
            formUser.setForcePasswordChange(true);

            UserTable saved = userRepository.save(formUser);

            for (Long pid : finalIds) {
                UserPermission up = new UserPermission();
                up.setUserId(saved.getId());
                up.setPermissionId(pid);
                up.setAllowed(true);
                up.setCreatedBy(loginUserId);
                userPermissionRepository.save(up);
            }
        }

        return "redirect:/settings/user";
    }

    // ===================== HELPER =====================
    private String reloadWithError(CustomUserDetails userDetails, Model model, String msg) {
        Long userId = userDetails.getUserId();
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> codes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream().map(Permission::getPermissionCode).collect(Collectors.toList());
        model.addAttribute("permissions", codes);

        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        Page<UserTable> usersPage = userRepository.searchUsers(null, pageable);
        model.addAttribute("users", usersPage.getContent());
        model.addAttribute("currentPage", 0);
        model.addAttribute("totalPages", usersPage.getTotalPages());
        model.addAttribute("totalItems", usersPage.getTotalElements());
        model.addAttribute("pageSize", PAGE_SIZE);
        model.addAttribute("startIndex", usersPage.getTotalElements() == 0 ? 0 : 1);
        model.addAttribute("endIndex", (int) Math.min(PAGE_SIZE, usersPage.getTotalElements()));
        model.addAttribute("searchQuery", "");

        List<Permission> assignable = permissionRepository.findAssignablePermissions(codes);
        Map<String, Map<String, List<Permission>>> tree = new LinkedHashMap<>();
        for (Permission p : assignable) {
            String mod = (p.getModule() == null || p.getModule().isBlank()) ? "General" : p.getModule();
            String sub = (p.getSubModule() == null || p.getSubModule().isBlank()) ? "" : p.getSubModule();
            tree.computeIfAbsent(mod, k -> new LinkedHashMap<>())
                .computeIfAbsent(sub, k -> new ArrayList<>())
                .add(p);
        }
        model.addAttribute("permTree", tree);
        model.addAttribute("error", msg);
        return "settings/user";
    }
}
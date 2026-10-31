package com.stockmanagement.controller;

import com.stockmanagement.config.RequirePermission;
import com.stockmanagement.entity.Permission;
import com.stockmanagement.entity.UserPermission;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.PermissionRepository;
import com.stockmanagement.repository.UserPermissionRepository;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.CompanyService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;
import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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
    private final CompanyService companyService;   // [COMPANY] dropdown ki list + "+ New company…"
    private final JdbcTemplate jdbc;   // username rename par saari text history update karne ke liye

    public UserController(UserPermissionService userPermissionService,
                          UserRepository userRepository,
                          PermissionRepository permissionRepository,
                          UserPermissionRepository userPermissionRepository,
                          PasswordEncoder passwordEncoder,
                          CompanyService companyService,
                          JdbcTemplate jdbc) {
        this.userPermissionService = userPermissionService;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.userPermissionRepository = userPermissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.companyService = companyService;
        this.jdbc = jdbc;
    }


    // ===================== USERNAME RENAME CASCADE =====================
    /**
     * Username badalne par jahan bhi purana username text me saved hai wahan naya username.
     * Table/column naam neeche ki fixed list se hi aate hain (user input se kabhi nahi).
     * Sirf wahi rows badalti hain jahan text bilkul purane username ke barabar ho (username unique hai),
     * to dusre user ki rows ya poore naam (first/last) wale columns nahi chhute.
     * saveUser @Transactional hai, to user ka update aur ye sab ek saath hote hain ya sab wapas.
     */
    private static final String[][] USERNAME_TEXT_COLUMNS = {
        {"user_table", "created_by"}, {"user_table", "updated_by"},
        {"audit_logs", "user_name"},
        {"api_keys", "created_by"}, {"api_keys", "deleted_by"},
        {"approval_level", "created_by"},
        {"companies", "created_by"}, {"companies", "updated_by"},
        {"company_vendor", "created_by"},
        {"product_category", "created_by"},
        {"staff_stock_movement", "created_by"},
        {"stock_customer", "created_by"},
        {"stock_inward", "created_by"},
        {"stock_return", "created_by"}, {"stock_return", "closed_by"},
        {"stock_outward_activity", "actor_name"},
        {"stock_unit_history", "actor_name"},
        {"vendors", "created_by"}
    };

    private void cascadeUsername(String oldUsername, String newUsername) {
        if (oldUsername == null || newUsername == null || oldUsername.isBlank()
                || newUsername.isBlank() || oldUsername.equals(newUsername)) {
            return;
        }
        for (String[] tc : USERNAME_TEXT_COLUMNS) {
            jdbc.update("UPDATE `" + tc[0] + "` SET `" + tc[1] + "` = ? WHERE `" + tc[1] + "` = ?",
                    newUsername, oldUsername);
        }
    }

    // ===================== MAIN PAGE =====================
    @GetMapping("/settings/user")
    @RequirePermission({"USER_VIEW", "USER_ADD", "USER_EDIT", "USER_DELETE"})
    public String userPage(@AuthenticationPrincipal CustomUserDetails userDetails,
                           @RequestParam(value = "page", defaultValue = "0") int page,
                           @RequestParam(value = "q", required = false) String q,
                           @RequestHeader(value = "HX-Target", required = false) String hxTarget,
                           Model model) {
        // Module ki koi bhi ek permission ho to page khulta hai; list sirf USER_VIEW walon ko dikhti hai
        requireAny(userDetails, "USER_VIEW", "USER_ADD", "USER_EDIT", "USER_DELETE");
        boolean canView = addAccessFlags(userDetails, model);

        Long userId = userDetails.getUserId();
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> permissionCodes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream().map(Permission::getPermissionCode).collect(Collectors.toList());
        model.addAttribute("permissions", permissionCodes);

        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE);
        Page<UserTable> usersPage = canView
                ? userRepository.searchUsers(q, companyScope(userDetails), pageable)
                : Page.<UserTable>empty(pageable);
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
    @RequirePermission({"USER_VIEW", "USER_EDIT"})
    public List<Long> getAssignedPermissions(@PathVariable Long userId,
                                             @AuthenticationPrincipal CustomUserDetails userDetails) {
        requireAny(userDetails, "USER_VIEW", "USER_EDIT");
        requireSameCompany(userDetails, userId);
        return userPermissionRepository.findByUserIdAndAllowedTrue(userId)
                .stream().map(UserPermission::getPermissionId).collect(Collectors.toList());
    }

    // ===================== SAVE USER (ADD + UPDATE) =====================
    @PostMapping("/settings/user/save")
    @Transactional
    @RequirePermission({"USER_ADD", "USER_EDIT"})
    public String saveUser(@ModelAttribute UserTable formUser,
                           @RequestParam(value = "permissionIds", required = false) List<Long> permissionIds,
                           @RequestParam(value = "newCompanyName", required = false) String newCompanyName,
                           @AuthenticationPrincipal CustomUserDetails userDetails,
                           Model model) {

        Long loginUserId = userDetails.getUserId();
        String loginUsername = userDetails.getUsername();
        boolean isEdit = formUser.getId() != null;
        boolean actorSuper = userPermissionService.hasPermission(loginUserId, "SUPER_ADMIN");

        // Add aur Edit ki alag permission (interceptor sirf "dono me se ek" dekhta hai)
        if (!actorSuper && !userPermissionService.hasPermission(loginUserId, isEdit ? "USER_EDIT" : "USER_ADD")) {
            throw new AccessDeniedException("You do not have permission to " + (isEdit ? "edit" : "add") + " users.");
        }

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

            // Doosri company ka user ya SUPER_ADMIN user (non-super actor ke liye) chhua nahi ja sakta
            if (!actorSuper) {
                requireSameCompany(userDetails, existing.getId());
                requireNotSuperAdminTarget(existing.getId());
            }

            if (!existing.getUsername().equals(formUser.getUsername())
                    && userRepository.existsByUsername(formUser.getUsername())) {
                return reloadWithError(userDetails, model, "Username already exists: " + formUser.getUsername());
            }
            if (!existing.getEmployeeId().equals(formUser.getEmployeeId())
                    && userRepository.existsByEmployeeId(formUser.getEmployeeId())) {
                return reloadWithError(userDetails, model, "Employee ID already exists: " + formUser.getEmployeeId());
            }

            // Company sirf SUPER_ADMIN badal sakta hai
            // [COMPANY] ab naam companies table se check hota hai; "+ New company…" chuna ho to nayi company banti hai
            if (actorSuper && formUser.getCompanyName() != null && !formUser.getCompanyName().isBlank()) {
                try {
                    existing.setCompanyName(companyService.resolveForUser(formUser.getCompanyName(), newCompanyName, loginUsername));
                } catch (IllegalArgumentException e) {
                    return reloadWithError(userDetails, model, e.getMessage());
                }
            }
            final String oldUsername = existing.getUsername();   // rename check ke liye
            existing.setEmployeeId(formUser.getEmployeeId());
            existing.setUsername(formUser.getUsername());
            existing.setFirstName(formUser.getFirstName());
            existing.setLastName(formUser.getLastName());
            existing.setEmail(formUser.getEmail());
            existing.setMobile(formUser.getMobile());
            existing.setDepartment(formUser.getDepartment());
            existing.setCity(formUser.getCity() == null || formUser.getCity().isBlank() ? null : formUser.getCity().trim());
            existing.setFieldStaff(Boolean.TRUE.equals(formUser.getFieldStaff()));

            if (formUser.getPassword() != null && !formUser.getPassword().isBlank()) {
                existing.setPassword(passwordEncoder.encode(formUser.getPassword()));
                existing.setForcePasswordChange(true);
            }

            // ✅ NEW: kisne last update kiya (username) + kab
            existing.setUpdatedBy(loginUsername);
            existing.setUpdatedAt(LocalDateTime.now());

            userRepository.saveAndFlush(existing);

            // Username badla ho to created_by / audit / API key owner jaisi saari text history me bhi naya username
            if (!oldUsername.equals(existing.getUsername())) {
                cascadeUsername(oldUsername, existing.getUsername());
            }

            // STEP 1: Direct DELETE query se sirf master ke jurisdiction wali permissions delete karo
            if (!masterIds.isEmpty()) {
                userPermissionRepository.deleteByUserIdAndPermissionIdIn(
                    existing.getId(),
                    new ArrayList<>(masterIds)
                );
            }

            // STEP 2: Nayi checked permissions save karo
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

            if (formUser.getPassword() == null || formUser.getPassword().isBlank()) {
                return reloadWithError(userDetails, model, "Password is required for a new user.");
            }

            // Mass-assignment protection: form se aaye system fields ignore
            formUser.setId(null);
            // Unique even if two users are created in the same millisecond
            String sysId;
            do {
                sysId = "SYS-" + System.currentTimeMillis() + "-" + java.util.concurrent.ThreadLocalRandom.current().nextInt(1000, 10000);
            } while (userRepository.existsBySystemGeneratedId(sysId));
            formUser.setSystemGeneratedId(sysId);
            formUser.setCreatedAt(null);
            formUser.setUpdatedAt(null);
            if (!actorSuper) {
                // Non-super: sirf apni company, aur koi role form se nahi (role = privilege escalation hota)
                formUser.setCompanyName(userDetails.getUser().getCompanyName());
                formUser.setRoleId(null);
            } else {
                // [COMPANY] SUPER_ADMIN: chuni hui company table me honi chahiye, ya "+ New company…" se nayi banegi
                try {
                    formUser.setCompanyName(companyService.resolveForUser(formUser.getCompanyName(), newCompanyName, loginUsername));
                } catch (IllegalArgumentException e) {
                    return reloadWithError(userDetails, model, e.getMessage());
                }
            }
            String st = formUser.getStatus() == null ? "" : formUser.getStatus().trim().toUpperCase();
            formUser.setStatus("INACTIVE".equals(st) ? "INACTIVE" : "ACTIVE");

            formUser.setPassword(passwordEncoder.encode(formUser.getPassword()));

            // ✅ NEW: created_by = username, parent_user_id = login user ki id, updated_by = username
            formUser.setCreatedBy(loginUsername);
            formUser.setParentUserId(loginUserId);
            formUser.setUpdatedBy(loginUsername);

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

    // ===================== DELETE USER (+ PERMISSIONS) =====================
    @PostMapping("/settings/user/delete/{id}")
    @ResponseBody
    @Transactional
    @RequirePermission({"USER_DELETE"})
    public org.springframework.http.ResponseEntity<String> deleteUser(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {

        Long loginUserId = userDetails.getUserId();

        // Security: koi apne aap ko delete na kar sake
        if (loginUserId.equals(id)) {
            return org.springframework.http.ResponseEntity
                    .badRequest().body("You cannot delete your own account.");
        }

        // Security: sirf USER_DELETE permission wala hi delete kar sake
        boolean canDelete = userPermissionService.getAllowedPermissionDetails(loginUserId)
                .stream().anyMatch(p -> "USER_DELETE".equals(p.getPermissionCode()));
        if (!canDelete) {
            return org.springframework.http.ResponseEntity
                    .status(403).body("You do not have permission to delete users.");
        }

        if (!userRepository.existsById(id)) {
            return org.springframework.http.ResponseEntity
                    .status(404).body("User not found.");
        }

        // Doosri company ka user / SUPER_ADMIN user delete nahi (SUPER_ADMIN khud ko chhodkar sab kar sakta hai)
        if (!userPermissionService.hasPermission(loginUserId, "SUPER_ADMIN")) {
            if (!sameCompany(userDetails, id) || userPermissionService.hasPermission(id, "SUPER_ADMIN")) {
                return org.springframework.http.ResponseEntity
                        .status(403).body("You cannot delete this user.");
            }
        }

        // STEP 1: pehle user ki saari permissions delete
        userPermissionRepository.deleteByUserId(id);

        // STEP 2: phir user delete
        userRepository.deleteById(id);

        return org.springframework.http.ResponseEntity.ok("Deleted");
    }

    // ===================== SCOPE HELPERS =====================

    private void require(CustomUserDetails user, String permission) {
        Long id = user.getUserId();
        if (!userPermissionService.hasPermission(id, permission)
                && !userPermissionService.hasPermission(id, "SUPER_ADMIN")) {
            throw new AccessDeniedException("You do not have permission: " + permission);
        }
    }

    private boolean hasPerm(CustomUserDetails user, String permission) {
        Long id = user.getUserId();
        return userPermissionService.hasPermission(id, permission)
                || userPermissionService.hasPermission(id, "SUPER_ADMIN");
    }

    private void requireAny(CustomUserDetails user, String... permissions) {
        for (String p : permissions) {
            if (hasPerm(user, p)) return;
        }
        throw new AccessDeniedException("You do not have permission for this page.");
    }

    /** Template ke liye canView/canAdd/canEdit/canDelete set karta hai; canView return karta hai. */
    private boolean addAccessFlags(CustomUserDetails user, Model model) {
        boolean canView = hasPerm(user, "USER_VIEW");
        model.addAttribute("canView", canView);
        model.addAttribute("canAdd", hasPerm(user, "USER_ADD"));
        model.addAttribute("canEdit", hasPerm(user, "USER_EDIT"));
        model.addAttribute("canDelete", hasPerm(user, "USER_DELETE"));
        // [COMPANY] Company dropdown: SUPER_ADMIN ko saari companies + "+ New company…", baaki ko sirf apni (locked)
        boolean superAdmin = userPermissionService.hasPermission(user.getUserId(), "SUPER_ADMIN");
        model.addAttribute("isSuperAdmin", superAdmin);
        model.addAttribute("myCompany", user.getUser().getCompanyName());
        model.addAttribute("companies", superAdmin ? companyService.activeNames() : List.of());
        return canView;
    }

    /** null = saari companies (SUPER_ADMIN), warna apni company. */
    private String companyScope(CustomUserDetails actor) {
        return userPermissionService.hasPermission(actor.getUserId(), "SUPER_ADMIN")
                ? null : actor.getUser().getCompanyName();
    }

    private boolean sameCompany(CustomUserDetails actor, Long targetUserId) {
        String mine = actor.getUser().getCompanyName();
        return userRepository.findById(targetUserId)
                .map(t -> mine != null && mine.equals(t.getCompanyName()))
                .orElse(false);
    }

    private void requireSameCompany(CustomUserDetails actor, Long targetUserId) {
        if (userPermissionService.hasPermission(actor.getUserId(), "SUPER_ADMIN")) return;
        if (!sameCompany(actor, targetUserId)) {
            throw new AccessDeniedException("User belongs to another company.");
        }
    }

    private void requireNotSuperAdminTarget(Long targetUserId) {
        if (userPermissionService.hasPermission(targetUserId, "SUPER_ADMIN")) {
            throw new AccessDeniedException("You cannot modify a Super Admin.");
        }
    }

    // ===================== HELPER =====================
    private String reloadWithError(CustomUserDetails userDetails, Model model, String msg) {
        Long userId = userDetails.getUserId();
        model.addAttribute("userId", userId);
        model.addAttribute("username", userDetails.getUsername());

        List<String> codes = userPermissionService.getAllowedPermissionDetails(userId)
                .stream().map(Permission::getPermissionCode).collect(Collectors.toList());
        model.addAttribute("permissions", codes);

        boolean canView = addAccessFlags(userDetails, model);

        Pageable pageable = PageRequest.of(0, PAGE_SIZE);
        Page<UserTable> usersPage = canView
                ? userRepository.searchUsers(null, companyScope(userDetails), pageable)
                : Page.<UserTable>empty(pageable);
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
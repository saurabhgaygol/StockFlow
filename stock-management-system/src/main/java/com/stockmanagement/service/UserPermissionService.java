package com.stockmanagement.service;



import com.stockmanagement.dto.PermissionResponseDTO;
import com.stockmanagement.entity.Permission;
import com.stockmanagement.entity.RolePermission;
import com.stockmanagement.entity.UserPermission;
import com.stockmanagement.repository.PermissionRepository;
import com.stockmanagement.repository.RolePermissionRepository;
import com.stockmanagement.repository.UserPermissionRepository;
import com.stockmanagement.repository.UserRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserPermissionService {

    private final UserPermissionRepository userPermissionRepository;
    private final PermissionRepository permissionRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserRepository userRepository;

    public UserPermissionService(
            UserPermissionRepository userPermissionRepository,
            PermissionRepository permissionRepository,
            RolePermissionRepository rolePermissionRepository,
            UserRepository userRepository) {

        this.userPermissionRepository = userPermissionRepository;
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.userRepository = userRepository;
    }

    public List<Permission> getAllowedPermissionDetails(Long userId) {

        Set<Long> effectivePermissionIds = new HashSet<>();

        userRepository.findById(userId).ifPresent(user -> {
            if (user.getRoleId() != null) {
                for (RolePermission rp : rolePermissionRepository.findByRoleIdAndAllowedTrue(user.getRoleId())) {
                    effectivePermissionIds.add(rp.getPermissionId());
                }
            }
        });

        for (UserPermission up : userPermissionRepository.findByUserId(userId)) {
            if (Boolean.TRUE.equals(up.getAllowed())) {
                effectivePermissionIds.add(up.getPermissionId());
            } else {
                effectivePermissionIds.remove(up.getPermissionId());
            }
        }

        List<Permission> permissions = new ArrayList<>();
        for (Long permissionId : effectivePermissionIds) {
            permissionRepository.findById(permissionId).ifPresent(permissions::add);
        }

        return permissions;
    }

    public PermissionResponseDTO getPermissionResponse(Long userId) {

        List<Permission> permissions = getAllowedPermissionDetails(userId);

        Map<String, Map<String, List<String>>> grouped = new LinkedHashMap<>();

        for (Permission permission : permissions) {
            String module = permission.getModule();
            String subModule = permission.getSubModule();

            grouped.computeIfAbsent(module, key -> new LinkedHashMap<>())
                    .computeIfAbsent(subModule, key -> new ArrayList<>())
                    .add(permission.getPermissionCode());
        }

        List<PermissionResponseDTO.ModuleDTO> modules = new ArrayList<>();

        for (Map.Entry<String, Map<String, List<String>>> moduleEntry : grouped.entrySet()) {
            List<PermissionResponseDTO.ChildDTO> children = new ArrayList<>();

            for (Map.Entry<String, List<String>> childEntry : moduleEntry.getValue().entrySet()) {
                children.add(new PermissionResponseDTO.ChildDTO(childEntry.getKey(), childEntry.getValue()));
            }

            modules.add(new PermissionResponseDTO.ModuleDTO(moduleEntry.getKey(), children));
        }

        modules.sort((a, b) -> Integer.compare(getModuleOrder(a.getModule()), getModuleOrder(b.getModule())));

        return new PermissionResponseDTO(modules);
    }

    private int getModuleOrder(String module) {
        return switch (module) {
            case "Dashboard" -> 1;
            case "Reports" -> 2;
            case "Settings" -> 3;
            default -> 999;
        };
    }

    public boolean hasPermission(Long userId, String permissionCode) {
        Permission permission = permissionRepository.findByPermissionCode(permissionCode).orElse(null);
        if (permission == null) {
            return false;
        }
        return getAllowedPermissionDetails(userId).stream()
                .anyMatch(p -> p.getId().equals(permission.getId()));
    }
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    // ===== PURANE METHODS (JAISE THE, WAISE HI) =====
    Optional<Permission> findById(Long id);

    Optional<Permission> findByPermissionCode(String permissionCode);

    // ===== NAYE METHODS (Add/Edit User form ke liye) =====

    @Query("SELECT p FROM Permission p WHERE p.permissionCode IN :codes " +
           "AND p.status = 'ACTIVE' ORDER BY p.module, p.subModule, p.action")
    List<Permission> findAssignablePermissions(@Param("codes") List<String> codes);

    @Query("SELECT DISTINCT p.subModule FROM Permission p WHERE p.module = :module " +
           "AND p.subModule IS NOT NULL AND p.subModule <> '' AND p.status = 'ACTIVE' " +
           "ORDER BY p.subModule")
    List<String> findDistinctSubModulesByModule(@Param("module") String module);

    @Query("SELECT DISTINCT p.module FROM Permission p WHERE p.status = 'ACTIVE' ORDER BY p.module")
    List<String> findDistinctActiveModules();
}
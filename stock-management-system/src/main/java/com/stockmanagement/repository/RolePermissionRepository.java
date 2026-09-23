package com.stockmanagement.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stockmanagement.entity.RolePermission;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    List<RolePermission> findByRoleIdAndAllowedTrue(Long roleId);
}
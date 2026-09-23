package com.stockmanagement.repository;



import com.stockmanagement.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

    Optional<Permission> findById(Long id);

    Optional<Permission> findByPermissionCode(String permissionCode);
}
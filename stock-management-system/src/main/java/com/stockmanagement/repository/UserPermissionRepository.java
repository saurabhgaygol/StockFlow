package com.stockmanagement.repository;



import com.stockmanagement.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    List<UserPermission> findByUserIdAndAllowedTrue(Long userId);

    boolean existsByUserIdAndPermissionIdAndAllowedTrue(Long userId, Long permissionId);

    List<UserPermission> findByUserId(Long userId);
}
package com.stockmanagement.repository;

import com.stockmanagement.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {

    List<UserPermission> findByUserIdAndAllowedTrue(Long userId);

    boolean existsByUserIdAndPermissionIdAndAllowedTrue(Long userId, Long permissionId);

    List<UserPermission> findByUserId(Long userId);

    // Direct DELETE — saari permissions delete
    @Modifying
    @Query("DELETE FROM UserPermission up WHERE up.userId = :userId")
    void deleteByUserIdDirect(@Param("userId") Long userId);

    // ✅ Specific permission IDs delete karo (500 error fix)
    @Modifying
    @Query("DELETE FROM UserPermission up WHERE up.userId = :userId AND up.permissionId IN :permissionIds")
    void deleteByUserIdAndPermissionIdIn(@Param("userId") Long userId,
                                          @Param("permissionIds") List<Long> permissionIds);
}
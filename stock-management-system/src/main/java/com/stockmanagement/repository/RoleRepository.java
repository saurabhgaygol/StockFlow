package com.stockmanagement.repository;

import com.stockmanagement.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleRepository extends JpaRepository<Role, Long> {

    List<Role> findByStatusOrderByRoleNameAsc(String status);
}
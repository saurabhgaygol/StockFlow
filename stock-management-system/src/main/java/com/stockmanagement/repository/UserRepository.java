package com.stockmanagement.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.stockmanagement.entity.UserTable;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<UserTable, Long> {

    Optional<UserTable> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByEmployeeId(String employeeId);

    boolean existsBySystemGeneratedId(String systemGeneratedId);

    List<UserTable> findByRoleIdAndCompanyNameAndStatus(Long roleId, String companyName, String status);

    List<UserTable> findByCompanyNameAndStatusOrderByFirstNameAsc(String companyName, String status);

    @Query("SELECT DISTINCT u.companyName FROM UserTable u WHERE u.companyName IS NOT NULL ORDER BY u.companyName")
    List<String> findDistinctCompanyNames();

    // ===================== PAGINATION + SEARCH =====================
    @Query("SELECT u FROM UserTable u WHERE " +
           "(:keyword IS NULL OR :keyword = '' OR " +
           " LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(u.firstName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(u.lastName) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(u.employeeId) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           " LOWER(u.companyName) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY u.id DESC")
    Page<UserTable> searchUsers(@Param("keyword") String keyword, Pageable pageable);

    // ===================== FIELD STAFF STOCK =====================

    /** Active users of a company that are marked as Field Staff. */
    @Query("SELECT u FROM UserTable u WHERE u.companyName = :companyName AND u.status = 'ACTIVE' "
         + "AND u.fieldStaff = true "
         + "ORDER BY u.firstName ASC, u.id ASC")
    List<UserTable> findFieldStaff(@Param("companyName") String companyName);
}
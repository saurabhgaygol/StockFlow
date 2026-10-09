package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "user_table",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_system_generated_id", columnNames = "system_generated_id"),
        @UniqueConstraint(name = "uk_employee_id", columnNames = "employee_id"),
        @UniqueConstraint(name = "uk_username", columnNames = "username")
    }
)
public class UserTable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "system_generated_id", nullable = false, unique = true)
    private String systemGeneratedId;

    @Column(name = "employee_id", nullable = false, unique = true)
    private String employeeId;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column
    private String email;

    @Column
    private String mobile;

    @Column
    private String department;

    @Column(name = "company_name")
    private String companyName;

    /** Work location (Pune / Mumbai ...), used for Field Staff stock. */
    @Column(name = "city", length = 100)
    private String city;

    /** true = field staff who can hold device stock (Pavan, Rahul, Rajesh ...). */
    @Column(name = "field_staff")
    private Boolean fieldStaff = false;

    @Column(name = "role_id")
    private Long roleId;

    @Column(name = "parent_user_id")
    private Long parentUserId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "force_password_change", nullable = false)
    private Boolean forcePasswordChange = true;

    // CHANGED: Long -> String (username save hoga)
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    // NEW: last update karne wale ka username
    @Column(name = "updated_by")
    private String updatedBy;

    // CHANGED: updatable = false
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSystemGeneratedId() { return systemGeneratedId; }
    public void setSystemGeneratedId(String systemGeneratedId) { this.systemGeneratedId = systemGeneratedId; }

    public String getEmployeeId() { return employeeId; }
    public void setEmployeeId(String employeeId) { this.employeeId = employeeId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public Boolean getFieldStaff() { return fieldStaff; }
    public void setFieldStaff(Boolean fieldStaff) { this.fieldStaff = fieldStaff; }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }

    public Long getParentUserId() { return parentUserId; }
    public void setParentUserId(Long parentUserId) { this.parentUserId = parentUserId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Boolean getForcePasswordChange() { return forcePasswordChange; }
    public void setForcePasswordChange(Boolean forcePasswordChange) { this.forcePasswordChange = forcePasswordChange; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A shop customer, found at the billing counter by mobile number or name. 1 loyalty point = Rs 1. */
@Entity
@Table(name = "retail_customer",
    uniqueConstraints = @UniqueConstraint(name = "uk_retail_customer_mobile", columnNames = {"company_name", "mobile"}),
    indexes = @Index(name = "idx_retail_customer_name", columnList = "company_name, name"))
public class RetailCustomer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(nullable = false, length = 15)
    private String mobile;

    @Column(nullable = false, length = 120)
    private String name;

    private LocalDate dob;

    @Column(length = 120)
    private String email;

    @Column(name = "points_balance", nullable = false)
    private Long pointsBalance = 0L;

    @Column(name = "total_bills", nullable = false)
    private Integer totalBills = 0;

    @Column(name = "total_spent", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Column(name = "last_visit")
    private LocalDateTime lastVisit;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public LocalDate getDob() { return dob; }
    public void setDob(LocalDate dob) { this.dob = dob; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Long getPointsBalance() { return pointsBalance; }
    public void setPointsBalance(Long pointsBalance) { this.pointsBalance = pointsBalance; }
    public Integer getTotalBills() { return totalBills; }
    public void setTotalBills(Integer totalBills) { this.totalBills = totalBills; }
    public BigDecimal getTotalSpent() { return totalSpent; }
    public void setTotalSpent(BigDecimal totalSpent) { this.totalSpent = totalSpent; }
    public LocalDateTime getLastVisit() { return lastVisit; }
    public void setLastVisit(LocalDateTime lastVisit) { this.lastVisit = lastVisit; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
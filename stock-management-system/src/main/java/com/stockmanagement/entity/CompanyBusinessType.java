package com.stockmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * One business type given to one company for Tag Studio (one row per type).
 * A company can have one or many types, e.g. Clothing + Footwear.
 * Only Super Admin writes this (Settings > Shop Features). Rows stay when Tag Studio is switched OFF,
 * so switching it ON again brings back the same types.
 */
@Entity
@Table(
    name = "company_business_type",
    uniqueConstraints = @UniqueConstraint(name = "uk_company_business_type", columnNames = {"company_name", "business_type"})
)
public class CompanyBusinessType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** Business type code, e.g. CLOTHING, JEWELLERY, IMITATION, PHARMACY ... (full list: TagStudioService) */
    @Column(name = "business_type", nullable = false, length = 30)
    private String businessType;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
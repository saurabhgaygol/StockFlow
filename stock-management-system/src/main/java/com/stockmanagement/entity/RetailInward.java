package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One "Save" of new retail stock = one inward (the load the stock came in with). */
@Entity
@Table(
    name = "retail_inward",
    uniqueConstraints = @UniqueConstraint(name = "uk_retail_inward_no", columnNames = {"company_name", "inward_no"}),
    indexes = @Index(name = "idx_retail_inward_company", columnList = "company_name, id")
)
public class RetailInward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** e.g. INW-2026-000012 (set right after the first save, when the id is known) */
    @Column(name = "inward_no", length = 30)
    private String inwardNo;

    @Column(name = "total_variants", nullable = false)
    private Integer totalVariants = 0;

    @Column(name = "total_qty", nullable = false, precision = 14, scale = 3)
    private BigDecimal totalQty = BigDecimal.ZERO;

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
    public String getInwardNo() { return inwardNo; }
    public void setInwardNo(String inwardNo) { this.inwardNo = inwardNo; }
    public Integer getTotalVariants() { return totalVariants; }
    public void setTotalVariants(Integer totalVariants) { this.totalVariants = totalVariants; }
    public BigDecimal getTotalQty() { return totalQty; }
    public void setTotalQty(BigDecimal totalQty) { this.totalQty = totalQty; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
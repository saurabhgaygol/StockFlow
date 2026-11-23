package com.stockmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * One "Create tags" transaction of a company: TAG-2026-000001, TAG-2026-000002 ...
 * The number runs per company and per year (every company starts at 000001).
 * Its products are TagBatchItem rows; every printed piece is a TagLabel with its own barcode.
 */
@Entity
@Table(
    name = "tag_batch",
    uniqueConstraints = @UniqueConstraint(name = "uk_tag_batch_company_no", columnNames = {"company_name", "batch_no"}),
    indexes = @Index(name = "idx_tag_batch_company", columnList = "company_name, id")
)
public class TagBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** TAG-2026-000001 */
    @Column(name = "batch_no", nullable = false, length = 30, updatable = false)
    private String batchNo;

    @Column(name = "batch_year", nullable = false)
    private Integer batchYear;

    /** running number inside the company and year (1, 2, 3 ...) */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    /** CLOTHING, FOOTWEAR ... (one of the company's business types) */
    @Column(name = "business_type", nullable = false, length = 30)
    private String businessType;

    @Column(name = "total_items", nullable = false)
    private Integer totalItems;

    @Column(name = "total_tags", nullable = false)
    private Integer totalTags;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** last edit (details changed, qty raised or products added). Barcodes are never changed by an edit. */
    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }

    public Integer getBatchYear() { return batchYear; }
    public void setBatchYear(Integer batchYear) { this.batchYear = batchYear; }

    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public Integer getTotalItems() { return totalItems; }
    public void setTotalItems(Integer totalItems) { this.totalItems = totalItems; }

    public Integer getTotalTags() { return totalTags; }
    public void setTotalTags(Integer totalTags) { this.totalTags = totalTags; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(Long updatedByUserId) { this.updatedByUserId = updatedByUserId; }

    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
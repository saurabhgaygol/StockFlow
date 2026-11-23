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
 * The barcode of one product row of a batch, e.g. T2026000013-001 (exactly one per product).
 * The product's Qty says how many tags with this barcode are printed; copies are not stored.
 * The barcode is unique inside the company and never changes, so a lost tag is printed again with the same code.
 */
@Entity
@Table(
    name = "tag_label",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_tag_label_company_barcode", columnNames = {"company_name", "barcode"}),
        // one barcode per product row; its Qty says how many tags are printed
        @UniqueConstraint(name = "uk_tag_label_item", columnNames = {"item_id"})
    },
    indexes = @Index(name = "idx_tag_label_batch", columnList = "batch_id, seq_no")
)
public class TagLabel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, updatable = false)
    private String companyName;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private Long batchId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** 1, 2, 3 ... inside the batch */
    @Column(name = "seq_no", nullable = false)
    private Integer seqNo;

    @Column(name = "barcode", nullable = false, length = 40, updatable = false)
    private String barcode;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }

    public Long getItemId() { return itemId; }
    public void setItemId(Long itemId) { this.itemId = itemId; }

    public Integer getSeqNo() { return seqNo; }
    public void setSeqNo(Integer seqNo) { this.seqNo = seqNo; }

    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One stock-out (outward) of a retail variant, with a snapshot of the item as it was. */
@Entity
@Table(
    name = "retail_stock_out",
    indexes = {
        @Index(name = "idx_retail_stock_out_company", columnList = "company_name, id"),
        @Index(name = "idx_retail_stock_out_group", columnList = "company_name, out_group_id")
    }
)
public class RetailStockOut {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** e.g. OUT-2026-000005 (set right after the first save, when the id is known) */
    @Column(name = "out_no", length = 30)
    private String outNo;

    /** items of one outward share one group id (= id of its first item); it is also the id used in URLs */
    @Column(name = "out_group_id")
    private Long outGroupId;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "product_label", nullable = false, length = 255)
    private String productLabel;

    @Column(name = "style_code", length = 50)
    private String styleCode;

    @Column(name = "size_label", length = 50)
    private String sizeLabel;

    @Column(length = 50)
    private String colour;

    @Column(length = 64)
    private String barcode;

    @Column(nullable = false, length = 10)
    private String unit;

    /** always positive: how much went out */
    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal qty;

    @Column(length = 150)
    private String reason;

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
    public String getOutNo() { return outNo; }
    public void setOutNo(String outNo) { this.outNo = outNo; }
    public Long getOutGroupId() { return outGroupId; }
    public void setOutGroupId(Long outGroupId) { this.outGroupId = outGroupId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public String getProductLabel() { return productLabel; }
    public void setProductLabel(String productLabel) { this.productLabel = productLabel; }
    public String getStyleCode() { return styleCode; }
    public void setStyleCode(String styleCode) { this.styleCode = styleCode; }
    public String getSizeLabel() { return sizeLabel; }
    public void setSizeLabel(String sizeLabel) { this.sizeLabel = sizeLabel; }
    public String getColour() { return colour; }
    public void setColour(String colour) { this.colour = colour; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
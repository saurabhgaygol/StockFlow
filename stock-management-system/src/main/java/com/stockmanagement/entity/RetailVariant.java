package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Retail stock row: one sellable variant (size / colour) of a product, with its own barcode and quantity.
 * Company-wise: companyName always comes from the logged-in user, never from a form.
 */
@Entity
@Table(
    name = "retail_variant",
    uniqueConstraints = @UniqueConstraint(name = "uk_retail_variant_barcode", columnNames = {"company_name", "barcode"}),
    indexes = @Index(name = "idx_retail_variant_company", columnList = "company_name, product_id")
)
public class RetailVariant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** points to product_category.id */
    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "size_label", length = 50)
    private String sizeLabel;

    @Column(length = 50)
    private String colour;

    /** optional; unique per company when present (MySQL allows many NULLs) */
    @Column(length = 64)
    private String barcode;

    /** optional style / article code that groups the sizes and colours of one design */
    @Column(name = "style_code", length = 50)
    private String styleCode;

    @Column(nullable = false, length = 10)
    private String unit = "pcs";

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal qty = BigDecimal.ZERO;

    @Column(precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false)
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

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getSizeLabel() { return sizeLabel; }
    public void setSizeLabel(String sizeLabel) { this.sizeLabel = sizeLabel; }
    public String getColour() { return colour; }
    public void setColour(String colour) { this.colour = colour; }
    public String getBarcode() { return barcode; }
    public void setBarcode(String barcode) { this.barcode = barcode; }
    public String getStyleCode() { return styleCode; }
    public void setStyleCode(String styleCode) { this.styleCode = styleCode; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public BigDecimal getQty() { return qty; }
    public void setQty(BigDecimal qty) { this.qty = qty; }
    public BigDecimal getMrp() { return mrp; }
    public void setMrp(BigDecimal mrp) { this.mrp = mrp; }
    public BigDecimal getSalePrice() { return salePrice; }
    public void setSalePrice(BigDecimal salePrice) { this.salePrice = salePrice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
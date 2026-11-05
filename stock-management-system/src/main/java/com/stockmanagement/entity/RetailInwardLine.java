package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Snapshot of one variant as it was received in an inward (stays the same even if the variant is edited later). */
@Entity
@Table(
    name = "retail_inward_line",
    indexes = @Index(name = "idx_retail_inward_line", columnList = "inward_id")
)
public class RetailInwardLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inward_id", nullable = false)
    private Long inwardId;

    @Column(name = "variant_id")
    private Long variantId;

    @Column(name = "product_label", nullable = false, length = 255)
    private String productLabel;

    @Column(name = "size_label", length = 50)
    private String sizeLabel;

    @Column(length = 50)
    private String colour;

    @Column(length = 64)
    private String barcode;

    @Column(name = "style_code", length = 50)
    private String styleCode;

    @Column(nullable = false, length = 10)
    private String unit;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal qty;

    @Column(precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(name = "sale_price", precision = 12, scale = 2)
    private BigDecimal salePrice;

    public Long getId() { return id; }
    public Long getInwardId() { return inwardId; }
    public void setInwardId(Long inwardId) { this.inwardId = inwardId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public String getProductLabel() { return productLabel; }
    public void setProductLabel(String productLabel) { this.productLabel = productLabel; }
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
}
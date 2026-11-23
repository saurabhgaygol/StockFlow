package com.stockmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * One product row of a tag batch, e.g. "Shirt / M / Navy / MV-LS-204 / MRP 1499 / qty 3".
 * qty = how many tags (pieces) are printed for this row.
 */
@Entity
@Table(name = "tag_batch_item", indexes = @Index(name = "idx_tag_item_batch", columnList = "batch_id"))
public class TagBatchItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "category", nullable = false, length = 60)
    private String category;

    /** size / UK size / purity / net qty ... (label depends on the business type) */
    @Column(name = "size_label", length = 30)
    private String sizeLabel;

    /** colour / shade / metal / brand ... (label depends on the business type) */
    @Column(name = "colour", length = 40)
    private String colour;

    /** style code / article no / design code / batch no ... (optional) */
    @Column(name = "style_code", length = 40)
    private String styleCode;

    @Column(name = "mrp", precision = 12, scale = 2)
    private BigDecimal mrp;

    @Column(name = "qty", nullable = false)
    private Integer qty;

    public Long getId() { return id; }

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }

    public Integer getLineNo() { return lineNo; }
    public void setLineNo(Integer lineNo) { this.lineNo = lineNo; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSizeLabel() { return sizeLabel; }
    public void setSizeLabel(String sizeLabel) { this.sizeLabel = sizeLabel; }

    public String getColour() { return colour; }
    public void setColour(String colour) { this.colour = colour; }

    public String getStyleCode() { return styleCode; }
    public void setStyleCode(String styleCode) { this.styleCode = styleCode; }

    public BigDecimal getMrp() { return mrp; }
    public void setMrp(BigDecimal mrp) { this.mrp = mrp; }

    public Integer getQty() { return qty; }
    public void setQty(Integer qty) { this.qty = qty; }
}
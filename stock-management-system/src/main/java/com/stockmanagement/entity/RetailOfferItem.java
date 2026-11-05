package com.stockmanagement.entity;

import jakarta.persistence.*;

/** One thing an offer applies to: a variant, a whole product, a category or a style code. */
@Entity
@Table(
    name = "retail_offer_item",
    indexes = {
        @Index(name = "idx_retail_offer_item_offer", columnList = "offer_id"),
        @Index(name = "idx_retail_offer_item_company", columnList = "company_name, scope_type")
    }
)
public class RetailOfferItem {

    public static final String VARIANT = "VARIANT";
    public static final String PRODUCT = "PRODUCT";
    public static final String CATEGORY = "CATEGORY";
    public static final String STYLE = "STYLE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    @Column(name = "scope_type", nullable = false, length = 10)
    private String scopeType;

    /** variant id, product id, category name or style code (depends on scopeType) */
    @Column(name = "ref_value", nullable = false, length = 150)
    private String refValue;

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }
    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }
    public String getRefValue() { return refValue; }
    public void setRefValue(String refValue) { this.refValue = refValue; }
}
package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Shop details printed on invoices (one row per company). */
@Entity
@Table(name = "retail_shop_profile")
public class RetailShopProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, unique = true)
    private String companyName;

    @Column(name = "shop_name", length = 150)
    private String shopName;

    @Column(name = "address", length = 300)
    private String address;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "email", length = 150)
    private String email;

    @Column(name = "website", length = 200)
    private String website;

    @Column(name = "gstin", length = 15)
    private String gstin;

    @Column(name = "state_name", length = 60)
    private String stateName;

    @Column(name = "footer_note", length = 200)
    private String footerNote;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() { updatedAt = LocalDateTime.now(); }


    @Column(name = "points_rate_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal pointsRatePercent = new BigDecimal("0.50");

    /** no fixed value: blank until the shop sets it (blank = birthday offer is off) */
    @Column(name = "birthday_discount_percent", precision = 5, scale = 2)
    private BigDecimal birthdayDiscountPercent;

    /** no fixed value: blank until the shop sets it (blank = every manual discount needs a manager) */
    @Column(name = "cashier_discount_limit", precision = 5, scale = 2)
    private BigDecimal cashierDiscountLimit;

    /** GST % used for any product that has no GST % of its own (new shops start at 18) */
    @Column(name = "default_gst_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal defaultGstRate = new BigDecimal("18.00");

    /** the shop's UPI ID (e.g. dukaan@okhdfcbank); used to show a payment QR with the bill amount */
    @Column(name = "upi_id", length = 100)
    private String upiId;

    public String getUpiId() { return upiId; }
    public void setUpiId(String upiId) { this.upiId = upiId; }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getShopName() { return shopName; }
    public void setShopName(String shopName) { this.shopName = shopName; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public String getGstin() { return gstin; }
    public void setGstin(String gstin) { this.gstin = gstin; }
    public String getStateName() { return stateName; }
    public void setStateName(String stateName) { this.stateName = stateName; }
    public String getFooterNote() { return footerNote; }
    public void setFooterNote(String footerNote) { this.footerNote = footerNote; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public BigDecimal getPointsRatePercent() { return pointsRatePercent; }
    public void setPointsRatePercent(BigDecimal pointsRatePercent) { this.pointsRatePercent = pointsRatePercent; }
    public BigDecimal getBirthdayDiscountPercent() { return birthdayDiscountPercent; }
    public void setBirthdayDiscountPercent(BigDecimal birthdayDiscountPercent) { this.birthdayDiscountPercent = birthdayDiscountPercent; }
    public BigDecimal getDefaultGstRate() { return defaultGstRate; }
    public void setDefaultGstRate(BigDecimal defaultGstRate) { this.defaultGstRate = defaultGstRate; }
    public BigDecimal getCashierDiscountLimit() { return cashierDiscountLimit; }
    public void setCashierDiscountLimit(BigDecimal cashierDiscountLimit) { this.cashierDiscountLimit = cashierDiscountLimit; }
}
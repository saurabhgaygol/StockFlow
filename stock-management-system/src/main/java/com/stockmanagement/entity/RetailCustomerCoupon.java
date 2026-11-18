package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** A one-time coupon code. Either tied to one customer (mobile number), or open: anyone who brings it can use it once. */
@Entity
@Table(name = "retail_customer_coupon",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_retail_cust_coupon_code", columnNames = {"company_name", "code"}),
        @UniqueConstraint(name = "uk_retail_cust_coupon_mobile", columnNames = {"company_name", "offer_id", "mobile"})
    },
    indexes = @Index(name = "idx_retail_cust_coupon_offer", columnList = "company_name, offer_id"))
public class RetailCustomerCoupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    /** empty for a one-time code that anyone can use */
    @Column(length = 15)
    private String mobile;

    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(name = "used_bill_id")
    private Long usedBillId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getOfferId() { return offerId; }
    public void setOfferId(Long offerId) { this.offerId = offerId; }
    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getUsedBillId() { return usedBillId; }
    public void setUsedBillId(Long usedBillId) { this.usedBillId = usedBillId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
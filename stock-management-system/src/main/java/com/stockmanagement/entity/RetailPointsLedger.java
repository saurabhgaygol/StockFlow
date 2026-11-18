package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Every change of a customer's loyalty points: earned on a bill, used on a bill, or reversed when a bill is cancelled. */
@Entity
@Table(name = "retail_points_ledger",
    indexes = @Index(name = "idx_retail_points_customer", columnList = "company_name, customer_id"))
public class RetailPointsLedger {

    public static final String EARNED = "EARNED";
    public static final String REDEEMED = "REDEEMED";
    public static final String CANCEL_EARNED = "CANCEL_EARNED";
    public static final String CANCEL_REDEEMED = "CANCEL_REDEEMED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "bill_id")
    private Long billId;

    /** + points added, - points taken */
    @Column(nullable = false)
    private Long points;

    @Column(nullable = false, length = 60)
    private String reason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public Long getBillId() { return billId; }
    public void setBillId(Long billId) { this.billId = billId; }
    public Long getPoints() { return points; }
    public void setPoints(Long points) { this.points = points; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
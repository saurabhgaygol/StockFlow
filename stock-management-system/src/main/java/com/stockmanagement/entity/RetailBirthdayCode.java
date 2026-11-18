package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** A one-time birthday code: valid for one customer, for the whole birthday month, and used only once. */
@Entity
@Table(name = "retail_birthday_code",
    uniqueConstraints = @UniqueConstraint(name = "uk_retail_birthday_code", columnNames = {"company_name", "code"}),
    indexes = @Index(name = "idx_retail_birthday_customer", columnList = "company_name, customer_id, valid_on"))
public class RetailBirthdayCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(name = "valid_on", nullable = false)
    private LocalDate validOn;

    @Column(name = "used_bill_id")
    private Long usedBillId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public LocalDate getValidOn() { return validOn; }
    public void setValidOn(LocalDate validOn) { this.validOn = validOn; }
    public Long getUsedBillId() { return usedBillId; }
    public void setUsedBillId(Long usedBillId) { this.usedBillId = usedBillId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
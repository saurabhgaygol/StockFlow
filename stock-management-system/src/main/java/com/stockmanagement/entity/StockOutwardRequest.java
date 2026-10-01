package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_outward_request", indexes = {
        @Index(name = "idx_so_company_status", columnList = "company_name, status"),
        @Index(name = "idx_so_requested_by", columnList = "requested_by_id") })
public class StockOutwardRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_no", length = 30, unique = true)
    private String requestNo;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "requested_by_id", nullable = false)
    private Long requestedById;

    @Column(name = "requested_by_name", nullable = false, length = 150)
    private String requestedByName;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    // customer ki details request ke waqt ki copy (baad mein customer badle to purani request waisi hi rahe)
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_mobile", length = 20)
    private String customerMobile;

    @Column(name = "customer_company", length = 200)
    private String customerCompany;

    @Column(name = "customer_gst", length = 20)
    private String customerGst;

    @Column(name = "customer_email", length = 150)
    private String customerEmail;

    @Column(name = "customer_address", length = 300)
    private String customerAddress;

    @Column(name = "customer_city", length = 100)
    private String customerCity;

    @Column(name = "customer_state", length = 100)
    private String customerState;

    @Column(name = "customer_pincode", length = 10)
    private String customerPincode;

    @Column(name = "deal_name", length = 200)
    private String dealName;

    @Column(length = 1000)
    private String remarks;

    // PENDING / ON_HOLD / ISSUED / REJECTED / CANCELLED
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "current_level", nullable = false)
    private Integer currentLevel;

    @Column(name = "total_levels", nullable = false)
    private Integer totalLevels;

    @Column(name = "total_amount", precision = 14, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getRequestedById() { return requestedById; }
    public void setRequestedById(Long requestedById) { this.requestedById = requestedById; }
    public String getRequestedByName() { return requestedByName; }
    public void setRequestedByName(String requestedByName) { this.requestedByName = requestedByName; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public String getCustomerMobile() { return customerMobile; }
    public void setCustomerMobile(String customerMobile) { this.customerMobile = customerMobile; }
    public String getCustomerCompany() { return customerCompany; }
    public void setCustomerCompany(String customerCompany) { this.customerCompany = customerCompany; }
    public String getCustomerGst() { return customerGst; }
    public void setCustomerGst(String customerGst) { this.customerGst = customerGst; }
    public String getCustomerEmail() { return customerEmail; }
    public void setCustomerEmail(String customerEmail) { this.customerEmail = customerEmail; }
    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }
    public String getCustomerCity() { return customerCity; }
    public void setCustomerCity(String customerCity) { this.customerCity = customerCity; }
    public String getCustomerState() { return customerState; }
    public void setCustomerState(String customerState) { this.customerState = customerState; }
    public String getCustomerPincode() { return customerPincode; }
    public void setCustomerPincode(String customerPincode) { this.customerPincode = customerPincode; }
    public String getDealName() { return dealName; }
    public void setDealName(String dealName) { this.dealName = dealName; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getCurrentLevel() { return currentLevel; }
    public void setCurrentLevel(Integer currentLevel) { this.currentLevel = currentLevel; }
    public Integer getTotalLevels() { return totalLevels; }
    public void setTotalLevels(Integer totalLevels) { this.totalLevels = totalLevels; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
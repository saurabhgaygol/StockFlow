package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * One line per device per movement of FIELD STAFF stock
 * (office -> staff, staff -> office, staff -> staff, staff -> customer ...).
 * This is the permanent "who gave what to whom" register for staff stock.
 */
@Entity
@Table(name = "staff_stock_movement", indexes = {
        @Index(name = "idx_ssm_company", columnList = "company_name, created_at"),
        @Index(name = "idx_ssm_stock", columnList = "stock_id"),
        @Index(name = "idx_ssm_batch", columnList = "batch_no"),
        @Index(name = "idx_ssm_to", columnList = "to_user_id"),
        @Index(name = "idx_ssm_from", columnList = "from_user_id") })
public class StaffStockMovement {

    public static final String ISSUE = "ISSUE";                 // office -> staff
    public static final String RETURN_TO_OFFICE = "RETURN";     // staff -> office
    public static final String TRANSFER = "TRANSFER";           // staff -> staff
    public static final String EXCHANGE_TEMP = "EXCH_TEMP";     // staff stock fitted at customer (temporary)
    public static final String EXCHANGE_PERM = "EXCH_PERM";     // staff stock fitted at customer (permanent)
    public static final String TEMP_BACK = "TEMP_BACK";         // temporary device taken back by staff
    public static final String SOLD = "SOLD";                   // sold to customer from staff stock

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    /** SI-2026-000012 : all devices moved in one action share this number. */
    @Column(name = "batch_no", length = 30)
    private String batchNo;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "imei_number", length = 60)
    private String imeiNumber;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Column(name = "movement_type", nullable = false, length = 12)
    private String movementType;

    /** null = office. */
    @Column(name = "from_user_id")
    private Long fromUserId;

    @Column(name = "from_name", length = 150)
    private String fromName;

    /** null = office / customer. */
    @Column(name = "to_user_id")
    private Long toUserId;

    @Column(name = "to_name", length = 150)
    private String toName;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "vehicle_no", length = 60)
    private String vehicleNo;

    /** Return case / outward request this movement belongs to (RC-... / SO-...). */
    @Column(name = "ref_no", length = 30)
    private String refNo;

    @Column(length = 500)
    private String remarks;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }
    public String getImeiNumber() { return imeiNumber; }
    public void setImeiNumber(String imeiNumber) { this.imeiNumber = imeiNumber; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }
    public Long getFromUserId() { return fromUserId; }
    public void setFromUserId(Long fromUserId) { this.fromUserId = fromUserId; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public Long getToUserId() { return toUserId; }
    public void setToUserId(Long toUserId) { this.toUserId = toUserId; }
    public String getToName() { return toName; }
    public void setToName(String toName) { this.toName = toName; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getVehicleNo() { return vehicleNo; }
    public void setVehicleNo(String vehicleNo) { this.vehicleNo = vehicleNo; }
    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }
    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }
    public Long getCreatedById() { return createdById; }
    public void setCreatedById(Long createdById) { this.createdById = createdById; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /** Readable label for the history table. */
    public String getTypeLabel() {
        if (movementType == null) return "";
        return switch (movementType) {
            case ISSUE -> "Issued to staff";
            case RETURN_TO_OFFICE -> "Returned to office";
            case TRANSFER -> "Staff to staff";
            case EXCHANGE_TEMP -> "Fitted (temporary)";
            case EXCHANGE_PERM -> "Fitted (permanent)";
            case TEMP_BACK -> "Temporary taken back";
            case SOLD -> "Sold from staff stock";
            default -> movementType;
        };
    }
}
package com.stockmanagement.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One customer return of one unit (IMEI).
 * The unit itself keeps living in stock_inward (same row, same IMEI);
 * this table is the permanent record of "who returned it, when, why, and what we did".
 */
@Entity
@Table(name = "stock_return", indexes = {
        @Index(name = "idx_sr_company", columnList = "company_name, created_at"),
        @Index(name = "idx_sr_stock", columnList = "stock_id"),
        @Index(name = "idx_sr_imei", columnList = "company_name, imei_number") })
public class StockReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "stock_id", nullable = false)
    private Long stockId;

    @Column(name = "imei_number", length = 60)
    private String imeiNumber;

    @Column(name = "product_name", length = 100)
    private String productName;

    /** The sale that is being returned. */
    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "request_no", length = 30)
    private String requestNo;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "sold_at")
    private LocalDateTime soldAt;

    @Column(name = "sold_price", precision = 12, scale = 2)
    private BigDecimal soldPrice;

    /** Real-world day the device came back. */
    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    @Column(nullable = false, length = 1000)
    private String reason;

    /** REFURBISHED / NEW / DAMAGED */
    @Column(name = "condition_after", nullable = false, length = 20)
    private String conditionAfter;

    /** Status the unit got after the return: AVAILABLE or DAMAGED. */
    @Column(name = "status_after", nullable = false, length = 20)
    private String statusAfter;

    @Column(name = "warranty_end_date")
    private LocalDate warrantyEndDate;

    /** null = warranty was not recorded for this unit. */
    @Column(name = "in_warranty")
    private Boolean inWarranty;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // ===================== return CASE (phase A) =====================
    // Old rows (created before this feature) have null here and are treated as CLOSED.

    /** RC-2026-000123 */
    @Column(name = "case_no", length = 30)
    private String caseNo;

    /** WITH_VENDOR (device is at the vendor) or CLOSED. */
    @Column(name = "case_status", length = 20)
    private String caseStatus;

    /** RESTOCK / DAMAGED / VENDOR - what was decided when the device came back. */
    @Column(name = "resolution", length = 20)
    private String resolution;

    @Column(name = "vendor_id")
    private Long vendorId;

    @Column(name = "vendor_name", length = 150)
    private String vendorName;

    @Column(name = "challan_no", length = 100)
    private String challanNo;

    @Column(name = "vendor_issue", length = 1000)
    private String vendorIssue;

    @Column(name = "sent_date")
    private LocalDate sentDate;

    @Column(name = "expected_back_date")
    private LocalDate expectedBackDate;

    @Column(name = "vendor_received_date")
    private LocalDate vendorReceivedDate;

    /** REPAIRED / REPLACED / NOT_REPAIRABLE */
    @Column(name = "vendor_result", length = 20)
    private String vendorResult;

    @Column(name = "vendor_notes", length = 1000)
    private String vendorNotes;

    @Column(name = "repair_cost", precision = 12, scale = 2)
    private BigDecimal repairCost;

    /** When the vendor swapped the device: the new unit that came back. */
    @Column(name = "replacement_stock_id")
    private Long replacementStockId;

    @Column(name = "replacement_imei", length = 60)
    private String replacementImei;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Column(name = "closed_by", length = 150)
    private String closedBy;

    // ===================== exchange (phase B) =====================

    /** Exchange: the NEW device that was given to the customer in place of the returned one. */
    @Column(name = "exchange_stock_id")
    private Long exchangeStockId;

    @Column(name = "exchange_imei", length = 60)
    private String exchangeImei;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public Long getStockId() { return stockId; }
    public void setStockId(Long stockId) { this.stockId = stockId; }

    public String getImeiNumber() { return imeiNumber; }
    public void setImeiNumber(String imeiNumber) { this.imeiNumber = imeiNumber; }

    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public LocalDateTime getSoldAt() { return soldAt; }
    public void setSoldAt(LocalDateTime soldAt) { this.soldAt = soldAt; }

    public BigDecimal getSoldPrice() { return soldPrice; }
    public void setSoldPrice(BigDecimal soldPrice) { this.soldPrice = soldPrice; }

    public LocalDate getReturnDate() { return returnDate; }
    public void setReturnDate(LocalDate returnDate) { this.returnDate = returnDate; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getConditionAfter() { return conditionAfter; }
    public void setConditionAfter(String conditionAfter) { this.conditionAfter = conditionAfter; }

    public String getStatusAfter() { return statusAfter; }
    public void setStatusAfter(String statusAfter) { this.statusAfter = statusAfter; }

    public LocalDate getWarrantyEndDate() { return warrantyEndDate; }
    public void setWarrantyEndDate(LocalDate warrantyEndDate) { this.warrantyEndDate = warrantyEndDate; }

    public Boolean getInWarranty() { return inWarranty; }
    public void setInWarranty(Boolean inWarranty) { this.inWarranty = inWarranty; }

    public Long getCreatedById() { return createdById; }
    public void setCreatedById(Long createdById) { this.createdById = createdById; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    // ===================== case getters / setters =====================

    public String getCaseNo() { return caseNo; }
    public void setCaseNo(String caseNo) { this.caseNo = caseNo; }

    public String getCaseStatus() { return caseStatus; }
    public void setCaseStatus(String caseStatus) { this.caseStatus = caseStatus; }

    public String getResolution() { return resolution; }
    public void setResolution(String resolution) { this.resolution = resolution; }

    public Long getVendorId() { return vendorId; }
    public void setVendorId(Long vendorId) { this.vendorId = vendorId; }

    public String getVendorName() { return vendorName; }
    public void setVendorName(String vendorName) { this.vendorName = vendorName; }

    public String getChallanNo() { return challanNo; }
    public void setChallanNo(String challanNo) { this.challanNo = challanNo; }

    public String getVendorIssue() { return vendorIssue; }
    public void setVendorIssue(String vendorIssue) { this.vendorIssue = vendorIssue; }

    public LocalDate getSentDate() { return sentDate; }
    public void setSentDate(LocalDate sentDate) { this.sentDate = sentDate; }

    public LocalDate getExpectedBackDate() { return expectedBackDate; }
    public void setExpectedBackDate(LocalDate expectedBackDate) { this.expectedBackDate = expectedBackDate; }

    public LocalDate getVendorReceivedDate() { return vendorReceivedDate; }
    public void setVendorReceivedDate(LocalDate vendorReceivedDate) { this.vendorReceivedDate = vendorReceivedDate; }

    public String getVendorResult() { return vendorResult; }
    public void setVendorResult(String vendorResult) { this.vendorResult = vendorResult; }

    public String getVendorNotes() { return vendorNotes; }
    public void setVendorNotes(String vendorNotes) { this.vendorNotes = vendorNotes; }

    public BigDecimal getRepairCost() { return repairCost; }
    public void setRepairCost(BigDecimal repairCost) { this.repairCost = repairCost; }

    public Long getReplacementStockId() { return replacementStockId; }
    public void setReplacementStockId(Long replacementStockId) { this.replacementStockId = replacementStockId; }

    public String getReplacementImei() { return replacementImei; }
    public void setReplacementImei(String replacementImei) { this.replacementImei = replacementImei; }

    public LocalDateTime getClosedAt() { return closedAt; }
    public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }

    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }

    /** Old rows (no case status) count as closed. */
    public boolean isOpenAtVendor() { return "WITH_VENDOR".equals(caseStatus); }

    public Long getExchangeStockId() { return exchangeStockId; }
    public void setExchangeStockId(Long exchangeStockId) { this.exchangeStockId = exchangeStockId; }

    public String getExchangeImei() { return exchangeImei; }
    public void setExchangeImei(String exchangeImei) { this.exchangeImei = exchangeImei; }

    /** One readable line for the screen: what finally happened to the device. */
    public String getOutcome() {
        String base = baseOutcome();
        if (exchangeImei != null) {
            return base + " | Exchange: new IMEI " + exchangeImei + " given to customer";
        }
        return base;
    }

    private String baseOutcome() {
        if ("WITH_VENDOR".equals(caseStatus)) {
            return "At vendor: " + (vendorName != null ? vendorName : "-");
        }
        if (vendorResult != null) {
            switch (vendorResult) {
                case "REPAIRED":
                    return "Repaired by vendor -> " + statusAfter + " (" + conditionAfter + ")";
                case "REPLACED":
                    return "Replaced by vendor -> new IMEI " + (replacementImei != null ? replacementImei : "-");
                case "NOT_REPAIRABLE":
                    return "Not repairable -> " + statusAfter;
                default:
                    break;
            }
        }
        return conditionAfter + " -> " + statusAfter;
    }
}
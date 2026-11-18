package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One sale (invoice). Amounts are a snapshot: later price or offer changes never alter an old bill. */
@Entity
@Table(name = "retail_bill",
    indexes = {
        @Index(name = "idx_retail_bill_company", columnList = "company_name, id"),
        @Index(name = "idx_retail_bill_date", columnList = "company_name, bill_date")
    })
public class RetailBill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "bill_no", length = 30)
    private String billNo;

    /** random secret in the link sent to the customer (/b/{token}); nobody can guess it */
    @Column(name = "share_token", length = 40)
    private String shareToken;

    public String getShareToken() { return shareToken; }
    public void setShareToken(String shareToken) { this.shareToken = shareToken; }

    @Column(name = "bill_date", nullable = false)
    private LocalDateTime billDate;

    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "customer_mobile", length = 15)
    private String customerMobile;

    @Column(name = "customer_gstin", length = 15)
    private String customerGstin;

    @Column(name = "gross_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "item_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal itemDiscount;

    @Column(name = "bill_offer_id")
    private Long billOfferId;

    @Column(name = "bill_offer_name", length = 120)
    private String billOfferName;

    @Column(name = "bill_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal billDiscount;

    @Column(name = "taxable_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxableAmount;

    @Column(name = "cgst_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal cgstAmount;

    @Column(name = "sgst_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal sgstAmount;

    @Column(name = "round_off", nullable = false, precision = 6, scale = 2)
    private BigDecimal roundOff;

    @Column(name = "grand_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal grandTotal;

    @Column(name = "status", nullable = false, length = 12)
    private String status;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "cancelled_by", length = 150)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 200)
    private String cancelReason;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    @PrePersist
    protected void onCreate() { createdAt = LocalDateTime.now(); }


    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "manual_discount", nullable = false, precision = 12, scale = 2)
    private BigDecimal manualDiscount = BigDecimal.ZERO;

    @Column(name = "manual_reason", length = 200)
    private String manualReason;

    @Column(name = "manual_approved_by", length = 150)
    private String manualApprovedBy;

    @Column(name = "points_redeemed", nullable = false)
    private Long pointsRedeemed = 0L;

    @Column(name = "points_earned", nullable = false)
    private Long pointsEarned = 0L;

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getBillNo() { return billNo; }
    public void setBillNo(String billNo) { this.billNo = billNo; }
    public LocalDateTime getBillDate() { return billDate; }
    public void setBillDate(LocalDateTime billDate) { this.billDate = billDate; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerMobile() { return customerMobile; }
    public void setCustomerMobile(String customerMobile) { this.customerMobile = customerMobile; }
    public String getCustomerGstin() { return customerGstin; }
    public void setCustomerGstin(String customerGstin) { this.customerGstin = customerGstin; }
    public BigDecimal getGrossAmount() { return grossAmount; }
    public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
    public BigDecimal getItemDiscount() { return itemDiscount; }
    public void setItemDiscount(BigDecimal itemDiscount) { this.itemDiscount = itemDiscount; }
    public Long getBillOfferId() { return billOfferId; }
    public void setBillOfferId(Long billOfferId) { this.billOfferId = billOfferId; }
    public String getBillOfferName() { return billOfferName; }
    public void setBillOfferName(String billOfferName) { this.billOfferName = billOfferName; }
    public BigDecimal getBillDiscount() { return billDiscount; }
    public void setBillDiscount(BigDecimal billDiscount) { this.billDiscount = billDiscount; }
    public BigDecimal getTaxableAmount() { return taxableAmount; }
    public void setTaxableAmount(BigDecimal taxableAmount) { this.taxableAmount = taxableAmount; }
    public BigDecimal getCgstAmount() { return cgstAmount; }
    public void setCgstAmount(BigDecimal cgstAmount) { this.cgstAmount = cgstAmount; }
    public BigDecimal getSgstAmount() { return sgstAmount; }
    public void setSgstAmount(BigDecimal sgstAmount) { this.sgstAmount = sgstAmount; }
    public BigDecimal getRoundOff() { return roundOff; }
    public void setRoundOff(BigDecimal roundOff) { this.roundOff = roundOff; }
    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedById() { return createdById; }
    public void setCreatedById(Long createdById) { this.createdById = createdById; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public String getCancelledBy() { return cancelledBy; }
    public void setCancelledBy(String cancelledBy) { this.cancelledBy = cancelledBy; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getCustomerId() { return customerId; }
    public void setCustomerId(Long customerId) { this.customerId = customerId; }
    public BigDecimal getManualDiscount() { return manualDiscount; }
    public void setManualDiscount(BigDecimal manualDiscount) { this.manualDiscount = manualDiscount; }
    public String getManualReason() { return manualReason; }
    public void setManualReason(String manualReason) { this.manualReason = manualReason; }
    public String getManualApprovedBy() { return manualApprovedBy; }
    public void setManualApprovedBy(String manualApprovedBy) { this.manualApprovedBy = manualApprovedBy; }
    public Long getPointsRedeemed() { return pointsRedeemed; }
    public void setPointsRedeemed(Long pointsRedeemed) { this.pointsRedeemed = pointsRedeemed; }
    public Long getPointsEarned() { return pointsEarned; }
    public void setPointsEarned(Long pointsEarned) { this.pointsEarned = pointsEarned; }
}
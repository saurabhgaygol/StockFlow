package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** One offer of a company. It is live when enabled is ON and today is inside the optional start/end dates. */
@Entity
@Table(
    name = "retail_offer",
    indexes = @Index(name = "idx_retail_offer_company", columnList = "company_name, id")
)
public class RetailOffer {

    public static final String PERCENT = "PERCENT";
    public static final String FLAT = "FLAT";
    public static final String FIXED = "FIXED";
    public static final String BXGY = "BXGY";
    /** bill offers: % off or rupees off the whole bill once it reaches min_bill */
    public static final String BILLPCT = "BILLPCT";
    public static final String BILLFLAT = "BILLFLAT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "offer_type", nullable = false, length = 10)
    private String offerType;

    /** percent, rupees off, or fixed price (not used by BXGY) */
    @Column(precision = 12, scale = 2)
    private BigDecimal value;

    @Column(name = "buy_qty")
    private Integer buyQty;

    @Column(name = "get_qty")
    private Integer getQty;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** optional: only stock whose latest inward is at least this many months old (blank = any age) */
    @Column(name = "min_age_months")
    private Integer minAgeMonths;

    /** Buy X Get Y: which piece of a group is free (CHEAPEST / COSTLIEST) */
    @Column(name = "free_pick", length = 10)
    private String freePick;

    /** Buy X Get Y: a group is made of ANY barcode of the offer or the SAME barcode */
    @Column(name = "group_mode", length = 10)
    private String groupMode;

    /** may be combined with other offers (billing uses this later) */
    @Column(nullable = false)
    private Boolean combinable = Boolean.FALSE;

    /** smaller number wins first; blank = best price wins */
    @Column
    private Integer priority;

    /** bill offers: minimum bill amount */
    @Column(name = "min_bill", precision = 12, scale = 2)
    private BigDecimal minBill;

    /** true = the cashier cannot remove this offer at billing */
    @Column(name = "locked_for_cashier", nullable = false)
    private Boolean lockedForCashier = Boolean.FALSE;

    /** manual ON/OFF switch */
    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOfferType() { return offerType; }
    public void setOfferType(String offerType) { this.offerType = offerType; }
    public BigDecimal getValue() { return value; }
    public void setValue(BigDecimal value) { this.value = value; }
    public Integer getBuyQty() { return buyQty; }
    public void setBuyQty(Integer buyQty) { this.buyQty = buyQty; }
    public Integer getGetQty() { return getQty; }
    public void setGetQty(Integer getQty) { this.getQty = getQty; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public String getFreePick() { return freePick; }
    public void setFreePick(String freePick) { this.freePick = freePick; }
    public String getGroupMode() { return groupMode; }
    public void setGroupMode(String groupMode) { this.groupMode = groupMode; }
    public Boolean getCombinable() { return combinable; }
    public void setCombinable(Boolean combinable) { this.combinable = combinable; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public BigDecimal getMinBill() { return minBill; }
    public void setMinBill(BigDecimal minBill) { this.minBill = minBill; }
    public Boolean getLockedForCashier() { return lockedForCashier; }
    public void setLockedForCashier(Boolean lockedForCashier) { this.lockedForCashier = lockedForCashier; }
    public Integer getMinAgeMonths() { return minAgeMonths; }
    public void setMinAgeMonths(Integer minAgeMonths) { this.minAgeMonths = minAgeMonths; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
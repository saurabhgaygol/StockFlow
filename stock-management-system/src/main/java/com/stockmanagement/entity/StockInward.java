package com.stockmanagement.entity;


import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "stock_inward",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_serial_company", columnNames = {"serial_number", "company_name"})
    },
    indexes = {
        @Index(name = "idx_stock_company_status", columnList = "company_name, status"),
        @Index(name = "idx_stock_vendor", columnList = "vendor_id"),
        @Index(name = "idx_stock_category", columnList = "category_id"),
        @Index(name = "idx_stock_serial", columnList = "serial_number")
    }
)
public class StockInward {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ---- Basic identity ----
    @Column(name = "vendor_id", nullable = false)
    private Long vendorId;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "product_id")
    private Long productId;

    @Column(name = "serial_number", nullable = false, length = 120)
    private String serialNumber;

    @Column(name = "imei_number", length = 60)
    private String imeiNumber;

    // NEW or REFURBISHED
    @Column(name = "item_condition", length = 20)
    private String condition = "NEW";

    // ---- Quantity & pricing ----
    @Column(nullable = false)
    private Integer quantity = 1;

    @Column(name = "unit_price", precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "total_amount", precision = 12, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "tax_percent", precision = 5, scale = 2)
    private BigDecimal taxPercent;

    @Column(name = "grand_total", precision = 12, scale = 2)
    private BigDecimal grandTotal;

    // ---- Dates & warranty ----
    @Column(name = "purchase_date")
    private LocalDate purchaseDate;

    @Column(name = "warranty_period_months")
    private Integer warrantyPeriodMonths;

    @Column(name = "warranty_start_date")
    private LocalDate warrantyStartDate;

    @Column(name = "warranty_end_date")
    private LocalDate warrantyEndDate;

    // ---- Purchase reference ----
    @Column(name = "invoice_number", length = 100)
    private String invoiceNumber;

    @Column(name = "po_number", length = 100)
    private String poNumber;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    // ---- Storage & status ----
    @Column(length = 150)
    private String warehouse;

    // AVAILABLE / RESERVED / ISSUED / RETURNED / WITH_STAFF
    @Column(nullable = false, length = 20)
    private String status = "AVAILABLE";

    @Column(length = 500)
    private String remarks;

    // ---- System / audit ----
    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
 // Stock Outward se issue hone par set hota hai
    @Column(name = "outward_request_id")
    private Long outwardRequestId;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    // ============================================================
    // FIELD STAFF STOCK (status WITH_STAFF)
    // ============================================================
    /** User (field staff) who physically holds this unit. null = not with staff. */
    @Column(name = "holder_user_id")
    private Long holderUserId;

    @Column(name = "holder_since")
    private LocalDateTime holderSince;

    /** TEMPORARY when staff has fitted it at a customer as a stand-in; null otherwise. */
    @Column(name = "install_type", length = 12)
    private String installType;

    @Column(name = "install_customer", length = 200)
    private String installCustomer;

    @Column(name = "install_vehicle", length = 60)
    private String installVehicle;

    /** stock_return.id of the exchange case this temporary install belongs to. */
    @Column(name = "install_return_id")
    private Long installReturnId;

    @Column(name = "install_at")
    private LocalDateTime installAt;

    // ============================================================
    // READ-ONLY RELATIONSHIPS (JOIN ke liye)
    // insertable=false, updatable=false — kyunki vendorId/categoryId/productId
    // fields already insert/update handle karte hain.
    // ============================================================
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id", insertable = false, updatable = false)
    @JsonIgnore
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", insertable = false, updatable = false)
    @JsonIgnore
    private ProductCategory category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", insertable = false, updatable = false)
    @JsonIgnore
    private ProductCategory product;

    /** Not stored: staff name shown in the Stock Inward list ("with Pavan"). */
    @Transient
    private String holderName;

    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }


    // ---- Getters and Setters ----

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getVendorId() { return vendorId; }
    public void setVendorId(Long vendorId) { this.vendorId = vendorId; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getImeiNumber() { return imeiNumber; }
    public void setImeiNumber(String imeiNumber) { this.imeiNumber = imeiNumber; }

    public String getCondition() { return condition; }
    public void setCondition(String condition) { this.condition = condition; }

    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public BigDecimal getTaxPercent() { return taxPercent; }
    public void setTaxPercent(BigDecimal taxPercent) { this.taxPercent = taxPercent; }

    public BigDecimal getGrandTotal() { return grandTotal; }
    public void setGrandTotal(BigDecimal grandTotal) { this.grandTotal = grandTotal; }

    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }

    public Integer getWarrantyPeriodMonths() { return warrantyPeriodMonths; }
    public void setWarrantyPeriodMonths(Integer warrantyPeriodMonths) { this.warrantyPeriodMonths = warrantyPeriodMonths; }

    public LocalDate getWarrantyStartDate() { return warrantyStartDate; }
    public void setWarrantyStartDate(LocalDate warrantyStartDate) { this.warrantyStartDate = warrantyStartDate; }

    public LocalDate getWarrantyEndDate() { return warrantyEndDate; }
    public void setWarrantyEndDate(LocalDate warrantyEndDate) { this.warrantyEndDate = warrantyEndDate; }

    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }

    public String getPoNumber() { return poNumber; }
    public void setPoNumber(String poNumber) { this.poNumber = poNumber; }

    public String getBatchNumber() { return batchNumber; }
    public void setBatchNumber(String batchNumber) { this.batchNumber = batchNumber; }

    public String getWarehouse() { return warehouse; }
    public void setWarehouse(String warehouse) { this.warehouse = warehouse; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    // ---- Relationship getters/setters ----
    public Vendor getVendor() { return vendor; }
    public void setVendor(Vendor vendor) { this.vendor = vendor; }

    public ProductCategory getCategory() { return category; }
    public void setCategory(ProductCategory category) { this.category = category; }

    public ProductCategory getProduct() { return product; }
    public void setProduct(ProductCategory product) { this.product = product; }

    // ---- Convenience getters (template ke liye) ----
    public String getVendorName() {
        return this.vendor != null ? this.vendor.getVendorName() : null;
    }

    public String getCategoryName() {
        return this.category != null ? this.category.getCategoryName() : null;
    }

    public String getProductName() {
        return this.product != null ? this.product.getProductName() : null;
    }

	public Long getOutwardRequestId() {
		return outwardRequestId;
	}

	public void setOutwardRequestId(Long outwardRequestId) {
		this.outwardRequestId = outwardRequestId;
	}

	// ---- Field staff stock getters/setters ----
	public Long getHolderUserId() { return holderUserId; }
	public void setHolderUserId(Long holderUserId) { this.holderUserId = holderUserId; }

	public LocalDateTime getHolderSince() { return holderSince; }
	public void setHolderSince(LocalDateTime holderSince) { this.holderSince = holderSince; }

	public String getInstallType() { return installType; }
	public void setInstallType(String installType) { this.installType = installType; }

	public String getInstallCustomer() { return installCustomer; }
	public void setInstallCustomer(String installCustomer) { this.installCustomer = installCustomer; }

	public String getInstallVehicle() { return installVehicle; }
	public void setInstallVehicle(String installVehicle) { this.installVehicle = installVehicle; }

	public Long getInstallReturnId() { return installReturnId; }
	public void setInstallReturnId(Long installReturnId) { this.installReturnId = installReturnId; }

	public LocalDateTime getInstallAt() { return installAt; }
	public void setInstallAt(LocalDateTime installAt) { this.installAt = installAt; }

	public LocalDateTime getIssuedAt() {
		return issuedAt;
	}

	public void setIssuedAt(LocalDateTime issuedAt) {
		this.issuedAt = issuedAt;
	}
}
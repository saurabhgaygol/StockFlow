package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_unit_history", indexes = {
        @Index(name = "idx_suh_stock", columnList = "stock_id, created_at"),
        @Index(name = "idx_suh_imei", columnList = "company_name, imei_number") })
public class StockUnitHistory {

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

    // INWARD / SOLD / STATUS_CHANGED (aage: RETURNED, REPLACED, SENT_TO_VENDOR, REPAIRED ...)
    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", length = 20)
    private String toStatus;

    @Column(name = "request_id")
    private Long requestId;

    @Column(name = "request_no", length = 30)
    private String requestNo;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(length = 1000)
    private String reason;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    /** Real-world date of the event (e.g. the day the device actually came back). Optional. */
    @Column(name = "event_date")
    private java.time.LocalDate eventDate;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getCompanyName() {
		return companyName;
	}

	public void setCompanyName(String companyName) {
		this.companyName = companyName;
	}

	public Long getStockId() {
		return stockId;
	}

	public void setStockId(Long stockId) {
		this.stockId = stockId;
	}

	public String getImeiNumber() {
		return imeiNumber;
	}

	public void setImeiNumber(String imeiNumber) {
		this.imeiNumber = imeiNumber;
	}

	public String getProductName() {
		return productName;
	}

	public void setProductName(String productName) {
		this.productName = productName;
	}

	public String getEventType() {
		return eventType;
	}

	public void setEventType(String eventType) {
		this.eventType = eventType;
	}

	public String getFromStatus() {
		return fromStatus;
	}

	public void setFromStatus(String fromStatus) {
		this.fromStatus = fromStatus;
	}

	public String getToStatus() {
		return toStatus;
	}

	public void setToStatus(String toStatus) {
		this.toStatus = toStatus;
	}

	public Long getRequestId() {
		return requestId;
	}

	public void setRequestId(Long requestId) {
		this.requestId = requestId;
	}

	public String getRequestNo() {
		return requestNo;
	}

	public void setRequestNo(String requestNo) {
		this.requestNo = requestNo;
	}

	public String getCustomerName() {
		return customerName;
	}

	public void setCustomerName(String customerName) {
		this.customerName = customerName;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public Long getActorId() {
		return actorId;
	}

	public void setActorId(Long actorId) {
		this.actorId = actorId;
	}

	public String getActorName() {
		return actorName;
	}

	public void setActorName(String actorName) {
		this.actorName = actorName;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public java.time.LocalDate getEventDate() {
		return eventDate;
	}

	public void setEventDate(java.time.LocalDate eventDate) {
		this.eventDate = eventDate;
	}
    
    
    
    
    
    
    
    
}
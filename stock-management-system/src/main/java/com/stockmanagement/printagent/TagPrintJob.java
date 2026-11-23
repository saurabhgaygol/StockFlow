package com.stockmanagement.printagent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** One "Print" press: the tags of a batch to print on one printer of one agent. Status QUEUED > SENT > DONE or FAILED. */
@Entity
@Table(name = "tag_print_job", indexes = @Index(name = "ix_tag_print_job_agent_status", columnList = "agent_id, status"))
public class TagPrintJob {

    public static final String QUEUED = "QUEUED", SENT = "SENT", DONE = "DONE", FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, updatable = false)
    private String companyName;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "batch_no", length = 40)
    private String batchNo;

    @Column(name = "printer_code", nullable = false, length = 20)
    private String printerCode;

    @Column(name = "printer_name", nullable = false, length = 200)
    private String printerName;

    @Column(name = "width_mm", nullable = false)
    private Float widthMm;

    @Column(name = "height_mm", nullable = false)
    private Float heightMm;

    @Column(name = "dpi", nullable = false)
    private Integer dpi;

    /** "tagId:copies;tagId:copies" */
    @Column(name = "items", nullable = false, length = 8000)
    private String items;

    @Column(name = "total_tags", nullable = false)
    private Integer totalTags;

    @Column(name = "status", nullable = false, length = 12)
    private String status = QUEUED;

    @Column(name = "message", length = 300)
    private String message;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "done_at")
    private LocalDateTime doneAt;

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getPrinterCode() { return printerCode; }
    public void setPrinterCode(String printerCode) { this.printerCode = printerCode; }
    public String getPrinterName() { return printerName; }
    public void setPrinterName(String printerName) { this.printerName = printerName; }
    public Float getWidthMm() { return widthMm; }
    public void setWidthMm(Float widthMm) { this.widthMm = widthMm; }
    public Float getHeightMm() { return heightMm; }
    public void setHeightMm(Float heightMm) { this.heightMm = heightMm; }
    public Integer getDpi() { return dpi; }
    public void setDpi(Integer dpi) { this.dpi = dpi; }
    public String getItems() { return items; }
    public void setItems(String items) { this.items = items; }
    public Integer getTotalTags() { return totalTags; }
    public void setTotalTags(Integer totalTags) { this.totalTags = totalTags; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDateTime getDoneAt() { return doneAt; }
    public void setDoneAt(LocalDateTime doneAt) { this.doneAt = doneAt; }
}
package com.stockmanagement.printagent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/** Which real printer prints one tag size (printer_code, e.g. "50x38") for a shop. Chosen once in Tag Studio. */
@Entity
@Table(name = "tag_printer_route",
       uniqueConstraints = @UniqueConstraint(name = "uk_tag_printer_route", columnNames = {"company_name", "printer_code"}))
public class TagPrinterRoute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, updatable = false)
    private String companyName;

    @Column(name = "printer_code", nullable = false, length = 20, updatable = false)
    private String printerCode;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "printer_name", nullable = false, length = 200)
    private String printerName;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getPrinterCode() { return printerCode; }
    public void setPrinterCode(String printerCode) { this.printerCode = printerCode; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getPrinterName() { return printerName; }
    public void setPrinterName(String printerName) { this.printerName = printerName; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
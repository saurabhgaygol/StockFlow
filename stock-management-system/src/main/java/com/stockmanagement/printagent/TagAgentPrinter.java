package com.stockmanagement.printagent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** A printer the agent found on its computer (the list is replaced every time the agent reports). */
@Entity
@Table(name = "tag_agent_printer", indexes = @Index(name = "ix_tag_agent_printer_agent", columnList = "agent_id"))
public class TagAgentPrinter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_id", nullable = false)
    private Long agentId;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "printer_name", nullable = false, length = 200)
    private String printerName;

    @Column(name = "is_default", nullable = false)
    private boolean defaultPrinter;

    @Column(name = "status", length = 40)
    private String status;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public Long getAgentId() { return agentId; }
    public void setAgentId(Long agentId) { this.agentId = agentId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getPrinterName() { return printerName; }
    public void setPrinterName(String printerName) { this.printerName = printerName; }
    public boolean isDefaultPrinter() { return defaultPrinter; }
    public void setDefaultPrinter(boolean defaultPrinter) { this.defaultPrinter = defaultPrinter; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
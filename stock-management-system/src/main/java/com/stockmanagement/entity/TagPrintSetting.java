package com.stockmanagement.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * Tag Studio print setting of one company: the printer (tag size) and the tag design every PDF is made with.
 * Stays the same until someone in the shop changes it. Codes come from TagDesigns.PRINTERS / TagDesigns.DESIGNS.
 */
@Entity
@Table(name = "tag_print_setting",
       uniqueConstraints = @UniqueConstraint(name = "uk_tag_print_setting_company", columnNames = "company_name"))
public class TagPrintSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, updatable = false)
    private String companyName;

    @Column(name = "printer_code", nullable = false, length = 20)
    private String printerCode;

    @Column(name = "design_code", nullable = false, length = 30)
    private String designCode;

    @Column(name = "updated_by_user_id")
    private Long updatedByUserId;

    @Column(name = "updated_by_name", length = 150)
    private String updatedByName;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }

    public String getPrinterCode() { return printerCode; }
    public void setPrinterCode(String printerCode) { this.printerCode = printerCode; }

    public String getDesignCode() { return designCode; }
    public void setDesignCode(String designCode) { this.designCode = designCode; }

    public Long getUpdatedByUserId() { return updatedByUserId; }
    public void setUpdatedByUserId(Long updatedByUserId) { this.updatedByUserId = updatedByUserId; }

    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
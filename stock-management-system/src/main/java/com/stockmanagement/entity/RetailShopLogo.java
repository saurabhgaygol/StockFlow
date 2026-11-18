package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The shop logo printed on bills (one row per company; kept apart from the profile so the profile stays light). */
@Entity
@Table(name = "retail_shop_logo")
public class RetailShopLogo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, unique = true)
    private String companyName;

    @Column(name = "content_type", nullable = false, length = 40)
    private String contentType;

    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @Column(name = "image_data", nullable = false, columnDefinition = "LONGBLOB")
    private byte[] imageData;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public byte[] getImageData() { return imageData; }
    public void setImageData(byte[] imageData) { this.imageData = imageData; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
package com.stockmanagement.entity;

import jakarta.persistence.*;

/** Company-wise AI policy (abhi: employees apni personal API key laga sakte hain ya nahi). */
@Entity
@Table(name = "ai_company_setting",
       uniqueConstraints = @UniqueConstraint(name = "uk_ai_setting_company", columnNames = "company_name"))
public class AiSetting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 100)
    private String companyName;

    @Column(name = "allow_personal_keys", nullable = false)
    private Boolean allowPersonalKeys = true;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public Boolean getAllowPersonalKeys() { return allowPersonalKeys; }
    public void setAllowPersonalKeys(Boolean allowPersonalKeys) { this.allowPersonalKeys = allowPersonalKeys; }
}
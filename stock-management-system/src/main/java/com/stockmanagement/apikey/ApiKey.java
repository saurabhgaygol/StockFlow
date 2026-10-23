package com.stockmanagement.apikey;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * API key (Settings > API Keys) jisse ChatGPT / Claude / koi bhi software StockFlow se baat karta hai.
 *
 * Security:
 *  - Asli key kabhi save nahi hoti. Sirf SHA-256 hash (keyHash) save hota hai, isliye DB chori ho to bhi key nahi milti.
 *  - keyPrefix sirf pehchaan ke liye (sf_live_Ab12Cd...) screen par dikhta hai.
 *  - Ek user ki ek hi chalu key (deletedAt == null). Nayi key ke liye pehle purani delete karni hoti hai.
 *  - Delete = deletedAt set; key usi second se band. Record audit ke liye rehta hai.
 *  - expiresAt null = no expiry.
 *  - canWrite false = sirf data dekhna. true = add/edit bhi, par hamesha StockFlow me Confirm ke baad.
 *  - allowedIps khali = koi bhi IP; warna sirf in IPs se chalegi.
 */
@Entity
@Table(name = "api_keys")
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "company_name")
    private String companyName;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "key_prefix", nullable = false, length = 20)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, unique = true, length = 64)
    private String keyHash;

    @Column(name = "can_write", nullable = false)
    private Boolean canWrite = false;

    @Column(name = "allowed_ips", length = 500)
    private String allowedIps;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "last_used_ip", length = 64)
    private String lastUsedIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 150)
    private String deletedBy;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** Key abhi chal sakti hai? (delete nahi hui, expire nahi hui) */
    public boolean isUsable(LocalDateTime now) {
        return deletedAt == null && (expiresAt == null || expiresAt.isAfter(now));
    }

    public boolean isExpired(LocalDateTime now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }
    public Boolean getCanWrite() { return Boolean.TRUE.equals(canWrite); }
    public void setCanWrite(Boolean canWrite) { this.canWrite = canWrite; }
    public String getAllowedIps() { return allowedIps; }
    public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getLastUsedIp() { return lastUsedIp; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String deletedBy) { this.deletedBy = deletedBy; }
}
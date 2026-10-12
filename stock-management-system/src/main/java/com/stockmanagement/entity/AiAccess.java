package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Company connection kis ko mili aur kis level par.
 * targetType: USER (targetId = user id) | ROLE (targetId = role id) | COMPANY (targetId null = poori company)
 * accessLevel: USE | SHARE | MANAGE
 * Koi row nahi = sirf banane wala (super admin) use kar sakta hai.
 */
@Entity
@Table(name = "ai_access")
public class AiAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** USE | SHARE | MANAGE */
    @Column(name = "access_level", nullable = false, length = 10,
            columnDefinition = "VARCHAR(10) NOT NULL DEFAULT 'USE'")
    private String accessLevel = "USE";

    /** Kisne diya. null = super admin ka base grant. */
    @Column(name = "granted_by")
    private Long grantedBy;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** Per-user daily message limit (chat step me enforce hoga). null = no limit. */
    @Column(name = "daily_limit")
    private Integer dailyLimit;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long connectionId) { this.connectionId = connectionId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getAccessLevel() { return accessLevel == null ? "USE" : accessLevel; }
    public void setAccessLevel(String accessLevel) { this.accessLevel = accessLevel; }
    public Long getGrantedBy() { return grantedBy; }
    public void setGrantedBy(Long grantedBy) { this.grantedBy = grantedBy; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public Integer getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(Integer dailyLimit) { this.dailyLimit = dailyLimit; }
}
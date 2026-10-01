package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "approval_level", indexes = {
        @Index(name = "idx_approval_level_company", columnList = "company_name, status, level_no") })
public class ApprovalLevel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150)
    private String companyName;

    @Column(name = "level_no", nullable = false)
    private Integer levelNo;

    @Column(name = "level_name", nullable = false, length = 100)
    private String levelName;

    // USER = ek specific person, ROLE = us role ka koi bhi banda
    @Column(name = "approver_type", nullable = false, length = 10)
    private String approverType;

    @Column(name = "approver_user_id")
    private Long approverUserId;

    @Column(name = "approver_role_id")
    private Long approverRoleId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_by", length = 150)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
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

	public Integer getLevelNo() {
		return levelNo;
	}

	public void setLevelNo(Integer levelNo) {
		this.levelNo = levelNo;
	}

	public String getLevelName() {
		return levelName;
	}

	public void setLevelName(String levelName) {
		this.levelName = levelName;
	}

	public String getApproverType() {
		return approverType;
	}

	public void setApproverType(String approverType) {
		this.approverType = approverType;
	}

	public Long getApproverUserId() {
		return approverUserId;
	}

	public void setApproverUserId(Long approverUserId) {
		this.approverUserId = approverUserId;
	}

	public Long getApproverRoleId() {
		return approverRoleId;
	}

	public void setApproverRoleId(Long approverRoleId) {
		this.approverRoleId = approverRoleId;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
}
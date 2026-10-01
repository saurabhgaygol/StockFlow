package com.stockmanagement.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "stock_outward_approval", indexes = {
        @Index(name = "idx_soa_request", columnList = "request_id, level_no"),
        @Index(name = "idx_soa_user", columnList = "approver_user_id"),
        @Index(name = "idx_soa_role", columnList = "approver_role_id") })
public class StockOutwardApproval {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false)
    private Long requestId;

    @Column(name = "level_no", nullable = false)
    private Integer levelNo;

    @Column(name = "level_name", nullable = false, length = 100)
    private String levelName;

    @Column(name = "approver_type", nullable = false, length = 10)
    private String approverType;

    @Column(name = "approver_user_id")
    private Long approverUserId;

    @Column(name = "approver_role_id")
    private Long approverRoleId;

    @Column(name = "approver_label", length = 200)
    private String approverLabel;

    // WAITING / PENDING / APPROVED / ON_HOLD / REJECTED
    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "acted_by_id")
    private Long actedById;

    @Column(name = "acted_by_name", length = 150)
    private String actedByName;

    @Column(name = "comment_text", length = 1000)
    private String comment;

    @Column(name = "acted_at")
    private LocalDateTime actedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

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

	public Long getRequestId() {
		return requestId;
	}

	public void setRequestId(Long requestId) {
		this.requestId = requestId;
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

	public String getApproverLabel() {
		return approverLabel;
	}

	public void setApproverLabel(String approverLabel) {
		this.approverLabel = approverLabel;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Long getActedById() {
		return actedById;
	}

	public void setActedById(Long actedById) {
		this.actedById = actedById;
	}

	public String getActedByName() {
		return actedByName;
	}

	public void setActedByName(String actedByName) {
		this.actedByName = actedByName;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

	public LocalDateTime getActedAt() {
		return actedAt;
	}

	public void setActedAt(LocalDateTime actedAt) {
		this.actedAt = actedAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
    
    
    
    
    
    
    
}
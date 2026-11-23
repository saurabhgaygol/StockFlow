package com.stockmanagement.printagent;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * One computer of a shop that runs the StockFlow Printer Agent. A row starts as a pairing code (token_hash empty) made
 * in Tag Studio; the agent exchanges that code for a long secret token, of which only the SHA-256 hash is kept here.
 */
@Entity
@Table(name = "tag_agent")
public class TagAgent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_name", nullable = false, length = 150, updatable = false)
    private String companyName;

    /** SHA-256 (hex) of the agent token; null until the code is used */
    @Column(name = "token_hash", length = 64, unique = true)
    private String tokenHash;

    /** one-time pairing code (upper case, no dash); cleared when used */
    @Column(name = "pair_code", length = 12, unique = true)
    private String pairCode;

    @Column(name = "pair_expires_at")
    private LocalDateTime pairExpiresAt;

    @Column(name = "computer_name", length = 120)
    private String computerName;

    @Column(name = "agent_version", length = 20)
    private String agentVersion;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @Column(name = "created_by_name", length = 150)
    private String createdByName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "paired_at")
    private LocalDateTime pairedAt;

    public Long getId() { return id; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getPairCode() { return pairCode; }
    public void setPairCode(String pairCode) { this.pairCode = pairCode; }
    public LocalDateTime getPairExpiresAt() { return pairExpiresAt; }
    public void setPairExpiresAt(LocalDateTime pairExpiresAt) { this.pairExpiresAt = pairExpiresAt; }
    public String getComputerName() { return computerName; }
    public void setComputerName(String computerName) { this.computerName = computerName; }
    public String getAgentVersion() { return agentVersion; }
    public void setAgentVersion(String agentVersion) { this.agentVersion = agentVersion; }
    public LocalDateTime getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(LocalDateTime lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getPairedAt() { return pairedAt; }
    public void setPairedAt(LocalDateTime pairedAt) { this.pairedAt = pairedAt; }
}
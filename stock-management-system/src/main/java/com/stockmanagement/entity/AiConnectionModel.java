package com.stockmanagement.entity;

import jakarta.persistence.*;

/** Ek connection ke andar enabled models (jaise claude-sonnet-5-5, gpt-4o ...). */
@Entity
@Table(name = "ai_connection_model",
       uniqueConstraints = @UniqueConstraint(name = "uk_ai_conn_model", columnNames = {"connection_id", "model_id"}))
public class AiConnectionModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false)
    private Long connectionId;

    @Column(name = "model_id", nullable = false, length = 150)
    private String modelId;

    @Column(name = "display_name", length = 150)
    private String displayName;

    @Column(name = "is_default", nullable = false)
    private Boolean defaultModel = false;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long connectionId) { this.connectionId = connectionId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Boolean getDefaultModel() { return defaultModel; }
    public void setDefaultModel(Boolean defaultModel) { this.defaultModel = defaultModel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
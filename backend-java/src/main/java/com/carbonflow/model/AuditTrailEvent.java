package com.carbonflow.model;

import java.time.Instant;

public class AuditTrailEvent {
    private String id;
    private String organizationId;
    private String entityType;
    private String entityId;
    private String action; // CREATE, UPDATE, DELETE, CALCULATION_RUN, AUDIT_TRANSITION, EVIDENCE_VERIFIED
    private String performedBy;
    private String detailsJson;
    private Instant timestamp;

    public AuditTrailEvent() {}

    public AuditTrailEvent(String id, String organizationId, String entityType, String entityId,
                           String action, String performedBy, String detailsJson, Instant timestamp) {
        this.id = id;
        this.organizationId = organizationId;
        this.entityType = entityType;
        this.entityId = entityId;
        this.action = action;
        this.performedBy = performedBy;
        this.detailsJson = detailsJson;
        this.timestamp = timestamp;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getPerformedBy() { return performedBy; }
    public void setPerformedBy(String performedBy) { this.performedBy = performedBy; }

    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}

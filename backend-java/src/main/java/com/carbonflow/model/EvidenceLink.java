package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code evidence_links} row (V1) — polymorphic association to
 * {@code ACTIVITY_DATA | AUDIT | FACILITY} (CHECK-constrained). The table
 * has no {@code organization_id}: the service validates <b>both</b> sides
 * (evidence record and target entity) against the caller's organization
 * before any insert, so a cross-tenant link can never be written.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvidenceLink {

    private String id;
    private String evidenceRecordId;
    private String entityType;
    private String entityId;
    private Instant createdAt;

    public EvidenceLink() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getEvidenceRecordId() {
        return evidenceRecordId;
    }

    public void setEvidenceRecordId(String evidenceRecordId) {
        this.evidenceRecordId = evidenceRecordId;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

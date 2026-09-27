package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * {@code correction_requests} row (V1) — the record that drives the
 * documented correction path REVIEW → CORRECTION_REQUESTED → DATA_COLLECTION.
 * {@code activity_data_id} is {@code NOT NULL} (V1), so a correction request
 * always names the data record it targets; ownership of that record is
 * tenant-validated through {@code ScopeService.requireActivityData}.
 *
 * <p>No {@code organization_id} column: tenancy resolves through the parent
 * audit on every statement.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CorrectionRequest {

    private String id;
    private String auditId;
    private String activityDataId;
    private String reason;
    private String requestedBy;
    private boolean isResolved;
    private Instant createdAt;

    public CorrectionRequest() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getActivityDataId() {
        return activityDataId;
    }

    public void setActivityDataId(String activityDataId) {
        this.activityDataId = activityDataId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public void setRequestedBy(String requestedBy) {
        this.requestedBy = requestedBy;
    }

    @JsonProperty("isResolved")
    public boolean isResolved() {
        return isResolved;
    }

    @JsonProperty("isResolved")
    public void setResolved(boolean resolved) {
        isResolved = resolved;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

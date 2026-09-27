package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code review_findings} row (V1). Severity
 * {@code LOW|MEDIUM|HIGH|CRITICAL} and status
 * {@code OPEN|IN_REVIEW|RESOLVED|DISMISSED} are CHECK-constrained in the
 * schema. Unresolved HIGH/CRITICAL findings block REVIEW → APPROVED
 * (docs/AUDIT_WORKFLOW.md §2 — enforced server-side in Phase 5).
 *
 * <p>No {@code organization_id} column exists: tenancy resolves through the
 * parent audit on every statement (the {@code boundary_facilities} pattern
 * from ADR-015).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewFinding {

    private String id;
    private String auditId;
    private String activityDataId;
    private String severity;
    private String title;
    private String description;
    private String status;
    private String createdBy;
    private String resolvedBy;
    private Instant createdAt;

    public ReviewFinding() {
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

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

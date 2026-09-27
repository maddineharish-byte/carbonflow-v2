package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code audit_approvals} row (V1). Rows are created <b>only</b> by the
 * governed REVIEW → APPROVED transition (never by a direct endpoint call),
 * so approval stays inside the workflow with {@code audits.approve}
 * enforced on the transition (ADR-016).
 *
 * <p>{@code signatureHash} is a SHA-256 integrity hash of the canonical
 * approval payload — an audit-trail checksum, <b>not</b> a digital
 * signature and not an external certification. CarbonFlow performs
 * audit-preparation/governance only.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditApproval {

    private String id;
    private String auditId;
    private String approverId;
    private String role;
    private String signatureHash;
    private Instant timestamp;

    public AuditApproval() {
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

    public String getApproverId() {
        return approverId;
    }

    public void setApproverId(String approverId) {
        this.approverId = approverId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getSignatureHash() {
        return signatureHash;
    }

    public void setSignatureHash(String signatureHash) {
        this.signatureHash = signatureHash;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}

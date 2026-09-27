package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * {@code audit_checklist_items} row (V1). Mandatory items satisfying the
 * checklist are the server-side gate before APPROVED / AUDIT_READY / LOCKED
 * (docs/AUDIT_WORKFLOW.md §2) — never a frontend-only check.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditChecklistItem {

    private String id;
    private String auditId;
    private String code;
    private String title;
    private boolean isMandatory;
    private boolean isSatisfied;
    private String verifiedBy;
    private Instant verifiedAt;
    private String notes;

    public AuditChecklistItem() {
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

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    @JsonProperty("isMandatory")
    public boolean isMandatory() {
        return isMandatory;
    }

    @JsonProperty("isMandatory")
    public void setMandatory(boolean mandatory) {
        isMandatory = mandatory;
    }

    @JsonProperty("isSatisfied")
    public boolean isSatisfied() {
        return isSatisfied;
    }

    @JsonProperty("isSatisfied")
    public void setSatisfied(boolean satisfied) {
        isSatisfied = satisfied;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public void setVerifiedBy(String verifiedBy) {
        this.verifiedBy = verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

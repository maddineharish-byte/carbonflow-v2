package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * {@code carbon_audits} row (V1, ten-state status per V7/ADR-005) plus the
 * read enrichments the Node reference returns.
 *
 * <p>List view adds {@code periodName}, {@code checklistSummary} and
 * {@code openFindingsCount}; detail view adds the full {@code period} and
 * the child collections (checklist, findings, comments) plus the
 * Phase 5 additions corrections/approvals/lockEvent. {@code NON_NULL}
 * keeps each view to exactly its own field set.
 *
 * <p>{@code status} is a plain String: the server validates it exclusively
 * through {@code AuditStateMachine} so the canonical ten-state machine
 * (DRAFT → … → LOCKED, ADR-005 + V7) stays the single source of truth — no
 * second audit-state model exists anywhere in this codebase.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CarbonAudit {

    private String id;
    private String organizationId;
    private String reportingPeriodId;
    private String status;
    private String initiatedBy;
    private String approvedBy;
    private Instant lockedAt;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;

    // --- list-view enrichments (Node parity) ---
    private String periodName;
    private ChecklistSummary checklistSummary;
    private Integer openFindingsCount;

    // --- detail-view enrichments ---
    private ReportingPeriod period;
    private List<AuditChecklistItem> checklist;
    private List<ReviewFinding> findings;
    private List<ReviewComment> comments;
    private List<CorrectionRequest> corrections;
    private List<AuditApproval> approvals;
    private AuditLockEvent lockEvent;

    public CarbonAudit() {
    }

    public static class ChecklistSummary {
        private int total;
        private int satisfied;

        public ChecklistSummary() {
        }

        public ChecklistSummary(int total, int satisfied) {
            this.total = total;
            this.satisfied = satisfied;
        }

        public int getTotal() {
            return total;
        }

        public void setTotal(int total) {
            this.total = total;
        }

        public int getSatisfied() {
            return satisfied;
        }

        public void setSatisfied(int satisfied) {
            this.satisfied = satisfied;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getReportingPeriodId() {
        return reportingPeriodId;
    }

    public void setReportingPeriodId(String reportingPeriodId) {
        this.reportingPeriodId = reportingPeriodId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(String initiatedBy) {
        this.initiatedBy = initiatedBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void setLockedAt(Instant lockedAt) {
        this.lockedAt = lockedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getPeriodName() {
        return periodName;
    }

    public void setPeriodName(String periodName) {
        this.periodName = periodName;
    }

    public ChecklistSummary getChecklistSummary() {
        return checklistSummary;
    }

    public void setChecklistSummary(ChecklistSummary checklistSummary) {
        this.checklistSummary = checklistSummary;
    }

    public Integer getOpenFindingsCount() {
        return openFindingsCount;
    }

    public void setOpenFindingsCount(Integer openFindingsCount) {
        this.openFindingsCount = openFindingsCount;
    }

    public ReportingPeriod getPeriod() {
        return period;
    }

    public void setPeriod(ReportingPeriod period) {
        this.period = period;
    }

    public List<AuditChecklistItem> getChecklist() {
        return checklist;
    }

    public void setChecklist(List<AuditChecklistItem> checklist) {
        this.checklist = checklist;
    }

    public List<ReviewFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<ReviewFinding> findings) {
        this.findings = findings;
    }

    public List<ReviewComment> getComments() {
        return comments;
    }

    public void setComments(List<ReviewComment> comments) {
        this.comments = comments;
    }

    public List<CorrectionRequest> getCorrections() {
        return corrections;
    }

    public void setCorrections(List<CorrectionRequest> corrections) {
        this.corrections = corrections;
    }

    public List<AuditApproval> getApprovals() {
        return approvals;
    }

    public void setApprovals(List<AuditApproval> approvals) {
        this.approvals = approvals;
    }

    public AuditLockEvent getLockEvent() {
        return lockEvent;
    }

    public void setLockEvent(AuditLockEvent lockEvent) {
        this.lockEvent = lockEvent;
    }
}

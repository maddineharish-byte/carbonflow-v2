package com.carbonflow.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request payloads for the Phase 5 governance surface (audits, checklist,
 * findings, comments, corrections, transition).
 *
 * <p>Validation follows the Node reference where a Node handler exists
 * (title+description required for findings, blank-comment rejection) and
 * the Phase 3/4 convention elsewhere: absence of an optional body field
 * means "leave unchanged" (COALESCE updates), never "set to null". Fields
 * are boxed so absence is distinguishable from {@code false}/{@code 0}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class GovernanceRequests {

    private GovernanceRequests() {
    }

    /** POST /audits — {@code reportingPeriodId} is mandatory and scoped. */
    public static class AuditCreateRequest {
        private String reportingPeriodId;
        private String notes;

        public String getReportingPeriodId() {
            return reportingPeriodId;
        }

        public void setReportingPeriodId(String reportingPeriodId) {
            this.reportingPeriodId = reportingPeriodId;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    /** PUT /audits/:id — draft metadata only; status is never client-settable. */
    public static class AuditUpdateRequest {
        private String notes;

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    /**
     * POST /audits/:id/transition — the client proposes, the server decides
     * (state machine + permission + prerequisites all server-side).
     */
    public static class TransitionRequest {
        private String targetState;
        private String reason;

        public String getTargetState() {
            return targetState;
        }

        public void setTargetState(String targetState) {
            this.targetState = targetState;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    /** POST /audits/:id/checklist — extra (non-canonical) checklist item. */
    public static class ChecklistCreateRequest {
        private String code;
        private String title;
        private Boolean isMandatory;

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

        public Boolean getIsMandatory() {
            return isMandatory;
        }

        public void setIsMandatory(Boolean isMandatory) {
            this.isMandatory = isMandatory;
        }
    }

    /** PUT /audits/:id/checklist/:itemId — structural fields only. */
    public static class ChecklistUpdateRequest {
        private String title;
        private Boolean isMandatory;
        private String notes;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public Boolean getIsMandatory() {
            return isMandatory;
        }

        public void setIsMandatory(Boolean isMandatory) {
            this.isMandatory = isMandatory;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    /**
     * POST /audits/:id/checklist/:itemId/verify — {@code isSatisfied} must be
     * present (the Node handler silently coerced a missing value to false;
     * Java rejects the ambiguous request instead — ADR-016).
     */
    public static class ChecklistVerifyRequest {
        private Boolean isSatisfied;
        private String notes;

        public Boolean getIsSatisfied() {
            return isSatisfied;
        }

        public void setIsSatisfied(Boolean isSatisfied) {
            this.isSatisfied = isSatisfied;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    /** POST /audits/:id/findings — title and description required (Node). */
    public static class FindingCreateRequest {
        private String title;
        private String description;
        private String severity;
        private String activityDataId;

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

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getActivityDataId() {
            return activityDataId;
        }

        public void setActivityDataId(String activityDataId) {
            this.activityDataId = activityDataId;
        }
    }

    /** PUT /audits/:id/findings/:findingId — any subset of governed fields. */
    public static class FindingUpdateRequest {
        private String title;
        private String description;
        private String severity;
        private String status;

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

        public String getSeverity() {
            return severity;
        }

        public void setSeverity(String severity) {
            this.severity = severity;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }

    /** POST /audits/:id/comments — non-blank text (Node EMPTY_COMMENT). */
    public static class CommentCreateRequest {
        private String commentText;

        public String getCommentText() {
            return commentText;
        }

        public void setCommentText(String commentText) {
            this.commentText = commentText;
        }
    }

    /** PUT /audits/:id/comments/:commentId — author-only edit (greenfield). */
    public static class CommentUpdateRequest {
        private String commentText;

        public String getCommentText() {
            return commentText;
        }

        public void setCommentText(String commentText) {
            this.commentText = commentText;
        }
    }

    /** POST /audits/:id/corrections — targets one org-owned activity record. */
    public static class CorrectionCreateRequest {
        private String activityDataId;
        private String reason;

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
    }

    /** PUT /audits/:id/corrections/:correctionId — lifecycle flag. */
    public static class CorrectionUpdateRequest {
        private Boolean isResolved;

        public Boolean getIsResolved() {
            return isResolved;
        }

        public void setIsResolved(Boolean isResolved) {
            this.isResolved = isResolved;
        }
    }

    /**
     * POST /evidence/:id/link — entity ownership is resolved server-side;
     * entityType is one of ACTIVITY_DATA | AUDIT | FACILITY.
     */
    public static class EvidenceLinkRequest {
        private String entityType;
        private String entityId;

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
    }
}

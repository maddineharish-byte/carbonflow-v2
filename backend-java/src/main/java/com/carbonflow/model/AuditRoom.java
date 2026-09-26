package com.carbonflow.model;

import com.carbonflow.model.enums.AuditStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class AuditRoom {
    private String id;
    private String organizationId;
    private String reportingPeriodId;
    private String title;
    private AuditStatus status;
    private String assignedAuditorEmail;
    private List<ChecklistItem> checklist = new ArrayList<>();
    private List<AuditFinding> findings = new ArrayList<>();
    private Instant createdAt;

    public static class ChecklistItem {
        private String id;
        private String title;
        private boolean completed;
        private boolean mandatory;

        public ChecklistItem() {}

        public ChecklistItem(String id, String title, boolean completed, boolean mandatory) {
            this.id = id;
            this.title = title;
            this.completed = completed;
            this.mandatory = mandatory;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }

        public boolean isCompleted() { return completed; }
        public void setCompleted(boolean completed) { this.completed = completed; }

        public boolean isMandatory() { return mandatory; }
        public void setMandatory(boolean mandatory) { this.mandatory = mandatory; }
    }

    public static class AuditFinding {
        private String id;
        private String severity; // LOW, MEDIUM, HIGH, CRITICAL
        private String description;
        private String status; // OPEN, RESOLVED, WAIVED

        public AuditFinding() {}

        public AuditFinding(String id, String severity, String description, String status) {
            this.id = id;
            this.severity = severity;
            this.description = description;
            this.status = status;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getSeverity() { return severity; }
        public void setSeverity(String severity) { this.severity = severity; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public AuditRoom() {}

    public AuditRoom(String id, String organizationId, String reportingPeriodId, String title,
                     AuditStatus status, String assignedAuditorEmail, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.reportingPeriodId = reportingPeriodId;
        this.title = title;
        this.status = status;
        this.assignedAuditorEmail = assignedAuditorEmail;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getReportingPeriodId() { return reportingPeriodId; }
    public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public AuditStatus getStatus() { return status; }
    public void setStatus(AuditStatus status) { this.status = status; }

    public String getAssignedAuditorEmail() { return assignedAuditorEmail; }
    public void setAssignedAuditorEmail(String assignedAuditorEmail) { this.assignedAuditorEmail = assignedAuditorEmail; }

    public List<ChecklistItem> getChecklist() { return checklist; }
    public void setChecklist(List<ChecklistItem> checklist) { this.checklist = checklist; }

    public List<AuditFinding> getFindings() { return findings; }
    public void setFindings(List<AuditFinding> findings) { this.findings = findings; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

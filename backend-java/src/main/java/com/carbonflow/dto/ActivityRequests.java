package com.carbonflow.dto;

/**
 * {@code POST /api/v1/activity-data} and {@code PUT /api/v1/activity-data/:id}
 * payloads.
 *
 * <p>Dates stay strings so the service layer can apply Node's strict
 * {@code YYYY-MM-DD} contract (an unparseable date answers
 * {@code VALIDATION_ERROR}, not {@code INVALID_JSON} — the same approach as
 * the Phase 4 scope payloads).
 *
 * <p>Text fields ({@code category}, {@code activityType}, {@code unit},
 * {@code source}, {@code notes}) and {@code quantity} are typed
 * {@code Object}: the Node reference rejects non-string text with
 * {@code VALIDATION_ERROR} and parses quantity as
 * {@code Number(value)} — declaring them {@code String}/{@code BigDecimal}
 * would let Jackson silently coerce JSON numbers/booleans into the payload
 * and change which contract error fires. The service performs the same
 * type checks.
 *
 * <p>On update, {@code null} means "leave unchanged"; an empty string clears
 * the nullable text fields ({@code notes}, {@code departmentId}), matching the
 * create-time treatment of empty strings.
 */
public class ActivityRequests {

    private ActivityRequests() {
    }

    public static class ActivityCreateRequest {
        private String reportingPeriodId;
        private String facilityId;
        private String departmentId;
        private String scope;
        private String status;
        private Object category;
        private Object activityType;
        private Object quantity;
        private Object unit;
        private String startDate;
        private String endDate;
        private Object source;
        private Object notes;

        public String getReportingPeriodId() { return reportingPeriodId; }
        public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

        public String getFacilityId() { return facilityId; }
        public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

        public String getDepartmentId() { return departmentId; }
        public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Object getCategory() { return category; }
        public void setCategory(Object category) { this.category = category; }

        public Object getActivityType() { return activityType; }
        public void setActivityType(Object activityType) { this.activityType = activityType; }

        public Object getQuantity() { return quantity; }
        public void setQuantity(Object quantity) { this.quantity = quantity; }

        public Object getUnit() { return unit; }
        public void setUnit(Object unit) { this.unit = unit; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }

        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }

        public Object getSource() { return source; }
        public void setSource(Object source) { this.source = source; }

        public Object getNotes() { return notes; }
        public void setNotes(Object notes) { this.notes = notes; }
    }

    public static class ActivityUpdateRequest {
        private String departmentId;
        private String scope;
        private String status;
        private Object category;
        private Object activityType;
        private Object quantity;
        private Object unit;
        private String startDate;
        private String endDate;
        private Object source;
        private Object notes;

        public String getDepartmentId() { return departmentId; }
        public void setDepartmentId(String departmentId) { this.departmentId = departmentId; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public Object getCategory() { return category; }
        public void setCategory(Object category) { this.category = category; }

        public Object getActivityType() { return activityType; }
        public void setActivityType(Object activityType) { this.activityType = activityType; }

        public Object getQuantity() { return quantity; }
        public void setQuantity(Object quantity) { this.quantity = quantity; }

        public Object getUnit() { return unit; }
        public void setUnit(Object unit) { this.unit = unit; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }

        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }

        public Object getSource() { return source; }
        public void setSource(Object source) { this.source = source; }

        public Object getNotes() { return notes; }
        public void setNotes(Object notes) { this.notes = notes; }
    }
}

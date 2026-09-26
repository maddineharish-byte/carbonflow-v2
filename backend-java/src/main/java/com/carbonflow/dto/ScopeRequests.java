package com.carbonflow.dto;

import java.util.List;

/**
 * Phase 4 scope-domain payloads (legal entities, facilities, departments,
 * reporting periods, organizational boundaries).
 *
 * <p>Dates are carried as strings on purpose: the service layer parses them
 * and answers Node-style ({@code VALIDATION_ERROR} for an unparseable date,
 * {@code INVALID_DATE_RANGE} for an inverted range) instead of letting
 * Jackson's type conversion surface as {@code INVALID_JSON}.
 *
 * <p>No field here encodes a region: countries, jurisdictions, currencies and
 * time zones remain caller-supplied free-form values (global product
 * requirement).
 */
public class ScopeRequests {

    public static class FacilityRequest {
        private String name;
        private String facilityCode;
        private String facilityType;
        private String country;
        private String stateProvince;
        private String gridRegion;
        private Double floorAreaM2;
        private String legalEntityId;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getFacilityCode() { return facilityCode; }
        public void setFacilityCode(String facilityCode) { this.facilityCode = facilityCode; }

        public String getFacilityType() { return facilityType; }
        public void setFacilityType(String facilityType) { this.facilityType = facilityType; }

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }

        public String getStateProvince() { return stateProvince; }
        public void setStateProvince(String stateProvince) { this.stateProvince = stateProvince; }

        public String getGridRegion() { return gridRegion; }
        public void setGridRegion(String gridRegion) { this.gridRegion = gridRegion; }

        public Double getFloorAreaM2() { return floorAreaM2; }
        public void setFloorAreaM2(Double floorAreaM2) { this.floorAreaM2 = floorAreaM2; }

        public String getLegalEntityId() { return legalEntityId; }
        public void setLegalEntityId(String legalEntityId) { this.legalEntityId = legalEntityId; }
    }

    public static class LegalEntityRequest {
        private String name;
        private String jurisdiction;
        private String registrationNumber;
        private Double ownershipPercentage;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getJurisdiction() { return jurisdiction; }
        public void setJurisdiction(String jurisdiction) { this.jurisdiction = jurisdiction; }

        public String getRegistrationNumber() { return registrationNumber; }
        public void setRegistrationNumber(String registrationNumber) { this.registrationNumber = registrationNumber; }

        public Double getOwnershipPercentage() { return ownershipPercentage; }
        public void setOwnershipPercentage(Double ownershipPercentage) { this.ownershipPercentage = ownershipPercentage; }
    }

    public static class DepartmentRequest {
        private String facilityId;
        private String name;

        public String getFacilityId() { return facilityId; }
        public void setFacilityId(String facilityId) { this.facilityId = facilityId; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class ReportingPeriodRequest {
        private String name;
        private String startDate;
        private String endDate;
        private String status;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getStartDate() { return startDate; }
        public void setStartDate(String startDate) { this.startDate = startDate; }

        public String getEndDate() { return endDate; }
        public void setEndDate(String endDate) { this.endDate = endDate; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class BoundaryRequest {
        private String reportingPeriodId;
        private String consolidationApproach;
        private String notes;
        private List<String> facilityIds;

        public String getReportingPeriodId() { return reportingPeriodId; }
        public void setReportingPeriodId(String reportingPeriodId) { this.reportingPeriodId = reportingPeriodId; }

        public String getConsolidationApproach() { return consolidationApproach; }
        public void setConsolidationApproach(String consolidationApproach) { this.consolidationApproach = consolidationApproach; }

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }

        public List<String> getFacilityIds() { return facilityIds; }
        public void setFacilityIds(List<String> facilityIds) { this.facilityIds = facilityIds; }
    }

    /** Partial update: only non-null fields are written (org-PUT semantics). */
    public static class BoundaryUpdateRequest {
        private String consolidationApproach;
        private String notes;

        public String getConsolidationApproach() { return consolidationApproach; }
        public void setConsolidationApproach(String consolidationApproach) { this.consolidationApproach = consolidationApproach; }

        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
    }

    public static class BoundaryFacilityRequest {
        private String facilityId;

        public String getFacilityId() { return facilityId; }
        public void setFacilityId(String facilityId) { this.facilityId = facilityId; }
    }
}

package com.carbonflow.dto;

/**
 * Organization administration payloads.
 *
 * <p>{@link UpdateOrganizationRequest} mirrors the partial-update semantics of
 * the Node reference backend's {@code PUT /organizations/current}: only the
 * provided (non-blank) fields are written.
 */
public class OrganizationRequests {

    public static class UpdateOrganizationRequest {
        private String name;
        private String country;
        private String industry;
        private String consolidationApproach;
        private Integer baseYear;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getCountry() { return country; }
        public void setCountry(String country) { this.country = country; }

        public String getIndustry() { return industry; }
        public void setIndustry(String industry) { this.industry = industry; }

        public String getConsolidationApproach() { return consolidationApproach; }
        public void setConsolidationApproach(String consolidationApproach) { this.consolidationApproach = consolidationApproach; }

        public Integer getBaseYear() { return baseYear; }
        public void setBaseYear(Integer baseYear) { this.baseYear = baseYear; }
    }

    /** Optional free-text note recorded in {@code organizations.status_note}. */
    public static class PlatformNoteRequest {
        private String note;

        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
    }
}

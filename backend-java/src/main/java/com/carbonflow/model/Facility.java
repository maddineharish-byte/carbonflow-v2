package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code facilities} row (V1 + V4 constraints), shaped exactly like the Node
 * reference backend's {@code FacilityRecord} (server/scope-repository.ts):
 * snake_case columns surface as camelCase JSON, optional columns are omitted
 * when null (the Node record drops {@code undefined} fields).
 *
 * <p>{@code updatedAt} is additive to the Node record (which simply does not
 * select the column) — documented in docs/API.md; it is schema-backed and
 * non-breaking for consumers.
 *
 * <p>Global product requirement: no country/currency/timezone/regulatory
 * field is assumed — {@code country} is free-form (ISO-3166 alpha-2 by
 * convention, 10 chars max per schema), and no region-specific defaults
 * exist anywhere in this class.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Facility {

    private String id;
    private String organizationId;
    private String legalEntityId;
    private String name;
    private String facilityCode;
    private String facilityType;
    private String country;
    private String stateProvince;
    private String gridRegion;
    private Double floorAreaM2;
    private Instant createdAt;
    private Instant updatedAt;

    public Facility() {
    }

    public Facility(String id, String organizationId, String legalEntityId, String name,
                    String facilityCode, String facilityType, String country,
                    String stateProvince, String gridRegion, Double floorAreaM2,
                    Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.legalEntityId = legalEntityId;
        this.name = name;
        this.facilityCode = facilityCode;
        this.facilityType = facilityType;
        this.country = country;
        this.stateProvince = stateProvince;
        this.gridRegion = gridRegion;
        this.floorAreaM2 = floorAreaM2;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public String getLegalEntityId() {
        return legalEntityId;
    }

    public void setLegalEntityId(String legalEntityId) {
        this.legalEntityId = legalEntityId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFacilityCode() {
        return facilityCode;
    }

    public void setFacilityCode(String facilityCode) {
        this.facilityCode = facilityCode;
    }

    public String getFacilityType() {
        return facilityType;
    }

    public void setFacilityType(String facilityType) {
        this.facilityType = facilityType;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getStateProvince() {
        return stateProvince;
    }

    public void setStateProvince(String stateProvince) {
        this.stateProvince = stateProvince;
    }

    public String getGridRegion() {
        return gridRegion;
    }

    public void setGridRegion(String gridRegion) {
        this.gridRegion = gridRegion;
    }

    public Double getFloorAreaM2() {
        return floorAreaM2;
    }

    public void setFloorAreaM2(Double floorAreaM2) {
        this.floorAreaM2 = floorAreaM2;
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
}

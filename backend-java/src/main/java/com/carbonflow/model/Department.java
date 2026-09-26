package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * {@code departments} row (V1). Evidence-based relationship: departments hang
 * off a <b>facility</b> ({@code facility_id UUID NOT NULL REFERENCES
 * facilities ON DELETE CASCADE}) — the organization → legal entity → facility
 * → department chain is preserved by construction; no relationship is
 * invented here.
 *
 * <p>Greenfield domain (the Node reference backend has no department
 * endpoints); field set is exactly the schema's — no status column exists, so
 * lifecycle is delete-only (ADR-015).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Department {

    private String id;
    private String organizationId;
    private String facilityId;
    private String name;
    private Instant createdAt;

    public Department() {
    }

    public Department(String id, String organizationId, String facilityId, String name,
                      Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.facilityId = facilityId;
        this.name = name;
        this.createdAt = createdAt;
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

    public String getFacilityId() {
        return facilityId;
    }

    public void setFacilityId(String facilityId) {
        this.facilityId = facilityId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

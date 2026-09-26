package com.carbonflow.model;

import java.time.Instant;

public class Facility {
    private String id;
    private String organizationId;
    private String name;
    private String facilityCode;
    private String country;
    private String regionOrGrid;
    private String address;
    private Double squareMeters;
    private String operationalStatus;
    private Instant createdAt;

    public Facility() {}

    public Facility(String id, String organizationId, String name, String facilityCode, String country, String regionOrGrid, String address, Double squareMeters, String operationalStatus, Instant createdAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.facilityCode = facilityCode;
        this.country = country;
        this.regionOrGrid = regionOrGrid;
        this.address = address;
        this.squareMeters = squareMeters;
        this.operationalStatus = operationalStatus;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFacilityCode() { return facilityCode; }
    public void setFacilityCode(String facilityCode) { this.facilityCode = facilityCode; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getRegionOrGrid() { return regionOrGrid; }
    public void setRegionOrGrid(String regionOrGrid) { this.regionOrGrid = regionOrGrid; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Double getSquareMeters() { return squareMeters; }
    public void setSquareMeters(Double squareMeters) { this.squareMeters = squareMeters; }

    public String getOperationalStatus() { return operationalStatus; }
    public void setOperationalStatus(String operationalStatus) { this.operationalStatus = operationalStatus; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

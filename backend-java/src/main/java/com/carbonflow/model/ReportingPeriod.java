package com.carbonflow.model;

import java.time.LocalDate;

public class ReportingPeriod {
    private String id;
    private String organizationId;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean isLocked;

    public ReportingPeriod() {}

    public ReportingPeriod(String id, String organizationId, String name, LocalDate startDate, LocalDate endDate, boolean isLocked) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.isLocked = isLocked;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public boolean isLocked() { return isLocked; }
    public void setLocked(boolean locked) { isLocked = locked; }
}

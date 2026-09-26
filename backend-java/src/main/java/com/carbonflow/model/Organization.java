package com.carbonflow.model;

import java.time.Instant;

public class Organization {
    private String id;
    private String name;
    private String slug;
    private String reportingYear;
    private Instant createdAt;

    public Organization() {}

    public Organization(String id, String name, String slug, String reportingYear, Instant createdAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.reportingYear = reportingYear;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }

    public String getReportingYear() { return reportingYear; }
    public void setReportingYear(String reportingYear) { this.reportingYear = reportingYear; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

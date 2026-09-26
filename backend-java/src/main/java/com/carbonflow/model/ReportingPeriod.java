package com.carbonflow.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/**
 * {@code reporting_periods} row (V1: {@code status IN ('OPEN','UNDER_AUDIT',
 * 'LOCKED')} and {@code end_date >= start_date}). The Node reference backend's
 * {@code ReportingPeriodRecord} shape: {@code startDate}/{@code endDate} are
 * calendar dates (ISO-8601 {@code YYYY-MM-DD} in JSON), {@code status} is the
 * V1 lifecycle string.
 *
 * <p>{@code createdAt}/{@code updatedAt} are selected by the Node record and
 * preserved verbatim here.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReportingPeriod {

    private String id;
    private String organizationId;
    private String name;
    private LocalDate startDate;
    private LocalDate endDate;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;

    public ReportingPeriod() {
    }

    public ReportingPeriod(String id, String organizationId, String name,
                           LocalDate startDate, LocalDate endDate, String status,
                           Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.organizationId = organizationId;
        this.name = name;
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = status;
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

package com.carbonflow.repository;

import com.carbonflow.model.ReportingPeriod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code reporting_periods} (V1: {@code status} lifecycle
 * check, {@code end_date >= start_date} check), ported from Node's
 * {@code listReportingPeriods}/{@code createReportingPeriod} statements.
 *
 * <p><b>Tenant-isolation audit:</b> {@code organization_id = ?} on every
 * read and write; inserts stamp the caller's organization. The V5 composite
 * index {@code (organization_id, id)} additionally lets downstream tenant
 * foreign keys reference periods safely.
 *
 * <p>No overlap restriction exists here on purpose: neither the schema, the
 * Node backend, nor the documentation defines one — only the V1 date-range
 * check applies (ADR-015).
 */
@Repository
public class ReportingPeriodRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, name, start_date, end_date, status, "
                    + "created_at, updated_at";

    static final RowMapper<ReportingPeriod> MAPPER = (rs, rowNum) -> new ReportingPeriod(
            rs.getString("id"),
            rs.getString("organization_id"),
            rs.getString("name"),
            rs.getDate("start_date").toLocalDate(),
            rs.getDate("end_date").toLocalDate(),
            rs.getString("status"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
            rs.getObject("updated_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public ReportingPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Node parity: {@code WHERE organization_id = ? ORDER BY start_date DESC}. */
    public List<ReportingPeriod> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM reporting_periods WHERE organization_id = ? "
                        + "ORDER BY start_date DESC",
                MAPPER, organizationId);
    }

    public Optional<ReportingPeriod> findById(String organizationId, String periodId) {
        List<ReportingPeriod> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM reporting_periods WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, periodId);
        return rows.stream().findFirst();
    }

    public ReportingPeriod insert(String organizationId, String name,
                                  java.time.LocalDate startDate, java.time.LocalDate endDate,
                                  String status) {
        return jdbc.queryForObject(
                "INSERT INTO reporting_periods (organization_id, name, start_date, end_date, status) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                MAPPER, organizationId, name, startDate, endDate, status);
    }

    public int update(String organizationId, String periodId, String name,
                      java.time.LocalDate startDate, java.time.LocalDate endDate, String status) {
        return jdbc.update(
                "UPDATE reporting_periods SET name = ?, start_date = ?, end_date = ?, status = ?, "
                        + "updated_at = CURRENT_TIMESTAMP WHERE id = ? AND organization_id = ?",
                name, startDate, endDate, status, periodId, organizationId);
    }
}

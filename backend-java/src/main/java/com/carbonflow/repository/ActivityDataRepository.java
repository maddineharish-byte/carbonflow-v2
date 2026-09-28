package com.carbonflow.repository;

import com.carbonflow.model.ActivityData;
import com.carbonflow.model.ActivityEvidence;
import com.carbonflow.model.enums.GHGScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code activity_data} (ADR-009 — no ORM), ported from
 * {@code server/activity-repository.ts} with the tenant predicate promoted to
 * every statement.
 *
 * <p><b>Tenant-isolation audit:</b> every read and write carries
 * {@code organization_id = ?} sourced from the authenticated tenant context;
 * a row of another organization is invisible to {@link #findById}/{@link #update}
 * and inserts stamp the caller's organization id. The V5 composite foreign
 * keys {@code (organization_id, reporting_period_id)} and
 * {@code (organization_id, facility_id)} are the database backstop: a
 * cross-tenant facility/period pair can never be written even if an
 * application check were bypassed.
 *
 * <p>Reads reproduce Node's {@code ACTIVITY_SELECT}: the facility name comes
 * from an org-matched join and {@code evidence} is the first record linked to
 * the activity (ordered by link creation). The latest calculation is attached
 * by the service through {@code CalculationRepository#latestByActivities}.
 */
@Repository
public class ActivityDataRepository {

    private static final String COLUMNS =
            "a.id, a.organization_id, a.reporting_period_id, a.facility_id, a.department_id, "
                    + "a.scope, a.category, a.activity_type, a.quantity, a.unit, "
                    + "a.start_date, a.end_date, a.source, a.status, a.notes, "
                    + "a.submitted_by, a.created_at, a.updated_at, "
                    + "f.name AS facility_name, "
                    + "ev.id AS evidence_id, ev.file_name AS evidence_file_name, "
                    + "ev.file_size_bytes AS evidence_file_size_bytes, "
                    + "ev.mime_type AS evidence_mime_type, ev.sha256_hash AS evidence_sha256_hash";

    private static final String FROM =
            " FROM activity_data a "
                    + "JOIN facilities f ON f.id = a.facility_id AND f.organization_id = a.organization_id "
                    + "LEFT JOIN LATERAL ("
                    + "  SELECT er.id, er.file_name, er.file_size_bytes, er.mime_type, er.sha256_hash"
                    + "    FROM evidence_links el"
                    + "    JOIN evidence_records er ON er.id = el.evidence_record_id"
                    + "     AND er.organization_id = a.organization_id"
                    + "   WHERE el.entity_type = 'ACTIVITY_DATA' AND el.entity_id = a.id"
                    + "   ORDER BY el.created_at, er.created_at"
                    + "   LIMIT 1"
                    + ") ev ON TRUE";

    private static final RowMapper<ActivityData> MAPPER = (rs, rowNum) -> {
        ActivityData activity = new ActivityData();
        activity.setId(rs.getString("id"));
        activity.setOrganizationId(rs.getString("organization_id"));
        activity.setReportingPeriodId(rs.getString("reporting_period_id"));
        activity.setFacilityId(rs.getString("facility_id"));
        activity.setDepartmentId(rs.getString("department_id"));
        activity.setScope(GHGScope.valueOf(rs.getString("scope")));
        activity.setCategory(rs.getString("category"));
        activity.setActivityType(rs.getString("activity_type"));
        activity.setQuantity(rs.getBigDecimal("quantity"));
        activity.setUnit(rs.getString("unit"));
        activity.setStartDate(rs.getObject("start_date", LocalDate.class));
        activity.setEndDate(rs.getObject("end_date", LocalDate.class));
        activity.setSource(rs.getString("source"));
        activity.setStatus(rs.getString("status"));
        activity.setNotes(rs.getString("notes"));
        activity.setSubmittedBy(rs.getString("submitted_by"));
        activity.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class).toInstant());
        activity.setUpdatedAt(rs.getObject("updated_at", OffsetDateTime.class).toInstant());
        activity.setFacilityName(rs.getString("facility_name"));
        String evidenceId = rs.getString("evidence_id");
        if (evidenceId != null) {
            activity.setEvidence(new ActivityEvidence(
                    evidenceId,
                    rs.getString("evidence_file_name"),
                    rs.getLong("evidence_file_size_bytes"),
                    rs.getString("evidence_mime_type"),
                    rs.getString("evidence_sha256_hash")));
        }
        return activity;
    };

    private final JdbcTemplate jdbc;

    public ActivityDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tenant-scoped existence check — never a bare {@code WHERE id = ?}. */
    public boolean exists(String organizationId, String activityDataId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM activity_data WHERE organization_id = ? AND id = ?",
                Integer.class, organizationId, activityDataId);
        return count != null && count > 0;
    }

    /** Phase 7 period summary: activity rows belonging to one reporting period. */
    public int countForPeriod(String organizationId, String periodId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM activity_data "
                        + "WHERE organization_id = ? AND reporting_period_id = ?",
                Integer.class, organizationId, periodId);
        return count == null ? 0 : count;
    }

    /**
     * Node's {@code listActivity}: optional period/facility/scope filters,
     * ordered {@code start_date DESC, created_at DESC}. Filters are validated
     * (uuid/scope enum) by the service before this is called.
     */
    public List<ActivityData> list(String organizationId, String periodId,
                                   String facilityId, String scope) {
        return jdbc.query(
                "SELECT " + COLUMNS + FROM
                        + " WHERE a.organization_id = ?"
                        + " AND (?::text IS NULL OR a.reporting_period_id = ?::uuid)"
                        + " AND (?::text IS NULL OR a.facility_id = ?::uuid)"
                        + " AND (?::text IS NULL OR a.scope = ?::text)"
                        + " ORDER BY a.start_date DESC, a.created_at DESC",
                MAPPER,
                organizationId, periodId, periodId, facilityId, facilityId, scope, scope);
    }

    public Optional<ActivityData> findById(String organizationId, String activityDataId) {
        List<ActivityData> rows = jdbc.query(
                "SELECT " + COLUMNS + FROM
                        + " WHERE a.organization_id = ? AND a.id = ?",
                MAPPER, organizationId, activityDataId);
        return rows.stream().findFirst();
    }

    /**
     * Inserts a validated activity. The caller has already verified the
     * facility/period belong to {@code organizationId}; the V5 composite
     * foreign keys reject anything else with SQLSTATE 23503, which the service
     * maps to {@code INVALID_ACTIVITY_RELATIONSHIP} (Node parity).
     *
     * <p>The row is re-read through {@link #findById} afterwards so the
     * response carries the same facility/evidence enrichment Node produces
     * from its {@code mapActivity}.
     */
    public ActivityData insert(String organizationId, String reportingPeriodId,
                               String facilityId, String departmentId, String scope,
                               String category, String activityType, BigDecimal quantity,
                               String unit, LocalDate startDate, LocalDate endDate,
                               String source, String status, String notes,
                               String submittedBy) {
        String id = jdbc.queryForObject(
                "INSERT INTO activity_data (organization_id, reporting_period_id, facility_id, "
                        + "department_id, scope, category, activity_type, quantity, unit, "
                        + "start_date, end_date, source, status, notes, submitted_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "RETURNING id::text",
                String.class,
                organizationId, reportingPeriodId, facilityId, departmentId, scope,
                category, activityType, quantity, unit, startDate, endDate, source,
                status, notes, submittedBy);
        return findById(organizationId, id)
                .orElseThrow(() -> new IllegalStateException("Activity row vanished after insert"));
    }

    /**
     * Full-row update of the mutable columns. The tenant anchors
     * ({@code reporting_period_id}, {@code facility_id}) and
     * {@code submitted_by} are deliberately not updatable: changing them would
     * desynchronize the persisted calculation/emission snapshots that copied
     * those ids at execution time.
     */
    public int update(String organizationId, String activityDataId, String departmentId,
                      String scope, String category, String activityType, BigDecimal quantity,
                      String unit, LocalDate startDate, LocalDate endDate, String source,
                      String status, String notes) {
        return jdbc.update(
                "UPDATE activity_data SET department_id = ?, scope = ?, category = ?, "
                        + "activity_type = ?, quantity = ?, unit = ?, start_date = ?, "
                        + "end_date = ?, source = ?, status = ?, notes = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE organization_id = ? AND id = ?",
                departmentId, scope, category, activityType, quantity, unit,
                startDate, endDate, source, status, notes, organizationId, activityDataId);
    }

    public int updateStatus(String organizationId, String activityDataId, String status) {
        return jdbc.update(
                "UPDATE activity_data SET status = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE organization_id = ? AND id = ?",
                status, organizationId, activityDataId);
    }

    /**
     * Greenfield submit: status transitions to SUBMITTED and the submitter is
     * stamped (Node's unused {@code updateActivityStatus} has no submitter
     * concept — the activity-data module is documented greenfield, ADR-017).
     */
    public int updateStatusAndSubmitter(String organizationId, String activityDataId,
                                        String status, String submittedBy) {
        return jdbc.update(
                "UPDATE activity_data SET status = ?, submitted_by = ?, "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE organization_id = ? AND id = ?",
                status, submittedBy, organizationId, activityDataId);
    }

    /**
     * Node's persistence-time row lock ({@code SELECT ... FOR SHARE} inside
     * {@code persistCalculation}) — the caller must hold a transaction. The
     * service re-checks the returned anchors (period/facility/scope/category)
     * against the snapshot it resolved earlier, so a concurrent edit between
     * resolution and persistence aborts with
     * {@code INVALID_CALCULATION_RELATIONSHIP} instead of writing a stale
     * result.
     *
     * <p>This locks the bare {@code activity_data} row: PostgreSQL rejects
     * {@code FOR SHARE} on the nullable side of an outer join, so the enriched
     * facility/evidence columns of {@link #COLUMNS}/{@link #FROM} cannot appear
     * here. The locked row is only read for the anchor re-check — the
     * enrichment is stubbed as NULL so the shared {@link #MAPPER} applies.
     */
    public Optional<ActivityData> lockForShare(String organizationId, String activityDataId) {
        List<ActivityData> rows = jdbc.query(
                "SELECT a.id, a.organization_id, a.reporting_period_id, a.facility_id, "
                        + "a.department_id, a.scope, a.category, a.activity_type, a.quantity, "
                        + "a.unit, a.start_date, a.end_date, a.source, a.status, a.notes, "
                        + "a.submitted_by, a.created_at, a.updated_at, "
                        + "NULL::text AS facility_name, "
                        + "NULL::uuid AS evidence_id, "
                        + "NULL::text AS evidence_file_name, "
                        + "NULL::bigint AS evidence_file_size_bytes, "
                        + "NULL::text AS evidence_mime_type, "
                        + "NULL::text AS evidence_sha256_hash "
                        + "FROM activity_data a "
                        + "WHERE a.organization_id = ? AND a.id = ? FOR SHARE",
                MAPPER, organizationId, activityDataId);
        return rows.stream().findFirst();
    }

    /** Tenant-scoped row count (platform self-test diagnostics). */
    public int countByOrganization(String organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM activity_data WHERE organization_id = ?",
                Integer.class, organizationId);
        return count == null ? 0 : count;
    }

    /**
     * Cross-tenant integrity invariant used by the platform self-test: every
     * activity must reference a facility and a reporting period of its own
     * organization (the V5 composite foreign keys guarantee this, so the count
     * must always be zero).
     */
    public int countTenantRelationshipViolations(String organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM activity_data a "
                        + "LEFT JOIN facilities f ON f.id = a.facility_id "
                        + "  AND f.organization_id = a.organization_id "
                        + "LEFT JOIN reporting_periods p ON p.id = a.reporting_period_id "
                        + "  AND p.organization_id = a.organization_id "
                        + "WHERE a.organization_id = ? AND (f.id IS NULL OR p.id IS NULL)",
                Integer.class, organizationId);
        return count == null ? 0 : count;
    }
}

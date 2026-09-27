package com.carbonflow.repository;

import com.carbonflow.model.ReviewFinding;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code review_findings} (ADR-009).
 *
 * <p><b>Tenant-isolation audit:</b> no organization column exists on the
 * table — every statement joins through {@code carbon_audits} and carries
 * {@code a.organization_id = ?}; a foreign finding id is invisible even
 * when its audit id is supplied, because both predicates must match.
 */
@Repository
public class FindingRepository {

    static final String COLUMNS =
            "rf.id::text, rf.audit_id::text, rf.activity_data_id::text, rf.severity, "
                    + "rf.title, rf.description, rf.status, rf.created_by::text, "
                    + "rf.resolved_by::text, rf.created_at";

    static final RowMapper<ReviewFinding> MAPPER = (rs, rowNum) -> {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(rs.getString("id"));
        finding.setAuditId(rs.getString("audit_id"));
        finding.setActivityDataId(rs.getString("activity_data_id"));
        finding.setSeverity(rs.getString("severity"));
        finding.setTitle(rs.getString("title"));
        finding.setDescription(rs.getString("description"));
        finding.setStatus(rs.getString("status"));
        finding.setCreatedBy(rs.getString("created_by"));
        finding.setResolvedBy(rs.getString("resolved_by"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        finding.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return finding;
    };

    private final JdbcTemplate jdbc;

    public FindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ReviewFinding> listByAudit(String organizationId, String auditId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM review_findings rf "
                        + "JOIN carbon_audits a ON a.id = rf.audit_id "
                        + "WHERE a.organization_id = ? AND rf.audit_id = ? "
                        + "ORDER BY rf.created_at, rf.id",
                MAPPER, organizationId, auditId);
    }

    public Optional<ReviewFinding> findById(String organizationId, String auditId,
                                            String findingId) {
        List<ReviewFinding> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM review_findings rf "
                        + "JOIN carbon_audits a ON a.id = rf.audit_id "
                        + "WHERE a.organization_id = ? AND rf.audit_id = ? AND rf.id = ?",
                MAPPER, organizationId, auditId, findingId);
        return rows.stream().findFirst();
    }

    public ReviewFinding insert(String auditId, String activityDataId, String severity,
                                String title, String description, String createdBy) {
        return jdbc.queryForObject(
                "INSERT INTO review_findings "
                        + "(audit_id, activity_data_id, severity, title, description, created_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?) "
                        + "RETURNING id::text, audit_id::text, activity_data_id::text, "
                        + "severity, title, description, status, created_by::text, "
                        + "resolved_by::text, created_at",
                MAPPER, auditId, activityDataId, severity, title, description, createdBy);
    }

    public int update(String organizationId, String auditId, String findingId,
                      String severity, String title, String description, String status,
                      String resolvedBy) {
        return jdbc.update(
                "UPDATE review_findings rf SET "
                        + "severity = COALESCE(?, rf.severity), "
                        + "title = COALESCE(?, rf.title), "
                        + "description = COALESCE(?, rf.description), "
                        + "status = COALESCE(?, rf.status), "
                        + "resolved_by = COALESCE(?, rf.resolved_by) "
                        + "FROM carbon_audits a "
                        + "WHERE a.id = rf.audit_id "
                        + "AND a.organization_id = ? AND rf.audit_id = ? AND rf.id = ?",
                severity, title, description, status, resolvedBy,
                organizationId, auditId, findingId);
    }

    /** Node parity: status -> RESOLVED with the resolving user stamped. */
    public int resolve(String organizationId, String auditId, String findingId,
                       String resolvedBy) {
        return jdbc.update(
                "UPDATE review_findings rf SET status = 'RESOLVED', resolved_by = ? "
                        + "FROM carbon_audits a "
                        + "WHERE a.id = rf.audit_id "
                        + "AND a.organization_id = ? AND rf.audit_id = ? AND rf.id = ?",
                resolvedBy, organizationId, auditId, findingId);
    }

    /**
     * Transition gate for APPROVED / AUDIT_READY / LOCKED: findings in
     * {@code OPEN} or {@code IN_REVIEW} with severity HIGH or CRITICAL
     * (docs/AUDIT_WORKFLOW.md §2 "zero unresolved high-severity findings").
     */
    public List<String> unresolvedHighSeverityTitles(String organizationId, String auditId) {
        return jdbc.queryForList(
                "SELECT rf.title FROM review_findings rf "
                        + "JOIN carbon_audits a ON a.id = rf.audit_id "
                        + "WHERE a.organization_id = ? AND rf.audit_id = ? "
                        + "AND rf.status IN ('OPEN', 'IN_REVIEW') "
                        + "AND rf.severity IN ('HIGH', 'CRITICAL') "
                        + "ORDER BY rf.created_at, rf.id",
                String.class, organizationId, auditId);
    }

    /** REVIEW -> CORRECTION_REQUESTED requires a logged (non-dismissed) finding. */
    public int activeFindingCount(String organizationId, String auditId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM review_findings rf "
                        + "JOIN carbon_audits a ON a.id = rf.audit_id "
                        + "WHERE a.organization_id = ? AND rf.audit_id = ? "
                        + "AND rf.status <> 'DISMISSED'",
                Integer.class, organizationId, auditId);
        return count == null ? 0 : count;
    }

    /** Node list enrichment: findings with status OPEN. */
    public int openCount(String organizationId, String auditId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM review_findings rf "
                        + "JOIN carbon_audits a ON a.id = rf.audit_id "
                        + "WHERE a.organization_id = ? AND rf.audit_id = ? "
                        + "AND rf.status = 'OPEN'",
                Integer.class, organizationId, auditId);
        return count == null ? 0 : count;
    }
}

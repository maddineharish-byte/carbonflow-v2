package com.carbonflow.repository;

import com.carbonflow.model.CorrectionRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code correction_requests} (ADR-009).
 *
 * <p><b>Tenant-isolation audit:</b> no organization column exists — every
 * statement joins through {@code carbon_audits} and carries
 * {@code a.organization_id = ?}. The referenced activity record is
 * separately tenant-validated by {@code ScopeService.requireActivityData}
 * before insert, so neither side of a correction request can cross a tenant
 * boundary.
 */
@Repository
public class CorrectionRepository {

    static final String COLUMNS =
            "cr.id::text, cr.audit_id::text, cr.activity_data_id::text, cr.reason, "
                    + "cr.requested_by::text, cr.is_resolved, cr.created_at";

    static final RowMapper<CorrectionRequest> MAPPER = (rs, rowNum) -> {
        CorrectionRequest request = new CorrectionRequest();
        request.setId(rs.getString("id"));
        request.setAuditId(rs.getString("audit_id"));
        request.setActivityDataId(rs.getString("activity_data_id"));
        request.setReason(rs.getString("reason"));
        request.setRequestedBy(rs.getString("requested_by"));
        request.setResolved(rs.getBoolean("is_resolved"));
        java.sql.Timestamp createdAt = rs.getTimestamp("created_at");
        request.setCreatedAt(createdAt == null ? null : createdAt.toInstant());
        return request;
    };

    private final JdbcTemplate jdbc;

    public CorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<CorrectionRequest> listByAudit(String organizationId, String auditId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM correction_requests cr "
                        + "JOIN carbon_audits a ON a.id = cr.audit_id "
                        + "WHERE a.organization_id = ? AND cr.audit_id = ? "
                        + "ORDER BY cr.created_at, cr.id",
                MAPPER, organizationId, auditId);
    }

    public Optional<CorrectionRequest> findById(String organizationId, String auditId,
                                                String correctionId) {
        List<CorrectionRequest> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM correction_requests cr "
                        + "JOIN carbon_audits a ON a.id = cr.audit_id "
                        + "WHERE a.organization_id = ? AND cr.audit_id = ? AND cr.id = ?",
                MAPPER, organizationId, auditId, correctionId);
        return rows.stream().findFirst();
    }

    public CorrectionRequest insert(String auditId, String activityDataId, String reason,
                                    String requestedBy) {
        return jdbc.queryForObject(
                "INSERT INTO correction_requests "
                        + "(audit_id, activity_data_id, reason, requested_by) "
                        + "VALUES (?, ?, ?, ?) "
                        + "RETURNING id::text, audit_id::text, activity_data_id::text, "
                        + "reason, requested_by::text, is_resolved, created_at",
                MAPPER, auditId, activityDataId, reason, requestedBy);
    }

    /**
     * Lifecycle flag only ({@code is_resolved}) — the reason text is
     * immutable history once written.
     */
    public int updateResolved(String organizationId, String auditId, String correctionId,
                              boolean resolved) {
        return jdbc.update(
                "UPDATE correction_requests cr SET is_resolved = ? "
                        + "FROM carbon_audits a "
                        + "WHERE a.id = cr.audit_id "
                        + "AND a.organization_id = ? AND cr.audit_id = ? AND cr.id = ?",
                resolved, organizationId, auditId, correctionId);
    }

    /** Node-list enrichment parity: unresolved correction count (future gates). */
    public int openCount(String organizationId, String auditId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM correction_requests cr "
                        + "JOIN carbon_audits a ON a.id = cr.audit_id "
                        + "WHERE a.organization_id = ? AND cr.audit_id = ? "
                        + "AND cr.is_resolved = FALSE",
                Integer.class, organizationId, auditId);
        return count == null ? 0 : count;
    }
}

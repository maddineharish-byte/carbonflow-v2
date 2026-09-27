package com.carbonflow.repository;

import com.carbonflow.model.AuditApproval;
import com.carbonflow.model.AuditLockEvent;
import com.carbonflow.model.CarbonAudit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code carbon_audits} plus its transition side-effect
 * tables {@code audit_approvals} and {@code audit_lock_events} (ADR-009 —
 * no ORM).
 *
 * <p><b>Tenant-isolation audit:</b> every statement carries
 * {@code organization_id = ?} from the authenticated tenant context; child
 * reads JOIN through {@code carbon_audits} so approvals/lock events inherit
 * the audit's organization predicate (V5 {@code uq_audits_tenant_id}
 * reinforces the pairing). Inserts stamp the caller's organization.
 *
 * <p>The unprefixed base column list is deliberate: it serves both
 * single-table SELECT and {@code INSERT … RETURNING}, where no table alias
 * exists (Phase 4 lesson from {@code BoundaryRepository}).
 */
@Repository
public class AuditRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, reporting_period_id::text, status, "
                    + "initiated_by::text, approved_by::text, locked_at, notes, "
                    + "created_at, updated_at";

    static final RowMapper<CarbonAudit> MAPPER = (rs, rowNum) -> {
        CarbonAudit audit = new CarbonAudit();
        audit.setId(rs.getString("id"));
        audit.setOrganizationId(rs.getString("organization_id"));
        audit.setReportingPeriodId(rs.getString("reporting_period_id"));
        audit.setStatus(rs.getString("status"));
        audit.setInitiatedBy(rs.getString("initiated_by"));
        audit.setApprovedBy(rs.getString("approved_by"));
        audit.setLockedAt(instant(rs, "locked_at"));
        audit.setNotes(rs.getString("notes"));
        audit.setCreatedAt(instant(rs, "created_at"));
        audit.setUpdatedAt(instant(rs, "updated_at"));
        return audit;
    };

    static final RowMapper<AuditApproval> APPROVAL_MAPPER = (rs, rowNum) -> {
        AuditApproval approval = new AuditApproval();
        approval.setId(rs.getString("id"));
        approval.setAuditId(rs.getString("audit_id"));
        approval.setApproverId(rs.getString("approver_id"));
        approval.setRole(rs.getString("role"));
        approval.setSignatureHash(rs.getString("signature_hash"));
        approval.setTimestamp(instant(rs, "timestamp"));
        return approval;
    };

    static final RowMapper<AuditLockEvent> LOCK_EVENT_MAPPER = (rs, rowNum) -> {
        AuditLockEvent event = new AuditLockEvent();
        event.setId(rs.getString("id"));
        event.setAuditId(rs.getString("audit_id"));
        event.setLockedBy(rs.getString("locked_by"));
        event.setInventoryHash(rs.getString("inventory_hash"));
        event.setLockedAt(instant(rs, "locked_at"));
        return event;
    };

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static java.time.Instant instant(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    // ------------------------------------------------------------------
    // carbon_audits
    // ------------------------------------------------------------------

    public CarbonAudit insert(String organizationId, String reportingPeriodId,
                              String initiatedBy, String notes) {
        return jdbc.queryForObject(
                "INSERT INTO carbon_audits (organization_id, reporting_period_id, "
                        + "initiated_by, notes) VALUES (?, ?, ?, ?) RETURNING " + COLUMNS,
                MAPPER, organizationId, reportingPeriodId, initiatedBy, notes);
    }

    public Optional<CarbonAudit> findById(String organizationId, String auditId) {
        List<CarbonAudit> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM carbon_audits "
                        + "WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, auditId);
        return rows.stream().findFirst();
    }

    /**
     * Row lock for transition serialization — must run inside the
     * caller's transaction so two concurrent transitions cannot both pass
     * the state-machine validation.
     */
    public Optional<CarbonAudit> findByIdForUpdate(String organizationId, String auditId) {
        List<CarbonAudit> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM carbon_audits "
                        + "WHERE organization_id = ? AND id = ? FOR UPDATE",
                MAPPER, organizationId, auditId);
        return rows.stream().findFirst();
    }

    /** Newest first (no Node ordering exists — greenfield, documented). */
    public List<CarbonAudit> list(String organizationId) {
        return jdbc.query(
                "SELECT a.id::text, a.organization_id::text, a.reporting_period_id::text, "
                        + "a.status, a.initiated_by::text, a.approved_by::text, a.locked_at, "
                        + "a.notes, a.created_at, a.updated_at, p.name AS period_name, "
                        + "(SELECT count(*) FROM audit_checklist_items ci "
                        + " WHERE ci.audit_id = a.id) AS checklist_total, "
                        + "(SELECT count(*) FROM audit_checklist_items ci "
                        + " WHERE ci.audit_id = a.id AND ci.is_satisfied = TRUE) "
                        + "AS checklist_satisfied, "
                        + "(SELECT count(*) FROM review_findings rf "
                        + " WHERE rf.audit_id = a.id AND rf.status = 'OPEN') AS open_findings "
                        + "FROM carbon_audits a "
                        + "JOIN reporting_periods p ON p.id = a.reporting_period_id "
                        + "WHERE a.organization_id = ? "
                        + "ORDER BY a.created_at DESC, a.id",
                (rs, rowNum) -> {
                    CarbonAudit audit = MAPPER.mapRow(rs, rowNum);
                    audit.setPeriodName(rs.getString("period_name"));
                    audit.setChecklistSummary(new CarbonAudit.ChecklistSummary(
                            rs.getInt("checklist_total"), rs.getInt("checklist_satisfied")));
                    audit.setOpenFindingsCount(rs.getInt("open_findings"));
                    return audit;
                },
                organizationId);
    }

    /** Draft-only metadata update (status never changes through this path). */
    public int updateNotes(String organizationId, String auditId, String notes) {
        return jdbc.update(
                "UPDATE carbon_audits SET notes = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE organization_id = ? AND id = ?",
                notes, organizationId, auditId);
    }

    /** State-machine write: status always travels with lock/approval facts. */
    public int updateTransition(String organizationId, String auditId, String status,
                                String approvedBy, OffsetDateTime lockedAt) {
        return jdbc.update(
                "UPDATE carbon_audits SET status = ?, "
                        + "approved_by = COALESCE(?, approved_by), "
                        + "locked_at = COALESCE(?, locked_at), "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "WHERE organization_id = ? AND id = ?",
                status, approvedBy, lockedAt, organizationId, auditId);
    }

    public boolean periodHasAudit(String organizationId, String reportingPeriodId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM carbon_audits "
                        + "WHERE organization_id = ? AND reporting_period_id = ?",
                Integer.class, organizationId, reportingPeriodId);
        return count != null && count > 0;
    }

    // ------------------------------------------------------------------
    // audit_approvals (written only by the REVIEW -> APPROVED transition)
    // ------------------------------------------------------------------

    public AuditApproval insertApproval(String auditId, String approverId, String role,
                                        String signatureHash) {
        return jdbc.queryForObject(
                "INSERT INTO audit_approvals (audit_id, approver_id, role, signature_hash) "
                        + "VALUES (?, ?, ?, ?) "
                        + "RETURNING id::text, audit_id::text, approver_id::text, role, "
                        + "signature_hash, timestamp",
                APPROVAL_MAPPER, auditId, approverId, role, signatureHash);
    }

    public List<AuditApproval> listApprovals(String organizationId, String auditId) {
        return jdbc.query(
                "SELECT ap.id::text, ap.audit_id::text, ap.approver_id::text, ap.role, "
                        + "ap.signature_hash, ap.timestamp "
                        + "FROM audit_approvals ap "
                        + "JOIN carbon_audits a ON a.id = ap.audit_id "
                        + "WHERE a.organization_id = ? AND ap.audit_id = ? "
                        + "ORDER BY ap.timestamp, ap.id",
                APPROVAL_MAPPER, organizationId, auditId);
    }

    // ------------------------------------------------------------------
    // audit_lock_events (written only by the AUDIT_READY -> LOCKED transition)
    // ------------------------------------------------------------------

    public AuditLockEvent insertLockEvent(String auditId, String lockedBy, String inventoryHash) {
        return jdbc.queryForObject(
                "INSERT INTO audit_lock_events (audit_id, locked_by, inventory_hash) "
                        + "VALUES (?, ?, ?) "
                        + "RETURNING id::text, audit_id::text, locked_by::text, "
                        + "inventory_hash, locked_at",
                LOCK_EVENT_MAPPER, auditId, lockedBy, inventoryHash);
    }

    public Optional<AuditLockEvent> findLockEvent(String organizationId, String auditId) {
        List<AuditLockEvent> rows = jdbc.query(
                "SELECT le.id::text, le.audit_id::text, le.locked_by::text, "
                        + "le.inventory_hash, le.locked_at "
                        + "FROM audit_lock_events le "
                        + "JOIN carbon_audits a ON a.id = le.audit_id "
                        + "WHERE a.organization_id = ? AND le.audit_id = ?",
                LOCK_EVENT_MAPPER, organizationId, auditId);
        return rows.stream().findFirst();
    }
}

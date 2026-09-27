package com.carbonflow.repository;

import com.carbonflow.model.AuditChecklistItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code audit_checklist_items} (ADR-009).
 *
 * <p><b>Tenant-isolation audit:</b> the table has no organization column —
 * every statement joins through {@code carbon_audits} and carries
 * {@code a.organization_id = ?} (the {@code boundary_facilities} pattern,
 * ADR-015). The transition gates read unsatisfied mandatory items through
 * the same predicate.
 */
@Repository
public class ChecklistRepository {

    static final String COLUMNS =
            "ci.id::text, ci.audit_id::text, ci.code, ci.title, ci.is_mandatory, "
                    + "ci.is_satisfied, ci.verified_by::text, ci.verified_at, ci.notes";

    static final RowMapper<AuditChecklistItem> MAPPER = (rs, rowNum) -> {
        AuditChecklistItem item = new AuditChecklistItem();
        item.setId(rs.getString("id"));
        item.setAuditId(rs.getString("audit_id"));
        item.setCode(rs.getString("code"));
        item.setTitle(rs.getString("title"));
        item.setMandatory(rs.getBoolean("is_mandatory"));
        item.setSatisfied(rs.getBoolean("is_satisfied"));
        item.setVerifiedBy(rs.getString("verified_by"));
        java.sql.Timestamp verifiedAt = rs.getTimestamp("verified_at");
        item.setVerifiedAt(verifiedAt == null ? null : verifiedAt.toInstant());
        item.setNotes(rs.getString("notes"));
        return item;
    };

    private final JdbcTemplate jdbc;

    public ChecklistRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditChecklistItem> listByAudit(String organizationId, String auditId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM audit_checklist_items ci "
                        + "JOIN carbon_audits a ON a.id = ci.audit_id "
                        + "WHERE a.organization_id = ? AND ci.audit_id = ? "
                        + "ORDER BY ci.code",
                MAPPER, organizationId, auditId);
    }

    public Optional<AuditChecklistItem> findById(String organizationId, String auditId,
                                                 String itemId) {
        List<AuditChecklistItem> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM audit_checklist_items ci "
                        + "JOIN carbon_audits a ON a.id = ci.audit_id "
                        + "WHERE a.organization_id = ? AND ci.audit_id = ? AND ci.id = ?",
                MAPPER, organizationId, auditId, itemId);
        return rows.stream().findFirst();
    }

    public AuditChecklistItem insert(String auditId, String code, String title,
                                     boolean mandatory) {
        return jdbc.queryForObject(
                "INSERT INTO audit_checklist_items (audit_id, code, title, is_mandatory) "
                        + "VALUES (?, ?, ?, ?) "
                        + "RETURNING id::text, audit_id::text, code, title, is_mandatory, "
                        + "is_satisfied, verified_by::text, verified_at, notes",
                MAPPER, auditId, code, title, mandatory);
    }

    /** Structural edits — satisfaction changes only through {@link #verify}. */
    public int updateStructure(String organizationId, String auditId, String itemId,
                               String title, Boolean mandatory, String notes) {
        return jdbc.update(
                "UPDATE audit_checklist_items ci SET "
                        + "title = COALESCE(?, ci.title), "
                        + "is_mandatory = COALESCE(?, ci.is_mandatory), "
                        + "notes = COALESCE(?, ci.notes) "
                        + "FROM carbon_audits a "
                        + "WHERE a.id = ci.audit_id "
                        + "AND a.organization_id = ? AND ci.audit_id = ? AND ci.id = ?",
                title, mandatory, notes, organizationId, auditId, itemId);
    }

    public int verify(String organizationId, String auditId, String itemId,
                      boolean satisfied, String verifiedBy, String notes) {
        return jdbc.update(
                "UPDATE audit_checklist_items ci SET "
                        + "is_satisfied = ?, verified_by = ?, "
                        + "verified_at = CURRENT_TIMESTAMP, "
                        + "notes = COALESCE(?, ci.notes) "
                        + "FROM carbon_audits a "
                        + "WHERE a.id = ci.audit_id "
                        + "AND a.organization_id = ? AND ci.audit_id = ? AND ci.id = ?",
                satisfied, verifiedBy, notes, organizationId, auditId, itemId);
    }

    /** Transition gate: mandatory items not yet satisfied, for one audit. */
    public List<String> unsatisfiedMandatoryCodes(String organizationId, String auditId) {
        return jdbc.queryForList(
                "SELECT ci.code FROM audit_checklist_items ci "
                        + "JOIN carbon_audits a ON a.id = ci.audit_id "
                        + "WHERE a.organization_id = ? AND ci.audit_id = ? "
                        + "AND ci.is_mandatory = TRUE AND ci.is_satisfied = FALSE "
                        + "ORDER BY ci.code",
                String.class, organizationId, auditId);
    }
}

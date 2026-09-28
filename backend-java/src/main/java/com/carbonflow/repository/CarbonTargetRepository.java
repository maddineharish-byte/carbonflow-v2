package com.carbonflow.repository;

import com.carbonflow.model.CarbonTarget;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code carbon_targets} (ADR-009 — no ORM; V1 columns:
 * NOT NULL period FKs, NUMERIC(18,4) values, NUMERIC(5,2) percentage, status
 * CHECK, nullable owner/notes, {@code created_at} only — the table has no
 * {@code updated_at}, so updates change values without a timestamp column).
 *
 * <p><b>Tenant-isolation audit:</b> {@code organization_id = ?} on every
 * statement. Listing order {@code created_at DESC, id DESC} — newest first
 * with a deterministic tiebreak (documented; Node returned insertion order).
 *
 * <p>Created in Phase 7 for dashboard counts; extended with the full target
 * queries in task 7.4.
 */
@Repository
public class CarbonTargetRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, name, baseline_period_id::text, "
                    + "target_period_id::text, baseline_value_t, target_value_t, "
                    + "reduction_percentage, status, owner_id::text, notes, created_at";

    static final RowMapper<CarbonTarget> MAPPER = (rs, rowNum) -> new CarbonTarget(
            rs.getString("id"),
            rs.getString("organization_id"),
            rs.getString("name"),
            rs.getString("baseline_period_id"),
            rs.getString("target_period_id"),
            rs.getBigDecimal("baseline_value_t"),
            rs.getBigDecimal("target_value_t"),
            rs.getBigDecimal("reduction_percentage"),
            rs.getString("status"),
            rs.getString("owner_id"),
            rs.getString("notes"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public CarbonTargetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code GET /analytics/dashboard} targets counter. */
    public int count(String organizationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM carbon_targets WHERE organization_id = ?",
                Integer.class, organizationId);
        return count == null ? 0 : count;
    }

    public List<CarbonTarget> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM carbon_targets WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id DESC",
                MAPPER, organizationId);
    }

    public Optional<CarbonTarget> findById(String organizationId, String targetId) {
        List<CarbonTarget> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM carbon_targets "
                        + "WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, targetId);
        return rows.stream().findFirst();
    }

    public CarbonTarget insert(String organizationId, String name, String baselinePeriodId,
                               String targetPeriodId, BigDecimal baselineValueT,
                               BigDecimal targetValueT, BigDecimal reductionPercentage,
                               String status, String ownerId, String notes) {
        return jdbc.queryForObject(
                "INSERT INTO carbon_targets "
                        + "(organization_id, name, baseline_period_id, target_period_id, "
                        + " baseline_value_t, target_value_t, reduction_percentage, status, "
                        + " owner_id, notes) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                MAPPER, organizationId, name, baselinePeriodId, targetPeriodId,
                baselineValueT, targetValueT, reductionPercentage, status, ownerId, notes);
    }

    /**
     * Full mutable-state update — {@code owner_id}, {@code created_at} and the
     * tenant are untouched by this statement (immutable identity fields).
     */
    public int update(String organizationId, String targetId, String name,
                      String baselinePeriodId, String targetPeriodId,
                      BigDecimal baselineValueT, BigDecimal targetValueT,
                      BigDecimal reductionPercentage, String status, String notes) {
        return jdbc.update(
                "UPDATE carbon_targets SET name = ?, baseline_period_id = ?, "
                        + "target_period_id = ?, baseline_value_t = ?, target_value_t = ?, "
                        + "reduction_percentage = ?, status = ?, notes = ? "
                        + "WHERE organization_id = ? AND id = ?",
                name, baselinePeriodId, targetPeriodId, baselineValueT, targetValueT,
                reductionPercentage, status, notes, organizationId, targetId);
    }
}

package com.carbonflow.repository;

import com.carbonflow.model.InventorySnapshot;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code inventory_snapshots} (V1: status lifecycle check,
 * {@code snapshot_hash} VARCHAR(64), index on
 * {@code (organization_id, reporting_period_id)}).
 *
 * <p><b>Tenant-isolation audit:</b> {@code organization_id = ?} on every
 * read and write; inserts stamp the caller's organization. Listing order is
 * {@code created_at DESC, id DESC} — newest first with a deterministic
 * tiebreak so identical databases always answer identical payloads.
 *
 * <p>{@code audit_id} is never written (schema-nullable, stays NULL — see the
 * model javadoc); no column beyond the V1 table exists or is needed.
 */
@Repository
public class InventorySnapshotRepository {

    static final String COLUMNS =
            "id::text, organization_id::text, reporting_period_id::text, audit_id::text, "
                    + "scope1_co2e_t, scope2_location_co2e_t, scope2_market_co2e_t, "
                    + "biogenic_co2e_t, status, snapshot_hash, created_at";

    static final RowMapper<InventorySnapshot> MAPPER = (rs, rowNum) -> new InventorySnapshot(
            rs.getString("id"),
            rs.getString("organization_id"),
            rs.getString("reporting_period_id"),
            rs.getString("audit_id"),
            rs.getBigDecimal("scope1_co2e_t"),
            rs.getBigDecimal("scope2_location_co2e_t"),
            rs.getBigDecimal("scope2_market_co2e_t"),
            rs.getBigDecimal("biogenic_co2e_t"),
            rs.getString("status"),
            rs.getString("snapshot_hash"),
            rs.getObject("created_at", OffsetDateTime.class).toInstant());

    private final JdbcTemplate jdbc;

    public InventorySnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<InventorySnapshot> list(String organizationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM inventory_snapshots "
                        + "WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id DESC",
                MAPPER, organizationId);
    }

    public Optional<InventorySnapshot> findById(String organizationId, String snapshotId) {
        List<InventorySnapshot> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM inventory_snapshots "
                        + "WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, snapshotId);
        return rows.stream().findFirst();
    }

    /**
     * All snapshots of one reporting period, newest first — the basis for the
     * one-ACTIVE-per-period rule and the LOCKED freeze check (service layer).
     */
    public List<InventorySnapshot> listByPeriod(String organizationId, String periodId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM inventory_snapshots "
                        + "WHERE organization_id = ? AND reporting_period_id = ? "
                        + "ORDER BY created_at DESC, id DESC",
                MAPPER, organizationId, periodId);
    }

    public InventorySnapshot insert(String organizationId, String reportingPeriodId,
                                    BigDecimal scope1Co2eT, BigDecimal scope2LocationCo2eT,
                                    BigDecimal scope2MarketCo2eT, BigDecimal biogenicCo2eT,
                                    String status, String snapshotHash) {
        return jdbc.queryForObject(
                "INSERT INTO inventory_snapshots "
                        + "(organization_id, reporting_period_id, scope1_co2e_t, "
                        + " scope2_location_co2e_t, scope2_market_co2e_t, biogenic_co2e_t, "
                        + " status, snapshot_hash) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                MAPPER, organizationId, reportingPeriodId, scope1Co2eT,
                scope2LocationCo2eT, scope2MarketCo2eT, biogenicCo2eT,
                status, snapshotHash);
    }

    /**
     * Status-only transition (ACTIVE → REVERTED supersede, ACTIVE → LOCKED) —
     * tenant-predicated, amounts and hash are immutable after insert.
     */
    public int updateStatus(String organizationId, String snapshotId, String status) {
        return jdbc.update(
                "UPDATE inventory_snapshots SET status = ? "
                        + "WHERE organization_id = ? AND id = ?",
                status, organizationId, snapshotId);
    }
}

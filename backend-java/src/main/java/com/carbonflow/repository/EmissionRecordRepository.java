package com.carbonflow.repository;

import com.carbonflow.model.EmissionRecord;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.model.enums.Scope2Method;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Explicit SQL for {@code emission_records} (ADR-009 — no ORM), ported from
 * {@code server/calculation-repository.ts}.
 *
 * <p><b>Tenant-isolation audit:</b> every statement predicates
 * {@code organization_id = ?} from the authenticated tenant context; V6 adds
 * composite foreign keys on {@code (organization_id, reporting_period_id)},
 * {@code (organization_id, facility_id)} and
 * {@code (organization_id, calculation_id)} as the database backstop.
 *
 * <p><b>Scope 2 dual reporting:</b> a record stores exactly one
 * {@code scope2_type} ({@code LOCATION_BASED} / {@code MARKET_BASED}) or none
 * for Scope 1/3 — location- and market-based results are separate rows. No
 * query in this class (or its services) ever sums across the two
 * perspectives.
 *
 * <p>Supersession: when an activity is re-calculated, its previous ACTIVE
 * records flip to SUPERSEDED (Node's statement); historical rows are never
 * deleted or rewritten, so past calculations keep their values.
 */
@Repository
public class EmissionRecordRepository {

    private static final String COLUMNS =
            "id, organization_id, reporting_period_id, facility_id, calculation_id, "
                    + "scope, category, scope2_type, co2e_tonnes, status, created_at";

    private static final RowMapper<EmissionRecord> MAPPER = (rs, rowNum) -> {
        EmissionRecord record = new EmissionRecord();
        record.setId(rs.getString("id"));
        record.setOrganizationId(rs.getString("organization_id"));
        record.setReportingPeriodId(rs.getString("reporting_period_id"));
        record.setFacilityId(rs.getString("facility_id"));
        record.setCalculationId(rs.getString("calculation_id"));
        record.setScope(GHGScope.valueOf(rs.getString("scope")));
        record.setCategory(rs.getString("category"));
        String scope2Type = rs.getString("scope2_type");
        record.setScope2Type(scope2Type == null ? null : Scope2Method.valueOf(scope2Type));
        record.setCo2eTonnes(rs.getBigDecimal("co2e_tonnes"));
        record.setStatus(rs.getString("status"));
        record.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class).toInstant());
        return record;
    };

    private final JdbcTemplate jdbc;

    public EmissionRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the engine-produced record (explicit id + created_at, Node parity). */
    public void insert(String organizationId, EmissionRecord record) {
        jdbc.update(
                "INSERT INTO emission_records (id, organization_id, reporting_period_id, "
                        + "facility_id, calculation_id, scope, category, scope2_type, "
                        + "co2e_tonnes, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.getId(), organizationId, record.getReportingPeriodId(),
                record.getFacilityId(), record.getCalculationId(), record.getScope().name(),
                record.getCategory(),
                record.getScope2Type() == null ? null : record.getScope2Type().name(),
                record.getCo2eTonnes(), record.getStatus(),
                // pgjdbc cannot infer a type for java.time.Instant (ADR-017
                // binding convention) — the timestamptz column takes Timestamp.
                record.getCreatedAt() == null ? null
                        : java.sql.Timestamp.from(record.getCreatedAt()));
    }

    /**
     * Node's supersession statement: every ACTIVE record of the given activity
     * flips to SUPERSEDED inside the same transaction as the new insert.
     */
    public int supersedeActiveForActivity(String organizationId, String activityId) {
        return jdbc.update(
                "UPDATE emission_records er SET status = 'SUPERSEDED' "
                        + "WHERE er.organization_id = ? AND er.status = 'ACTIVE' "
                        + "AND er.calculation_id IN ("
                        + "  SELECT c.id FROM calculations c"
                        + "   WHERE c.organization_id = ? AND c.activity_data_id = ?)",
                organizationId, organizationId, activityId);
    }

    /** Node's {@code listEmissionRecords} with all four optional filters. */
    public List<EmissionRecord> list(String organizationId, String periodId, String scope,
                                     String status, String calculationId) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM emission_records "
                        + "WHERE organization_id = ? "
                        + "AND (?::text IS NULL OR reporting_period_id = ?::uuid) "
                        + "AND (?::text IS NULL OR scope = ?) "
                        + "AND (?::text IS NULL OR status = ?) "
                        + "AND (?::text IS NULL OR calculation_id = ?::uuid) "
                        + "ORDER BY created_at DESC, id DESC",
                MAPPER,
                organizationId, periodId, periodId, scope, scope, status, status,
                calculationId, calculationId);
    }

    /**
     * Phase 7 export (task 7.6): tenant-scoped ACTIVE ledger with the four
     * client filters — every parameter is validated by the controller before
     * it reaches SQL ({@code 400} for malformed UUIDs/unknown enum values, so
     * a bad filter can never raise a cast error), and the ordering is the
     * deterministic {@code created_at DESC, id DESC} (identical databases
     * always produce byte-identical CSVs).
     */
    public List<EmissionRecord> listForExport(String organizationId, String periodId,
                                              String facilityId, String scope,
                                              String scope2Type) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM emission_records "
                        + "WHERE organization_id = ? AND status = 'ACTIVE' "
                        + "AND (?::text IS NULL OR reporting_period_id = ?::uuid) "
                        + "AND (?::text IS NULL OR facility_id = ?::uuid) "
                        + "AND (?::text IS NULL OR scope = ?) "
                        + "AND (?::text IS NULL OR scope2_type = ?) "
                        + "ORDER BY created_at DESC, id DESC",
                MAPPER,
                organizationId, periodId, periodId, facilityId, facilityId,
                scope, scope, scope2Type, scope2Type);
    }

    public Optional<EmissionRecord> findById(String organizationId, String emissionId) {
        List<EmissionRecord> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM emission_records "
                        + "WHERE organization_id = ? AND id = ?",
                MAPPER, organizationId, emissionId);
        return rows.stream().findFirst();
    }

    /** Node's {@code listActivityEmissionRecords}: ledger rows of one activity. */
    public List<EmissionRecord> listForActivity(String organizationId, String activityId) {
        return jdbc.query(
                "SELECT er.id, er.organization_id, er.reporting_period_id, er.facility_id, "
                        + "er.calculation_id, er.scope, er.category, er.scope2_type, "
                        + "er.co2e_tonnes, er.status, er.created_at "
                        + "FROM emission_records er "
                        + "JOIN calculations c ON c.id = er.calculation_id "
                        + "  AND c.organization_id = er.organization_id "
                        + "WHERE er.organization_id = ? AND c.activity_data_id = ? "
                        + "ORDER BY er.created_at DESC, er.id DESC",
                MAPPER, organizationId, activityId);
    }

    /**
     * Platform-wide diagnostic for the self-test endpoint
     * ({@code GET /test-suite/run}, gated behind {@code platform.tenants.manage}
     * — the only caller, and the reason this query is deliberately not
     * tenant-predicated): every {@code SCOPE_2} row must carry a
     * {@code scope2_type} and no other scope may carry one. The engine only
     * ever writes classified Scope 2 rows, so this invariant must hold for all
     * statuses (active and superseded alike).
     */
    public int countScope2ClassificationViolations() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM emission_records "
                        + "WHERE (scope = 'SCOPE_2' AND scope2_type IS NULL) "
                        + "   OR (scope <> 'SCOPE_2' AND scope2_type IS NOT NULL)",
                Integer.class);
        return count == null ? 0 : count;
    }

    /** Perspective totals of the self-test diagnostic (see above). */
    public record PerspectiveTotals(BigDecimal scope1, BigDecimal location, BigDecimal market) {
    }

    /**
     * Platform-wide ACTIVE totals per perspective, reported side by side —
     * location and market are summed independently and never added together
     * (ADR-002 dual reporting). Same caller restriction as
     * {@link #countScope2ClassificationViolations()}.
     */
    public PerspectiveTotals sumActivePerspectives() {
        PerspectiveTotals totals = jdbc.queryForObject(
                "SELECT COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_1'), 0), "
                        + "       COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "                                    AND scope2_type = 'LOCATION_BASED'), 0), "
                        + "       COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "                                    AND scope2_type = 'MARKET_BASED'), 0) "
                        + "FROM emission_records WHERE status = 'ACTIVE'",
                (rs, rowNum) -> new PerspectiveTotals(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)));
        return totals == null
                ? new PerspectiveTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                : totals;
    }

    /**
     * Phase 7: tenant- and period-scoped perspective sums of the ACTIVE
     * ledger — the read-only aggregation inventory snapshots persist (a
     * report over already-calculated records, never a second calculation).
     * Same dual-basis FILTER clauses as {@link #sumActivePerspectives()};
     * always one aggregated row (COALESCE), so {@code null} stays defensive.
     */
    public PerspectiveTotals sumActiveForPeriod(String organizationId, String periodId) {
        PerspectiveTotals totals = jdbc.queryForObject(
                "SELECT COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_1'), 0), "
                        + "       COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "                                    AND scope2_type = 'LOCATION_BASED'), 0), "
                        + "       COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "                                    AND scope2_type = 'MARKET_BASED'), 0) "
                        + "FROM emission_records "
                        + "WHERE status = 'ACTIVE' AND organization_id = ? "
                        + "AND reporting_period_id = ?",
                (rs, rowNum) -> new PerspectiveTotals(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)),
                organizationId, periodId);
        return totals == null
                ? new PerspectiveTotals(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)
                : totals;
    }
}

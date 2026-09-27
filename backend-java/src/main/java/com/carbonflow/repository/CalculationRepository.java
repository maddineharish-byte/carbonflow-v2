package com.carbonflow.repository;

import com.carbonflow.model.Calculation;
import com.carbonflow.model.CalculationGasResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit SQL for {@code calculations} + {@code calculation_gas_results}
 * (ADR-009 — no ORM), ported from {@code server/calculation-repository.ts}.
 *
 * <p><b>Tenant-isolation audit:</b> every statement predicates
 * {@code organization_id = ?} from the authenticated tenant context; the V6
 * composite foreign keys {@code (organization_id, activity_data_id)},
 * {@code (organization_id, reporting_period_id)} and the unique index on
 * {@code (organization_id, id)} are the database backstop.
 *
 * <p>Calculation rows are immutable snapshots: this repository exposes no
 * UPDATE for fact columns — only inserts. A later calculation supersedes the
 * previous one at the <em>emission-record</em> layer (ACTIVE → SUPERSEDED),
 * never by rewriting history.
 *
 * <p>Gas results are read back ordered by gas name (Node's
 * {@code json_agg(... ORDER BY gr.gas)}) while the run response returns them
 * in engine order (CO2, CH4, N2O) — same divergence the reference has.
 */
@Repository
public class CalculationRepository {

    private static final String COLUMNS =
            "c.id, c.organization_id, c.activity_data_id, c.reporting_period_id, "
                    + "c.factor_version_id, c.gwp_set_id, c.original_quantity, c.original_unit, "
                    + "c.normalized_quantity, c.normalized_unit, c.conversion_factor, "
                    + "c.factor_value, c.factor_unit, c.factor_source, c.factor_version_number, "
                    + "c.gwp_name, c.total_co2e_kg, c.total_co2e_tonnes, c.calculation_hash, "
                    + "c.calculated_at, c.calculated_by, c.factor_id";

    private static final String FROM = " FROM calculations c";

    private static final RowMapper<Calculation> MAPPER = (rs, rowNum) -> {
        Calculation calculation = new Calculation();
        calculation.setId(rs.getString("id"));
        calculation.setOrganizationId(rs.getString("organization_id"));
        calculation.setActivityDataId(rs.getString("activity_data_id"));
        calculation.setReportingPeriodId(rs.getString("reporting_period_id"));
        calculation.setFactorVersionId(rs.getString("factor_version_id"));
        calculation.setGwpSetId(rs.getString("gwp_set_id"));
        calculation.setOriginalQuantity(rs.getBigDecimal("original_quantity"));
        calculation.setOriginalUnit(rs.getString("original_unit"));
        calculation.setNormalizedQuantity(rs.getBigDecimal("normalized_quantity"));
        calculation.setNormalizedUnit(rs.getString("normalized_unit"));
        calculation.setConversionFactor(rs.getBigDecimal("conversion_factor"));
        calculation.setFactorValue(rs.getBigDecimal("factor_value"));
        calculation.setFactorUnit(rs.getString("factor_unit"));
        calculation.setFactorSource(rs.getString("factor_source"));
        calculation.setFactorVersion(rs.getInt("factor_version_number"));
        calculation.setGwpName(rs.getString("gwp_name"));
        calculation.setTotalCo2eKg(rs.getBigDecimal("total_co2e_kg"));
        calculation.setTotalCo2eTonnes(rs.getBigDecimal("total_co2e_tonnes"));
        calculation.setCalculationHash(rs.getString("calculation_hash"));
        calculation.setCalculatedAt(rs.getObject("calculated_at", OffsetDateTime.class).toInstant());
        calculation.setCalculatedBy(rs.getString("calculated_by"));
        calculation.setFactorId(rs.getString("factor_id"));
        return calculation;
    };

    private static final RowMapper<CalculationGasResult> GAS_MAPPER = (rs, rowNum) ->
            new CalculationGasResult(
                    rs.getString("gas"),
                    rs.getBigDecimal("raw_gas_emission_kg"),
                    rs.getBigDecimal("gwp_applied"),
                    rs.getBigDecimal("co2e_kg"));

    private final JdbcTemplate jdbc;

    public CalculationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the calculation snapshot exactly as produced by the engine
     * (22 columns — the same set Node's {@code persistCalculation} writes,
     * including the V6 provenance columns factor_id/unit/source/version and
     * the conversion factor).
     */
    public void insert(String organizationId, String userId, Calculation calculation) {
        jdbc.update(
                "INSERT INTO calculations (id, organization_id, activity_data_id, "
                        + "reporting_period_id, factor_version_id, gwp_set_id, "
                        + "original_quantity, original_unit, normalized_quantity, normalized_unit, "
                        + "conversion_factor, factor_value, factor_unit, factor_source, "
                        + "factor_version_number, gwp_name, total_co2e_kg, total_co2e_tonnes, "
                        + "calculation_hash, calculated_at, calculated_by, factor_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                calculation.getId(), organizationId, calculation.getActivityDataId(),
                calculation.getReportingPeriodId(), calculation.getFactorVersionId(),
                calculation.getGwpSetId(), calculation.getOriginalQuantity(),
                calculation.getOriginalUnit(), calculation.getNormalizedQuantity(),
                calculation.getNormalizedUnit(), calculation.getConversionFactor(),
                calculation.getFactorValue(), calculation.getFactorUnit(),
                calculation.getFactorSource(), calculation.getFactorVersion(),
                calculation.getGwpName(), calculation.getTotalCo2eKg(),
                calculation.getTotalCo2eTonnes(), calculation.getCalculationHash(),
                // pgjdbc cannot infer a type for java.time.Instant (ADR-017
                // binding convention) — the timestamptz column takes Timestamp.
                calculation.getCalculatedAt() == null ? null
                        : java.sql.Timestamp.from(calculation.getCalculatedAt()),
                userId, calculation.getFactorId());
    }

    public void insertGasResults(Calculation calculation) {
        for (CalculationGasResult gas : calculation.getGasResults()) {
            jdbc.update(
                    "INSERT INTO calculation_gas_results "
                            + "(calculation_id, gas, raw_gas_emission_kg, gwp_applied, co2e_kg) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    calculation.getId(), gas.getGas(), gas.getRawGasEmissionKg(),
                    gas.getGwpApplied(), gas.getCo2eKg());
        }
    }

    public Optional<Calculation> findById(String organizationId, String calculationId) {
        List<Calculation> rows = jdbc.query(
                "SELECT " + COLUMNS + FROM + " WHERE c.organization_id = ? AND c.id = ?",
                MAPPER, organizationId, calculationId);
        return rows.stream().findFirst()
                .map(calculation -> withGasResults(List.of(calculation)).get(0));
    }

    /** Node's {@code listCalculations}: optional activity/period filters. */
    public List<Calculation> list(String organizationId, String activityId, String periodId) {
        List<Calculation> rows = jdbc.query(
                "SELECT " + COLUMNS + FROM
                        + " WHERE c.organization_id = ?"
                        + " AND (?::text IS NULL OR c.activity_data_id = ?::uuid)"
                        + " AND (?::text IS NULL OR c.reporting_period_id = ?::uuid)"
                        + " ORDER BY c.calculated_at DESC, c.id DESC",
                MAPPER, organizationId, activityId, activityId, periodId, periodId);
        return withGasResults(rows);
    }

    /**
     * Node's {@code listLatestCalculationsByActivities}: the newest
     * calculation per activity (ordered {@code calculated_at DESC, id DESC}),
     * keyed by activity id — used to enrich activity listings.
     */
    public Map<String, Calculation> latestByActivities(String organizationId, String[] activityIds) {
        Map<String, Calculation> byActivity = new LinkedHashMap<>();
        if (activityIds.length == 0) {
            return byActivity;
        }
        List<Calculation> rows = jdbc.query(
                "SELECT " + COLUMNS + FROM
                        + " WHERE c.organization_id = ?"
                        + " AND c.id = ("
                        + "     SELECT c2.id FROM calculations c2"
                        + "      WHERE c2.organization_id = c.organization_id"
                        + "        AND c2.activity_data_id = c.activity_data_id"
                        + "      ORDER BY c2.calculated_at DESC, c2.id DESC LIMIT 1)"
                        + " AND c.activity_data_id = ANY (?::uuid[])",
                MAPPER, organizationId, (Object) activityIds);
        for (Calculation calculation : withGasResults(rows)) {
            byActivity.put(calculation.getActivityDataId(), calculation);
        }
        return byActivity;
    }

    /**
     * Node's activity-row lock inside {@code persistCalculation}
     * ({@code SELECT ... FOR SHARE}) lives in
     * {@code ActivityDataRepository#lockForShare} — the persistence layer locks
     * the activity row, re-checks its anchors against the resolved snapshot so
     * a concurrent edit between resolution and persistence aborts the run.
     */

    /** Attaches gas results (ordered by gas) to each calculation. */
    private List<Calculation> withGasResults(List<Calculation> calculations) {
        if (calculations.isEmpty()) {
            return calculations;
        }
        String[] ids = calculations.stream().map(Calculation::getId).toArray(String[]::new);
        Map<String, List<CalculationGasResult>> byCalculation = new LinkedHashMap<>();
        jdbc.query(
                "SELECT calculation_id, gas, raw_gas_emission_kg, gwp_applied, co2e_kg "
                        + "FROM calculation_gas_results WHERE calculation_id = ANY (?::uuid[]) "
                        + "ORDER BY gas",
                (rs, rowNum) -> {
                    byCalculation.computeIfAbsent(rs.getString("calculation_id"),
                            key -> new ArrayList<>())
                            .add(GAS_MAPPER.mapRow(rs, rowNum));
                    return null;
                },
                (Object) ids);
        for (Calculation calculation : calculations) {
            List<CalculationGasResult> gases = byCalculation.get(calculation.getId());
            calculation.setGasResults(gases == null ? new ArrayList<>() : gases);
        }
        return calculations;
    }
}

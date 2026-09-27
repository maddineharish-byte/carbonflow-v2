package com.carbonflow.repository;

import com.carbonflow.model.EmissionFactor;
import com.carbonflow.model.EmissionFactorVersion;
import com.carbonflow.model.enums.GHGScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit SQL for {@code emission_factors}/{@code emission_factor_versions},
 * ported from {@code server/calculation-repository.ts}.
 *
 * <p>Reference data is global (no {@code organization_id} in either table) —
 * the factor library is shared platform-wide and versioned by
 * {@code (emission_factor_id, version_number)} with exactly one
 * {@code ACTIVE} version per factor.
 *
 * <p><b>Selection rule ({@link #resolveActiveVersion}):</b> the version whose
 * id was explicitly requested, otherwise the highest version number, but only
 * among {@code ACTIVE} versions of the factor matching the activity's
 * {@code activity_type}. An explicit id that belongs to another factor, or to
 * a superseded/archived version, resolves to nothing — the service answers
 * {@code FACTOR_NOT_FOUND} rather than silently falling back to an arbitrary
 * version.
 */
@Repository
public class EmissionFactorRepository {

    private static final String VERSION_COLUMNS =
            "efv.id, efv.emission_factor_id, efv.version_number, efv.co2_factor, "
                    + "efv.ch4_factor, efv.n2o_factor, efv.co2e_factor, efv.factor_unit, "
                    + "efv.source, efv.source_year, efv.geography, efv.status, "
                    + "efv.effective_start, efv.effective_end";

    private static final RowMapper<EmissionFactorVersion> VERSION_MAPPER = (rs, rowNum) -> {
        EmissionFactorVersion version = new EmissionFactorVersion();
        version.setId(rs.getString("id"));
        version.setEmissionFactorId(rs.getString("emission_factor_id"));
        version.setVersionNumber(rs.getInt("version_number"));
        version.setCo2Factor(rs.getBigDecimal("co2_factor"));
        version.setCh4Factor(rs.getBigDecimal("ch4_factor"));
        version.setN2oFactor(rs.getBigDecimal("n2o_factor"));
        version.setCo2eFactor(rs.getBigDecimal("co2e_factor"));
        version.setFactorUnit(rs.getString("factor_unit"));
        version.setSource(rs.getString("source"));
        version.setSourceYear(rs.getInt("source_year"));
        version.setGeography(rs.getString("geography"));
        version.setStatus(rs.getString("status"));
        version.setEffectiveStart(rs.getObject("effective_start", java.time.LocalDate.class));
        java.sql.Date effectiveEnd = rs.getDate("effective_end");
        version.setEffectiveEnd(effectiveEnd == null ? null : effectiveEnd.toLocalDate());
        return version;
    };

    private final JdbcTemplate jdbc;

    public EmissionFactorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Factors ordered like Node, each carrying its versions by number. */
    public List<EmissionFactor> listWithVersions() {
        List<EmissionFactor> factors = jdbc.query(
                "SELECT id, scope, category, activity_type, fuel_or_activity, input_unit "
                        + "FROM emission_factors ORDER BY activity_type, fuel_or_activity",
                (rs, rowNum) -> {
                    EmissionFactor factor = new EmissionFactor();
                    factor.setId(rs.getString("id"));
                    factor.setScope(GHGScope.valueOf(rs.getString("scope")));
                    factor.setCategory(rs.getString("category"));
                    factor.setActivityType(rs.getString("activity_type"));
                    factor.setFuelOrActivity(rs.getString("fuel_or_activity"));
                    factor.setInputUnit(rs.getString("input_unit"));
                    return factor;
                });
        if (factors.isEmpty()) {
            return factors;
        }
        Map<String, EmissionFactor> byId = new LinkedHashMap<>();
        for (EmissionFactor factor : factors) {
            byId.put(factor.getId(), factor);
        }
        List<EmissionFactorVersion> versions = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM emission_factor_versions efv "
                        + "WHERE efv.emission_factor_id = ANY (?::uuid[]) "
                        + "ORDER BY efv.emission_factor_id, efv.version_number",
                VERSION_MAPPER, (Object) byId.keySet().toArray(new String[0]));
        for (EmissionFactorVersion version : versions) {
            EmissionFactor factor = byId.get(version.getEmissionFactorId());
            if (factor != null) {
                factor.getVersions().add(version);
            }
        }
        return factors;
    }

    /**
     * The resolution query of {@code resolveCalculationReferences}: ACTIVE
     * version of the factor matching {@code activityType}, optionally pinned to
     * an exact id, highest version number first.
     *
     * <p>Returns {@code null} for a malformed uuid so the service can answer
     * {@code VALIDATION_ERROR} without letting PostgreSQL's uuid parser raise
     * a raw cast error (Node validates the uuid before querying).
     */
    public Optional<ResolvedVersion> resolveActiveVersion(String activityType, String factorVersionId) {
        if (factorVersionId != null && !isUuid(factorVersionId)) {
            return Optional.empty();
        }
        List<ResolvedVersion> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + ", ef.input_unit "
                        + "FROM emission_factor_versions efv "
                        + "JOIN emission_factors ef ON ef.id = efv.emission_factor_id "
                        + "WHERE ef.activity_type = ? "
                        + "  AND efv.status = 'ACTIVE' "
                        + "  AND (?::text IS NULL OR efv.id = ?::uuid) "
                        + "ORDER BY efv.version_number DESC "
                        + "LIMIT 1",
                (rs, rowNum) -> new ResolvedVersion(VERSION_MAPPER.mapRow(rs, rowNum),
                        rs.getString("input_unit")),
                activityType, factorVersionId, factorVersionId);
        return rows.stream().findFirst();
    }

    /**
     * The persistence-side re-read: the pinned version must still exist and be
     * {@code ACTIVE}, otherwise the snapshot taken at execution time no longer
     * matches the database (Node: {@code INVALID_CALCULATION_REFERENCE}).
     */
    public Optional<EmissionFactorVersion> findActiveVersion(String factorVersionId) {
        if (!isUuid(factorVersionId)) {
            return Optional.empty();
        }
        List<EmissionFactorVersion> rows = jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM emission_factor_versions efv "
                        + "WHERE efv.id = ? AND efv.status = 'ACTIVE'",
                VERSION_MAPPER, factorVersionId);
        return rows.stream().findFirst();
    }

    private static boolean isUuid(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** An ACTIVE version together with its factor's reference unit. */
    public record ResolvedVersion(EmissionFactorVersion version, String inputUnit) {
    }
}

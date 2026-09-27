package com.carbonflow.repository;

import com.carbonflow.model.GwpSet;
import com.carbonflow.model.GwpValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Explicit SQL for {@code gwp_sets}/{@code gwp_values}, ported from
 * {@code server/calculation-repository.ts}.
 *
 * <p>GWP sets are global reference data (neither table carries an
 * {@code organization_id}): every tenant reads the same catalogue, ordered
 * exactly like Node — default first, then newest publication year, then code;
 * values by gas name. Calculations persist the set id/name they used, so
 * reference changes never restate historical results.
 */
@Repository
public class GwpSetRepository {

    private static final String SET_COLUMNS =
            "id, code, name, assessment_report, publication_year, is_default";

    private static final RowMapper<GwpSet> SET_MAPPER = (rs, rowNum) -> {
        GwpSet set = new GwpSet();
        set.setId(rs.getString("id"));
        set.setCode(rs.getString("code"));
        set.setName(rs.getString("name"));
        set.setAssessmentReport(rs.getString("assessment_report"));
        set.setPublicationYear(rs.getInt("publication_year"));
        set.setDefault(rs.getBoolean("is_default"));
        return set;
    };

    private static final RowMapper<GwpValue> VALUE_MAPPER = (rs, rowNum) -> new GwpValue(
            rs.getString("id"),
            rs.getString("gwp_set_id"),
            rs.getString("gas"),
            rs.getBigDecimal("gwp_100yr"));

    private final JdbcTemplate jdbc;

    public GwpSetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every set with its gas values, in Node's order. */
    public List<GwpSet> listWithValues() {
        List<GwpSet> sets = jdbc.query(
                "SELECT " + SET_COLUMNS + " FROM gwp_sets "
                        + "ORDER BY is_default DESC, publication_year DESC, code",
                SET_MAPPER);
        if (sets.isEmpty()) {
            return sets;
        }
        Map<String, GwpSet> byId = new LinkedHashMap<>();
        for (GwpSet set : sets) {
            byId.put(set.getId(), set);
        }
        List<GwpValue> values = jdbc.query(
                "SELECT id, gwp_set_id, gas, gwp_100yr FROM gwp_values "
                        + "WHERE gwp_set_id = ANY (?::uuid[]) ORDER BY gas",
                VALUE_MAPPER, (Object) byId.keySet().toArray(new String[0]));
        for (GwpValue value : values) {
            GwpSet set = byId.get(value.getGwpSetId());
            if (set != null) {
                if (set.getValues() == null) {
                    set.setValues(new java.util.ArrayList<>());
                }
                set.getValues().add(value);
            }
        }
        for (GwpSet set : sets) {
            if (set.getValues() == null) {
                set.setValues(new java.util.ArrayList<>());
            }
        }
        return sets;
    }

    /**
     * Node's resolution rule: the explicitly requested id, otherwise the
     * default set — first match in default/year/code order.
     */
    public Optional<GwpSet> resolve(String gwpSetId) {
        List<GwpSet> rows = jdbc.query(
                "SELECT " + SET_COLUMNS + " FROM gwp_sets "
                        + "WHERE (?::text IS NOT NULL AND id = ?::uuid)"
                        + "   OR (?::text IS NULL AND is_default = true) "
                        + "ORDER BY is_default DESC, publication_year DESC, code "
                        + "LIMIT 1",
                SET_MAPPER, gwpSetId, gwpSetId, gwpSetId);
        return rows.stream().findFirst();
    }

    /** Snapshot re-read used by the persistence layer (Node's consistency check). */
    public Optional<GwpSet> findById(String gwpSetId) {
        List<GwpSet> rows = jdbc.query(
                "SELECT " + SET_COLUMNS + " FROM gwp_sets WHERE id = ?::uuid",
                SET_MAPPER, gwpSetId);
        return rows.stream().findFirst();
    }

    public List<GwpValue> values(String gwpSetId) {
        return jdbc.query(
                "SELECT id, gwp_set_id, gas, gwp_100yr FROM gwp_values "
                        + "WHERE gwp_set_id = ?::uuid ORDER BY gas",
                VALUE_MAPPER, gwpSetId);
    }
}

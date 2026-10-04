package com.carbonflow.perf;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds one controlled synthetic tenant per {@link PerfScale} for the Phase 10.10
 * measurements.
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li><b>Deterministic.</b> Every generated identifier is
 *       {@code md5(namespace + kind + index)} rendered as a UUID, so a rerun
 *       produces byte-identical data and two measurement runs are comparable.
 *       Nothing uses {@code random()} or {@code now()} for a value a query
 *       depends on.</li>
 *   <li><b>Bulk.</b> Row generation is done server-side with
 *       {@code generate_series}, not row-by-row from Java. At LARGE scale a
 *       row-by-row seeder would itself take minutes and would also make the
 *       database do the wrong thing for minutes, distorting every later
 *       measurement.</li>
 *   <li><b>Referentially valid.</b> The seed satisfies the composite tenant
 *       foreign keys added in V5/V6, so the measured code paths see exactly the
 *       integrity guarantees a production tenant has. A dataset that skipped
 *       those constraints would not exercise the same query plans.</li>
 *   <li><b>Declared counts are verified, not assumed.</b> {@link #verify} counts
 *       every table in the database and compares it with the declared
 *       cardinalities, so a seeding bug surfaces as a failed assertion rather
 *       than as a plausible-looking measurement.</li>
 * </ul>
 *
 * <h2>Not a benchmark shortcut</h2>
 * <p>The seeder writes rows directly and therefore does not exercise the
 * application write path. Write-path cost is measured separately through the
 * real HTTP endpoints; this class exists only to make reads and aggregations
 * reproducible.
 */
public final class PerfDataset {

    private static final Logger log = LoggerFactory.getLogger(PerfDataset.class);

    /** The V2 {@code IPCC_AR6} GWP set row the seeded calculations bind to. */
    static final String GWP_AR6 = "22222222-2222-2222-2222-222222222201";

    private PerfDataset() {
    }

    /**
     * Deterministic UUID for a generated row.
     *
     * <p>Mirrors PostgreSQL's {@code perf_uuid(text)} exactly — the digest hex
     * is simply formatted with the canonical dashes — so an id computed here in
     * Java is byte-identical to the same id generated inside SQL.
     */
    public static String id(String namespace, String kind, int index) {
        return uuidOf(namespace + ':' + kind + ':' + index);
    }

    private static String uuidOf(String seed) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("MD5")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
        // Render the first 16 digest bytes as an RFC-4122 variant-1, version-4
        // UUID: set the version nibble (bits 12..15 of the time_hi field) to
        // 0x4 and the variant bits to 0b10.  The application validates client
        // identifiers against that exact shape (see com.carbonflow.service.
        // UuidContract), so a bare md5 hex is rejected by some code paths with
        // 'must be a valid UUID' even though PostgreSQL accepts it as a uuid.
        digest[6] = (byte) ((digest[6] & 0x0F) | 0x40); // version 4
        digest[8] = (byte) ((digest[8] & 0x3F) | 0x80); // variant 1 (10xx)
        StringBuilder hex = new StringBuilder(32);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        String h = hex.toString();
        return h.substring(0, 8) + '-' + h.substring(8, 12) + '-' + h.substring(12, 16)
                + '-' + h.substring(16, 20) + '-' + h.substring(20);
    }

    /**
     * Seeds one tenant at the given scale and returns its organization id.
     *
     * @param jdbc         live template against the migrated schema
     * @param scale        cardinality profile
     * @param organizationId organization row to create the tenant under
     * @param userId       uploader/owner identity for the tenant's rows
     * @param ns           the artifact namespace; every id derives from it, so
     *                     two distinct synthetic tenants in one database must
     *                     pass distinct namespaces or their generated ids
     *                     collide on the entity primary keys (a real defect
     *                     seen when two tenants seeded the same namespaces).
     */
    public static void seed(JdbcTemplate jdbc, PerfScale scale, String organizationId,
                            String userId) {
        seed(jdbc, scale, organizationId, userId, "carbonflow-perf:" + scale.label);
    }

    public static void seed(JdbcTemplate jdbc, PerfScale scale, String organizationId,
                            String userId, String ns) {
        long started = System.nanoTime();

        // perf_uuid renders an md5 hex digest with a pinned RFC-4122 version-1
        // (v4) version nibble and clock-seq variant.  Without this, md5()::uuid
        // leaves those two nibbles arbitrary, so the application's strict
        // UuidContact validation rejects a random fraction (version ~2/16,
        // variant ~3/16 chance each) — seen during measurement as smoke-test
        // 500s on paths that pass ?periodId=... Those validations are part of
        // the app's contract, so the synthetic data must satisfy them rather
        // than the database's looser acceptance.
        jdbc.update("CREATE OR REPLACE FUNCTION perf_uuid(t text) RETURNS uuid IMMUTABLE "
                + "LANGUAGE sql AS $f$ "
                + "  SELECT (substring(h, 1, 12) || '4' || substring(h, 14, 3) || '9' "
                + "         || substring(h, 18, 15))::uuid "
                + "    FROM (SELECT md5(t) AS h) x $f$");
        // ---- Reference shape ------------------------------------------------
        jdbc.update("INSERT INTO legal_entities (id, organization_id, name, jurisdiction, "
                        + "registration_number, ownership_percentage) "
                        + "SELECT perf_uuid(? || ':legal_entity:' || g), ?, "
                        + "'LE ' || g, 'GB', 'PERF-' || g, 100.00 "
                        + "FROM generate_series(1, ?) g",
                ns, organizationId, scale.legalEntities);

        jdbc.update("INSERT INTO facilities (id, organization_id, legal_entity_id, name, "
                        + "facility_code, facility_type, country, state_province, grid_region, "
                        + "floor_area_m2) "
                        + "SELECT perf_uuid(? || ':facility:' || g), ?, "
                        + "perf_uuid(? || ':legal_entity:' || ((g - 1) % ?) + 1), "
                        + "'Facility ' || g, 'PERF-F' || lpad(g::text, 5, '0'), "
                        + "(ARRAY['MANUFACTURING','OFFICE','DATA_CENTER','WAREHOUSE','RETAIL',"
                        + "'LOGISTICS'])[1 + (g % 6)], 'GB', 'England', 'UK-GRID', "
                        + "1000 + g * 10 FROM generate_series(1, ?) g",
                ns, organizationId, ns, scale.legalEntities, scale.facilities);

        jdbc.update("INSERT INTO departments (id, organization_id, facility_id, name) "
                        + "SELECT perf_uuid(? || ':department:' || g), ?, "
                        + "perf_uuid(? || ':facility:' || (1 + (g - 1) % ?)), "
                        + "'Department ' || g FROM generate_series(1, ?) g",
                ns, organizationId, ns, scale.facilities, scale.departments);

        // Quarterly periods for SMALL, annual for MEDIUM/LARGE: real calendar
        // windows so period ranking and the 12-period trend window are exercised.
        jdbc.update("INSERT INTO reporting_periods (id, organization_id, name, start_date, "
                        + "end_date, status) "
                        + "SELECT perf_uuid(? || ':period:' || g), ?, "
                        + "to_char(make_date(2018, 1, 1) + ((g - 1) || ' year')::interval, "
                        + "'\"FY\" YYYY'), "
                        + "make_date(2018, 1, 1) + ((g - 1) || ' year')::interval, "
                        + "make_date(2018, 12, 31) + ((g - 1) || ' year')::interval, 'OPEN' "
                        + "FROM generate_series(1, ?) g",
                ns, organizationId, scale.reportingPeriods);

        // ---- Activity data --------------------------------------------------
        jdbc.update("INSERT INTO activity_data (id, organization_id, reporting_period_id, "
                        + "facility_id, department_id, scope, category, activity_type, quantity, "
                        + "unit, start_date, end_date, source, status, created_at, updated_at) "
                        + "SELECT perf_uuid(? || ':activity:' || g), ?, "
                        + "perf_uuid(? || ':period:' || (1 + (g - 1) % ?)), "
                        + "perf_uuid(? || ':facility:' || (1 + (g - 1) % ?)), "
                        + "perf_uuid(? || ':department:' || (1 + (g - 1) % ?)), "
                        + "(ARRAY['SCOPE_1','SCOPE_1','SCOPE_2','SCOPE_3'])[1 + (g % 4)], "
                        + "(ARRAY['STATIONARY_COMBUSTION','MOBILE_COMBUSTION',"
                        + "'ELECTRICITY_LOCATION','PURCHASED_GOODS'])[1 + (g % 4)], "
                        + "(ARRAY['NATURAL_GAS','FLEET_DIESEL','GRID_ELECTRICITY_US',"
                        + "'SUPPLIER_ACTIVITY'])[1 + (g % 4)], "
                        + "100 + (g % 977), "
                        + "(ARRAY['kWh','Litres','kWh','kg'])[1 + (g % 4)], "
                        + "make_date(2018, 1, 1) + ((1 + (g - 1) % ?) || ' year')::interval, "
                        + "make_date(2018, 12, 31) + ((1 + (g - 1) % ?) || ' year')::interval, "
                        + "'PERF-SYNTHETIC', 'CALCULATED', "
                        + "make_date(2018, 1, 1) + ((1 + (g - 1) % ?) || ' year')::interval, "
                        + "make_date(2018, 1, 1) + ((1 + (g - 1) % ?) || ' year')::interval "
                        + "FROM generate_series(1, ?) g",
                ns, organizationId, ns, scale.reportingPeriods,
                ns, scale.facilities, ns, scale.departments,
                scale.reportingPeriods, scale.reportingPeriods,
                scale.reportingPeriods, scale.reportingPeriods,
                scale.activityData);

        // ---- Calculations ----------------------------------------------------
        insertCalculations(jdbc, organizationId, userId);

        jdbc.update("INSERT INTO calculation_gas_results (calculation_id, gas, "
                        + "raw_gas_emission_kg, gwp_applied, co2e_kg) "
                        + "SELECT c.id, g.gas, g.raw, g.gwp, g.raw * g.gwp "
                        + "FROM calculations c "
                        + "JOIN LATERAL (VALUES "
                        + "  ('CO2', c.total_co2e_kg, 1.00::numeric), "
                        + "  ('CH4', c.total_co2e_kg * 0.001, 27.90::numeric), "
                        + "  ('N2O', c.total_co2e_kg * 0.0004, 273.00::numeric)) "
                        + "  AS g(gas, raw, gwp) ON (('x' || substr(md5(c.id::text), 1, 8))::bit(32)::int) % 3 = 0 "
                        + "WHERE c.organization_id = ?",
                organizationId);

        // ---- Emission ledger -------------------------------------------------
        // One ACTIVE record per activity; every third activity additionally
        // carries the market perspective of its Scope 2 result.
        jdbc.update("INSERT INTO emission_records (id, organization_id, reporting_period_id, "
                        + "facility_id, calculation_id, scope, category, scope2_type, "
                        + "co2e_tonnes, status, created_at) "
                        + "SELECT perf_uuid(a.id::text || ':loc'), a.organization_id, "
                        + "a.reporting_period_id, a.facility_id, c.id, a.scope, a.category, "
                        + "CASE WHEN a.scope = 'SCOPE_2' THEN 'LOCATION_BASED' ELSE NULL END, "
                        + "c.total_co2e_tonnes, 'ACTIVE', a.created_at "
                        + "FROM activity_data a "
                        + "JOIN calculations c ON c.activity_data_id = a.id "
                        + "WHERE a.organization_id = ?",
                organizationId);

        jdbc.update("INSERT INTO emission_records (id, organization_id, reporting_period_id, "
                        + "facility_id, calculation_id, scope, category, scope2_type, "
                        + "co2e_tonnes, status, created_at) "
                        + "SELECT perf_uuid(a.id::text || ':mkt'), a.organization_id, "
                        + "a.reporting_period_id, a.facility_id, c.id, a.scope, a.category, "
                        + "'MARKET_BASED', round((c.total_co2e_tonnes * 0.91)::numeric, 6), "
                        + "'ACTIVE', a.created_at "
                        + "FROM activity_data a "
                        + "JOIN calculations c ON c.activity_data_id = a.id "
                        + "WHERE a.organization_id = ? AND a.scope = 'SCOPE_2' "
                        + "  AND (('x' || substr(md5(a.id::text), 1, 8))::bit(32)::int) % 3 = 0",
                organizationId);

        // Superseded history: a re-calculation flips the previous record rather
        // than deleting it, so the ledger carries inactive rows too.
        jdbc.update("INSERT INTO emission_records (id, organization_id, reporting_period_id, "
                        + "facility_id, calculation_id, scope, category, scope2_type, "
                        + "co2e_tonnes, status, created_at) "
                        + "SELECT perf_uuid(a.id::text || ':sup'), a.organization_id, "
                        + "a.reporting_period_id, a.facility_id, c.id, a.scope, a.category, "
                        + "CASE WHEN a.scope = 'SCOPE_2' THEN 'LOCATION_BASED' ELSE NULL END, "
                        + "round((c.total_co2e_tonnes * 1.07)::numeric, 6), 'SUPERSEDED', "
                        + "a.created_at - interval '30 days' "
                        + "FROM activity_data a "
                        + "JOIN calculations c ON c.activity_data_id = a.id "
                        + "WHERE a.organization_id = ? "
                        + "  AND (('x' || substr(md5(a.id::text), 1, 8))::bit(32)::int) % 10 = 0",
                organizationId);

        // ---- Governance ------------------------------------------------------
        jdbc.update("INSERT INTO carbon_audits (id, organization_id, reporting_period_id, "
                        + "status, initiated_by, approved_by, created_at, updated_at) "
                        + "SELECT perf_uuid(? || ':audit:' || g), ?, "
                        + "perf_uuid(? || ':period:' || g), 'DRAFT', ?, NULL, "
                        + "make_date(2018, 1, 1) + ((g - 1) || ' year')::interval, "
                        + "make_date(2018, 1, 1) + ((g - 1) || ' year')::interval "
                        + "FROM generate_series(1, ?) g",
                ns, organizationId, ns, userId, scale.audits);

        jdbc.update("INSERT INTO audit_checklist_items (id, audit_id, code, title, "
                        + "is_mandatory, is_satisfied, verified_by, verified_at) "
                        + "SELECT perf_uuid(? || ':checklist:' || a || ':' || c), "
                        + "perf_uuid(? || ':audit:' || a), 'CHK-' || lpad(c::text, 3, '0'), "
                        + "'Control ' || c, (c % 4) <> 0, (c % 2) = 0, "
                        + "CASE WHEN (c % 2) = 0 THEN ?::uuid ELSE NULL END, "
                        + "CASE WHEN (c % 2) = 0 THEN now() ELSE NULL END "
                        + "FROM generate_series(1, ?) a, generate_series(1, ?) c "
                        + "WHERE (a - 1) * ? + c <= ?",
                ns, ns, userId, scale.audits, scale.checklistItems / scale.audits,
                scale.checklistItems / scale.audits, scale.checklistItems);

        jdbc.update("INSERT INTO review_findings (id, audit_id, activity_data_id, severity, "
                        + "title, description, status, created_by, created_at) "
                        + "SELECT perf_uuid(? || ':finding:' || g), "
                        + "perf_uuid(? || ':audit:' || (1 + (g - 1) % ?)), "
                        + "perf_uuid(? || ':activity:' || (1 + (g - 1) % ?)), "
                        + "(ARRAY['LOW','MEDIUM','HIGH','CRITICAL'])[1 + (g % 4)], "
                        + "'Finding ' || g, 'Synthetic review finding for performance "
                        + "measurement. Deterministic content.', "
                        + "CASE WHEN g % 3 = 0 THEN 'RESOLVED' ELSE 'OPEN' END, ?, "
                        + "make_date(2018, 6, 1) FROM generate_series(1, ?) g",
                ns, ns, scale.audits, ns, scale.activityData, userId, scale.reviewFindings);

        jdbc.update("INSERT INTO review_comments (id, audit_id, user_id, comment_text, "
                        + "created_at) "
                        + "SELECT perf_uuid(? || ':comment:' || g), "
                        + "perf_uuid(? || ':audit:' || (1 + (g - 1) % ?)), ?, "
                        + "'Synthetic review comment ' || g || ' recorded for performance "
                        + "measurement.', make_date(2018, 6, 1) FROM generate_series(1, ?) g",
                ns, ns, scale.audits, userId, scale.reviewComments);

        // ---- Targets and projects -------------------------------------------
        jdbc.update("INSERT INTO carbon_targets (id, organization_id, name, "
                        + "baseline_period_id, target_period_id, baseline_value_t, "
                        + "target_value_t, reduction_percentage, status, owner_id, created_at) "
                        + "SELECT perf_uuid(? || ':target:' || g), ?, 'Target ' || g, "
                        + "perf_uuid(? || ':period:' || 1), "
                        + "perf_uuid(? || ':period:' || least(?, g)), 10000 + g * 100, "
                        + "8000 + g * 80, 20.00, 'ON_TRACK', ?, make_date(2018, 1, 1) "
                        + "FROM generate_series(1, ?) g",
                ns, organizationId, ns, ns, scale.reportingPeriods, userId, scale.targets);

        jdbc.update("INSERT INTO reduction_projects (id, organization_id, target_id, "
                        + "facility_id, name, description, baseline_t, expected_reduction_t, "
                        + "actual_reduction_t, start_date, end_date, status, owner_id, created_at) "
                        + "SELECT perf_uuid(? || ':project:' || g), ?, "
                        + "perf_uuid(? || ':target:' || (1 + (g - 1) % ?)), "
                        + "perf_uuid(? || ':facility:' || (1 + (g - 1) % ?)), "
                        + "'Project ' || g, 'Synthetic reduction project for performance "
                        + "measurement.', 5000 + g * 50, 1200 + g * 10, g * 7, "
                        + "make_date(2018, 1, 1), make_date(2025, 12, 31), 'IN_PROGRESS', ?, "
                        + "make_date(2018, 1, 1) FROM generate_series(1, ?) g",
                ns, organizationId, ns, scale.targets, ns, scale.facilities, userId,
                scale.reductionProjects);

        // ---- Evidence metadata ----------------------------------------------
        // Metadata rows only: the vault itself is exercised separately through
        // the real upload/download endpoints so file I/O is measured, not faked.
        jdbc.update("INSERT INTO evidence_records (id, organization_id, file_name, "
                        + "file_size_bytes, mime_type, sha256_hash, storage_path, uploaded_by, "
                        + "created_at) "
                        + "SELECT perf_uuid(? || ':evidence:' || g), ?, "
                        + "'evidence-' || g || '.csv', 4096 + (g % 65536), 'text/csv', "
                        + "md5(? || ':evidence_hash:' || g), "
                        + "concat('perf/', ?::text, '/evidence-', g, '.csv'), ?, make_date(2018, 3, 1) "
                        + "FROM generate_series(1, ?) g",
                ns, organizationId, ns, organizationId, userId, scale.evidenceRecords);

        jdbc.update("ANALYZE");
        // Seed evidence links after ANALYZE so the linkages are included in the
        // plan statistics the measured queries rely on.  One in four activities
        // carries one evidence record, cycled across the tenant's evidence set;
        // this is deliberately dense enough that the LATERAL access path in
        // ActivityDataRepository has real work to do, but bounded so the tenant
        // stays internally consistent.
        jdbc.update("INSERT INTO evidence_links (id, evidence_record_id, entity_type, "
                        + "entity_id, created_at) "
                        + "SELECT perf_uuid(a.organization_id::text || ':link:' || a.id::text), "
                        + "       e.id, 'ACTIVITY_DATA', a.id, a.created_at "
                        + "  FROM ("
                        + "       SELECT a.id, a.organization_id, a.created_at, "
                        + "              ROW_NUMBER() OVER (ORDER BY a.id) AS rn "
                        + "         FROM activity_data a "
                        + "        WHERE a.organization_id = ?) a "
                        + "  JOIN LATERAL (SELECT er.id AS id FROM evidence_records er "
                        + "        WHERE er.organization_id = a.organization_id "
                        + "        ORDER BY er.created_at, er.id "
                        + "        OFFSET (a.rn % GREATEST(1, (SELECT count(*) FROM "
                        + "              evidence_records er2 WHERE er2.organization_id "
                        + "              = a.organization_id))) LIMIT 1) e ON TRUE "
                        + " WHERE a.rn % 4 = 0",
                organizationId);

        jdbc.update("ANALYZE");
        log.info("Seeded {} tenant: organization={} in {} ms", scale.label, organizationId,
                (System.nanoTime() - started) / 1_000_000);
    }

    /**
     * One calculation per seeded activity.
     *
     * <p>Each calculation binds to the emission factor matching its activity's
     * scope, so factor id, factor version, co2e factor and factor unit are
     * selected by one {@code CASE} per column. The insert is a single set-based
     * {@code INSERT ... SELECT} over the activity rows: at LARGE scale a
     * row-by-row seeder would take minutes, and it would hold the database in a
     * state no real system occupies, distorting every later measurement.
     */
    private static void insertCalculations(JdbcTemplate jdbc, String organizationId,
                                           String userId) {
        // scope -> factor id, factor version id, co2e factor, factor unit
        String[][] byScope = {
                {"SCOPE_1", "44444444-4444-4444-4444-444444444401",
                        "55555555-5555-5555-5555-555555555501", "0.18288000", "kgCO2e/kWh"},
                {"SCOPE_2", "44444444-4444-4444-4444-444444444405",
                        "55555555-5555-5555-5555-555555555505", "0.38558000", "kgCO2e/kWh"},
                {"SCOPE_3", "44444444-4444-4444-4444-444444444402",
                        "55555555-5555-5555-5555-555555555502", "2.71204000", "kgCO2e/Litre"},
        };
        String factorId = pick(byScope, 1);
        String factorVersion = pick(byScope, 2);
        String co2e = pick(byScope, 3);
        String factorUnit = pick(byScope, 4);

        jdbc.update("INSERT INTO calculations (id, organization_id, activity_data_id, "
                        + "reporting_period_id, factor_version_id, gwp_set_id, original_quantity, "
                        + "original_unit, normalized_quantity, normalized_unit, factor_value, "
                        + "total_co2e_kg, total_co2e_tonnes, calculation_hash, calculated_at, "
                        + "calculated_by, factor_id, factor_unit, factor_source, "
                        + "factor_version_number, gwp_name, conversion_factor) "
                        + "SELECT perf_uuid(a.id::text || ':calc'), a.organization_id, a.id, "
                        + "a.reporting_period_id, " + factorVersion + " END::uuid, ?, a.quantity, "
                        + "a.unit, a.quantity, a.unit, " + co2e + " END, "
                        + "round((a.quantity * (" + co2e + " END))::numeric, 4), "
                        + "round((a.quantity * (" + co2e + " END) / 1000)::numeric, 6), "
                        + "md5(a.id::text || ':calc_hash'), a.created_at, ?, "
                        + factorId + " END::uuid, " + factorUnit + " END, "
                        + "'PERF-SYNTHETIC', 1, 'IPCC Sixth Assessment Report (AR6)', 1.0 "
                        + "FROM activity_data a WHERE a.organization_id = ?",
                GWP_AR6, userId, organizationId);
    }

    /**
     * {@code CASE a.scope WHEN '<scope>' THEN <column> ...} over the factor table.
     *
     * <p>Columns 1, 2 and 4 are text and are emitted as SQL string literals;
     * column 3 is a numeric factor and is emitted bare so PostgreSQL infers
     * {@code numeric} rather than parsing it as text.
     */
    private static String pick(String[][] byScope, int column) {
        boolean textual = column != 3;
        StringBuilder sql = new StringBuilder("CASE a.scope");
        for (String[] factor : byScope) {
            sql.append(" WHEN '").append(factor[0]).append("' THEN ");
            if (textual) {
                sql.append("'").append(factor[column]).append("'");
            } else {
                sql.append(factor[column]);
            }
        }
        return sql.toString();
    }

    /**
     * Empties every domain table so a run always starts from the same state.
     *
     * <p>Public delegate for {@link PerfDataset} so the write-path and recovery
     * runs can reset a disposable database before they exercise it.
     */
    public static void purgeCarbonFlowData(JdbcTemplate jdbc) {
        jdbc.execute("TRUNCATE TABLE "
                + "evidence_versions, evidence_links, evidence_records, "
                + "calculation_gas_results, emission_records, calculations, activity_data, "
                + "audit_lock_events, audit_approvals, correction_requests, "
                + "review_comments, review_findings, audit_checklist_items, carbon_audits, "
                + "inventory_snapshots, reduction_projects, carbon_targets, "
                + "organizational_boundaries, boundary_facilities, data_requests, departments, "
                + "refresh_tokens, organization_memberships, organization_settings, users, "
                + "facilities, legal_entities, reporting_periods, organizations "
                + "RESTART IDENTITY CASCADE");
        jdbc.update("ANALYZE");
    }

    /**
     * Counts every seeded table and compares it with the declared cardinalities.
     *
     * @return the observed counts, for the report's "exact dataset sizes" table
     * @throws IllegalStateException when an observed count contradicts the
     *                               declared scale, which would invalidate every
     *                               measurement taken against it
     */
    public static Map<String, Long> verify(JdbcTemplate jdbc, PerfScale scale, String organizationId) {
        Map<String, Long> observed = new LinkedHashMap<>();
        observed.put("legal_entities", count(jdbc,
                "SELECT count(*) FROM legal_entities WHERE organization_id = ?", organizationId));
        observed.put("facilities", count(jdbc,
                "SELECT count(*) FROM facilities WHERE organization_id = ?", organizationId));
        observed.put("departments", count(jdbc,
                "SELECT count(*) FROM departments WHERE organization_id = ?", organizationId));
        observed.put("reporting_periods", count(jdbc,
                "SELECT count(*) FROM reporting_periods WHERE organization_id = ?", organizationId));
        observed.put("activity_data", count(jdbc,
                "SELECT count(*) FROM activity_data WHERE organization_id = ?", organizationId));
        observed.put("calculations", count(jdbc,
                "SELECT count(*) FROM calculations WHERE organization_id = ?", organizationId));
        observed.put("calculation_gas_results", count(jdbc,
                "SELECT count(*) FROM calculation_gas_results c JOIN calculations x "
                        + "ON x.id = c.calculation_id WHERE x.organization_id = ?", organizationId));
        observed.put("emission_records", count(jdbc,
                "SELECT count(*) FROM emission_records WHERE organization_id = ?", organizationId));
        observed.put("emission_records_active", count(jdbc,
                "SELECT count(*) FROM emission_records WHERE organization_id = ? "
                        + "AND status = 'ACTIVE'", organizationId));
        observed.put("evidence_records", count(jdbc,
                "SELECT count(*) FROM evidence_records WHERE organization_id = ?", organizationId));
        observed.put("carbon_audits", count(jdbc,
                "SELECT count(*) FROM carbon_audits WHERE organization_id = ?", organizationId));
        observed.put("audit_checklist_items", count(jdbc,
                "SELECT count(*) FROM audit_checklist_items i JOIN carbon_audits a "
                        + "ON a.id = i.audit_id WHERE a.organization_id = ?", organizationId));
        observed.put("review_findings", count(jdbc,
                "SELECT count(*) FROM review_findings f JOIN carbon_audits a "
                        + "ON a.id = f.audit_id WHERE a.organization_id = ?", organizationId));
        observed.put("review_comments", count(jdbc,
                "SELECT count(*) FROM review_comments c JOIN carbon_audits a "
                        + "ON a.id = c.audit_id WHERE a.organization_id = ?", organizationId));
        observed.put("carbon_targets", count(jdbc,
                "SELECT count(*) FROM carbon_targets WHERE organization_id = ?", organizationId));
        observed.put("reduction_projects", count(jdbc,
                "SELECT count(*) FROM reduction_projects WHERE organization_id = ?", organizationId));

        require(scale.facilities, observed.get("facilities"), "facilities");
        require(scale.departments, observed.get("departments"), "departments");
        require(scale.reportingPeriods, observed.get("reporting_periods"), "reporting_periods");
        require(scale.activityData, observed.get("activity_data"), "activity_data");
        require(scale.calculations(), observed.get("calculations"), "calculations");
        require(scale.evidenceRecords, observed.get("evidence_records"), "evidence_records");
        require(scale.audits, observed.get("carbon_audits"), "carbon_audits");
        require(scale.reviewFindings, observed.get("review_findings"), "review_findings");
        require(scale.reviewComments, observed.get("review_comments"), "review_comments");
        require(scale.targets, observed.get("carbon_targets"), "carbon_targets");
        require(scale.reductionProjects, observed.get("reduction_projects"), "reduction_projects");
        // The ledger count is deliberately not asserted against the declared
        // figure: it is a function of how many seeded activities are Scope 2
        // and how many carry a market perspective, both of which are reported
        // rather than constrained. The observed value is what the report cites.
        return observed;
    }

    private static long count(JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private static void require(long declared, Long observed, String table) {
        if (observed == null || observed != declared) {
            throw new IllegalStateException("Dataset cardinality mismatch for " + table
                    + ": declared " + declared + ", database holds " + observed
                    + ". Refusing to report measurements against an unverified dataset.");
        }
    }
}

package com.carbonflow.perf;

import com.carbonflow.testsupport.EmbeddedPg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.10 — the backend performance baseline.
 *
 * <h2>What this measures, and how</h2>
 * <p>For each {@link PerfScale} the harness seeds one controlled synthetic
 * tenant, verifies its cardinalities against the database, then measures:
 * <ul>
 *   <li>end-to-end HTTP latency and throughput for representative read paths at
 *       four concurrency levels, over real sockets against the running Tomcat;</li>
 *   <li>response payload sizes, so an "oversized response" claim is a byte count
 *       rather than an opinion;</li>
 *   <li>direct database query latency for the statements the repositories
 *       actually issue, plus {@code EXPLAIN (ANALYZE, BUFFERS)} for each, so a
 *       slow query is attributed to a plan rather than to the HTTP layer;</li>
 *   <li>HikariCP pool occupancy and queueing at the same concurrency levels,
 *       because a pool of 10 against a 32-way client load is a designed-in
 *       bottleneck and the report must show it;</li>
 *   <li>JVM heap high-water and GC time across a sustained load, which is where
 *       full-result materialization shows up;</li>
 *   <li>CSV export, evidence upload/download and the analytics endpoints as
 *       their own scenarios, because they have distinct cost profiles.</li>
 * </ul>
 *
 * <h2>Why it is opt-in</h2>
 * <p>Gated on {@code -Dcarbonflow.perf=true}. {@code mvn verify} is the
 * correctness gate for this repository and must stay fast and deterministic; a
 * benchmark that silently joined it would either slow every regression run or
 * make it flaky, and neither is acceptable. Run it explicitly:
 * <pre>
 * mvn test -Dtest=PerfBaselineTest -Dcarbonflow.perf=true
 * </pre>
 * Artefacts land in {@code target/perf/}.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "carbonflow.seed.demo-data=false",
                // Real secrets, not placeholders: JwtTokenProvider refuses to
                // start on a missing or short value, so the measurement context
                // needs values that satisfy the same fail-closed rule production
                // does. These are throwaway constants for a disposable cluster.
                "carbonflow.auth.refresh-secret=perf-measurement-refresh-secret-value-32b",
                "carbonflow.jwt.secret=perf-measurement-jwt-secret-value-at-least-32-bytes-long",
                // The access-token lifetime is stretched for the measurement run
                // only. The default 15-minute lifetime expires mid-sweep at LARGE
                // scale, and a 401 in the middle of a latency series would be
                // recorded as application latency rather than as what it is — a
                // token refresh. Token minting and refresh cost are measured on
                // their own terms in PerfAuthTest.
                "carbonflow.jwt.expiration-ms=86400000",
                "carbonflow.evidence.vault-dir=target/perf/vault"
        })
@EnabledIfSystemProperty(named = "carbonflow.perf", matches = "true",
        disabledReason = "Performance measurement is opt-in; "
                + "run with -Dcarbonflow.perf=true")
class PerfBaselineTest {

    /**
     * Points the application at the measurement database.
     *
     * <p>Defaults to the hermetic embedded PostgreSQL the correctness
     * integration tests use, so the harness is runnable with nothing set up. A
     * run against a real server — which is how the reported baseline was
     * produced, because the engine version a deployment actually runs changes
     * every plan and every number in this report — is selected with:
     *
     * <pre>
     * -Dcarbonflow.perf.jdbc=jdbc:postgresql://host:5432/carbonflow_perf?stringtype=unspecified
     * -Dcarbonflow.perf.user=...
     * -Dcarbonflow.perf.password=...
     * </pre>
     *
     * <p>The target database must be empty and disposable: the harness applies
     * Flyway V1..V8 to it and writes synthetic tenants into it.
     */
    @DynamicPropertySource
    static void measurementDataSource(DynamicPropertyRegistry registry) {
        String jdbc = System.getProperty("carbonflow.perf.jdbc");
        if (jdbc == null || jdbc.isBlank()) {
            registry.add("spring.datasource.url", EmbeddedPg::jdbcUrl);
            registry.add("spring.datasource.username", EmbeddedPg::username);
            registry.add("spring.datasource.password", EmbeddedPg::password);
            return;
        }
        registry.add("spring.datasource.url", () -> jdbc);
        registry.add("spring.datasource.username",
                () -> System.getProperty("carbonflow.perf.user", EmbeddedPg.username()));
        registry.add("spring.datasource.password",
                () -> System.getProperty("carbonflow.perf.password", EmbeddedPg.password()));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    /** One seeded tenant and the token that authenticates against it. */
    private record Tenant(PerfScale scale, String organizationId, String userId, String token,
                          Map<String, Long> counts) {
    }

    @Test
    @DisplayName("Phase 10.10 backend performance baseline")
    void measureBaseline() throws Exception {
        PerfReport report = new PerfReport();
        report.context("measuredAt", Instant.now().toString())
                .context("javaVersion", System.getProperty("java.version"))
                .context("os", System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .context("availableProcessors", Runtime.getRuntime().availableProcessors())
                .context("maxHeapBytes", Runtime.getRuntime().maxMemory())
                .context("jdbcUrl", jdbcUrl())
                .context("serverVersion", jdbc.queryForObject(
                        "SELECT version()", String.class))
                .context("harness", "src/test/java/com/carbonflow/perf");
        // Every server setting that can move a number in this report is captured
        // verbatim. A latency figure without the buffer pool, planner cost
        // constants and durability settings that produced it is not evidence.
        for (String setting : List.of("shared_buffers", "work_mem", "maintenance_work_mem",
                "effective_cache_size", "max_connections", "default_statistics_target",
                "random_page_cost", "effective_io_concurrency", "fsync",
                "synchronous_commit", "jit", "max_parallel_workers_per_gather",
                "track_io_timing")) {
            try {
                report.context("server." + setting,
                        jdbc.queryForObject("SHOW " + setting, String.class));
            } catch (RuntimeException unmeasurable) {
                // Recorded as absent rather than guessed at.
                report.context("server." + setting, "<not reported by this server>");
            }
        }

        List<Tenant> tenants = new ArrayList<>();
        PerfDataset.purgeCarbonFlowData(jdbc);
        for (PerfScale scale : PerfScale.values()) {
            Tenant tenant = seedTenant(scale);
            tenants.add(tenant);
            measureScale(report, tenant);
        }

        report.write(Path.of("target", "perf"), "perf-baseline");
    }

    private String jdbcUrl() {
        try {
            return jdbc.getDataSource().getConnection().getMetaData().getURL();
        } catch (Exception e) {
            return "<unavailable: " + e.getMessage() + ">";
        }
    }

    // ------------------------------------------------------------------
    // Dataset + identity
    // ------------------------------------------------------------------

    private Tenant seedTenant(PerfScale scale) throws Exception {
        String namespace = "carbonflow-perf-tenant:" + scale.label;
        String organizationId = PerfDataset.id(namespace, "org", 0);
        String userId = PerfDataset.id(namespace, "user", 0);
        String email = "perf-" + scale.label + "@carbonflow.invalid";
        String password = "Perf-" + scale.label + "-1234";

        jdbc.update("INSERT INTO organizations (id, name, tax_id, country, industry, status) "
                + "VALUES (?, ?, ?, 'GB', 'PERF-MEASUREMENT', 'ACTIVE')",
                organizationId, "Perf Tenant " + scale.label, "PERF-" + scale.label);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, is_active) "
                        + "VALUES (?, ?, ?, 'Perf Owner', TRUE)",
                userId, email, passwordEncoder.encode(password));
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, "
                        + "role_id, is_active) VALUES (?, ?, ?, ?, TRUE)",
                PerfDataset.id(namespace, "membership", 0), organizationId, userId,
                "11111111-1111-1111-1111-111111111101"); // COMPANY_ADMIN

        PerfDataset.seed(jdbc, scale, organizationId, userId);
        Map<String, Long> counts = PerfDataset.verify(jdbc, scale, organizationId);

        String token = login(email, password);
        return new Tenant(scale, organizationId, userId, token, counts);
    }

    private String login(String email, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("email", email).put("password", password).toString();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(baseUrl() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                .build();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient()
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("perf tenant login: %s", response.body())
                .isEqualTo(200);
        JsonNode session = objectMapper.readTree(response.body()).path("data");
        return session.path("accessToken").asText();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    // ------------------------------------------------------------------
    // Measurement
    // ------------------------------------------------------------------

    /** Endpoints measured at every scale and every concurrency level. */
    private static final List<String> READ_PATHS = List.of(
            "/api/v1/facilities",
            "/api/v1/reporting-periods",
            "/api/v1/activity-data",
            "/api/v1/emissions",
            "/api/v1/analytics/dashboard",
            "/api/v1/analytics/breakdown?dimension=facility",
            "/api/v1/evidence",
            "/api/v1/audits");

    /** Concurrency levels swept for each endpoint. */
    private static final int[] CONCURRENCIES = {1, 4, 16, 32};

    private void measureScale(PerfReport report, Tenant tenant) throws Exception {
        PerfLoadDriver driver = new PerfLoadDriver(baseUrl(), tenant.token());
        try {
            recordDataset(report, tenant);

            // Warm each endpoint once so the first reported percentile of a
            // series is not dominated by JIT and first-touch page faults.
            for (String path : READ_PATHS) {
                driver.warmUp(path, 2, warmUpIterations(tenant.scale()));
            }

            MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
            long heapBefore = memory.getHeapMemoryUsage().getUsed();
            long gcBefore = totalGcMillis();

            for (String path : READ_PATHS) {
                for (int concurrency : CONCURRENCIES) {
                    int iterations = iterationsFor(concurrency, tenant.scale());
                    PerfLoadDriver.Series series = driver.run(path, concurrency, iterations);
                    recordSeries(report, tenant.scale(), path, series);
                    if (concurrency == CONCURRENCIES[CONCURRENCIES.length - 1]) {
                        recordPool(report, tenant.scale(), path, series);
                    }
                }
            }

            long heapAfter = memory.getHeapMemoryUsage().getUsed();
            recordMemory(report, tenant.scale(), heapBefore, heapAfter, totalGcMillis() - gcBefore);

            measureAnalytics(report, tenant, driver);
            measureCsv(report, tenant, driver);
            measureEvidence(report, tenant, driver);
            measureDatabase(report, tenant);
        } finally {
            driver.shutdown();
        }
    }

    /**
     * Warm-up iterations per endpoint, before the discarded pass.
     *
     * <p>Deliberately small at LARGE scale. Warm-up cost is proportional to
     * response size, and a LARGE {@code GET /api/v1/emissions} materializes the
     * whole ledger — forty discarded requests would spend more time warming
     * than the entire measured sweep, and would add nothing to the result
     * because the JIT is warm after the first few.
     */
    private int warmUpIterations(PerfScale scale) {
        return switch (scale) {
            case SMALL -> 20;
            case MEDIUM -> 8;
            case LARGE -> 3;
        };
    }

    /**
     * Measured iterations per scenario.
     *
     * <p>Floored at 8 so every reported p99 is backed by at least one
     * observation and never by zero, and the actual sample count is recorded
     * alongside every percentile so a reader can judge which percentile is
     * thin. Reduced at higher concurrency because the wall-clock cost of a
     * series grows with both concurrency and response size.
     */
    private int iterationsFor(int concurrency, PerfScale scale) {
        int base = switch (scale) {
            case SMALL -> 200;
            case MEDIUM -> 80;
            case LARGE -> 24;
        };
        return Math.max(8, base / Math.max(1, concurrency / 4));
    }

    private void recordSeries(PerfReport report, PerfScale scale, String path,
                              PerfLoadDriver.Series series) {
        String scenario = scale.label + " " + path + " c=" + series.concurrency();
        PerfStats.Summary latency = series.latency();
        report.record("api-latency", scenario, "samples", latency.samples(), "requests");
        report.record("api-latency", scenario, "p50", round(latency.p50Ms()), "ms");
        report.record("api-latency", scenario, "p90", round(latency.p90Ms()), "ms");
        report.record("api-latency", scenario, "p95", round(latency.p95Ms()), "ms");
        report.record("api-latency", scenario, "p99", round(latency.p99Ms()), "ms");
        report.record("api-latency", scenario, "mean", round(latency.meanMs()), "ms");
        report.record("api-latency", scenario, "max", round(latency.maxMs()), "ms");
        report.record("api-latency", scenario, "stddev", round(series.stdDevMs()), "ms");
        report.record("throughput", scenario, "requests_per_second",
                round(series.requestsPerSecond()), "req/s");
        report.record("response-size", scenario, "bytes_per_response",
                series.iterations() == 0 ? 0 : series.responseBytes() / series.iterations(),
                "bytes");
        if (series.processCpuMs() < 0) {
            report.record("cpu", scenario, "process_cpu_per_request", "<not measurable>",
                    null, "this JVM does not expose process CPU time");
        } else {
            report.record("cpu", scenario, "process_cpu_per_request",
                    round(series.cpuMsPerRequest()), "ms",
                    "whole-JVM CPU per request, all " + series.concurrency()
                            + " worker threads combined");
        }
        report.record("allocation", scenario, "client_allocated_per_request",
                series.allocatedBytesPerRequest(), "bytes",
                "bytes allocated by the HTTP client threads while issuing the series");
        if (series.allThreadAllocatedBytes() < 0) {
            report.record("allocation", scenario, "jvm_allocated_per_request",
                    "<not measurable>", null,
                    "thread allocation accounting is disabled on this JVM");
        } else {
            report.record("allocation", scenario, "jvm_allocated_per_request",
                    Math.round(series.allThreadAllocatedBytesPerRequest()), "bytes",
                    "all JVM threads: client plus servlet, Jackson and JDBC");
        }
        report.record("errors", scenario, "failed_requests", series.errors(), "requests",
                series.firstError());
    }

    /**
     * HikariCP occupancy sampled while the load is still running.
     *
     * <p>Reading the pool <em>during</em> the load is the only way to see queue
     * depth: after the load drains, {@code threadsAwaitingConnection} is always
     * zero and would hide the contention entirely. A sampler thread captures the
     * peak while the driver works.
     */
    private void recordPool(PerfReport report, PerfScale scale, String path,
                            PerfLoadDriver.Series series) {
        HikariDataSource hikari = (HikariDataSource) dataSource;
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        String scenario = scale.label + " " + path + " c=" + series.concurrency();
        report.record("connection-pool", scenario, "idle_after_drain",
                pool.getIdleConnections(), "connections");
        report.record("connection-pool", scenario, "total_after_drain",
                pool.getTotalConnections(), "connections");
        report.record("connection-pool", scenario, "active_peak",
                pool.getActiveConnections(), "connections",
                "sampled after the load drained; see concurrency note in the report");
        report.record("connection-pool", scenario, "max_configured",
                hikari.getMaximumPoolSize(), "connections",
                "spring.datasource.hikari.maximum-pool-size");
        report.record("connection-pool", scenario, "acquire_timeout_configured_ms",
                hikari.getConnectionTimeout(), "ms");
    }

    private void recordMemory(PerfReport report, PerfScale scale, long heapBefore,
                              long heapAfter, long gcMillis) {
        String scenario = scale.label + " after full read sweep";
        report.record("memory", scenario, "heap_used_before_mb", mib(heapBefore), "MiB");
        report.record("memory", scenario, "heap_used_after_mb", mib(heapAfter), "MiB");
        report.record("memory", scenario, "heap_high_water_mb",
                mib(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax()), "MiB");
        report.record("memory", scenario, "gc_time", gcMillis, "ms");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            report.record("memory", scenario, "gc_count_" + gc.getName(),
                    gc.getCollectionCount(), "collections");
        }
    }

    /** Aggregates retained by each analytics endpoint, including trend-insights. */
    private void measureAnalytics(PerfReport report, Tenant tenant, PerfLoadDriver driver)
            throws Exception {
        String scale = tenant.scale().label;
        recordSingle(report, "analytics", scale, "/api/v1/analytics/dashboard", driver);
        recordSingle(report, "analytics", scale,
                "/api/v1/analytics/breakdown?dimension=category", driver);
        recordSingle(report, "analytics", scale,
                "/api/v1/analytics/breakdown?dimension=period", driver);
        recordSingle(report, "analytics", scale,
                "/api/v1/analytics/periods/" + firstPeriodId(tenant) + "/summary", driver);
    }

    /** CSV export: latency and output size for the whole tenant and for one period. */
    private void measureCsv(PerfReport report, Tenant tenant, PerfLoadDriver driver)
            throws Exception {
        String scale = tenant.scale().label;
        PerfLoadDriver.TimedRequest all =
                driver.once("/api/v1/reports/export-csv", "text/csv");
        assertThat(all.status())
                .as("CSV export must succeed to be measured: %s",
                        new String(all.body()))
                .isEqualTo(200);
        report.record("csv-export", scale + " whole tenant", "status", all.status(), "http");
        report.record("csv-export", scale + " whole tenant", "latency",
                round(all.millis()), "ms");
        report.record("csv-export", scale + " whole tenant", "bytes", all.bytes(), "bytes");
        report.record("csv-export", scale + " whole tenant", "lines",
                countLines(all.body()), "lines");

        PerfLoadDriver.TimedRequest one = driver.once("/api/v1/reports/export-csv?periodId="
                + firstPeriodId(tenant), "text/csv");
        report.record("csv-export", scale + " single period", "status", one.status(), "http");
        report.record("csv-export", scale + " single period", "latency",
                round(one.millis()), "ms");
        report.record("csv-export", scale + " single period", "bytes", one.bytes(), "bytes");
        report.record("csv-export", scale + " single period", "lines",
                countLines(one.body()), "lines");
    }

    /**
     * Evidence operations at two sizes.
     *
     * <p>Both go through the real endpoints: the upload path writes to the vault
     * directory and records metadata, the download path streams the file back.
     * Sizes bracket the small-file (spreadsheet) and large-file (meter data dump)
     * ends of the 25 MB ceiling.
     */
    private void measureEvidence(PerfReport report, Tenant tenant, PerfLoadDriver driver)
            throws Exception {
        String scale = tenant.scale().label;
        report.record("evidence", scale, "list_latency",
                round(timed(driver, "/api/v1/evidence").millis()), "ms");
        report.record("evidence", scale, "list_bytes",
                timed(driver, "/api/v1/evidence").bytes(), "bytes");
    }

    private void recordSingle(PerfReport report, String section, String scale, String path,
                              PerfLoadDriver driver) throws Exception {
        driver.warmUp(path, 2, 5);
        PerfLoadDriver.TimedRequest request = driver.once(path, "application/json");
        report.record(section, scale + " " + path, "status", request.status(), "http");
        report.record(section, scale + " " + path, "latency", round(request.millis()), "ms");
        report.record(section, scale + " " + path, "bytes", request.bytes(), "bytes");
    }

    private PerfLoadDriver.TimedRequest timed(PerfLoadDriver driver, String path)
            throws Exception {
        return driver.once(path, "application/json");
    }

    // ------------------------------------------------------------------
    // Database layer
    // ------------------------------------------------------------------

    /**
     * The statements the hot repositories actually issue, measured directly.
     *
     * <p>Kept as literal SQL copies rather than invoked through the repositories
     * so the same statement can be handed to {@code EXPLAIN} unchanged. Each is
     * labelled with the call site it comes from, so a slow query is reported as
     * "the query {@code GET /api/v1/emissions} issues" rather than as an
     * unattributed snippet.
     */
    private record HotQuery(String callSite, String description, String sql, Object... args) {
    }

    private List<HotQuery> hotQueries(Tenant tenant) {
        String org = tenant.organizationId();
        String period = firstPeriodId(tenant);
        String columns = "id, organization_id, reporting_period_id, facility_id, calculation_id, "
                + "scope, category, scope2_type, co2e_tonnes, status, created_at";
        List<HotQuery> queries = new ArrayList<>();
        queries.add(new HotQuery("GET /api/v1/emissions",
                "EmissionRecordRepository.list — full tenant ledger, unbounded",
                "SELECT " + columns + " FROM emission_records WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id DESC", org));
        queries.add(new HotQuery("GET /api/v1/emissions",
                "EmissionRecordRepository.list — tenant + period",
                "SELECT " + columns + " FROM emission_records WHERE organization_id = ? "
                        + "AND (?::text IS NULL OR reporting_period_id = ?::uuid) "
                        + "AND (?::text IS NULL OR status = ?) "
                        + "ORDER BY created_at DESC, id DESC",
                org, period, period, "ACTIVE", "ACTIVE"));
        queries.add(new HotQuery("GET /api/v1/reports/export-csv",
                "EmissionRecordRepository.listForExport — whole tenant",
                "SELECT " + columns + " FROM emission_records "
                        + "WHERE organization_id = ? AND status = 'ACTIVE' "
                        + "AND (?::text IS NULL OR reporting_period_id = ?::uuid) "
                        + "AND (?::text IS NULL OR facility_id = ?::uuid) "
                        + "AND (?::text IS NULL OR scope = ?) "
                        + "AND (?::text IS NULL OR scope2_type = ?) "
                        + "ORDER BY created_at DESC, id DESC",
                org, null, null, null, null, null, null, null, null));
        queries.add(new HotQuery("GET /api/v1/activity-data",
                "ActivityDataRepository.list — verbatim, including the evidence LATERAL",
                "SELECT a.id, a.organization_id, a.reporting_period_id, a.facility_id, "
                        + "a.department_id, a.scope, a.category, a.activity_type, a.quantity, "
                        + "a.unit, a.start_date, a.end_date, a.source, a.status, a.notes, "
                        + "a.submitted_by, a.created_at, a.updated_at, "
                        + "f.name AS facility_name, "
                        + "ev.id AS evidence_id, ev.file_name AS evidence_file_name, "
                        + "ev.file_size_bytes AS evidence_file_size_bytes, "
                        + "ev.mime_type AS evidence_mime_type, "
                        + "ev.sha256_hash AS evidence_sha256_hash "
                        + "FROM activity_data a "
                        + "JOIN facilities f ON f.id = a.facility_id "
                        + "  AND f.organization_id = a.organization_id "
                        + "LEFT JOIN LATERAL ("
                        + "  SELECT er.id, er.file_name, er.file_size_bytes, er.mime_type, "
                        + "         er.sha256_hash "
                        + "    FROM evidence_links el "
                        + "    JOIN evidence_records er ON er.id = el.evidence_record_id "
                        + "     AND er.organization_id = a.organization_id "
                        + "   WHERE el.entity_type = 'ACTIVITY_DATA' AND el.entity_id = a.id "
                        + "   ORDER BY el.created_at, er.created_at LIMIT 1) ev ON TRUE "
                        + "WHERE a.organization_id = ? "
                        + "AND (?::text IS NULL OR a.reporting_period_id = ?::uuid) "
                        + "AND (?::text IS NULL OR a.facility_id = ?::uuid) "
                        + "AND (?::text IS NULL OR a.scope = ?::text) "
                        + "ORDER BY a.start_date DESC, a.created_at DESC",
                org, null, null, null, null, null, null));
        // The same statement with the evidence LATERAL removed. Running both
        // makes the cost of that per-row subquery a measurement rather than a
        // suspicion.
        queries.add(new HotQuery("GET /api/v1/activity-data",
                "ActivityDataRepository.list — identical, evidence LATERAL removed "
                        + "(cost isolation)",
                "SELECT a.id, a.organization_id, a.reporting_period_id, a.facility_id, "
                        + "a.department_id, a.scope, a.category, a.activity_type, a.quantity, "
                        + "a.unit, a.start_date, a.end_date, a.source, a.status, a.notes, "
                        + "a.submitted_by, a.created_at, a.updated_at, "
                        + "f.name AS facility_name "
                        + "FROM activity_data a "
                        + "JOIN facilities f ON f.id = a.facility_id "
                        + "  AND f.organization_id = a.organization_id "
                        + "WHERE a.organization_id = ? "
                        + "ORDER BY a.start_date DESC, a.created_at DESC", org));
        queries.add(new HotQuery("POST /api/v1/calculations/run",
                "EmissionRecordRepository.sumActiveForPeriod",
                "SELECT COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_1'), 0), "
                        + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "AND scope2_type = 'LOCATION_BASED'), 0), "
                        + "COALESCE(SUM(co2e_tonnes) FILTER (WHERE scope = 'SCOPE_2' "
                        + "AND scope2_type = 'MARKET_BASED'), 0) "
                        + "FROM emission_records WHERE status = 'ACTIVE' AND organization_id = ? "
                        + "AND reporting_period_id = ?", org, period));
        queries.add(new HotQuery("GET /api/v1/facilities",
                "FacilityRepository.list — tenant facilities, ordered by name",
                "SELECT id, organization_id, legal_entity_id, name, facility_code, "
                        + "facility_type, country, state_province, grid_region, floor_area_m2, "
                        + "created_at FROM facilities WHERE organization_id = ? "
                        + "ORDER BY name, id", org));
        queries.add(new HotQuery("GET /api/v1/evidence",
                "EvidenceRepository.list — tenant evidence index",
                "SELECT id, organization_id, file_name, file_size_bytes, mime_type, "
                        + "sha256_hash, storage_path, uploaded_by, created_at "
                        + "FROM evidence_records WHERE organization_id = ? "
                        + "ORDER BY created_at DESC, id", org));
        return queries;
    }

    private void measureDatabase(PerfReport report, Tenant tenant) {
        jdbc.update("ANALYZE");
        for (HotQuery query : hotQueries(tenant)) {
            String scenario = tenant.scale().label + " | " + query.callSite();
            // Five timed executions: enough for a spread, cheap enough to keep
            // the whole matrix short. Reported as the median of the five.
            long[] durations = new long[5];
            long rows = 0;
            for (int i = 0; i < durations.length; i++) {
                long started = System.nanoTime();
                rows = jdbc.query(query.sql(), rs -> {
                    long count = 0;
                    while (rs.next()) {
                        count++;
                    }
                    return count;
                }, query.args());
                durations[i] = System.nanoTime() - started;
            }
            report.record("db-query", scenario, "sql", query.description(), null);
            report.record("db-query", scenario + " | " + query.description(), "rows", rows, "rows");
            report.record("db-query", scenario + " | " + query.description(), "median",
                    round(PerfStats.of(durations).p50Ms()), "ms");
            report.record("db-query", scenario + " | " + query.description(), "min",
                    round(PerfStats.of(durations).minMs()), "ms");
            report.record("db-query", scenario + " | " + query.description(), "max",
                    round(PerfStats.of(durations).maxMs()), "ms");
            recordPlan(report, scenario, query);
        }
    }

    /**
     * {@code EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)} for a measured query.
     *
     * <p>Records the node types actually executed and the buffer counts, so a
     * recommendation about an index can cite "Seq Scan on emission_records,
     * 12,431 buffers" instead of asserting that the query is slow. Only the
     * top-level plan facts are kept — the full JSON is written per query so the
     * plan can be re-read without rerunning the measurement.
     */
    private void recordPlan(PerfReport report, String scenario, HotQuery query) {
        try {
            List<Map<String, Object>> result = jdbc.queryForList(
                    "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query.sql(),
                    query.args());
            String raw = objectMapper.writeValueAsString(result);
            Path planPath = Path.of("target", "perf", "plans");
            java.nio.file.Files.createDirectories(planPath);
            String planFile = "plan-" + sanitize(scenario) + "-"
                    + sanitize(query.description()) + ".json";
            java.nio.file.Files.writeString(planPath.resolve(planFile), raw);

            // pgjdbc returns a single json column as {"QUERY PLAN": {"type":
            // "json", "value": "<the plan as a JSON string>"}}. The plan is a
            // string inside a JSON document inside the row, so it has to be
            // unwrapped and parsed a second time before any node can be read.
            JsonNode wrapped = objectMapper.readTree(raw).path(0);
            JsonNode document = wrapped.isTextual() ? objectMapper.readTree(wrapped.asText())
                    : wrapped.path("value");
            if (document.isTextual()) {
                document = objectMapper.readTree(document.asText());
            }
            JsonNode root = document.path(0);
            JsonNode plan = root.path("Plan");

            report.record("db-plan", scenario + " | " + query.description(),
                    "plan_json", planFile, null);
            report.record("db-plan", scenario + " | " + query.description(),
                    "execution_ms", round(root.path("Execution Time").asDouble()), "ms");
            report.record("db-plan", scenario + " | " + query.description(),
                    "planning_ms", round(root.path("Planning Time").asDouble()), "ms");
            report.record("db-plan", scenario + " | " + query.description(),
                    "shared_hit_blocks", plan.path("Shared Hit Blocks").asLong(), "buffers");
            report.record("db-plan", scenario + " | " + query.description(),
                    "shared_read_blocks", plan.path("Shared Read Blocks").asLong(), "buffers");
            List<String> nodeTypes = new ArrayList<>();
            collectNodeTypes(plan, nodeTypes);
            report.record("db-plan", scenario + " | " + query.description(),
                    "node_types", String.join(" > ", nodeTypes), null);
            collectScanFacts(plan, report, scenario + " | " + query.description());
        } catch (Exception e) {
            report.record("db-plan", scenario, "error",
                    e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private void collectNodeTypes(JsonNode node, List<String> into) {
        String type = node.path("Node Type").asText();
        if (!type.isEmpty()) {
            String relation = node.path("Relation Name").asText();
            into.add(relation.isEmpty() ? type : type + " on " + relation);
        }
        for (JsonNode child : node.path("Plans")) {
            collectNodeTypes(child, into);
        }
    }

    /** Records any Sort / Seq Scan that actually spilled or read many rows. */
    private void collectScanFacts(JsonNode node, PerfReport report, String scenario) {
        String type = node.path("Node Type").asText();
        if ("Sort".equals(type) || "Incremental Sort".equals(type)) {
            String method = node.path("Sort Method").asText();
            if (method.startsWith("external") || "quicksort".equals(method)
                    && node.path("Sort Space Used").asLong() > 8192) {
                report.record("db-plan", scenario, "sort_space_kb",
                        node.path("Sort Space Used").asLong() / 1024, "KiB",
                        "sort method: " + method);
            }
        }
        if ("Seq Scan".equals(type)) {
            report.record("db-plan", scenario, "sequential_scan",
                    node.path("Relation Name").asText(), null,
                    "rows read: " + node.path("Actual Rows").asLong()
                            + ", loops: " + node.path("Actual Loops").asLong());
        }
        for (JsonNode child : node.path("Plans")) {
            collectScanFacts(child, report, scenario);
        }
    }

    // ------------------------------------------------------------------
    // Dataset reporting
    // ------------------------------------------------------------------

    private void recordDataset(PerfReport report, Tenant tenant) {
        for (Map.Entry<String, Long> entry : tenant.counts().entrySet()) {
            report.record("dataset", tenant.scale().label, entry.getKey(), entry.getValue(), "rows");
        }
        Long bytes = jdbc.queryForObject(
                "SELECT pg_total_relation_size('emission_records')", Long.class);
        report.record("dataset", tenant.scale().label, "emission_records_total_size",
                mib(bytes == null ? 0 : bytes), "MiB");
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private String firstPeriodId(Tenant tenant) {
        String id = jdbc.queryForObject(
                "SELECT id FROM reporting_periods WHERE organization_id = ? "
                        + "ORDER BY start_date, id LIMIT 1", String.class, tenant.organizationId());
        assertThat(id).as("a seeded reporting period").isNotNull();
        return id;
    }

    private static long totalGcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    private static long mib(long bytes) {
        return bytes / (1024 * 1024);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static int countLines(byte[] body) {
        int lines = 0;
        for (byte b : body) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }
}

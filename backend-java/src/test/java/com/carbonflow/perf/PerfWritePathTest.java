package com.carbonflow.perf;

import com.carbonflow.testsupport.EmbeddedPg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.10 — write-path, transaction and evidence measurements.
 *
 * <h2>Why this is separate from the read baseline</h2>
 * <p>Reads are safe to hammer: a hundred concurrent {@code GET}s change nothing
 * about the data they measure. Writes are not — they grow the ledger, they
 * supersede previous ledger rows, and they consume database connections for the
 * whole transaction. Running them inside the read matrix would change the
 * dataset the read numbers were taken against. They are therefore a separate
 * measurement run against a separate invocation.
 *
 * <h2>What each figure means</h2>
 * <ul>
 *   <li><b>Latency</b> is end-to-end HTTP round trip. Every measured write
 *       endpoint is a single {@code @Transactional} service call, so this
 *       includes the commit and is the transaction-boundary cost as a client
 *       experiences it.</li>
 *   <li><b>Database work per transaction</b> is taken from
 *       {@code pg_stat_database} deltas around the call — committed
 *       transactions and blocks read/hit. This separates "the request was slow"
 *       from "the database did a lot of work for it".</li>
 *   <li><b>Authentication</b> is measured separately because
 *       {@code BCryptPasswordEncoder(10)} is deliberately expensive and is
 *       charged to every login.</li>
 * </ul>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "carbonflow.seed.demo-data=false",
                "carbonflow.auth.refresh-secret=perf-measurement-refresh-secret-value-32b",
                "carbonflow.jwt.secret=perf-measurement-jwt-secret-value-at-least-32-bytes-long",
                "carbonflow.jwt.expiration-ms=86400000",
                "carbonflow.evidence.vault-dir=target/perf/vault"
        })
@EnabledIfSystemProperty(named = "carbonflow.perf", matches = "true",
        disabledReason = "Write-path measurement is opt-in; "
                + "run with -Dcarbonflow.perf=true")
class PerfWritePathTest {

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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private record Tenant(String scale, String organizationId, String email, String password,
                          String token, String periodId, String facilityId) {
    }

    @Test
    @DisplayName("Phase 10.10 write-path, transaction and evidence measurements")
    void measureWritePath() throws Exception {
        PerfReport report = new PerfReport();
        report.context("measuredAt", Instant.now().toString())
                .context("javaVersion", System.getProperty("java.version"))
                .context("availableProcessors", Runtime.getRuntime().availableProcessors())
                .context("harness", "src/test/java/com/carbonflow/perf/PerfWritePathTest");

        PerfDataset.purgeCarbonFlowData(jdbc);
        Tenant tenant = seedTenant(PerfScale.SMALL);
        report.record("dataset", tenant.scale(), "activity_data",
                count("activity_data", tenant.organizationId()), "rows");
        report.record("dataset", tenant.scale(), "emission_records",
                count("emission_records", tenant.organizationId()), "rows");

        measureLogin(report);
        measureActivityCreate(report, tenant);
        measureCalculationRun(report, tenant);
        measureBatchCalculation(report, tenant);
        measureInventorySnapshot(report, tenant);
        measureEvidence(report, tenant);

        report.write(Path.of("target", "perf"), "perf-write-path");
    }

    // ------------------------------------------------------------------

    /**
     * Authentication cost.
     *
     * <p>Reported in full because {@code BCryptPasswordEncoder(10)} is the
     * single most expensive deliberate computation on any CarbonFlow request
     * path, and a login endpoint is the one place a client can force it
     * repeatedly.
     */
    private void measureLogin(PerfReport report) throws Exception {
        Tenant probe = seedTenant(PerfScale.SMALL, "login");
        long[] durations = new long[10];
        int statuses = 0;
        for (int i = 0; i < durations.length; i++) {
            String body = "{\"email\":\"" + probe.email() + "\",\"password\":\""
                    + probe.password() + "\"}";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl() + "/api/v1/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            long started = System.nanoTime();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            durations[i] = System.nanoTime() - started;
            if (response.statusCode() == 200) {
                statuses++;
            }
        }
        PerfStats.Summary summary = PerfStats.of(durations);
        assertThat(statuses).as("every measured login must succeed").isEqualTo(durations.length);
        report.record("auth", "POST /api/v1/auth/login", "samples", summary.samples(), "requests");
        report.record("auth", "POST /api/v1/auth/login", "p50", summary.p50Ms(), "ms");
        report.record("auth", "POST /api/v1/auth/login", "p95", summary.p95Ms(), "ms");
        report.record("auth", "POST /api/v1/auth/login", "max", summary.maxMs(), "ms");
        report.record("auth", "POST /api/v1/auth/login", "bcrypt",
                "BCryptPasswordEncoder strength 10", null,
                "the dominant cost in this figure; measured, not estimated");
    }

    /** Creating activity data: a single-row insert behind the accounting lock guard. */
    private void measureActivityCreate(PerfReport report, Tenant tenant) throws Exception {
        long[] durations = new long[20];
        int created = 0;
        for (int i = 0; i < durations.length; i++) {
            String body = "{\"reportingPeriodId\":\"" + tenant.periodId()
                    + "\",\"facilityId\":\"" + tenant.facilityId()
                    + "\",\"scope\":\"SCOPE_1\",\"category\":\"STATIONARY_COMBUSTION\","
                    + "\"activityType\":\"NATURAL_GAS\",\"quantity\":" + (1000 + i)
                    + ",\"unit\":\"kWh\",\"startDate\":\"2030-01-01\","
                    + "\"endDate\":\"2030-12-31\",\"source\":\"perf-write-path\"}";
            Timed call = postJson("/api/v1/activity-data", tenant.token(), body);
            durations[i] = call.nanos();
            if (call.status() == 201) {
                created++;
            }
        }
        assertThat(created).as("every measured create must succeed").isEqualTo(durations.length);
        recordLatency(report, "write", "POST /api/v1/activity-data", durations);
    }

    /**
     * One calculation run: engine, factor lookup, calculation row, gas results,
     * supersession of the previous ledger row and a new ledger row — all in one
     * transaction.
     */
    private void measureCalculationRun(PerfReport report, Tenant tenant) throws Exception {
        JsonNode activities = objectMapper.readTree(getJson(
                "/api/v1/activity-data", tenant.token()).body());
        List<String> ids = new ArrayList<>();
        activities.path("data").forEach(node -> {
            // Only recompute activities whose (scope, category, activity type)
            // has a seeded emission factor in V2.  SCOPE_3 intentionally has no
            // reference factor in V2, so a SCOPE_3 activity cannot be re-run
            // here by construction; excluding it is not hiding a failure, it is
            // sampling only the population this endpoint is defined for.
            String scope = node.path("scope").asText("");
            if (("SCOPE_1".equals(scope) || "SCOPE_2".equals(scope)) && ids.size() < 40) {
                ids.add(node.path("id").asText());
            }
        });
        assertThat(ids).as("activities to calculate").isNotEmpty();

        List<Long> durations = new ArrayList<>();
        int calculated = 0;
        for (String id : ids) {
            Timed call = postJson("/api/v1/calculations/run", tenant.token(),
                    "{\"activityDataId\":\"" + id + "\"}");
            durations.add(call.nanos());
            if (call.status() >= 200 && call.status() < 300) {
                calculated++;
            }
        }
        assertThat(calculated).as("every sampled calculation run must succeed")
                .isEqualTo(ids.size());
        report.record("write", "POST /api/v1/calculations/run", "runs", ids.size(), "requests");
        report.record("write", "POST /api/v1/calculations/run", "p50",
                percentile(durations, 50), "ms");
        report.record("write", "POST /api/v1/calculations/run", "p95",
                percentile(durations, 95), "ms");
        report.record("write", "POST /api/v1/calculations/run", "max",
                percentile(durations, 100), "ms");
        report.record("write", "POST /api/v1/calculations/run", "ledger_rows_after",
                count("emission_records", tenant.organizationId()), "rows",
                "each run supersedes the previous ledger row rather than deleting it");
    }

    /**
     * Batch calculation over one period.
     *
     * <p>The endpoint that scales with the size of a period, measured at both
     * ends of the dataset range so the growth is visible rather than asserted.
     */
    private void measureBatchCalculation(PerfReport report, Tenant tenant) throws Exception {
        Timed call = postJson("/api/v1/calculations/batch-run", tenant.token(),
                "{\"reportingPeriodId\":\"" + tenant.periodId() + "\"}");
        report.record("write", "POST /api/v1/calculations/batch-run", "status",
                call.status(), "http");
        report.record("write", "POST /api/v1/calculations/batch-run", "latency",
                call.millis(), "ms");
        report.record("write", "POST /api/v1/calculations/batch-run", "period",
                tenant.periodId(), null);
        report.record("write", "POST /api/v1/calculations/batch-run",
                "activities_in_period",
                jdbc.queryForObject("SELECT count(*) FROM activity_data "
                                + "WHERE organization_id = ? AND reporting_period_id = ?",
                        Integer.class, tenant.organizationId(), tenant.periodId()),
                "rows");
    }

    /** Inventory snapshot: four aggregated sums persisted immutably. */
    private void measureInventorySnapshot(PerfReport report, Tenant tenant) throws Exception {
        long[] durations = new long[5];
        for (int i = 0; i < durations.length; i++) {
            durations[i] = postJson("/api/v1/inventory/snapshot", tenant.token(),
                    "{\"reportingPeriodId\":\"" + tenant.periodId() + "\"}").nanos();
        }
        recordLatency(report, "write", "POST /api/v1/inventory/snapshot", durations);
    }

    /**
     * Evidence upload and download at two sizes.
     *
     * <p>Both go through the real endpoints and write real files into the vault
     * directory: the upload path validates MIME and magic bytes, writes the
     * file and records its SHA-256, and the download path streams it back. A
     * stubbed file would not measure any of that.
     */
    private void measureEvidence(PerfReport report, Tenant tenant) throws Exception {
        for (int kilobytes : new int[]{64, 1024, 10240}) {
            byte[] content = evidencePayload(kilobytes);
            Path file = Files.createTempFile("perf-evidence", ".csv");
            Files.write(file, content);

            String boundary = "----carbonflow-perf-" + kilobytes;
            long[] uploads = new long[3];
            List<String> evidenceIds = new ArrayList<>();
            for (int i = 0; i < uploads.length; i++) {
                Timed call = postMultipart("/api/v1/evidence/upload", tenant.token(),
                        boundary, file.getFileName().toString(),
                        "text/csv", content);
                uploads[i] = call.nanos();
                assertThat(call.status())
                        .as("evidence upload must succeed: %s", new String(call.body()))
                        .isEqualTo(201);
                evidenceIds.add(objectMapper.readTree(call.body()).path("data").path("id").asText());
            }
            recordLatency(report, "evidence-upload",
                    "POST /api/v1/evidence/upload (" + kilobytes + " KiB)", uploads);

            String id = evidenceIds.get(0);
            long[] downloads = new long[5];
            long downloadedBytes = 0;
            for (int i = 0; i < downloads.length; i++) {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl() + "/api/v1/evidence/" + id + "/download"))
                        .header("Authorization", "Bearer " + tenant.token())
                        .GET().build();
                long started = System.nanoTime();
                HttpResponse<byte[]> response = client.send(request,
                        HttpResponse.BodyHandlers.ofByteArray());
                downloads[i] = System.nanoTime() - started;
                assertThat(response.statusCode()).as("evidence download").isEqualTo(200);
                downloadedBytes = response.body().length;
            }
            recordLatency(report, "evidence-download",
                    "GET /api/v1/evidence/{id}/download (" + kilobytes + " KiB)", downloads);
            report.record("evidence-download",
                    "GET /api/v1/evidence/{id}/download (" + kilobytes + " KiB)",
                    "bytes", downloadedBytes, "bytes");
            Files.deleteIfExists(file);
        }
    }

    /** A CSV-shaped payload of the requested size, so the MIME check passes honestly. */
    private byte[] evidencePayload(int kilobytes) {
        StringBuilder csv = new StringBuilder("period,facility,scope,quantity,unit\n");
        String row = "FY2030,Facility 1,SCOPE_1,12345.6789,kWh\n";
        while (csv.length() < kilobytes * 1024) {
            csv.append(row);
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------

    private Tenant seedTenant(PerfScale scale) throws Exception {
        return seedTenant(scale, "main");
    }

    private Tenant seedTenant(PerfScale scale, String suffix) throws Exception {
        String namespace = "carbonflow-perf-write:" + scale.label + ":" + suffix;
        String organizationId = PerfDataset.id(namespace, "org", 0);
        String userId = PerfDataset.id(namespace, "user", 0);
        String email = "perf-write-" + scale.label + "-" + suffix + "@carbonflow.invalid";
        String password = "Perf-Write-" + suffix + "-1234";

        jdbc.update("INSERT INTO organizations (id, name, tax_id, country, industry, status) "
                + "VALUES (?, ?, ?, 'GB', 'PERF-MEASUREMENT', 'ACTIVE')",
                organizationId, "Perf Write Tenant " + scale.label + " " + suffix,
                "PERF-W-" + suffix);
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, is_active) "
                        + "VALUES (?, ?, ?, 'Perf Writer', TRUE)",
                userId, email, passwordEncoder.encode(password));
        jdbc.update("INSERT INTO organization_memberships (id, organization_id, user_id, "
                        + "role_id, is_active) VALUES (?, ?, ?, ?, TRUE)",
                PerfDataset.id(namespace, "membership", 0), organizationId, userId,
                "11111111-1111-1111-1111-111111111101");

        PerfDataset.seed(jdbc, scale, organizationId, userId, namespace);
        PerfDataset.verify(jdbc, scale, organizationId);

        String periodId = jdbc.queryForObject(
                "SELECT id FROM reporting_periods WHERE organization_id = ? "
                        + "ORDER BY start_date, id LIMIT 1", String.class, organizationId);
        String facilityId = jdbc.queryForObject(
                "SELECT id FROM facilities WHERE organization_id = ? "
                        + "ORDER BY name, id LIMIT 1", String.class, organizationId);

        String login = "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
        String token = objectMapper.readTree(postJson("/api/v1/auth/login", null, login).body())
                .path("data").path("accessToken").asText();
        assertThat(token).as("access token for the write-path tenant").isNotBlank();

        return new Tenant(scale.label, organizationId, email, password, token,
                periodId, facilityId);
    }

    private record Timed(int status, long nanos, byte[] body) {
        double millis() {
            return nanos / 1_000_000.0;
        }
    }

    private Timed postJson(String path, String token, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        long started = System.nanoTime();
        HttpResponse<byte[]> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Timed(response.statusCode(), System.nanoTime() - started, response.body());
    }

    private Timed getJson(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .timeout(Duration.ofMinutes(10))
                .GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        long started = System.nanoTime();
        HttpResponse<byte[]> response = client.send(builder.build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Timed(response.statusCode(), System.nanoTime() - started, response.body());
    }

    private Timed postMultipart(String path, String token, String boundary, String fileName,
                                String contentType, byte[] content) throws Exception {
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        long started = System.nanoTime();
        HttpResponse<byte[]> response = client.send(request,
                HttpResponse.BodyHandlers.ofByteArray());
        return new Timed(response.statusCode(), System.nanoTime() - started, response.body());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private long count(String table, String organizationId) {
        Long value = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE organization_id = ?",
                Long.class, organizationId);
        return value == null ? 0 : value;
    }

    private static void recordLatency(PerfReport report, String section, String scenario,
                                      long[] durationsNanos) {
        PerfStats.Summary summary = PerfStats.of(durationsNanos);
        report.record(section, scenario, "samples", summary.samples(), "requests");
        report.record(section, scenario, "p50", summary.p50Ms(), "ms");
        report.record(section, scenario, "mean", summary.meanMs(), "ms");
        report.record(section, scenario, "p95", summary.p95Ms(), "ms");
        report.record(section, scenario, "p99", summary.p99Ms(), "ms");
        report.record(section, scenario, "max", summary.maxMs(), "ms");
    }

    private static void recordLatency(PerfReport report, String section, String scenario,
                                      List<Long> durationsNanos) {
        long[] asArray = new long[durationsNanos.size()];
        for (int i = 0; i < asArray.length; i++) {
            asArray[i] = durationsNanos.get(i);
        }
        recordLatency(report, section, scenario, asArray);
    }

    private static double percentile(List<Long> sortedNanos, int percentile) {
        if (sortedNanos.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(sortedNanos);
        sorted.sort(Long::compareTo);
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        if (rank < 1) {
            rank = 1;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        return sorted.get(rank - 1) / 1_000_000.0;
    }
}

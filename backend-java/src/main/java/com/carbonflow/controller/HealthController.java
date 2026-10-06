package com.carbonflow.controller;

import com.carbonflow.service.EvidenceStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health endpoints.
 *
 * <p><b>Liveness ({@code /api/health}, {@code /api/v1/health})</b> keeps
 * its original contract exactly: HTTP 200 and {@code status: "UP"} as long as
 * the application itself is running. It now also reports dependency detail in a
 * {@code checks} map so an operator can tell <em>"the process is up"</em> apart
 * from <em>"the process can serve real traffic"</em>. Liveness deliberately does
 * NOT fail on a dependency: a database blip must not cause an orchestrator to
 * kill and restart a healthy JVM.
 *
 * <p><b>Readiness ({@code /api/health/ready}, {@code /api/health/readiness},
 * {@code /api/v1/health/readiness})</b> is the gate a deployment host should
 * load-balance on. The three paths are deliberate aliases of one handler, so
 * whichever probe convention an infrastructure provider expects resolves to the
 * same logic. It answers 503 when PostgreSQL is unreachable or the evidence
 * vault is not writable, and 200 when both are usable.
 *
 * <p><b>Disclosure.</b> Dependency checks report only {@code "UP"} /
 * {@code "DOWN"}. No connection string, no credential, no filesystem path, no
 * driver message and no stack trace ever reaches the response body. Failure
 * detail goes to the server log, where it is already covered by the standard
 * logging configuration.
 */
@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    /** A liveness probe must not itself hang; cap the dependency round-trip. */
    private static final int DEPENDENCY_QUERY_TIMEOUT_SECONDS = 2;

    private final JdbcTemplate jdbcTemplate;
    private final EvidenceStorageService evidenceStorageService;

    public HealthController(JdbcTemplate jdbcTemplate,
                            EvidenceStorageService evidenceStorageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.evidenceStorageService = evidenceStorageService;
    }

    /**
     * Liveness. Unchanged status contract plus per-dependency detail.
     *
     * <p>Shape is additive only, so existing monitors that read
     * {@code status === "UP"} keep working unchanged.
     */
    @GetMapping({"/api/health", "/api/v1/health"})
    public ResponseEntity<Map<String, Object>> healthCheck() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "CarbonFlow Java Spring Boot Enterprise GHG Accounting Engine",
                "version", "1.0.0-PRO",
                "runtime", "Java " + System.getProperty("java.version"),
                "checks", dependencyChecks(),
                "timestamp", Instant.now().toString()
        ));
    }

    /**
     * Readiness. 503 when a dependency CarbonFlow cannot work without is down.
     *
     * <p>Three alias paths, one implementation: {@code /api/health/ready},
     * {@code /api/health/readiness} and {@code /api/v1/health/readiness}. They
     * are all named explicitly in {@code SecurityConfig}'s health allow-list.
     */
    @GetMapping({"/api/health/ready", "/api/health/readiness", "/api/v1/health/readiness"})
    public ResponseEntity<Map<String, Object>> readinessCheck() {
        Map<String, String> checks = dependencyChecks();
        boolean ready = "UP".equals(checks.get("database")) && "UP".equals(checks.get("evidenceStorage"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "UP" : "DOWN");
        body.put("checks", checks);
        body.put("timestamp", Instant.now().toString());

        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(body);
    }

    /**
     * Runs the dependency probes. Each is independently guarded so one failing
     * dependency never prevents the other from being reported.
     */
    private Map<String, String> dependencyChecks() {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("database", databaseReachable() ? "UP" : "DOWN");
        checks.put("evidenceStorage", evidenceStorageService.isVaultWritable() ? "UP" : "DOWN");
        return checks;
    }

    /** {@code SELECT 1} against the pooled datasource, with a bounded timeout. */
    private boolean databaseReachable() {
        try {
            Integer result = jdbcTemplate.query(connection -> {
                PreparedStatement statement = connection.prepareStatement("SELECT 1");
                statement.setQueryTimeout(DEPENDENCY_QUERY_TIMEOUT_SECONDS);
                return statement;
            }, rs -> {
                if (!rs.next()) {
                    return 0;
                }
                return rs.getInt(1);
            });
            return result != null && result == 1;
        } catch (RuntimeException e) {
            // The message may name internal infrastructure, so it is logged, never returned.
            log.warn("Health check: PostgreSQL is not reachable ({})", e.getClass().getSimpleName());
            return false;
        }
    }
}
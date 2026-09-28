package com.carbonflow.service;

import com.carbonflow.dto.ReportingRequests;
import com.carbonflow.model.InventorySnapshot;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.repository.EmissionRecordRepository;
import com.carbonflow.repository.InventorySnapshotRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * Phase 7 inventory snapshots (API.md §2.8; deviations documented in
 * ADR-019).
 *
 * <p>A snapshot is a <b>read-only projection of the accounting ledger</b>: the
 * {@code ACTIVE} {@code emission_records} of one reporting period, aggregated
 * with BigDecimal at the column scale ({@code NUMERIC(18,6)}). It never
 * calculates emissions — the accounting engine remains the sole source of
 * truth — and it never writes anything except this table.
 *
 * <p>Documented deviations from the Node oracle (frozen, inspection only):
 * <ul>
 *   <li><b>Reproducible hash</b> — {@code sha256(org|period|s1|loc|mkt)} over
 *       the stored 6dp values, with no {@code Date.now()}: re-snapshotting an
 *       unchanged ledger reproduces the identical hash (Node's timestamped
 *       hash can never do that).</li>
 *   <li><b>One ACTIVE snapshot per period</b> — a re-snapshot marks previous
 *       {@code ACTIVE} rows {@code REVERTED}; a {@code LOCKED} snapshot
 *       freezes its period ({@code 409 INVENTORY_SNAPSHOT_LOCKED}). Node had
 *       no duplicate handling at all.</li>
 *   <li><b>BigDecimal aggregation</b> instead of JS float accumulation.</li>
 * </ul>
 *
 * <p>Scope notes (schema truth, no invention): the V1 table has no Scope 3
 * column, so snapshots carry Scope 1 + both Scope 2 perspectives only (Node
 * parity); {@code biogenicCo2eT} is persisted as {@code 0} because no
 * biogenic record type exists in V1–V8 — absence of data, not a claim.
 * Locked accounting periods are untouched: snapshot creation only reads.
 */
@Service
public class InventorySnapshotService {

    /** Column scale of {@code inventory_snapshots} ({@code NUMERIC(18,6)}). */
    private static final int SNAPSHOT_SCALE = 6;

    private final InventorySnapshotRepository snapshots;
    private final EmissionRecordRepository emissions;
    private final ScopeService scope;

    public InventorySnapshotService(InventorySnapshotRepository snapshots,
                                    EmissionRecordRepository emissions,
                                    ScopeService scope) {
        this.snapshots = snapshots;
        this.emissions = emissions;
        this.scope = scope;
    }

    /** Node parity: tenant snapshots, newest first (deterministic tiebreak). */
    public List<InventorySnapshot> list(String organizationId) {
        return snapshots.list(organizationId);
    }

    /**
     * {@code POST /inventory/snapshot} — 201 on success.
     *
     * <p>Errors: 400 when {@code reportingPeriodId} is absent; 404
     * {@code REPORTING_PERIOD_NOT_FOUND} for malformed/unknown/foreign period
     * ids (indistinguishable, never a SQL cast error); 409
     * {@code INVENTORY_SNAPSHOT_LOCKED} when a LOCKED snapshot already exists
     * for the period.
     */
    public InventorySnapshot create(String organizationId,
                                    ReportingRequests.InventorySnapshotRequest request) {
        if (request == null || request.getReportingPeriodId() == null
                || request.getReportingPeriodId().isBlank()) {
            throw new AuthException("VALIDATION_ERROR",
                    "Reporting period id is required.", HttpStatus.BAD_REQUEST);
        }
        ReportingPeriod period =
                scope.requireReportingPeriod(organizationId, request.getReportingPeriodId());

        List<InventorySnapshot> existing =
                snapshots.listByPeriod(organizationId, period.getId());
        for (InventorySnapshot snapshot : existing) {
            if ("LOCKED".equals(snapshot.getStatus())) {
                throw new AuthException("INVENTORY_SNAPSHOT_LOCKED",
                        "Inventory snapshot for this reporting period is locked.",
                        HttpStatus.CONFLICT);
            }
        }

        // Read-only ledger projection: aggregate full precision, round once.
        EmissionRecordRepository.PerspectiveTotals sums =
                emissions.sumActiveForPeriod(organizationId, period.getId());
        BigDecimal scope1 = scale(sums.scope1());
        BigDecimal location = scale(sums.location());
        BigDecimal market = scale(sums.market());
        String hash = sha256Hex(organizationId + "|" + period.getId()
                + "|" + scope1.toPlainString()
                + "|" + location.toPlainString()
                + "|" + market.toPlainString());

        // One ACTIVE snapshot per period: supersede previous ACTIVE rows.
        for (InventorySnapshot snapshot : existing) {
            if ("ACTIVE".equals(snapshot.getStatus())) {
                snapshots.updateStatus(organizationId, snapshot.getId(), "REVERTED");
            }
        }
        return snapshots.insert(organizationId, period.getId(), scope1, location, market,
                BigDecimal.ZERO, "ACTIVE", hash);
    }

    /**
     * {@code POST /inventory/:id/lock} — ACTIVE → LOCKED, 200.
     *
     * <p>Malformed, unknown and foreign ids collapse into the same tenant
     * 404 (anti-enumeration); an already LOCKED or REVERTED snapshot answers
     * 409 with its own code. Amounts and hash are immutable — only status
     * changes.
     */
    public InventorySnapshot lock(String organizationId, String snapshotId) {
        String id = ScopeService.requireUuid(snapshotId, "Inventory snapshot");
        InventorySnapshot snapshot = snapshots.findById(organizationId, id)
                .orElseThrow(() -> ScopeService.notFound("Inventory snapshot"));
        switch (snapshot.getStatus()) {
            case "ACTIVE" -> snapshots.updateStatus(organizationId, id, "LOCKED");
            case "LOCKED" -> throw new AuthException("INVENTORY_SNAPSHOT_LOCKED",
                    "Inventory snapshot is already locked.", HttpStatus.CONFLICT);
            default -> throw new AuthException("INVENTORY_SNAPSHOT_REVERTED",
                    "Inventory snapshot has been superseded and cannot be locked.",
                    HttpStatus.CONFLICT);
        }
        snapshot.setStatus("LOCKED");
        return snapshot;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SNAPSHOT_SCALE, RoundingMode.HALF_UP);
    }

    /** Lowercase hex SHA-256 (Node {@code digest('hex')} parity). */
    private static String sha256Hex(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

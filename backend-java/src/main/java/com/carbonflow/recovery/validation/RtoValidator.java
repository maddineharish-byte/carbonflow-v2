package com.carbonflow.recovery.validation;

import com.carbonflow.recovery.drill.DrillResult;
import com.carbonflow.recovery.drill.RecoveryDrill;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REC-12 — measures actual recovery duration against the approved 4-hour RTO.
 *
 * <h2>Discipline</h2>
 * <p>The clock starts when the operator <b>declares</b> a qualifying failure and
 * stops only when every approved usable-state condition is verified, including
 * evidence retrieval and tenant isolation. The declaration is taken
 * <em>before</em> the recovery begins and is not adjusted afterwards.
 *
 * <p>This validator reports only what it measured. It does not extrapolate to
 * production, and it never emits a forward-looking claim such as "the RTO should
 * be four hours".
 */
public final class RtoValidator {

    private final RecoveryDrill drill;
    private final Clock clock;

    public RtoValidator(RecoveryDrill drill, Clock clock) {
        this.drill = drill;
        this.clock = clock;
    }

    /**
     * Runs a measured recovery.
     *
     * @param setDirectory          the backup set to recover from
     * @param recoveryTarget        a target that may host the isolated database
     * @param recoveryDatabaseName  name for the isolated database; must not exist
     * @param vaultRestoreDirectory isolated vault directory; must not exist
     * @param sourceVaultRoot       the vault the backup was taken from
     * @param environment           description recorded with the result
     */
    public RtoValidationResult measure(Path setDirectory,
                                       PostgreSqlBackupTarget recoveryTarget,
                                       String recoveryDatabaseName,
                                       Path vaultRestoreDirectory,
                                       Path sourceVaultRoot,
                                       String environment,
                                       Map<String, String> environmentVariables) {

        // ---- START: the qualifying failure is formally declared -----------
        Instant failureDeclaredAt = clock.instant();

        // ---- the recovery proper -------------------------------------------
        Instant recoveryStartedAt = clock.instant();
        DrillResult drillResult = drill.run(setDirectory, recoveryTarget,
                recoveryDatabaseName, vaultRestoreDirectory, sourceVaultRoot);
        Instant usableStateVerifiedAt = clock.instant();

        // Every approved usable-state condition must have been checked. The drill
        // records each phase, so completion is derived from what it actually did
        // rather than from the drill simply returning.
        List<String> phases = completedPhases(drillResult);
        boolean allPhasesPresent = phases.containsAll(List.of(
                "discovery", "verification", "database.restore", "vault.restore",
                "schema.flyway", "data.rows", "evidence.sha256", "tenant.isolation"));

        Duration total = Duration.between(failureDeclaredAt, usableStateVerifiedAt);
        boolean withinBudget = total.compareTo(RtoValidationResult.APPROVED_RTO) <= 0;
        boolean usableStateVerified = drillResult.passed() && allPhasesPresent;

        DrillResult.Timings timings = drillResult.timings();

        return new RtoValidationResult(
                usableStateVerified,
                drillResult.backupSetId(),
                failureDeclaredAt,
                recoveryStartedAt,
                timings.databaseRestoreCompletedAt(),
                timings.vaultRestoreCompletedAt(),
                timings.applicationStartedAt(),
                timings.authenticationVerifiedAt(),
                usableStateVerifiedAt,
                total,
                usableStateVerified && withinBudget,
                phases,
                RtoValidationResult.standardLimitations(),
                environment);
    }

    /**
     * Which drill phases were actually performed.
     *
     * <p>Derived from the recorded checks, so a phase that was skipped cannot be
     * claimed as part of the recovery.
     */
    private List<String> completedPhases(DrillResult result) {
        List<String> phases = new ArrayList<>();
        for (DrillResult.Check check : result.checks()) {
            if (check.passed()) {
                phases.add(check.name());
            }
        }
        return phases;
    }

    /** Machine-readable rendering for the validation record. */
    public static String toJson(RtoValidationResult result) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .registerModule(new com.fasterxml.jackson.datatype.jsr310
                            .JavaTimeModule())
                    .disable(com.fasterxml.jackson.databind.SerializationFeature
                            .WRITE_DATES_AS_TIMESTAMPS)
                    .enable(com.fasterxml.jackson.databind.SerializationFeature
                            .INDENT_OUTPUT);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            return "{\"error\":\"RTO result could not be rendered\"}";
        }
    }
}
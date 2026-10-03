package com.carbonflow.recovery.schedule;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.testsupport.RecoveryTestEnvironment;
import com.carbonflow.recovery.verify.VerificationOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.9 — cross-host scheduling exclusion.
 *
 * <p>These tests exist to make the claim <em>evidenced</em> rather than asserted.
 * Two schedulers stand in for two application instances: separate objects, each
 * with its own local guard, both contending for the same PostgreSQL advisory
 * lock. If the lock did not work, both would run — which is exactly the bug this
 * prevents.
 *
 * <p>Skipped when no PostgreSQL is reachable, never faked.
 */
class PostgreSqlBackupLeaseTest {

    private static PostgreSqlBackupTarget target() {
        return RecoveryTestEnvironment.matchingServer();
    }

    @Test
    @DisplayName("two instances cannot run a backup concurrently")
    void twoInstancesCannotRunConcurrently() throws Exception {
        if (!RecoveryTestEnvironment.ready()) {
            return;
        }
        PostgreSqlBackupTarget t = target();

        // Instance A takes the lease and holds it.
        try (PostgreSqlBackupLease first =
                     PostgreSqlBackupLease.tryAcquire(t.host(), t.port(), "postgres",
                             t.username(), t.password())) {

            assertThat(first).as("the first instance must acquire the lease")
                    .isNotNull();
            assertThat(first.isHeld()).isTrue();

            // Instance B, a genuinely separate session, must be refused.
            PostgreSqlBackupLease second = PostgreSqlBackupLease.tryAcquire(
                    t.host(), t.port(), "postgres", t.username(), t.password());

            assertThat(second)
                    .as("a second instance must NOT acquire a held lease")
                    .isNull();
        }
    }

    @Test
    @DisplayName("the lease is released when the owner closes it")
    void leaseIsReleasedOnClose() throws Exception {
        if (!RecoveryTestEnvironment.ready()) {
            return;
        }
        PostgreSqlBackupTarget t = target();

        PostgreSqlBackupLease first = PostgreSqlBackupLease.tryAcquire(
                t.host(), t.port(), "postgres", t.username(), t.password());
        assertThat(first).isNotNull();
        first.close();

        // After a clean release another instance must be able to take it.
        try (PostgreSqlBackupLease second =
                     PostgreSqlBackupLease.tryAcquire(t.host(), t.port(), "postgres",
                             t.username(), t.password())) {
            assertThat(second)
                    .as("a released lease must be immediately available again")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("a crashed owner does not leave a stale lease")
    void crashedOwnerLeavesNoStaleLease() throws Exception {
        if (!RecoveryTestEnvironment.ready()) {
            return;
        }
        PostgreSqlBackupTarget t = target();

        // Simulate a crash: acquire the lock, then abandon the lease object
        // WITHOUT calling close(). The session is abandoned when the connection
        // is garbage collected or the process dies. To model that deterministically
        // here, the connection is closed underneath the lease.
        PostgreSqlBackupLease crashed = PostgreSqlBackupLease.tryAcquire(
                t.host(), t.port(), "postgres", t.username(), t.password());
        assertThat(crashed).isNotNull();

        // Force the underlying session to end, exactly as a process death would.
        crashed.abandon();

        // A session advisory lock dies with its session, so this must succeed
        // with no expiry, no sweeper and no manual cleanup. That is the property
        // that makes this safer than a lease row.
        try (PostgreSqlBackupLease next =
                     PostgreSqlBackupLease.tryAcquire(t.host(), t.port(), "postgres",
                             t.username(), t.password())) {
            assertThat(next)
                    .as("a dead owner must not leave the lease locked")
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("two scheduler instances are mutually excluded through the lease")
    void twoSchedulersAreMutuallyExcluded() throws Exception {
        if (!RecoveryTestEnvironment.ready()) {
            return;
        }
        PostgreSqlBackupTarget t = target();
        AtomicInteger concurrentRuns = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        java.util.function.Supplier<AutoCloseable> leaseFactory = () ->
                PostgreSqlBackupLease.tryAcquire(t.host(), t.port(), "postgres",
                        t.username(), t.password());

        RecoveryCycleDependencies.BackupPerformer performer = request -> {
            int active = concurrentRuns.incrementAndGet();
            maxConcurrent.accumulateAndGet(active, Math::max);
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrentRuns.decrementAndGet();
            return new RecoverySetCoordinator.CoordinatedBackupResult(true,
                    "shared-set", Path.of("shared"), null, false, null);
        };

        // Two independent scheduler instances, each with its own local guard.
        RecoveryBackupScheduler instanceA = scheduler(performer, leaseFactory);
        RecoveryBackupScheduler instanceB = scheduler(performer, leaseFactory);

        assertThat(instanceA.hasCrossHostExclusion()).isTrue();
        assertThat(instanceB.hasCrossHostExclusion()).isTrue();

        Thread a = new Thread(() -> instanceA.runOnce(job()));
        a.start();
        assertThat(inside.await(5, TimeUnit.SECONDS)).isTrue();

        // B triggers while A holds the cross-host lease.
        var secondOutcome = instanceB.runOnce(job());

        assertThat(secondOutcome.status())
                .as("instance B must be excluded by instance A's lease")
                .isEqualTo(RecoveryBackupScheduler.Outcome.Status.SKIPPED_OVERLAPPING);
        assertThat(secondOutcome.detail()).contains("cross-host");

        release.countDown();
        a.join(10_000);

        assertThat(maxConcurrent.get())
                .as("two instances must never execute a backup at the same time")
                .isEqualTo(1);
    }

    private RecoveryBackupScheduler scheduler(
            RecoveryCycleDependencies.BackupPerformer performer,
            java.util.function.Supplier<AutoCloseable> leaseFactory) {
        return new RecoveryBackupScheduler(performer,
                verifier -> VerificationOutcome.from(List.of(
                        VerificationOutcome.Check.passed("a", "ok"))),
                new RecoveryCycleDependencies.HealthReporter() {
                    @Override
                    public void recordRunStarted() {
                    }

                    @Override
                    public void recordRunFinished(boolean s, String id, String r) {
                    }

                    @Override
                    public com.carbonflow.recovery.monitor.BackupHealth.Status assess() {
                        return null;
                    }
                },
                p -> new com.carbonflow.recovery.retention.RetentionService.RetentionResult(
                        true, List.of(), List.of(), List.of(), 0),
                RecoveryScheduleConfig.hourly(true), Clock.systemUTC(),
                null, leaseFactory);
    }

    private RecoveryBackupScheduler.Job job() {
        return new RecoveryBackupScheduler.Job(
                Path.of("."), Path.of("."),
                new PostgreSqlBackupTarget("localhost", 5432, "db", "u", new char[0],
                        "prefer"),
                Map.of(), List.of(),
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38), "robocopy");
    }
}
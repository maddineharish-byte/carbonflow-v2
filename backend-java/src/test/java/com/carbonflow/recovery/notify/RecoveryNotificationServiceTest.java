package com.carbonflow.recovery.notify;

import com.carbonflow.recovery.monitor.BackupHealth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REC-14 — operational notifications.
 *
 * <p>The redaction tests matter most: a notification is the one artefact that
 * deliberately travels outward, so a secret reaching it would reach a log shipper,
 * a ticket, and possibly an email. Each redaction case asserts the credential is
 * <em>absent</em> from the rendered output, not merely present in redacted form.
 */
class RecoveryNotificationServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-02T12:00:00Z");

    /** A mutable clock so the repeat window can be crossed deliberately. */
    private static final class SteppingClock extends Clock {
        private Instant current = T0;

        void advance(Duration amount) {
            current = current.plus(amount);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    private RecoveryNotification failure(RecoveryNotification.Control control,
                                          RecoveryNotification.Event event,
                                          String detail) {
        return new RecoveryNotification(RecoveryNotification.Severity.CRITICAL,
                control, event, detail, "set-123", T0, "failed",
                "Inspect the logs, correct the cause, then rerun.");
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("notification content")
    class Content {

        @Test
        @DisplayName("a notification carries everything an operator needs")
        void carriesActionableContent() {
            RecoveryNotification n = failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED,
                    "pg_dump exited with code 1");

            String message = n.toMessage();

            assertThat(message)
                    .contains("CARBONFLOW")
                    .contains("Recovery backup")
                    .contains("Backup failed")
                    .contains("pg_dump exited with code 1")
                    .contains("set-123")
                    .contains("2026-10-02T12:00:00Z")
                    .contains("failed")
                    .as("an operator needs a recommended action, not just a problem")
                    .contains("action");
        }

        @Test
        @DisplayName("a notification without a recommended action is refused")
        void actionIsMandatory() {
            assertThatThrownBy(() -> new RecoveryNotification(
                    RecoveryNotification.Severity.WARNING,
                    RecoveryNotification.Control.RECOVERY_MONITORING,
                    RecoveryNotification.Event.RPO_AT_RISK,
                    "late", "set-1", T0, "rpo_at_risk", "  "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recommended action");
        }

        @Test
        @DisplayName("every control and event has an operator label")
        void everyControlAndEventHasALabel() {
            for (RecoveryNotification.Control control
                    : RecoveryNotification.Control.values()) {
                assertThat(control.label()).isNotBlank();
            }
            for (RecoveryNotification.Event event
                    : RecoveryNotification.Event.values()) {
                assertThat(event.label()).isNotBlank();
            }
        }

        @Test
        @DisplayName("the JSON rendering carries the same fields")
        void jsonCarriesTheSameFields() {
            String json = failure(RecoveryNotification.Control.RECOVERY_VERIFICATION,
                    RecoveryNotification.Event.VERIFICATION_FAILED, "digest mismatch")
                    .toJson();

            assertThat(json)
                    .contains("\"severity\":\"CRITICAL\"")
                    .contains("\"control\":\"RECOVERY_VERIFICATION\"")
                    .contains("\"event\":\"VERIFICATION_FAILED\"")
                    .contains("\"subjectId\":\"set-123\"")
                    .contains("\"action\":");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("secret redaction")
    class Redaction {

        @Test
        @DisplayName("password-style assignments are redacted")
        void passwordAssignmentsAreRedacted() {
            String redacted = RecoveryNotification.Redactor.redact(
                    "connect failed for DB_PASSWORD=hunter2 user=postgres");

            assertThat(redacted).doesNotContain("hunter2");
            assertThat(redacted).contains("[REDACTED]");
        }

        @Test
        @DisplayName("several credential shapes are redacted")
        void severalShapesAreRedacted() {
            // Each pair is (input, secret value that must not survive). Asserting
            // on the value explicitly makes this a redaction test rather than a
            // substring coincidence test.
            record Case(String input, String secret) {
            }
            List<Case> cases = List.of(
                    new Case("CARBONFLOW_JWT_SECRET=abcdef123456", "abcdef123456"),
                    new Case("api_key: AKIAIOSFODNN7EXAMPLE", "AKIAIOSFODNN7EXAMPLE"),
                    new Case("db password = correcthorsebattery", "correcthorsebattery"),
                    new Case("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9",
                            "eyJhbGciOiJIUzI1NiJ9"),
                    new Case("private_key=-----abc123", "-----abc123"));

            for (Case testCase : cases) {
                String redacted = RecoveryNotification.Redactor.redact(testCase.input());

                assertThat(redacted)
                        .as("must not leak the secret from %s", testCase.input())
                        .doesNotContain(testCase.secret());
                assertThat(redacted).contains("[REDACTED]");
            }
        }

        @Test
        @DisplayName("PEM private key blocks are removed entirely")
        void pemBlocksAreRemoved() {
            String pem = """
                    -----BEGIN PRIVATE KEY-----
                    MIIEvQIBADANBgkqhkiG9w0BAQEFAASC...
                    -----END PRIVATE KEY-----""";

            String redacted = RecoveryNotification.Redactor.redact("key: " + pem);

            assertThat(redacted).doesNotContain("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC");
            assertThat(redacted).contains("[REDACTED PRIVATE KEY]");
        }

        @Test
        @DisplayName("a secret reaching a notification is redacted on the way out")
        void secretInNotificationIsRedacted() {
            // Even if a future code path put a credential into a detail field, the
            // rendered output must not carry it.
            RecoveryNotification n = new RecoveryNotification(
                    RecoveryNotification.Severity.CRITICAL,
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED,
                    "authentication failed: PGPASSWORD=s3cr3tvalue",
                    "set-1", T0, "failed", "Check configuration");

            assertThat(n.toMessage()).doesNotContain("s3cr3tvalue");
            assertThat(n.toJson()).doesNotContain("s3cr3tvalue");
        }

        @Test
        @DisplayName("ordinary text is not mangled")
        void ordinaryTextIsPreserved() {
            String text = "Newest backup is 45 minutes old; the approved RPO window "
                    + "is 60 minutes.";

            assertThat(RecoveryNotification.Redactor.redact(text)).isEqualTo(text);
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("delivery and deduplication")
    class Delivery {

        private final SteppingClock clock = new SteppingClock();
        private final LoggingNotificationProvider provider =
                new LoggingNotificationProvider();

        private RecoveryNotificationService service() {
            return new RecoveryNotificationService(List.of(provider), clock,
                    RecoveryNotification.defaultRepeatInterval());
        }

        @Test
        @DisplayName("a failure notification is delivered")
        void failureIsDelivered() {
            RecoveryNotificationService service = service();

            boolean delivered = service.notify(failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED, "pg_dump failed"));

            assertThat(delivered).isTrue();
            assertThat(provider.recorded()).hasSize(1);
            assertThat(service.stats().delivered()).isEqualTo(1);
        }

        @Test
        @DisplayName("an unresolved condition is suppressed within the repeat window")
        void repeatedConditionIsSuppressed() {
            RecoveryNotificationService service = service();
            RecoveryNotification n = failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED, "still failing");

            assertThat(service.notify(n)).isTrue();
            for (int i = 0; i < 5; i++) {
                assertThat(service.notify(n))
                        .as("a persistent outage must not alert repeatedly")
                        .isFalse();
            }
            assertThat(provider.recorded()).hasSize(1);
            assertThat(service.stats().suppressed()).isEqualTo(5);
        }

        @Test
        @DisplayName("the same condition alerts again once the repeat window passes")
        void alertsAgainAfterTheRepeatWindow() {
            RecoveryNotificationService service = service();
            RecoveryNotification n = failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED, "still failing");

            assertThat(service.notify(n)).isTrue();
            clock.advance(RecoveryNotification.defaultRepeatInterval().plusSeconds(1));

            assertThat(service.notify(n))
                    .as("a long outage must not be forgotten")
                    .isTrue();
            assertThat(provider.recorded()).hasSize(2);
        }

        @Test
        @DisplayName("a resolved condition re-arms alerting")
        void resolvedConditionRearmsAlerting() {
            RecoveryNotificationService service = service();
            RecoveryNotification n = failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED, "failed");

            service.notify(n);
            service.clear(RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED);

            assertThat(service.notify(n))
                    .as("a recurrence after recovery must alert immediately")
                    .isTrue();
            assertThat(provider.recorded()).hasSize(2);
        }

        @Test
        @DisplayName("different conditions do not suppress each other")
        void differentConditionsAreIndependent() {
            RecoveryNotificationService service = service();

            assertThat(service.notify(failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED, "a"))).isTrue();
            assertThat(service.notify(failure(
                    RecoveryNotification.Control.RECOVERY_VERIFICATION,
                    RecoveryNotification.Event.VERIFICATION_FAILED, "b"))).isTrue();
            assertThat(service.notify(failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_STALE, "c"))).isTrue();

            assertThat(provider.recorded()).hasSize(3);
        }

        @Test
        @DisplayName("a provider failure is contained and counted")
        void providerFailureIsContained() {
            RecoveryNotificationService service = new RecoveryNotificationService(
                    List.of(new RecoveryNotificationService.Provider() {
                        @Override
                        public void deliver(RecoveryNotification notification) {
                            throw new IllegalStateException("channel unavailable");
                        }

                        @Override
                        public String name() {
                            return "broken";
                        }
                    }),
                    clock, RecoveryNotification.defaultRepeatInterval());

            // Must not propagate: the caller is usually the backup scheduler.
            assertThat(service.notify(failure(
                    RecoveryNotification.Control.RECOVERY_BACKUP,
                    RecoveryNotification.Event.BACKUP_FAILED, "x")))
                    .isFalse();
            assertThat(service.stats().providerFailures()).isEqualTo(1);
        }

        @Test
        @DisplayName("the shipped provider reports that it does not reach a human")
        void logProviderDoesNotClaimDelivery() {
            RecoveryNotificationService service = service();

            assertThat(service.providerNames()).containsExactly("structured-log");
            assertThat(service.hasOutOfBandDelivery())
                    .as("a log line is a channel, not a notification to a person")
                    .isFalse();
            assertThat(service.stats().outOfBandDelivery()).isFalse();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("health to notification mapping")
    class Mapping {

        private final SteppingClock clock = new SteppingClock();
        private final LoggingNotificationProvider provider =
                new LoggingNotificationProvider();

        private RecoveryNotificationService service() {
            return new RecoveryNotificationService(List.of(provider), clock,
                    RecoveryNotification.defaultRepeatInterval());
        }

        private BackupHealth.Status status(BackupHealth health, Duration age,
                                            String setId, String reason) {
            return new BackupHealth.Status(health, T0, T0, age, setId, reason,
                    new ArrayList<>());
        }

        @Test
        @DisplayName("HEALTHY and RUNNING raise nothing")
        void healthyStatesRaiseNothing() {
            RecoveryNotificationService service = service();

            assertThat(service.notifyForHealth(status(BackupHealth.HEALTHY,
                    Duration.ofMinutes(5), "set-1", "fine"))).isEmpty();
            assertThat(service.notifyForHealth(status(BackupHealth.RUNNING,
                    Duration.ofSeconds(30), null, "running"))).isEmpty();
            assertThat(provider.recorded()).isEmpty();
        }

        @Test
        @DisplayName("STALE raises a critical stale notification")
        void staleRaisesNotification() {
            RecoveryNotificationService service = service();

            var sent = service.notifyForHealth(status(BackupHealth.STALE,
                    Duration.ofMinutes(95), "set-1", "newest backup is older than the RPO window"));

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).severity())
                    .isEqualTo(RecoveryNotification.Severity.CRITICAL);
            assertThat(sent.get(0).event())
                    .isEqualTo(RecoveryNotification.Event.BACKUP_STALE);
            assertThat(sent.get(0).subjectId()).isEqualTo("set-1");
        }

        @Test
        @DisplayName("no backup at all raises BACKUP_MISSING, not STALE")
        void missingBackupRaisesMissing() {
            RecoveryNotificationService service = service();

            var sent = service.notifyForHealth(status(BackupHealth.STALE,
                    null, null, "no backup set exists in the backup root"));

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).event())
                    .isEqualTo(RecoveryNotification.Event.BACKUP_MISSING);
        }

        @Test
        @DisplayName("RPO_AT_RISK raises a warning, not a critical")
        void rpoAtRiskRaisesWarning() {
            RecoveryNotificationService service = service();

            var sent = service.notifyForHealth(status(BackupHealth.RPO_AT_RISK,
                    Duration.ofMinutes(46), "set-1", "late"));

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).severity())
                    .isEqualTo(RecoveryNotification.Severity.WARNING);
            assertThat(sent.get(0).event())
                    .isEqualTo(RecoveryNotification.Event.RPO_AT_RISK);
            assertThat(sent.get(0).detail()).contains("60 minutes");
        }

        @Test
        @DisplayName("FAILED raises a critical backup failure")
        void failedRaisesCritical() {
            RecoveryNotificationService service = service();

            var sent = service.notifyForHealth(status(BackupHealth.FAILED,
                    Duration.ofMinutes(5), "set-1", "most recent attempt failed"));

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).severity())
                    .isEqualTo(RecoveryNotification.Severity.CRITICAL);
            assertThat(sent.get(0).event())
                    .isEqualTo(RecoveryNotification.Event.BACKUP_FAILED);
        }

        @Test
        @DisplayName("UNVERIFIABLE tells the operator not to restore")
        void unverifiableWarnsAgainstRestoring() {
            RecoveryNotificationService service = service();

            var sent = service.notifyForHealth(status(BackupHealth.UNVERIFIABLE,
                    Duration.ofMinutes(5), "set-1", "archive unreadable"));

            assertThat(sent).hasSize(1);
            assertThat(sent.get(0).event())
                    .isEqualTo(RecoveryNotification.Event.BACKUP_UNVERIFIABLE);
            assertThat(sent.get(0).action())
                    .as("the most important action is: do not restore from this set")
                    .contains("Do NOT restore");
        }

        @Test
        @DisplayName("recovery re-arms alerting for conditions raised while unhealthy")
        void recoveryRearmsAlerting() {
            RecoveryNotificationService service = service();
            var stale = status(BackupHealth.STALE, Duration.ofHours(2), "set-1", "late");

            assertThat(service.notifyForHealth(stale)).hasSize(1);
            // Same instant: would be suppressed if not for the health recovery.
            service.notifyForHealth(status(BackupHealth.HEALTHY, Duration.ofMinutes(1),
                    "set-1", "fine"));
            assertThat(service.notifyForHealth(stale))
                    .as("a fresh outage after recovery must alert again")
                    .hasSize(1);
        }
    }
}

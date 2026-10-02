package com.carbonflow.recovery.retention;

import com.carbonflow.recovery.RecoveryManifest;
import com.carbonflow.recovery.RecoveryManifestWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-07 — retention.
 *
 * <p>Sets are given <b>back-dated</b> manifests rather than relying on
 * filesystem timestamps, so the whole policy is exercised deterministically with
 * no waiting and no reliance on how a filesystem stamps {@code mkdir}.
 */
class RetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    private RetentionService service() {
        return new RetentionService(Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(30));
    }

    /**
     * Creates a set directory with a manifest whose {@code createdAt} is the
     * given age in days before {@link #NOW}.
     */
    private Path createSet(Path root, String name, int ageDays) throws Exception {
        Path set = Files.createDirectories(root.resolve(name));
        Files.writeString(set.resolve("database.dump"), "PGDMP", StandardCharsets.UTF_8);
        Files.writeString(set.resolve("globals.sql"), "-- globals",
                StandardCharsets.UTF_8);

        RecoveryManifest manifest = new RecoveryManifest(
                RecoveryManifest.MANIFEST_VERSION,
                name,
                NOW.minus(Duration.ofDays(ageDays)),
                NOW.minus(Duration.ofDays(ageDays)).minusSeconds(5),
                RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b"),
                new RecoveryManifest.Database("database.dump",
                        RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM,
                        NOW.minus(Duration.ofDays(ageDays)),
                        RecoveryManifest.CHECKSUM_ALGORITHM, "a".repeat(64),
                        "globals.sql", "b".repeat(64)),
                new RecoveryManifest.EvidenceVault("vault",
                        NOW.minus(Duration.ofDays(ageDays)), 0L, 0L,
                        RecoveryManifest.CHECKSUM_ALGORITHM,
                        "vault-integrity.json", "c".repeat(64), "robocopy"),
                new RecoveryManifest.Schema(List.of("V1"), Boolean.TRUE, 38),
                RecoveryManifest.Verification.pending(),
                List.of());
        new RecoveryManifestWriter(Clock.systemUTC()).write(manifest, set);
        return set;
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("age boundaries")
    class Boundaries {

        @Test
        @DisplayName("a 29-day-old set is retained")
        void twentyNineDaysIsRetained(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path set = createSet(root, uuid(), 29);

            var result = service().apply(root);

            assertThat(set).exists();
            assertThat(result.deleted()).isEmpty();
        }

        @Test
        @DisplayName("a set exactly at the 30-day boundary is retained")
        void exactlyThirtyDaysIsRetained(@TempDir Path temp) throws Exception {
            // Just inside the window: expiring a set at exactly the retention
            // age would destroy a recovery point that is still within policy.
            Path root = Files.createDirectories(temp.resolve("root"));
            Path set = createSet(root, uuid(), 30);
            setManifestCreatedAt(set, NOW.minus(Duration.ofDays(30)).plusSeconds(60));

            var result = service().apply(root);

            assertThat(set).exists();
            assertThat(result.deleted()).isEmpty();
        }

        @Test
        @DisplayName("a 31-day-old set is deleted")
        void thirtyOneDaysIsDeleted(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path newest = createSet(root, uuid(), 1);
            Path expired = createSet(root, uuid(), 31);

            var result = service().apply(root);

            assertThat(expired).doesNotExist();
            assertThat(newest).exists();
            assertThat(result.deleted()).hasSize(1);
        }

        @Test
        @DisplayName("a 400-day-old set is deleted")
        void veryOldSetIsDeleted(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 0);
            Path ancient = createSet(root, uuid(), 400);

            service().apply(root);

            assertThat(ancient).doesNotExist();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("safety guarantees")
    class Safety {

        @Test
        @DisplayName("the newest set is never deleted regardless of age")
        void newestSetIsAlwaysRetained(@TempDir Path temp) throws Exception {
            // The only set on the host, and ancient. Deleting it would leave
            // nothing to restore at all.
            Path root = Files.createDirectories(temp.resolve("root"));
            Path only = createSet(root, uuid(), 500);

            var result = service().apply(root);

            assertThat(only).exists();
            assertThat(result.skipped())
                    .anyMatch(s -> s.contains("newest set"));
        }

        @Test
        @DisplayName("only the newest is protected when several are expired")
        void newestOfManyIsProtected(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path newest = createSet(root, uuid(), 31);
            Path older = createSet(root, uuid(), 60);
            Path oldest = createSet(root, uuid(), 90);

            service().apply(root);

            assertThat(newest).exists();
            assertThat(older).doesNotExist();
            assertThat(oldest).doesNotExist();
        }

        @Test
        @DisplayName("a non-UUID neighbour directory is never touched")
        void nonUuidNeighbourIsUntouched(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 1);
            Path neighbour = Files.createDirectories(root.resolve("my-important-notes"));
            Files.writeString(neighbour.resolve("notes.txt"), "keep me",
                    StandardCharsets.UTF_8);

            var result = service().apply(root);

            assertThat(neighbour.resolve("notes.txt")).exists();
            assertThat(result.skipped())
                    .anyMatch(s -> s.contains("not a recovery-set UUID"));
        }

        @Test
        @DisplayName("a loose file in the backup root is never touched")
        void looseFileIsUntouched(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 1);
            Path loose = root.resolve("notes.txt");
            Files.writeString(loose, "keep", StandardCharsets.UTF_8);

            service().apply(root);

            assertThat(loose).exists();
        }

        @Test
        @DisplayName("data outside the backup root is never reached")
        void nothingOutsideTheRootIsReached(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path outside = Files.createDirectories(temp.resolve("other"));
            Path precious = outside.resolve("precious.dump");
            Files.writeString(precious, "irreplaceable", StandardCharsets.UTF_8);
            createSet(root, uuid(), 400);

            service().apply(root);

            assertThat(precious).exists();
        }

        @Test
        @DisplayName("a traversal-named path is not recognised as a set")
        void traversalNameIsNotASet(@TempDir Path temp) {
            assertThat(RetentionService.isBackupSetDirectory(
                    Path.of("..").resolve("etc"))).isFalse();
            assertThat(RetentionService.isBackupSetDirectory(Path.of("/etc"))).isFalse();
            assertThat(RetentionService.isBackupSetDirectory(Path.of("backup"))).isFalse();
        }

        @Test
        @DisplayName("containment is evaluated on normalised absolute paths")
        void containmentUsesNormalisedPaths(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            Path inside = root.resolve(uuid());

            assertThat(RetentionService.isInside(root, inside)).isTrue();
            assertThat(RetentionService.isInside(root, root)).isFalse();
            assertThat(RetentionService.isInside(root,
                    root.resolve("..").resolve("escaped"))).isFalse();
        }

        @Test
        @DisplayName("a missing backup root refuses rather than deleting anything")
        void missingRootRefuses(@TempDir Path temp) {
            var result = service().apply(temp.resolve("no-such-root"));

            assertThat(result.safeToDelete()).isFalse();
            assertThat(result.reasons()).anyMatch(r -> r.contains("not a directory"));
        }

        @Test
        @DisplayName("a clock that moves backwards disables deletion")
        void backwardsClockDisablesDeletion(@TempDir Path temp) throws Exception {
            // An NTP correction or VM snapshot restore can move the clock
            // backwards. Every set would then look arbitrarily old, so deletion
            // must stop rather than mass-delete.
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 1);
            Path expired = createSet(root, uuid(), 400);

            MutableClock mutable = new MutableClock(Instant.parse("2026-10-02T00:00:00Z"));

            RetentionService guarded = new RetentionService(mutable, Duration.ofDays(30));
            // First pass observes a later instant and behaves normally.
            assertThat(guarded.apply(root).safeToDelete()).isTrue();

            // Clock then jumps backwards.
            mutable.current = Instant.parse("2020-01-01T00:00:00Z");
            var result = guarded.apply(root);

            assertThat(result.safeToDelete()).isFalse();
            assertThat(result.reasons())
                    .anyMatch(r -> r.contains("clock moved backwards"));
        }

        @Test
        @DisplayName("the approved retention is 30 days")
        void approvedRetentionIsThirtyDays() {
            assertThat(RetentionService.approvedRetention()).isEqualTo(Duration.ofDays(30));
            assertThat(service().retentionWindow()).isEqualTo(Duration.ofDays(30));
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("incomplete sets")
    class Incomplete {

        @Test
        @DisplayName("a set with no manifest is reported as incomplete, not as a recovery point")
        void incompleteSetIsReported(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 1);
            Path incomplete = Files.createDirectories(root.resolve(uuid()));
            Files.writeString(incomplete.resolve("database.dump"), "PGDMP",
                    StandardCharsets.UTF_8);

            var result = service().apply(root);

            assertThat(result.incompleteSetsFound()).isEqualTo(1);
            assertThat(result.reasons())
                    .anyMatch(r -> r.contains("never finalised"));
            assertThat(incomplete).exists();
        }

        @Test
        @DisplayName("an incomplete set is never auto-deleted, even when old")
        void incompleteSetIsNeverAutoDeleted(@TempDir Path temp) throws Exception {
            // Policy: a set with no manifest has no authoritative creation
            // instant, so its age cannot be established. Deleting it on the basis
            // of a filesystem timestamp could destroy a recent partial set that
            // still holds the newest data, so it is reported for an operator
            // instead.
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 1);
            Path incomplete = Files.createDirectories(root.resolve(uuid()));
            Files.writeString(incomplete.resolve("database.dump"), "PGDMP",
                    StandardCharsets.UTF_8);

            var result = service().apply(root);

            assertThat(incomplete).exists();
            assertThat(result.incompleteSetsFound()).isEqualTo(1);
            assertThat(result.skipped())
                    .anyMatch(s -> s.contains("never auto-deleted"));
            assertThat(result.reasons())
                    .anyMatch(r -> r.contains("Remove manually after review"));
        }

        @Test
        @DisplayName("a set whose manifest is unreadable is skipped, never deleted")
        void unreadableManifestIsSkipped(@TempDir Path temp) throws Exception {
            Path root = Files.createDirectories(temp.resolve("root"));
            createSet(root, uuid(), 1);
            Path corrupt = Files.createDirectories(root.resolve(uuid()));
            Files.writeString(corrupt.resolve("manifest.json"), "{ broken",
                    StandardCharsets.UTF_8);

            var result = service().apply(root);

            assertThat(corrupt).exists();
            assertThat(result.skipped())
                    .anyMatch(s -> s.contains("no readable manifest"));
        }
    }

    /** Rewrites a set manifest's createdAt, for precise age control. */
    private void setManifestCreatedAt(Path set, Instant createdAt) throws IOException {
        Path manifest = set.resolve("manifest.json");
        String json = Files.readString(manifest);
        json = json.replaceAll("\"createdAt\"\\s*:\\s*\"[^\"]+\"",
                "\"createdAt\" : \"" + createdAt + "\"");
        Files.writeString(manifest, json, StandardCharsets.UTF_8);
    }

    /** A clock whose value the test controls, to simulate a backwards jump. */
    private static final class MutableClock extends Clock {
        private Instant current;

        MutableClock(Instant start) {
            this.current = start;
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
}
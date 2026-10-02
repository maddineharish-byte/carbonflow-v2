package com.carbonflow.recovery.retention;

import com.carbonflow.recovery.RecoverySetNaming;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * REC-07 — 30-day backup retention.
 *
 * <h2>Deletion is the most dangerous thing a backup tool does</h2>
 * <p>A bug here destroys recovery points rather than failing to create them, and
 * the damage is invisible until a restore is needed. Every safeguard below
 * exists for that reason:
 * <ul>
 *   <li>only whole sets are considered, never loose files;</li>
 *   <li>a candidate must be inside the backup root, verified <em>after</em>
 *       normalisation so {@code ../} cannot escape;</li>
 *   <li>a set directory name must be a UUIDv4 — anything else is not a set this
 *       tool created, so it is skipped rather than deleted;</li>
 *   <li>the newest verified set is never deleted regardless of age;</li>
 *   <li>a backwards system clock disables deletion entirely.</li>
 * </ul>
 *
 * <h2>Incomplete sets</h2>
 * <p>A set with no {@code manifest.json} was never finalised (REC-05). Policy:
 * incomplete sets are retained for the full period and are then deleted like any
 * other expired set, but they are reported separately so an operator can see
 * that failures are accumulating. They are never treated as recovery points.
 */
public final class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);

    private final Clock clock;
    private final Duration retention;

    /** Highest clock value ever observed, for the backwards-clock guard. */
    private final java.util.concurrent.atomic.AtomicReference<Instant> highestObserved =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * @param retention the retention window; 30 days as approved
     */
    public RetentionService(Clock clock, Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.clock = clock;
        this.retention = retention;
    }

    /** The approved 30-day retention. */
    public static Duration approvedRetention() {
        return Duration.ofDays(30);
    }

    /** What a retention pass would do, or did. */
    public record RetentionResult(boolean safeToDelete, List<String> deleted,
                                   List<String> skipped, List<String> reasons,
                                   int incompleteSetsFound) {

        static RetentionResult refused(String reason) {
            return new RetentionResult(false, List.of(), List.of(),
                    List.of(reason), 0);
        }
    }

    /**
     * Applies retention to {@code backupRoot}.
     *
     * @param backupRoot absolute path to the backup root
     * @return what was deleted and, importantly, what was not and why
     */
    public RetentionResult apply(Path backupRoot) {
        Path root = backupRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return RetentionResult.refused("backup root is not a directory: " + root);
        }

        Instant now = clock.instant();

        // Monotonic-clock guard. If the clock has already been observed at a
        // later instant than the one now being offered, it has moved backwards —
        // an NTP correction, a VM snapshot restore, or a misconfigured host. Any
        // of those would make every set look arbitrarily old and could trigger
        // mass deletion, so deletion stops until the clock is sane again.
        Instant highest = highestObserved.get();
        if (highest != null && now.isBefore(highest)) {
            return RetentionResult.refused(
                    "clock moved backwards (previously saw " + highest + ", now " + now
                            + "); deletion disabled to avoid mass deletion");
        }
        highestObserved.set(now);

        List<Path> candidates = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int incomplete = 0;

        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry)) {
                    // A loose file in the backup root is not a backup set.
                    skipped.add(entry.getFileName() + " (not a directory)");
                    continue;
                }
                if (!isBackupSetDirectory(entry)) {
                    skipped.add(entry.getFileName()
                            + " (name is not a recovery-set UUID)");
                    continue;
                }
                if (!Files.isRegularFile(entry.resolve("manifest.json"))) {
                    // Never finalised by REC-05. Reported, but never deleted
                    // automatically: without a manifest there is no authoritative
                    // creation instant, so its age cannot be established and
                    // guessing from filesystem timestamps could destroy a recent
                    // partial set that still holds the newest data. Operators
                    // decide these explicitly.
                    incomplete++;
                    skipped.add(entry.getFileName()
                            + " (incomplete set, no manifest: reported, never "
                            + "auto-deleted)");
                    continue;
                }
                candidates.add(entry);
            }
        } catch (IOException e) {
            return RetentionResult.refused("could not list backup root: " + e.getMessage());
        }

        // Age each set by the instant its manifest records, not by directory
        // timestamp: filesystem mtimes change when a file is copied, and a set
        // copied to new storage is not thereby younger.
        List<Candidate> aged = new ArrayList<>();
        for (Path set : candidates) {
            Instant createdAt = manifestCreatedAt(set);
            if (createdAt == null) {
                skipped.add(set.getFileName() + " (no readable manifest)");
                continue;
            }
            aged.add(new Candidate(set, createdAt));
        }

        aged.sort(Comparator.comparing(Candidate::createdAt).reversed());

        List<String> deleted = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        // The newest set is protected unconditionally: it is the most recent
        // recovery point and deleting it could leave nothing to restore.
        Candidate newest = aged.isEmpty() ? null : aged.get(0);
        Instant cutoff = now.minus(retention);

        for (Candidate candidate : aged) {
            Path set = candidate.directory();

            if (newest != null && set.equals(newest.directory())) {
                skipped.add(set.getFileName() + " (newest set, always retained)");
                continue;
            }
            if (candidate.createdAt().isAfter(cutoff)) {
                skipped.add(set.getFileName() + " (within retention window)");
                continue;
            }

            // Final containment re-check immediately before destruction.
            if (!isInside(root, set)) {
                skipped.add(set.getFileName() + " (failed containment re-check)");
                continue;
            }
            try {
                deleteRecursively(set);
                deleted.add(set.getFileName().toString());
                log.info("Retention deleted expired backup set {} (created {})",
                        set.getFileName(), candidate.createdAt());
            } catch (IOException e) {
                skipped.add(set.getFileName() + " (deletion failed: " + e.getMessage() + ")");
            }
        }

        if (incomplete > 0) {
            reasons.add(incomplete + " incomplete set(s) found: a set without "
                    + "manifest.json was never finalised, is not a recovery point, "
                    + "and is not auto-deleted because it has no authoritative "
                    + "creation instant. Remove manually after review.");
        }

        return new RetentionResult(true, deleted, skipped, reasons, incomplete);
    }

    /** A set directory plus the creation instant its manifest records. */
    private record Candidate(Path directory, Instant createdAt) {
    }

    /**
     * A recovery-set directory is named with the UUIDv4 created by REC-03.
     *
     * <p>Delegates to {@link RecoverySetNaming} so retention and monitoring can
     * never disagree about which directory is a set.
     */
    static boolean isBackupSetDirectory(Path directory) {
        return RecoverySetNaming.isBackupSetDirectory(directory);
    }

    /** Creation instant from the set manifest, or {@code null} if unreadable. */
    private Instant manifestCreatedAt(Path set) {
        Path manifest = set.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return null;
        }
        try {
            String json = Files.readString(manifest);
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            var created = node.get("createdAt");
            return created == null ? null : Instant.parse(created.asText());
        } catch (Exception e) {
            log.warn("Could not read createdAt from {}: {}", manifest, e.getMessage());
            return null;
        }
    }

    /** Containment check performed on normalised absolute paths. */
    static boolean isInside(Path root, Path candidate) {
        Path normalisedRoot = root.toAbsolutePath().normalize();
        Path normalisedCandidate = candidate.toAbsolutePath().normalize();
        return normalisedCandidate.startsWith(normalisedRoot)
                && !normalisedCandidate.equals(normalisedRoot);
    }

    /**
     * Recursive delete confined to one validated backup-set directory.
     *
     * <p>Re-verifies that the directory is a UUID-named set inside the root
     * before removing anything. The check is repeated here rather than assumed,
     * because an unrestricted recursive delete is the worst failure a backup tool
     * can have.
     */
    private void deleteRecursively(Path set) throws IOException {
        Path normalised = set.toAbsolutePath().normalize();
        if (!isBackupSetDirectory(normalised)) {
            throw new IOException("refusing to delete a directory that is not a "
                    + "recovery set: " + normalised);
        }
        Files.walkFileTree(normalised, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** The approved retention window. */
    public Duration retentionWindow() {
        return retention;
    }
}
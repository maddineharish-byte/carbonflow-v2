package com.carbonflow.recovery;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;

/**
 * Writes a {@link RecoveryManifest} into a backup set.
 *
 * <h2>"Manifest last", and what it actually means</h2>
 * <p>The approved sequence is quiesce → database → vault → checksums →
 * manifest. This class implements the last step only. Because the manifest is
 * the final artefact, a set whose backup was interrupted has no manifest and is
 * therefore not identifiable as a recovery set at all.
 *
 * <p>This is deliberately described as <b>completeness signalling</b>, not
 * immutability. Nothing here prevents an operator or an attacker from editing a
 * manifest after the fact, and no cryptographic mechanism is claimed. Making a
 * manifest tamper-evident requires signing (REC-09 territory), not ordering.
 *
 * <h2>Safe finalisation</h2>
 * <p>The file is written to a temporary sibling and then moved into place with
 * {@link StandardCopyOption#ATOMIC_MOVE}. A reader therefore observes either no
 * manifest or a complete one — never a half-written JSON document, which is a
 * particularly nasty failure during an incident because it looks like a
 * corrupt backup rather than an interrupted one.
 *
 * <h2>Not a Spring component</h2>
 * <p>This class carries no {@code @Component} and is not referenced by any
 * controller, so component scanning never instantiates it and the CarbonFlow
 * request path has no dependency on it. Recovery tooling is opt-in: the
 * application behaves identically whether or not this class is ever used.
 */
public final class RecoveryManifestWriter {

    /** Conventional file name, matching the design document. */
    public static final String MANIFEST_FILE_NAME = "manifest.json";

    private final ObjectMapper mapper;
    private final Clock clock;

    /** Uses the system UTC clock. */
    public RecoveryManifestWriter() {
        this(Clock.systemUTC());
    }

    /**
     * @param clock injected so tests are deterministic; production passes UTC
     */
    public RecoveryManifestWriter(Clock clock) {
        this.clock = clock;
        this.mapper = new ObjectMapper()
                // Explicitly registered rather than relying on Spring's
                // auto-configured mapper: this writer is used by standalone
                // operational tooling, where no Spring context exists. With the
                // module registered and WRITE_DATES_AS_TIMESTAMPS disabled,
                // Instants render as UTC ISO-8601 with a trailing Z.
                .registerModule(new JavaTimeModule())
                // Strict ISO-8601-only Instants, overriding the stock lenient
                // deserialiser. See StrictInstantDeserializer.
                .registerModule(new RecoveryManifestTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Refuse lenient coercion outright. Without this, Jackson
                // silently accepts a bare epoch number such as 1750000000 for
                // an enum. A recovery boundary must be an explicit,
                // unambiguous UTC timestamp — a document that parses under two
                // different readings is worse than one that fails to parse.
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                // Fixed key order via @JsonPropertyOrder plus this feature
                // guarantees byte-identical output for identical metadata, which
                // a later phase can hash. Determinism, not immutability.
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        this.mapper.setDefaultPrettyPrinter(
                new DefaultPrettyPrinter()
                        .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                        .withArrayIndenter(new DefaultIndenter("  ", "\n")));
        // Indented for a human reader during an incident. Auditability was
        // deliberately chosen over a few hundred bytes.
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Builds a manifest with a generated identity and the current UTC time.
     *
     * <p>The generated verification state is always
     * {@link VerificationStatus#PENDING}. There is deliberately no overload
     * that accepts a status, because nothing in this layer has checked anything
     * and offering the parameter would invite a caller to assert a result it
     * did not produce.
     *
     * @param boundaryAt recovery boundary — the earlier of the DB snapshot and
     *                   the vault copy instant
     * @param application application identity
     * @param database database backup metadata (produced by REC-03)
     * @param vault vault backup metadata (produced by REC-04)
     * @param schema schema identity, already read from the source database
     */
    public RecoveryManifest newManifest(Instant boundaryAt,
                                        RecoveryManifest.Application application,
                                        RecoveryManifest.Database database,
                                        RecoveryManifest.EvidenceVault vault,
                                        RecoveryManifest.Schema schema) {
        return new RecoveryManifest(
                RecoveryManifest.MANIFEST_VERSION,
                RecoveryManifest.newBackupSetId(),
                clock.instant(),
                boundaryAt,
                application,
                database,
                vault,
                schema,
                RecoveryManifest.Verification.pending(),
                java.util.List.of());
    }

    /**
     * Serialises a manifest to deterministic, human-readable JSON.
     *
     * <p>Indented rather than compact: during an incident an operator reads this
     * file, and auditability was chosen over a few hundred bytes.
     */
    public String toJson(RecoveryManifest manifest) {
        try {
            return mapper.writeValueAsString(manifest);
        } catch (IOException e) {
            throw new UncheckedIOException("Recovery manifest could not be serialised", e);
        }
    }

    /** Reads a manifest back. Used by REC-06 verification and REC-10 drills. */
    public RecoveryManifest fromJson(String json) {
        try {
            return mapper.readValue(json, RecoveryManifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Recovery manifest could not be parsed", e);
        }
    }

    /**
     * Writes {@code <setDir>/manifest.json} atomically.
     *
     * <p>Creates the set directory if absent. The directory itself is supplied
     * by the caller — REC-03/REC-04/REC-05 own where sets live, so this class
     * imposes no backup-root policy of its own.
     *
     * @return the path actually written
     */
    public Path write(RecoveryManifest manifest, Path setDirectory) {
        if (manifest == null) {
            throw ManifestValidationException.invalid("manifest", "is required");
        }
        if (setDirectory == null) {
            throw ManifestValidationException.invalid("setDirectory", "is required");
        }
        Path target = setDirectory.resolve(MANIFEST_FILE_NAME);
        Path temporary = setDirectory.resolve(MANIFEST_FILE_NAME + ".tmp");

        try {
            Files.createDirectories(setDirectory);
            Files.writeString(temporary, toJson(manifest),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Some network and container filesystems cannot move atomically.
                // Fall back rather than fail: the ordering guarantee that matters
                // (manifest written last) is unaffected, only the crash-safety
                // of the final rename degrades.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException("Recovery manifest could not be written to "
                    + target, e);
        }
    }

    /**
     * Reads a manifest from a set directory, or {@code null} if the set has none.
     *
     * <p>Returning {@code null} rather than throwing is deliberate: REC-06 needs
     * to distinguish "no manifest, therefore not a complete set" from "manifest
     * present but unreadable", which is a verification failure.
     */
    public RecoveryManifest readIfPresent(Path setDirectory) {
        Path target = setDirectory.resolve(MANIFEST_FILE_NAME);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            return fromJson(Files.readString(target));
        } catch (IOException e) {
            throw new UncheckedIOException("Recovery manifest could not be read from "
                    + target, e);
        }
    }
}
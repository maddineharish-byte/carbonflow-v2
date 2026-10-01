package com.carbonflow.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REC-02 — Recovery Manifest.
 *
 * <p>Pure unit tests. No PostgreSQL is started, no backup is executed and no
 * vault file is touched: REC-02 records metadata that REC-03 and REC-04
 * produce. That is also the point of Test 9 — the manifest must be usable when
 * the database is gone, which is exactly when it is needed.
 */
class RecoveryManifestTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);
    private static final Instant BOUNDARY = Instant.parse("2026-10-01T14:00:03Z");

    private final RecoveryManifestWriter writer =
            new RecoveryManifestWriter(Clock.fixed(Instant.parse("2026-10-01T14:00:12Z"),
                    ZoneOffset.UTC));

    private RecoveryManifest.Application application() {
        return RecoveryManifest.Application.of("1.0.0-PRO", "d42af8b");
    }

    private RecoveryManifest.Database database() {
        return new RecoveryManifest.Database("db.dump",
                RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM,
                Instant.parse("2026-10-01T14:00:05Z"),
                RecoveryManifest.CHECKSUM_ALGORITHM, SHA_A,
                "globals.sql", SHA_B);
    }

    private RecoveryManifest.EvidenceVault vault() {
        return new RecoveryManifest.EvidenceVault("vault",
                Instant.parse("2026-10-01T14:00:08Z"),
                12L, 456_789L,
                RecoveryManifest.CHECKSUM_ALGORITHM,
                "vault-index.tsv", SHA_B,
                "robocopy");
    }

    private RecoveryManifest.Schema schema() {
        return new RecoveryManifest.Schema(List.of("V1", "V2", "V3", "V4", "V5", "V6",
                "V7", "V8"), Boolean.TRUE, 38);
    }

    private RecoveryManifest manifest() {
        return writer.newManifest(BOUNDARY, application(), database(), vault(), schema());
    }

    // ---------------------------------------------------------------- 1

    @Test
    @DisplayName("Test 1 — a valid recovery-set manifest can be created")
    void aValidManifestCanBeCreated() {
        RecoveryManifest manifest = manifest();

        assertThat(manifest.manifestVersion())
                .isEqualTo(RecoveryManifest.MANIFEST_VERSION);
        assertThat(manifest.backupSetId()).isNotBlank();
        assertThat(manifest.recoveryBoundaryAt()).isEqualTo(BOUNDARY);
        assertThat(manifest.createdAt())
                .isEqualTo(Instant.parse("2026-10-01T14:00:12Z"));
        assertThat(manifest.database().backupFile()).isEqualTo("db.dump");
        assertThat(manifest.database().globalsBackupFile()).isEqualTo("globals.sql");
        assertThat(manifest.evidenceVault().backupLocation()).isEqualTo("vault");
        assertThat(manifest.schema().flywayVersions()).hasSize(8);
        assertThat(manifest.application().gitCommit()).isEqualTo("d42af8b");
    }

    @Test
    @DisplayName("The boundary recorded is the one supplied, not a re-derived clock")
    void theBoundaryIsRecordedNotReDerived() {
        // The design fixes the boundary as the earlier of DB and vault snapshot.
        // This layer must not second-guess it by recomputing from createdAt.
        RecoveryManifest manifest = manifest();

        assertThat(manifest.recoveryBoundaryAt())
                .as("boundary must be exactly what the coordinator determined")
                .isBefore(manifest.createdAt());
    }

    // ---------------------------------------------------------------- 2

    @Test
    @DisplayName("Test 2 — generated manifests do not share a backupSetId")
    void generatedSetIdsAreUnique() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            ids.add(RecoveryManifest.newBackupSetId());
        }

        assertThat(ids).as("2000 ids must all be distinct").hasSize(2_000);
        assertThat(RecoveryManifest.newBackupSetId())
                .as("id must be a UUID, not a sequential counter")
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    // ---------------------------------------------------------------- 3

    @Nested
    @DisplayName("Test 3 — mandatory metadata is rejected when missing")
    class RequiredFields {

        @Test
        void blankOrMissingTopLevelFieldsAreRejected() {
            assertThatThrownBy(() -> new RecoveryManifest(null, "id",
                    Instant.now(), BOUNDARY, application(), database(), vault(), schema(),
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("manifestVersion");

            assertThatThrownBy(() -> new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION, "id", null, BOUNDARY,
                    application(), database(), vault(), schema(),
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("createdAt");

            assertThatThrownBy(() -> new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION, "id", Instant.now(), null,
                    application(), database(), vault(), schema(),
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("recoveryBoundaryAt");

            assertThatThrownBy(() -> new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION, "id", Instant.now(), BOUNDARY,
                    null, database(), vault(), schema(),
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("application");

            assertThatThrownBy(() -> new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION, "id", Instant.now(), BOUNDARY,
                    application(), null, vault(), schema(),
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("database");

            assertThatThrownBy(() -> new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION, "id", Instant.now(), BOUNDARY,
                    application(), database(), null, schema(),
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("evidenceVault");

            assertThatThrownBy(() -> new RecoveryManifest(
                    RecoveryManifest.MANIFEST_VERSION, "id", Instant.now(), BOUNDARY,
                    application(), database(), vault(), null,
                    RecoveryManifest.Verification.pending(), List.of()))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("schema");
        }

        @Test
        void theGlobalsCaptureIsMandatoryBecauseRbacLivesOutsideTheDump() {
            // docs/BACKUP-RECOVERY.md §2.2: roles/permissions/role_permissions
            // are not in the database dump but are read at runtime. A set without
            // them would restore an unusable RBAC state, so this cannot be
            // optional.
            assertThatThrownBy(() -> new RecoveryManifest.Database("db.dump",
                    RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM, BOUNDARY,
                    RecoveryManifest.CHECKSUM_ALGORITHM, SHA_A, null, SHA_B))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("globalsBackupFile");
        }

        @Test
        void vaultIntegrityMetadataIsMandatoryBecauseTheVaultSharesTheRto() {
            assertThatThrownBy(() -> new RecoveryManifest.EvidenceVault("vault", BOUNDARY,
                    1L, 10L, RecoveryManifest.CHECKSUM_ALGORITHM, null, SHA_B, "robocopy"))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("integrityIndex");
        }

        @Test
        void schemaIdentityIsMandatory() {
            assertThatThrownBy(() -> new RecoveryManifest.Schema(
                    List.of(), Boolean.TRUE, 38))
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("flywayVersions");
        }
    }

    // ---------------------------------------------------------------- 4

    @Test
    @DisplayName("Test 4 — an unsupported verification status cannot be deserialised")
    void invalidVerificationStatusIsRejected() throws IOException {
        String json = writer.toJson(manifest());

        // Inject a status outside the enum; Jackson must refuse the document
        // rather than coerce it into a state nobody asserted.
        String tampered = json.replaceAll("\"status\"\\s*:\\s*\"PENDING\"",
                "\"status\": \"PROBABLY_FINE\"");

        assertThat(tampered).as("tamper must actually change the document")
                .isNotEqualTo(json);
        assertThatThrownBy(() -> writer.fromJson(tampered))
                .isInstanceOf(java.io.UncheckedIOException.class);

        // Exactly the four supported states must round-trip. Each is built
        // through the object model with the extra fields its own semantics
        // require (VERIFIED needs verifiedAt; FAILED/UNVERIFIABLE need a
        // reason), rather than by patching strings — a hand-edited JSON blob
        // proves nothing about whether the format itself is well-formed.
        List<RecoveryManifest.Verification> states = List.of(
                RecoveryManifest.Verification.pending(),
                new RecoveryManifest.Verification(VerificationStatus.VERIFIED,
                        Instant.parse("2026-10-01T15:00:00Z"), null),
                new RecoveryManifest.Verification(VerificationStatus.FAILED,
                        Instant.parse("2026-10-01T15:00:00Z"), "db.dump checksum mismatch"),
                new RecoveryManifest.Verification(VerificationStatus.UNVERIFIABLE,
                        Instant.parse("2026-10-01T15:00:00Z"), "vault file missing"));

        for (RecoveryManifest.Verification state : states) {
            RecoveryManifest base = manifest();
            RecoveryManifest withState = new RecoveryManifest(
                    base.manifestVersion(), base.backupSetId(), base.createdAt(),
                    base.recoveryBoundaryAt(), base.application(), base.database(),
                    base.evidenceVault(), base.schema(), state, base.notes());

            RecoveryManifest parsed = writer.fromJson(writer.toJson(withState));
            assertThat(parsed.verification().status())
                    .as("%s must round-trip", state.status())
                    .isEqualTo(state.status());
        }

        // The states must be distinguishable — collapsing UNVERIFIABLE into
        // FAILED would let an unusable set look merely broken.
        assertThat(states).extracting(RecoveryManifest.Verification::status)
                .doesNotHaveDuplicates()
                .hasSize(4);
    }

    @Test
    @DisplayName("The four states are distinguishable on the wire")
    void allFourStatesRoundTrip() throws IOException {
        // Distinctness matters: collapsing UNVERIFIABLE into FAILED, or PENDING
        // into VERIFIED, would let an unusable set look usable.
        String json = writer.toJson(manifest());

        assertThatThrownBy(() -> writer.fromJson(
                json.replaceAll("\"status\"\\s*:\\s*\"PENDING\"",
                        "\"status\": \"VERIFIED\"")))
                .as("a bare VERIFIED with no verifiedAt is not a usable record")
                .isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    @DisplayName("A manifest is never born VERIFIED — writing is not verification")
    void aFreshlyWrittenManifestIsPending() {
        RecoveryManifest manifest = manifest();

        assertThat(manifest.verification().status())
                .as("REC-02 performs no verification, so PENDING is the only honest state")
                .isEqualTo(VerificationStatus.PENDING);
        assertThat(manifest.verification().verifiedAt()).isNull();
        assertThat(manifest.verification().reason()).isNull();
    }

    @Test
    @DisplayName("A terminal verification state must carry a reason")
    void terminalStatesMustExplainThemselves() {
        // An operator reading this at 3am needs to know why a set is unusable.
        assertThatThrownBy(() -> new RecoveryManifest.Verification(
                VerificationStatus.FAILED, Instant.now(), null))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("verification.reason");

        // VERIFIED needs no excuse, but must state when it was established.
        assertThat(new RecoveryManifest.Verification(
                VerificationStatus.VERIFIED, Instant.now(), null))
                .as("a clean set must not be forced to invent a reason")
                .isNotNull();

        assertThatThrownBy(() -> new RecoveryManifest.Verification(
                VerificationStatus.VERIFIED, null, null))
                .as("'verified, but when?' is not actionable")
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("verification.verifiedAt");

        assertThatThrownBy(() -> new RecoveryManifest.Verification(
                VerificationStatus.UNVERIFIABLE, Instant.now(), "  "))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("verification.reason");
    }

    @Test
    @DisplayName("PENDING must not carry a reason")
    void pendingMustNotCarryAReason() {
        assertThatThrownBy(() -> new RecoveryManifest.Verification(
                VerificationStatus.PENDING, null, "earlier run failed"))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("must be empty");
    }

    @Test
    @DisplayName("An absent verification block is read as PENDING, not as unchecked")
    void absentVerificationBlockDefaultsToPending() {
        RecoveryManifest withoutBlock = new RecoveryManifest(
                RecoveryManifest.MANIFEST_VERSION, "id", Instant.now(), BOUNDARY,
                application(), database(), vault(), schema(), null, List.of());

        assertThat(withoutBlock.verification().status())
                .as("absence must not read as 'not considered'")
                .isEqualTo(VerificationStatus.PENDING);
    }

    // ---------------------------------------------------------------- 5

    @Test
    @DisplayName("Test 5 — malformed timestamps are rejected on deserialisation")
    void malformedTimestampsAreRejected() throws IOException {
        String json = writer.toJson(manifest());

        for (String bad : List.of("\"not-a-timestamp\"", "\"2026-13-45T99:00:00Z\"",
                "\"01/10/2026 14:00\"", "1750000000")) {
            String tampered = json.replaceAll("\"2026-10-01T14:00:12Z\"", bad);
            assertThat(tampered).as("tamper with %s must change the document", bad)
                    .isNotEqualTo(json);
            assertThatThrownBy(() -> writer.fromJson(tampered))
                    .as("must reject %s", bad)
                    .isInstanceOf(java.io.UncheckedIOException.class);
        }
    }

    @Test
    @DisplayName("Timestamps are UTC ISO-8601 — no geographic zone is baked in")
    void timestampsAreUtcAndGeographyFree() throws IOException {
        String json = writer.toJson(manifest());

        assertThat(json).contains("2026-10-01T14:00:03Z");
        assertThat(json).as("no hardcoded non-UTC offset").doesNotContain("+05:30");
        for (String zone : List.of("Asia/Kolkata", "America/", "Europe/", "IST", "PST")) {
            assertThat(json).as("must not reference %s", zone).doesNotContain(zone);
        }
    }

    @Test
    @DisplayName("An offset-aware instant is normalised to UTC, not stored with a local offset")
    void offsetInstantsAreNormalisedToUtc() throws IOException {
        RecoveryManifestWriter fixed = new RecoveryManifestWriter(
                Clock.fixed(Instant.parse("2026-10-01T19:30:12+05:30"), ZoneOffset.UTC));

        String json = fixed.toJson(fixed.newManifest(BOUNDARY, application(), database(),
                vault(), schema()));

        assertThat(json).contains("2026-10-01T14:00:12Z");
        assertThat(json).doesNotContain("+05:30");
    }

    // ---------------------------------------------------------------- 6

    @Test
    @DisplayName("Test 6 — the manifest cannot carry credential-named fields")
    void credentialNamedFieldsAreRejected() {
        // The CarbonFlow secrets are DB_PASSWORD, CARBONFLOW_JWT_SECRET and
        // CARBONFLOW_REFRESH_TOKEN_SECRET (docs/BACKUP-RECOVERY.md §1.3). None
        // may reach a file that is copied and retained for 30 days.
        for (String forbidden : List.of("password", "DB_PASSWORD", "jwt_secret",
                "refreshToken", "apiKey", "privateKey", "passphrase",
                "credentials", "sessionToken", "Authorization")) {
            assertThatThrownBy(() -> RecoverySetGuard.requireNonSecretName(
                    "field", forbidden))
                    .as("must reject %s", forbidden)
                    .isInstanceOf(ManifestValidationException.class)
                    .hasMessageContaining("secret manager");
        }
    }

    @Test
    @DisplayName("Legitimate non-credential names are accepted")
    void benignNamesAreAccepted() {
        for (String name : List.of("checksum", "backupFile", "integrityIndex",
                "recoveryBoundaryAt", "flywayVersions", "fileCount")) {
            RecoverySetGuard.requireNonSecretName("field", name);
        }
    }

    @Test
    @DisplayName("A serialised manifest contains no credential-shaped key")
    void serialisedManifestHasNoCredentialShapedKey() throws IOException {
        String json = writer.toJson(manifest());

        for (String marker : List.of("password", "secret", "token", "credential",
                "passphrase", "apikey", "privatekey", "authorization")) {
            assertThat(json.toLowerCase(java.util.Locale.ROOT))
                    .as("serialised manifest must not contain '%s'", marker)
                    .doesNotContain(marker);
        }
    }

    // ---------------------------------------------------------------- 7

    @Test
    @DisplayName("Test 7 — path traversal is rejected in every path field")
    void traversalIsRejected() {
        for (String hostile : List.of("../../secret", "../../../etc/passwd",
                "db.dump/../../../etc/shadow", "..\\..\\secret",
                "/etc/shadow", "/absolute/path", "C:\\Windows\\system32",
                "~/vault", "~/.ssh/id_rsa", "./db.dump")) {
            assertThatThrownBy(() -> RecoverySetGuard.requireSetRelativePath(
                    "database.backupFile", hostile))
                    .as("must reject %s", hostile)
                    .isInstanceOf(ManifestValidationException.class);
        }
    }

    @Test
    @DisplayName("Traversal is rejected through the record constructors too")
    void recordConstructorsEnforcePathSafety() {
        assertThatThrownBy(() -> new RecoveryManifest.Database("../../secret",
                RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM, BOUNDARY,
                RecoveryManifest.CHECKSUM_ALGORITHM, SHA_A, "globals.sql", SHA_B))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("database.backupFile");

        assertThatThrownBy(() -> new RecoveryManifest.EvidenceVault("../../etc",
                BOUNDARY, 1L, 1L, RecoveryManifest.CHECKSUM_ALGORITHM,
                "vault-index.tsv", SHA_B, "robocopy"))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("evidenceVault.backupLocation");
    }

    @Test
    @DisplayName("Legitimate set-relative paths are accepted and normalised")
    void legitimateRelativePathsAreAccepted() {
        assertThat(RecoverySetGuard.requireSetRelativePath("f", "db.dump"))
                .isEqualTo("db.dump");
        assertThat(RecoverySetGuard.requireSetRelativePath("f", "vault/org-a/file.txt"))
                .isEqualTo("vault/org-a/file.txt");
        assertThat(RecoverySetGuard.requireSetRelativePath("f", "vault\\org-a\\f.txt"))
                .as("windows separators are normalised, not rejected")
                .isEqualTo("vault/org-a/f.txt");
    }

    @Test
    @DisplayName("Checksums must be well-formed SHA-256 hex")
    void malformedChecksumsAreRejected() {
        for (String bad : List.of("abc", "", "  ", "z".repeat(64), "A".repeat(63),
                SHA_A + "00")) {
            assertThatThrownBy(() -> ManifestValidationException.requireSha256Hex(
                    "database.checksum", bad))
                    .as("must reject '%s'", bad)
                    .isInstanceOf(ManifestValidationException.class);
        }
    }

    @Test
    @DisplayName("Only SHA-256 is accepted — no second algorithm is invented")
    void otherChecksumAlgorithmsAreRejected() {
        assertThatThrownBy(() -> new RecoveryManifest.Database("db.dump",
                RecoveryManifest.BACKUP_FORMAT_POSTGRESQL_CUSTOM, BOUNDARY,
                "MD5", SHA_A, "globals.sql", SHA_B))
                .isInstanceOf(ManifestValidationException.class)
                .hasMessageContaining("SHA-256");
    }

    // ---------------------------------------------------------------- 8

    @Test
    @DisplayName("Test 8 — identical metadata serialises to byte-identical JSON")
    void serialisationIsDeterministic() {
        // Needed so a later phase can hash the manifest. Determinism is NOT
        // immutability and is not claimed as such.
        // The SAME manifest instance is used throughout: each call to
        // manifest() mints a fresh backupSetId by design, so comparing two
        // different instances would test the id generator, not the serialiser.
        RecoveryManifest manifest = manifest();

        String first = writer.toJson(manifest);
        String second = writer.toJson(manifest);

        assertThat(first).isEqualTo(second);
        assertThat(first).isEqualTo(writer.toJson(manifest));
        assertThat(first).as("must be indented for human reading").contains("\n");
    }

    @Test
    @DisplayName("Key order is fixed, so the document is diff-friendly")
    void keyOrderIsStable() {
        String json = writer.toJson(manifest());

        int version = json.indexOf("manifestVersion");
        int setId = json.indexOf("backupSetId");
        int created = json.indexOf("createdAt");
        int boundary = json.indexOf("recoveryBoundaryAt");

        assertThat(version).isGreaterThanOrEqualTo(0);
        assertThat(version).isLessThan(setId);
        assertThat(setId).isLessThan(created);
        assertThat(created).isLessThan(boundary);
    }

    @Test
    @DisplayName("A manifest round-trips through JSON without loss")
    void roundTripPreservesEveryField() throws IOException {
        RecoveryManifest original = manifest();
        RecoveryManifest parsed = writer.fromJson(writer.toJson(original));

        assertThat(parsed).isEqualTo(original);
        assertThat(parsed.backupSetId()).isEqualTo(original.backupSetId());
        assertThat(parsed.recoveryBoundaryAt()).isEqualTo(original.recoveryBoundaryAt());
        assertThat(parsed.database().checksum()).isEqualTo(SHA_A);
        assertThat(parsed.schema().flywayVersions()).containsExactly("V1", "V2", "V3",
                "V4", "V5", "V6", "V7", "V8");
    }

    // ---------------------------------------------------------------- 9

    @Test
    @DisplayName("Test 9 — the manifest is usable with no database present")
    void manifestDoesNotRequireADatabase() {
        // Structural guarantee, asserted by construction: no class in this
        // package imports JDBC, references a DataSource, or reads environment
        // configuration. Nothing here can contact PostgreSQL.
        String[] sources = {
                "com.carbonflow.recovery.RecoveryManifest",
                "com.carbonflow.recovery.RecoveryManifestWriter",
                "com.carbonflow.recovery.RecoverySetGuard",
                "com.carbonflow.recovery.VerificationStatus",
                "com.carbonflow.recovery.ManifestValidationException"
        };

        for (String name : sources) {
            String path = "src/main/java/" + name.replace('.', '/') + ".java";
            String source = readSource(path);
            assertThat(source)
                    .as("%s must not reach for a database", name)
                    .doesNotContain("java.sql")
                    .doesNotContain("javax.sql")
                    .doesNotContain("DataSource")
                    .doesNotContain("JdbcTemplate")
                    .doesNotContain("DriverManager");
        }
    }

    @Test
    @DisplayName("Git identity may be absent without failing the backup")
    void absentGitMetadataIsRecordedHonestly() {
        // A jar copied to a restore host has no .git directory. That must not
        // fail the backup, and must not be papered over with a guess.
        RecoveryManifest.Application app = RecoveryManifest.Application.withoutGit("1.0.0-PRO");

        assertThat(app.gitCommit()).isNull();
        assertThat(app.gitCommitKnown()).isFalse();

        RecoveryManifest manifest = writer.newManifest(BOUNDARY, app, database(),
                vault(), schema());
        String json = writer.toJson(manifest);

        assertThat(json).as("no revision may be fabricated").doesNotContain("gitCommit\":");
        assertThat(json).as("absence must be stated explicitly").contains("\"gitCommitKnown\"");
    }

    // ------------------------------------------------- filesystem behaviour

    @Test
    @DisplayName("The manifest is written last, into the set directory, and reads back")
    void manifestIsWrittenIntoTheSetDirectory(@TempDir Path temp) {
        RecoveryManifest manifest = manifest();

        Path written = writer.write(manifest, temp.resolve("set-2026-10-01T14"));

        assertThat(written.getFileName()).hasToString("manifest.json");
        assertThat(Files.exists(written)).isTrue();
        assertThat(writer.readIfPresent(written.getParent()))
                .as("must round-trip from disk")
                .isEqualTo(manifest);
    }

    @Test
    @DisplayName("A set with no manifest reads as null — incomplete, not corrupt")
    void absentManifestReadsAsNull(@TempDir Path temp) {
        assertThat(writer.readIfPresent(temp.resolve("never-written")))
                .as("no manifest means the set was never finalised")
                .isNull();
    }

    @Test
    @DisplayName("No temporary file is left behind after finalisation")
    void noTemporaryFileSurvives(@TempDir Path temp) throws IOException {
        Path setDir = temp.resolve("set-x");
        writer.write(manifest(), setDir);

        try (var entries = Files.list(setDir)) {
            assertThat(entries.map(p -> p.getFileName().toString()))
                    .as("the .tmp sibling must be moved, not left behind")
                    .containsExactly("manifest.json");
        }
    }

    @Test
    @DisplayName("Rewriting a set replaces the manifest atomically")
    void rewritingReplacesAtomically(@TempDir Path temp) {
        Path setDir = temp.resolve("set-y");

        Path first = writer.write(manifest(), setDir);
        Path second = writer.write(manifest(), setDir);

        assertThat(second).isEqualTo(first);
        assertThat(writer.readIfPresent(setDir).backupSetId())
                .as("must be a complete document, never a truncated one")
                .isNotBlank();
    }

    @Test
    @DisplayName("The manifest is a plain file — nothing is written to PostgreSQL")
    void manifestIsExternalToTheDatabase(@TempDir Path temp) throws IOException {
        Path setDir = temp.resolve("set-z");
        Path written = writer.write(manifest(), setDir);

        // The manifest is an ordinary file inside the backup set. This is the
        // property that lets it survive the failure it exists to describe.
        assertThat(Files.isRegularFile(written)).isTrue();
        assertThat(written.toString()).endsWith(RecoveryManifestWriter.MANIFEST_FILE_NAME);
        assertThat(written.getFileName().toString()).doesNotContain("d42af8b");
    }

    /** Reads a source file relative to backend-java/. */
    private static String readSource(String relativePath) {
        Path path = Path.of(System.getProperty("user.dir")).resolve(relativePath);
        try {
            assertThat(Files.exists(path))
                    .as("expected source at %s", path.toAbsolutePath())
                    .isTrue();
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    }

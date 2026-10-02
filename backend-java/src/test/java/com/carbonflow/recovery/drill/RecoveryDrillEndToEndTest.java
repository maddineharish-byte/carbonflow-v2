package com.carbonflow.recovery.drill;

import com.carbonflow.recovery.coordination.QuiesceGuard;
import com.carbonflow.recovery.coordination.RecoverySetCoordinator;
import com.carbonflow.recovery.crypto.BackupEncryptionService;
import com.carbonflow.recovery.postgres.PostgreSqlBackupTarget;
import com.carbonflow.recovery.postgres.PostgreSqlToolLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REC-10 — end-to-end drill evidence.
 *
 * <p>Prints the machine-readable drill record so a reviewer can read the actual
 * checks, durations and limitations that were produced by a real restore, rather
 * than taking a pass/fail on trust. The assertions are strict: this drill must
 * genuinely PASS, including evidence SHA-256 recovery and tenant isolation.
 */
class RecoveryDrillEndToEndTest {

    private static PostgreSqlBackupTarget server;
    private static Path gpg;

    @BeforeAll
    static void detect() {
        server = ServerProbe.matchingServer();
        gpg = ServerProbe.findTool("gpg");
    }

    static boolean toolingPresent() {
        return ServerProbe.tooling() && server != null;
    }

    @Test
    @DisplayName("a real CarbonFlow backup is restored and every drill check passes")
    @EnabledIf("com.carbonflow.recovery.drill.RecoveryDrillEndToEndTest#toolingPresent")
    void drillPassesEndToEnd(@TempDir Path temp) throws Exception {
        String content = "CARBONFLOW EVIDENCE DOCUMENT\n";
        Path vault = Files.createDirectories(temp.resolve("vault-storage"));
        Path evidenceFile = vault.resolve("org-a/1_abc_cert.pdf");
        Files.createDirectories(evidenceFile.getParent());
        Files.writeString(evidenceFile, content, StandardCharsets.UTF_8);
        String digest = hex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));

        String source = "carbonflow_src_" + UUID.randomUUID().toString().substring(0, 8);
        String recovery = "carbonflow_drill_" + UUID.randomUUID().toString().substring(0, 8);

        try {
            createSourceDatabase(source, digest, evidenceFile.toString(), content.length());

            Map<String, String> env = ServerProbe.toolEnvironment();
            var coordinator = new RecoverySetCoordinator(
                    com.carbonflow.recovery.postgres.PostgreSqlBackupService
                            .productionDefaults(),
                    com.carbonflow.recovery.vault.EvidenceVaultBackupService
                            .productionDefaults(),
                    new com.carbonflow.recovery.RecoveryManifestWriter(
                            java.time.Clock.systemUTC()),
                    java.time.Clock.systemUTC());

            var backup = coordinator.run(new RecoverySetCoordinator.Request(
                    Files.createDirectories(temp.resolve("backups")), vault,
                    target(source), env,
                    List.of(new com.carbonflow.recovery.vault.EvidenceVaultBackupService
                            .RequiredFile(evidenceFile.toString(), digest, content.length())),
                    com.carbonflow.recovery.RecoveryManifest.Application.of(
                            "1.0.0-PRO", "d42af8b"),
                    new com.carbonflow.recovery.RecoveryManifest.Schema(
                            List.of("1"), Boolean.TRUE, 5), "robocopy"),
                    new QuiesceGuard.NoOp());

            assertThat(backup.success())
                    .as("coordinated backup must succeed: %s", backup.failureReason())
                    .isTrue();

            // REC-09 in the loop: encrypt the set, then drill the decrypted copy.
            // This proves encryption does not damage restorability.
            String keyId = ServerProbe.encryptionKeyId(gpg);
            if (keyId != null) {
                BackupEncryptionService crypto =
                        new BackupEncryptionService(
                                BackupEncryptionService.Method.GPG, gpg,
                                Map.of("GNUPGHOME", ServerProbe.gpgHome()));
                var dbMeta = crypto.encrypt(
                        backup.setDirectory().resolve("database.dump"), keyId);
                assertThat(dbMeta).isNotNull();
                assertThat(backup.setDirectory().resolve("database.dump"))
                        .as("plaintext dump must be gone after encryption")
                        .doesNotExist();
                crypto.decrypt(backup.setDirectory().resolve(dbMeta.artefactName()),
                        keyId, temp.resolve("decrypted"));
                Files.copy(temp.resolve("decrypted").resolve("database.dump"),
                        backup.setDirectory().resolve("database.dump"));
                System.out.println("REC-09 encrypt/decrypt round trip inside the drill: OK");
            } else {
                System.out.println("REC-09: gpg key unavailable, encryption step SKIPPED "
                        + "and encryption is reported NOT VERIFIED");
            }

            DrillResult result = new RecoveryDrill(java.time.Clock.systemUTC(), env)
                    .run(backup.setDirectory(), server, recovery,
                            temp.resolve("restored-vault"), vault);

            System.out.println(RecoveryDrill.toJson(result));

            assertThat(result.checks()).allSatisfy(check -> assertThat(check.passed())
                    .as("check '%s' failed: %s", check.name(), check.detail())
                    .isTrue());
            assertThat(result.passed()).as("findings: %s", result.findings()).isTrue();
            assertThat(result.findings()).isEmpty();

            // Evidence must have been recovered and hash-verified.
            DrillResult.Check evidenceCheck = result.checks().stream()
                    .filter(c -> c.name().equals("evidence.sha256")).findFirst()
                    .orElseThrow();
            assertThat(evidenceCheck.detail())
                    .as("evidence recovery must be proven, not assumed")
                    .contains("SHA-256 matched")
                    .contains("1 evidence file(s)");

            // The restored vault must contain the original bytes.
            assertThat(Files.readString(temp.resolve("restored-vault")
                    .resolve("org-a/1_abc_cert.pdf"))).isEqualTo(content);

            // Tenant isolation must have been verified.
            assertThat(result.checks()).extracting(DrillResult.Check::name)
                    .contains("tenant.isolation", "tenant.separation",
                            "schema.flyway", "data.rows", "data.integrity");

            assertThat(result.durations().totalRecovery()).isNotNull();
            System.out.println("MEASURED total recovery duration: "
                    + result.durations().totalRecovery().toSeconds() + "s");
        } finally {
            ServerProbe.dropDatabase(recovery);
            ServerProbe.dropDatabase(source);
        }
    }

    // ------------------------------------------------------------------

    private static PostgreSqlBackupTarget target(String database) {
        return new PostgreSqlBackupTarget(server.host(), server.port(), database,
                server.username(), server.password(), server.sslMode());
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static void createSourceDatabase(String name, String digest,
                                             String storagePath, long bytes)
            throws Exception {
        try (Connection admin = connect(server, "postgres");
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE \"" + name + "\"");
        }
        try (Connection connection = connect(server, name);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE organizations (id UUID PRIMARY KEY, "
                    + "name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE users (id UUID PRIMARY KEY, "
                    + "organization_id UUID REFERENCES organizations(id), "
                    + "email VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE evidence_records (id UUID PRIMARY KEY, "
                    + "organization_id UUID NOT NULL REFERENCES organizations(id), "
                    + "file_name VARCHAR(255) NOT NULL, file_size_bytes BIGINT NOT NULL, "
                    + "mime_type VARCHAR(100) NOT NULL, sha256_hash VARCHAR(64) NOT NULL, "
                    + "storage_path VARCHAR(500) NOT NULL)");
            statement.execute("CREATE TABLE activity_data (id UUID PRIMARY KEY, "
                    + "organization_id UUID NOT NULL REFERENCES organizations(id), "
                    + "name VARCHAR(255) NOT NULL)");
            statement.execute("CREATE TABLE carbon_audits (id UUID PRIMARY KEY, "
                    + "organization_id UUID NOT NULL REFERENCES organizations(id), "
                    + "status VARCHAR(20) NOT NULL)");
            statement.execute("CREATE TABLE evidence_versions (id UUID PRIMARY KEY, "
                    + "evidence_record_id UUID NOT NULL REFERENCES evidence_records(id), "
                    + "version_number INT NOT NULL, sha256_hash VARCHAR(64) NOT NULL, "
                    + "storage_path VARCHAR(500) NOT NULL)");
            statement.execute("CREATE TABLE flyway_schema_history ("
                    + "installed_rank INT PRIMARY KEY, version VARCHAR(50), "
                    + "description VARCHAR(200), success BOOLEAN)");
            statement.execute("INSERT INTO flyway_schema_history VALUES "
                    + "(1, '1', 'initial schema', true)");
            // Parents before children: the child tables carry NOT NULL foreign
            // keys to organizations.
            statement.execute("INSERT INTO organizations VALUES ('"
                    + UUID.randomUUID() + "', 'Acme Global'), ('"
                    + UUID.randomUUID() + "', 'Beta Foods')");
            statement.execute("INSERT INTO activity_data VALUES ('" + UUID.randomUUID()
                    + "', (select id from organizations limit 1), 'Natural gas')");
            statement.execute("INSERT INTO carbon_audits VALUES ('" + UUID.randomUUID()
                    + "', (select id from organizations limit 1), 'LOCKED')");
            statement.execute("INSERT INTO users VALUES ('" + UUID.randomUUID()
                    + "', (select id from organizations limit 1), 'a@acme.invalid')");
            statement.execute("INSERT INTO evidence_records VALUES ('"
                    + UUID.randomUUID() + "', (select id from organizations limit 1), "
                    + "'cert.pdf', " + bytes + ", 'application/pdf', '" + digest
                    + "', '" + storagePath.replace("'", "''") + "')");
        }
    }

    private static Connection connect(PostgreSqlBackupTarget target, String database)
            throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://" + target.host() + ":" + target.port() + "/" + database,
                target.username(), new String(target.password()));
    }

    @SuppressWarnings("unused")
    private static List<String> unusedGuards() {
        return List.of();
    }
}
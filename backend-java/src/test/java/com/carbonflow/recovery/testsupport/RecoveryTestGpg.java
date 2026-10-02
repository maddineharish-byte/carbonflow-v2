package com.carbonflow.recovery.testsupport;

import com.carbonflow.recovery.exec.SafeProcessRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * A throwaway GnuPG key pair for recovery tests.
 *
 * <p>Isolated in a temporary {@code GNUPGHOME} so a test never touches the
 * operator's real keyring, and generated with no passphrase so it is usable for
 * automation. No key material is ever written to the repository.
 *
 * <h2>Environment quirks handled here</h2>
 * <ul>
 *   <li>Git's bundled gpg mangles a {@code C:\...} {@code --homedir} path, so
 *       the home must be pre-created and addressed in POSIX form.</li>
 *   <li>An ed25519 key cannot encrypt, so an RSA encryption subkey is added.
 *       Generating a key directly with {@code encr} usage fails intermittently
 *       on this host with "No agent running".</li>
 * </ul>
 *
 * <p>Returns {@code null} when no key could be produced, so callers skip and
 * report the capability as NOT VERIFIED rather than passing falsely.
 */
public final class RecoveryTestGpg {

    private static Path gpgPath;
    private static String keyId;
    private static String home;

    private RecoveryTestGpg() {
    }

    /** The gpg executable, or {@code null} when unavailable. */
    public static synchronized Path gpg() {
        if (gpgPath == null) {
            gpgPath = RecoveryTestEnvironment.findTool("gpg");
        }
        return gpgPath;
    }

    /** The generated key fingerprint, or {@code null} when unavailable. */
    public static synchronized String encryptionKeyId(Path gpg) {
        if (gpg == null || gpgPath == null) {
            return null;
        }
        if (keyId != null || home != null) {
            return keyId;
        }
        try {
            Path homeDir = Files.createTempDirectory("carbonflow-rec-gnupg");
            home = RecoveryTestEnvironment.toPosix(homeDir.toString());

            SafeProcessRunner.Result keygen = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(gpgPath.toString())
                            .arg("--batch").arg("--homedir").arg(home)
                            .arg("--passphrase").arg("")
                            .arg("--quick-generate-key")
                            .arg("carbonflow-recovery-test@invalid")
                            .arg("ed25519").arg("sign").arg("never")
                            .timeout(Duration.ofMinutes(2)));
            if (!keygen.succeeded()) {
                return null;
            }

            SafeProcessRunner.Result list = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(gpgPath.toString())
                            .arg("--batch").arg("--homedir").arg(home)
                            .arg("--with-colons").arg("--fingerprint")
                            .timeout(Duration.ofMinutes(1)));
            String fingerprint = null;
            for (String line : list.stdout().split("\n")) {
                String[] parts = line.split(":");
                if (parts.length > 9 && "fpr".equals(parts[0])) {
                    fingerprint = parts[9];
                    break;
                }
            }
            if (fingerprint == null) {
                return null;
            }

            SafeProcessRunner.Result subkey = SafeProcessRunner.run(
                    new SafeProcessRunner.Command(gpgPath.toString())
                            .arg("--batch").arg("--yes").arg("--homedir").arg(home)
                            .arg("--passphrase").arg("")
                            .arg("--quick-add-key").arg(fingerprint)
                            .arg("rsa2048").arg("encr").arg("never")
                            .timeout(Duration.ofMinutes(2)));
            keyId = subkey.succeeded() ? fingerprint : null;
            return keyId;
        } catch (Exception e) {
            return null;
        }
    }

    /** The POSIX path of the throwaway keyring. */
    public static synchronized String home() {
        return home;
    }

    /** Child environment pointing gpg at the throwaway keyring. */
    public static Map<String, String> environment() {
        return home == null ? Map.of() : Map.of("GNUPGHOME", home);
    }
}
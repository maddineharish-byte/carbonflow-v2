package com.carbonflow.recovery.crypto;

import com.carbonflow.recovery.exec.SafeProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * REC-09 — encryption of recovery artefacts.
 *
 * <h2>Tool selection, and why gpg rather than age</h2>
 * <p>The design names {@code age} as preferred and {@code gpg} as an acceptable
 * fallback. On this build host {@code age} is not installed and {@code gpg} is,
 * so this implementation uses gpg and the selection is <b>discovered at
 * runtime</b> rather than hardcoded: {@link #select(List)} tries age first and
 * falls back. A host with age installed will therefore use age without a code
 * change, and the choice is recorded in the metadata either way.
 *
 * <h2>Asymmetric encryption only</h2>
 * <p>Backups are written using a <b>public</b> key or recipient. The private key
 * is never needed to create a backup and never passes through this class, so an
 * automated backup job — or anyone who can read the backup store — cannot
 * decrypt what it wrote. Decryption requires the private key supplied separately
 * at restore time.
 *
 * <p>Symmetric encryption with a passphrase was rejected: it would force the
 * passphrase into the backup process's environment, which is exactly the secret
 * handling REC-03 went to some trouble to avoid.
 *
 * <h2>No secret material is ever stored</h2>
 * <p>{@link EncryptionMetadata} records only the recipient key id and the method.
 * No passphrase, no private key path, no key material. Nothing here is written
 * into the manifest beyond that identifier.
 */
public final class BackupEncryptionService {

    /** Encryption methods this service can use. */
    public enum Method {
        /** age, X25519 + ChaCha20-Poly1305. Preferred. */
        AGE,
        /** GPG with an asymmetric key. Fallback where age is unavailable. */
        GPG
    }

    /**
     * Safe, non-secret description of how a set was encrypted.
     *
     * @param method       which tool was used
     * @param recipientKey identifier of the public key or age recipient
     * @param artefactName file name of the encrypted artefact
     * @param originalName file name of the plaintext it replaces
     */
    public record EncryptionMetadata(Method method, String recipientKey,
                                     String artefactName, String originalName) {

        public EncryptionMetadata {
            if (method == null || recipientKey == null || recipientKey.isBlank()) {
                throw new IllegalArgumentException(
                        "method and recipientKey are required");
            }
        }

        /**
         * A short label safe to record in a manifest or a drill report.
         *
         * <p>Only the key <em>identifier</em> is included, never key material.
         */
        public String describe() {
            return method.name().toLowerCase(Locale.ROOT) + ":" + recipientKey;
        }
    }

    private final Method method;
    private final Path toolPath;

    /**
     * Extra environment for the child gpg/age process.
     *
     * <p>Used to set {@code GNUPGHOME} so a backup can run against a specific
     * keyring. This is the mechanism GnuPG itself defines, so it is real
     * configuration rather than a test hook: an operator isolating a dedicated
     * backup keyring sets the same variable.
     */
    private final java.util.Map<String, String> environment;

    public BackupEncryptionService(Method method, Path toolPath) {
        this(method, toolPath, java.util.Map.of());
    }

    public BackupEncryptionService(Method method, Path toolPath,
                                   java.util.Map<String, String> environment) {
        this.method = method;
        this.toolPath = toolPath;
        this.environment = environment == null ? java.util.Map.of() : environment;
    }

    /**
     * Chooses an available tool: age if present, otherwise gpg.
     *
     * @param available map of tool name to resolved path, possibly empty
     * @return the selection
     * @throws IllegalStateException if neither tool is available, rather than
     *                               silently writing backups unencrypted
     */
    public static Method select(java.util.Map<String, Path> available) {
        if (available.containsKey("age")) {
            return Method.AGE;
        }
        if (available.containsKey("gpg")) {
            return Method.GPG;
        }
        // Failing loudly is essential: the alternative is writing customer
        // documents into an unencrypted backup store and calling it a success.
        throw new IllegalStateException(
                "No encryption tool available: neither age nor gpg was found. "
                        + "Refusing to write unencrypted backups.");
    }

    /** Builds a service for whichever tool is available. */
    public static BackupEncryptionService forAvailableTools(
            java.util.Map<String, Path> available) {
        Method selected = select(available);
        return new BackupEncryptionService(selected,
                available.get(selected.name().toLowerCase(Locale.ROOT)));
    }

    /**
     * Encrypts one file in place, producing {@code <name>.enc}.
     *
     * <p>The plaintext is deleted only after the ciphertext is fully written, so
     * a failure part-way leaves the original intact rather than losing both.
     *
     * @param plaintext the file to encrypt
     * @param recipient the public key id or age recipient
     * @return metadata describing the result
     */
    public EncryptionMetadata encrypt(Path plaintext, String recipient)
            throws IOException {

        Path ciphertext = plaintext.resolveSibling(
                plaintext.getFileName() + ".enc");

        SafeProcessRunner.Command command = method == Method.AGE
                ? ageEncryptCommand(plaintext, ciphertext, recipient)
                : gpgEncryptCommand(plaintext, ciphertext, recipient);

        command.timeout(Duration.ofMinutes(10)).environment(environment);

        SafeProcessRunner.Result result = SafeProcessRunner.run(command);
        if (!result.succeeded()) {
            throw new IOException("Encryption of " + plaintext.getFileName()
                    + " failed (exit " + result.exitCode() + ")");
        }
        if (!Files.isRegularFile(ciphertext) || Files.size(ciphertext) == 0) {
            throw new IOException("Encryption produced no output for "
                    + plaintext.getFileName());
        }

        // Only now is it safe to remove the plaintext. Order matters: deleting
        // first would risk losing the only copy of a customer's evidence.
        Files.deleteIfExists(plaintext);

        return new EncryptionMetadata(method, redactKey(recipient),
                ciphertext.getFileName().toString(), plaintext.getFileName().toString());
    }

    /**
     * Decrypts an encrypted artefact, for restore and drill.
     *
     * <p>Requires the private key to be available to the child process. The key
     * itself is never passed as an argument here and never logged.
     *
     * @param ciphertext the {@code .enc} artefact
     * @param recipient  the key id used to encrypt
     * @param outputDirectory where the plaintext is written
     * @return the decrypted file
     */
    public Path decrypt(Path ciphertext, String recipient, Path outputDirectory)
            throws IOException {

        if (!Files.isRegularFile(ciphertext)) {
            throw new IOException("Encrypted artefact not found: " + ciphertext);
        }
        Path outputDirectoryResolved = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(outputDirectoryResolved);

        String name = ciphertext.getFileName().toString();
        String plainName = name.endsWith(".enc")
                ? name.substring(0, name.length() - 4) : name;
        Path output = outputDirectoryResolved.resolve(plainName);

        SafeProcessRunner.Command command = method == Method.AGE
                ? ageDecryptCommand(ciphertext, output)
                : gpgDecryptCommand(ciphertext, output, recipient);

        command.timeout(Duration.ofMinutes(10)).environment(environment);

        SafeProcessRunner.Result result = SafeProcessRunner.run(command);
        if (!result.succeeded()) {
            throw new IOException("Decryption of " + name + " failed (exit "
                    + result.exitCode() + "); the private key must be available to "
                    + "the gpg keyring");
        }
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
            throw new IOException("Decryption produced no output for " + name);
        }
        return output;
    }

    private SafeProcessRunner.Command ageEncryptCommand(Path plaintext, Path ciphertext,
                                                        String recipient) {
        return new SafeProcessRunner.Command(toolPath.toString())
                .arg("--encrypt")
                .arg("--recipient").arg(recipient)
                .arg("--output").arg(ciphertext.toString())
                .arg(plaintext.toString());
    }

    private SafeProcessRunner.Command ageDecryptCommand(Path ciphertext, Path output) {
        return new SafeProcessRunner.Command(toolPath.toString())
                .arg("--decrypt")
                .arg("--output").arg(output.toString())
                .arg(ciphertext.toString());
    }

    /**
     * gpg command with a public-key recipient.
     *
     * <p>{@code --yes} to overwrite a stale artefact, {@code --batch} so it can
     * never block on an interactive prompt during a scheduled backup, and
     * {@code --trust-model always} because the backup host has no web of trust to
     * consult for a key it was configured with deliberately. No passphrase is
     * supplied: only a private key can decrypt, and that is the point.
     */
    private SafeProcessRunner.Command gpgEncryptCommand(Path plaintext, Path ciphertext,
                                                        String recipient) {
        return new SafeProcessRunner.Command(toolPath.toString())
                .arg("--batch")
                .arg("--yes")
                .arg("--trust-model")
                .arg("always")
                .arg("--recipient")
                .arg(recipient)
                .arg("--encrypt")
                .arg("--output")
                .arg(ciphertext.toString())
                .arg(plaintext.toString());
    }

    private SafeProcessRunner.Command gpgDecryptCommand(Path ciphertext, Path output,
                                                        String recipient) {
        return new SafeProcessRunner.Command(toolPath.toString())
                .arg("--batch")
                .arg("--yes")
                .arg("--trust-model")
                .arg("always")
                .arg("--output")
                .arg(output.toString())
                .arg("--decrypt")
                .arg(ciphertext.toString());
    }

    /**
     * Reduces a key identifier to a safe, recordable form.
     *
     * <p>Strips any inline secret suffix. An age recipient may be written as
     * {@code age1...#comment}; only the key part belongs in a manifest.
     */
    static String redactKey(String recipient) {
        if (recipient == null) {
            return null;
        }
        String value = recipient.trim();
        int comment = value.indexOf('#');
        if (comment >= 0) {
            value = value.substring(0, comment);
        }
        return value;
    }

    /** The method this service uses. */
    public Method method() {
        return method;
    }
}
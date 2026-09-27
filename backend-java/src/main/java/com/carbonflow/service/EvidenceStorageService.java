package com.carbonflow.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Private local-filesystem evidence vault — the Java port of the Node
 * reference's {@code LocalStorageAdapter} ({@code server/storage.ts}).
 *
 * <p><b>No cloud/object-storage dependency is introduced</b> (Phase 5 module
 * 18): files live under a single configurable base directory
 * ({@code carbonflow.evidence.vault-dir}, default {@code vault_storage} —
 * already gitignored), partitioned per tenant by organization id.
 *
 * <p>Validation preserved verbatim from Node, in the same order:
 * <ol>
 *   <li>25 MB size limit ({@link #MAX_FILE_SIZE_BYTES})</li>
 *   <li>MIME allow-list ({@link #ALLOWED_MIME_TYPES})</li>
 *   <li>magic-byte signature check per MIME type</li>
 *   <li>SHA-256 hashing of the exact stored bytes</li>
 *   <li>sanitized {@code <millis>_<uuid8>_<basename>} file name inside the
 *       tenant directory</li>
 * </ol>
 *
 * <p><b>Path safety:</b> every read/delete resolves the path and verifies
 * containment inside the base directory ({@code isPathInside}) — a stored
 * path that escapes the vault answers {@code 503 EVIDENCE_STORAGE_ERROR},
 * never a file outside the vault. Error codes/statuses match the Node
 * download handler: missing file → {@code 404 EVIDENCE_FILE_NOT_FOUND},
 * anything else → {@code 503 EVIDENCE_STORAGE_ERROR}, both with the Node
 * message "The evidence file is unavailable.".
 *
 * <p>Files are stored <b>unencrypted</b> on the local disk — CarbonFlow does
 * not claim encryption at rest (only what is actually implemented).
 */
@Service
public class EvidenceStorageService {

    /** Node ALLOWED_MIME_TYPES — verbatim. */
    public static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "text/csv",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "image/png",
            "image/jpeg",
            "image/jpg",
            "text/plain");

    public static final long MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE =
            {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private static final byte[] XLS_SIGNATURE =
            {(byte) 0xd0, (byte) 0xcf, (byte) 0x11, (byte) 0xe0,
                    (byte) 0xa1, (byte) 0xb1, (byte) 0x1a, (byte) 0xe1};

    /** Stored file metadata, mirroring Node's StoredFileMetadata. */
    public static final class StoredFile {
        private final String fileName;
        private final long fileSizeBytes;
        private final String mimeType;
        private final String sha256Hash;
        private final String storagePath;

        public StoredFile(String fileName, long fileSizeBytes, String mimeType,
                          String sha256Hash, String storagePath) {
            this.fileName = fileName;
            this.fileSizeBytes = fileSizeBytes;
            this.mimeType = mimeType;
            this.sha256Hash = sha256Hash;
            this.storagePath = storagePath;
        }

        public String getFileName() {
            return fileName;
        }

        public long getFileSizeBytes() {
            return fileSizeBytes;
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getSha256Hash() {
            return sha256Hash;
        }

        public String getStoragePath() {
            return storagePath;
        }
    }

    private final Path baseDir;

    public EvidenceStorageService(
            @Value("${carbonflow.evidence.vault-dir:vault_storage}") String vaultDir)
            throws IOException {
        this.baseDir = Paths.get(vaultDir).toAbsolutePath().normalize();
        Files.createDirectories(this.baseDir);
    }

    Path getBaseDir() {
        return baseDir;
    }

    // ------------------------------------------------------------------
    // Validation primitives (static — unit-testable without the container)
    // ------------------------------------------------------------------

    /** Node {@code hasExpectedSignature} — verbatim semantics per MIME. */
    public static boolean hasExpectedSignature(String mimeType, byte[] buffer) {
        if ("application/pdf".equals(mimeType)) {
            return buffer.length >= 5
                    && buffer[0] == '%' && buffer[1] == 'P' && buffer[2] == 'D'
                    && buffer[3] == 'F' && buffer[4] == '-';
        }
        if ("image/png".equals(mimeType)) {
            if (buffer.length < PNG_SIGNATURE.length) {
                return false;
            }
            for (int i = 0; i < PNG_SIGNATURE.length; i++) {
                if (buffer[i] != PNG_SIGNATURE[i]) {
                    return false;
                }
            }
            return true;
        }
        if ("image/jpeg".equals(mimeType) || "image/jpg".equals(mimeType)) {
            return buffer.length >= 3
                    && (buffer[0] & 0xff) == 0xff && (buffer[1] & 0xff) == 0xd8
                    && (buffer[2] & 0xff) == 0xff;
        }
        if ("application/vnd.ms-excel".equals(mimeType)) {
            if (buffer.length < XLS_SIGNATURE.length) {
                return false;
            }
            for (int i = 0; i < XLS_SIGNATURE.length; i++) {
                if (buffer[i] != XLS_SIGNATURE[i]) {
                    return false;
                }
            }
            return true;
        }
        if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)) {
            return buffer.length >= 2 && buffer[0] == 'P' && buffer[1] == 'K';
        }
        if ("text/plain".equals(mimeType) || "text/csv".equals(mimeType)) {
            int limit = Math.min(buffer.length, 4096);
            for (int i = 0; i < limit; i++) {
                if (buffer[i] == 0) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    public static String sha256Hex(byte[] buffer) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(buffer));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Node: {@code path.basename(name).replace(/[^a-zA-Z0-9._-]/g, '_')}. */
    static String safeBasename(String originalName) {
        String name = originalName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            sb.append(allowed ? c : '_');
        }
        return sb.toString();
    }

    /** Containment check — Node's {@code isPathInside}. */
    static boolean isPathInside(Path base, Path candidate) {
        Path normalizedBase = base.toAbsolutePath().normalize();
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        Path relative = normalizedBase.relativize(normalizedCandidate);
        return relative.toString().isEmpty()
                || (!relative.toString().startsWith("..") && !relative.isAbsolute());
    }

    // ------------------------------------------------------------------
    // Storage operations
    // ------------------------------------------------------------------

    /**
     * Validates (size → MIME → magic bytes), hashes and writes the file into
     * the caller's tenant directory. Node error messages are preserved
     * verbatim under {@code 400 UPLOAD_FAILED}.
     */
    public StoredFile saveFile(String organizationId, String originalName, String mimeType,
                               byte[] content) throws IOException {
        if (content == null || content.length > MAX_FILE_SIZE_BYTES) {
            long size = content == null ? 0 : content.length;
            throw new AuthException("UPLOAD_FAILED",
                    "File size " + size + " exceeds 25 MB limit.",
                    HttpStatus.BAD_REQUEST);
        }
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new AuthException("UPLOAD_FAILED",
                    "MIME type '" + mimeType + "' is not supported for evidence documents.",
                    HttpStatus.BAD_REQUEST);
        }
        if (!hasExpectedSignature(mimeType, content)) {
            throw new AuthException("UPLOAD_FAILED",
                    "File content does not match declared MIME type '" + mimeType + "'.",
                    HttpStatus.BAD_REQUEST);
        }

        Path tenantDir = baseDir.resolve(safeBasename(organizationId));
        Files.createDirectories(tenantDir);

        String safeName = System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_"
                + safeBasename(originalName);
        Path target = tenantDir.resolve(safeName).normalize();
        if (!isPathInside(baseDir, target)) {
            // Unreachable through the sanitized name — fail closed regardless.
            throw new AuthException("UPLOAD_FAILED",
                    "Evidence upload failed.", HttpStatus.BAD_REQUEST);
        }

        try {
            Files.write(target, content);
        } catch (IOException e) {
            // Node surfaces err.message; Java answers with the Node fallback
            // text so filesystem details never leak through the envelope.
            throw new AuthException("UPLOAD_FAILED",
                    "Evidence upload failed.", HttpStatus.BAD_REQUEST);
        }

        return new StoredFile(originalName, content.length, mimeType,
                sha256Hex(content), target.toString());
    }

    /**
     * Reads vault bytes with containment enforcement. Contract mapping is
     * the Node download handler's: missing on disk → 404
     * {@code EVIDENCE_FILE_NOT_FOUND}; traversal/IO → 503
     * {@code EVIDENCE_STORAGE_ERROR}; both with the shared message.
     */
    public byte[] readFile(String storagePath) throws IOException {
        Path resolved = Paths.get(storagePath).toAbsolutePath().normalize();
        if (!isPathInside(baseDir, resolved)) {
            throw new AuthException("EVIDENCE_STORAGE_ERROR",
                    "The evidence file is unavailable.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
        if (!Files.exists(resolved)) {
            throw new AuthException("EVIDENCE_FILE_NOT_FOUND",
                    "The evidence file is unavailable.",
                    HttpStatus.NOT_FOUND);
        }
        try {
            return Files.readAllBytes(resolved);
        } catch (IOException e) {
            throw new AuthException("EVIDENCE_STORAGE_ERROR",
                    "The evidence file is unavailable.",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Best-effort cleanup of a stored file (failed write, governed delete).
     * Missing files and containment violations are ignored: the caller has
     * already decided the file must go, and an out-of-vault path must never
     * be touched.
     */
    public void deleteFile(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return;
        }
        try {
            Path resolved = Paths.get(storagePath).toAbsolutePath().normalize();
            if (isPathInside(baseDir, resolved)) {
                Files.deleteIfExists(resolved);
            }
        } catch (IOException | RuntimeException ignored) {
            // Cleanup is best-effort; DB consistency is handled by the caller.
        }
    }
}

package com.carbonflow.recovery;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Streaming SHA-256 for recovery artefacts.
 *
 * <p>Shared by REC-03 (database artefacts), REC-04 (vault copy) and REC-05
 * (coordinator re-check) so there is exactly one hashing implementation in the
 * recovery package and no chance of two disagreeing about a digest.
 *
 * <p>Always streaming. A backup may be gigabytes, and loading one into a
 * {@code byte[]} merely to hash it would be a second outage waiting to happen.
 */
public final class RecoveryDigest {

    /** The only checksum algorithm in use. Never invent a second. */
    public static final String ALGORITHM = "SHA-256";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RecoveryDigest() {
    }

    /**
     * SHA-256 of a file, as lowercase hex.
     *
     * @throws IOException if the file cannot be read, or SHA-256 is unavailable
     */
    public static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(ALGORITHM + " unavailable in this JVM", e);
        }
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestStream = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[64 * 1024];
            while (digestStream.read(buffer) != -1) {
                // reading is the hashing
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Lowercase hex, without allocating a {@code byte[]} for the digest. */
    static String toHex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }

    /** {@code true} when the value is a well-formed lowercase SHA-256 hex digest. */
    public static boolean isSha256Hex(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
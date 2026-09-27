package com.carbonflow.service;

import java.util.regex.Pattern;

/**
 * The Node reference's uuid contract, used where {@code server/*.ts} calls
 * {@code assertUuid} on a client-supplied identifier:
 *
 * <pre>
 * /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
 * </pre>
 *
 * <p>Stricter than {@link java.util.UUID#fromString}: the version nibble must
 * be 1–5 and the variant nibble 8/9/a/b. That matters for contract parity —
 * the reference answers {@code 400 VALIDATION_ERROR} for
 * {@code 00000000-0000-0000-0000-000000000000} (version 0) while a lenient
 * parse would accept it and answer 404/FACTOR_NOT_FOUND instead, i.e. a
 * different code for the same malformed input.
 *
 * <p>Where the Phase 4/5 anti-enumeration rule applies instead (greenfield
 * activity verbs collapse malformed into the tenant-scoped 404), services call
 * this first only to decide *which* contract branch to take — never to leak
 * existence.
 */
public final class UuidContract {

    private static final Pattern NODE_UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                    + "[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$");

    private UuidContract() {
    }

    /** True when the value satisfies the reference's {@code UUID_PATTERN}. */
    public static boolean isNodeUuid(String value) {
        return value != null && NODE_UUID.matcher(value).matches();
    }
}

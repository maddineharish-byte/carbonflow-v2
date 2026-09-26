package com.carbonflow.security;

/**
 * Authority naming conventions used across the security filter chain and
 * method security.
 */
public final class Authorities {

    /** Authority prefix for permission codes, e.g. {@code PERMISSION_facilities.read}. */
    public static final String PREFIX = "PERMISSION_";

    /** Authority prefix for the assigned role, e.g. {@code ROLE_COMPANY_ADMIN}. */
    public static final String ROLE_PREFIX = "ROLE_";

    private Authorities() {
    }
}

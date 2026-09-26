package com.carbonflow.model.enums;

/**
 * Tenant lifecycle status (V8 {@code organizations.status}, ADR-014).
 *
 * <ul>
 *   <li>{@link #PENDING_ACTIVATION} — created through public registration;
 *       users cannot sign in yet.</li>
 *   <li>{@link #ACTIVE} — approved (or seeded); normal operation.</li>
 *   <li>{@link #REJECTED} — platform administrator refused the registration.</li>
 *   <li>{@link #SUSPENDED} — previously active tenant that has been suspended;
 *       existing sessions die at the next refresh.</li>
 * </ul>
 */
public enum OrganizationStatus {
    PENDING_ACTIVATION,
    ACTIVE,
    REJECTED,
    SUSPENDED;

    public static OrganizationStatus fromDb(String value) {
        for (OrganizationStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalStateException("Unknown organizations.status value in database: " + value);
    }

    /** Only an ACTIVE tenant may authenticate or refresh sessions. */
    public boolean allowsAuthentication() {
        return this == ACTIVE;
    }
}

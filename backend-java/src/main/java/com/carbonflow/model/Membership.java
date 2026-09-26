package com.carbonflow.model;

import com.carbonflow.model.enums.Role;

/**
 * An active-or-inactive link between a user and an organization carrying the
 * assigned role ({@code organization_memberships} joined with
 * {@code organizations} + {@code roles}). Roles are granted per membership,
 * never stored on the user (ADR-011).
 */
public record Membership(String userId, Organization organization, Role role, boolean active) {
}

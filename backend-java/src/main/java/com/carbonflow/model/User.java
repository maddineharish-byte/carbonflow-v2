package com.carbonflow.model;

import java.time.Instant;

/**
 * A row of the {@code users} table (V1): identity only — organization and
 * role live in {@code organization_memberships} (see {@link Membership}).
 */
public class User {
    private String id;
    private String email;
    private String passwordHash;
    private String fullName;
    private boolean active;
    private Instant createdAt;

    public User() {
    }

    public User(String id, String email, String passwordHash, String fullName, boolean active, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.active = active;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.carbonflow.model;

import com.carbonflow.model.enums.Role;
import java.time.Instant;
import java.util.List;

public class User {
    private String id;
    private String email;
    private String passwordHash;
    private String fullName;
    private String organizationId;
    private Role role;
    private List<String> facilityScopes; // empty list means all facilities
    private boolean active;
    private Instant createdAt;

    public User() {}

    public User(String id, String email, String passwordHash, String fullName, String organizationId, Role role, List<String> facilityScopes, boolean active, Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.fullName = fullName;
        this.organizationId = organizationId;
        this.role = role;
        this.facilityScopes = facilityScopes;
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

    public String getOrganizationId() { return organizationId; }
    public void setOrganizationId(String organizationId) { this.organizationId = organizationId; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public List<String> getFacilityScopes() { return facilityScopes; }
    public void setFacilityScopes(List<String> facilityScopes) { this.facilityScopes = facilityScopes; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

package com.carbonflow.dto;

import com.carbonflow.model.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Tenant user administration payloads (greenfield — {@code users.*}
 * permissions exist in RBAC but no endpoint was ever implemented in either
 * backend; shape recorded in ADR-014).
 */
public class UserRequests {

    /** POST /users — create a user with a membership in the caller's tenant. */
    public static class CreateUserRequest {
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid email address")
        private String email;
        @NotBlank(message = "password is required")
        @Size(min = 8, message = "password must be at least 8 characters")
        private String password;
        @NotBlank(message = "fullName is required")
        private String fullName;
        @NotBlank(message = "role is required")
        private String role;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    /** PATCH /users/{id} — partial update; unset fields are left untouched. */
    public static class UpdateUserRequest {
        private String fullName;
        private String role;

        public String getFullName() { return fullName; }
        public void setFullName(String fullName) { this.fullName = fullName; }

        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }

    /** A member of the caller's organization as returned by /users. */
    public record UserView(String id, String email, String fullName, Role role,
                           boolean active, Instant createdAt) {
    }
}

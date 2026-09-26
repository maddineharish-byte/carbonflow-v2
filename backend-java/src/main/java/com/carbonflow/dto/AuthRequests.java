package com.carbonflow.dto;

import com.carbonflow.model.enums.Role;

public class AuthRequests {

    public static class LoginRequest {
        private String email;
        private String password;

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    public static class LoginResponse {
        private String accessToken;
        private UserInfo user;

        public LoginResponse(String accessToken, UserInfo user) {
            this.accessToken = accessToken;
            this.user = user;
        }

        public String getAccessToken() { return accessToken; }
        public UserInfo getUser() { return user; }
    }

    public static class UserInfo {
        private String id;
        private String email;
        private String fullName;
        private String organizationId;
        private Role role;

        public UserInfo(String id, String email, String fullName, String organizationId, Role role) {
            this.id = id;
            this.email = email;
            this.fullName = fullName;
            this.organizationId = organizationId;
            this.role = role;
        }

        public String getId() { return id; }
        public String getEmail() { return email; }
        public String getFullName() { return fullName; }
        public String getOrganizationId() { return organizationId; }
        public Role getRole() { return role; }
    }
}

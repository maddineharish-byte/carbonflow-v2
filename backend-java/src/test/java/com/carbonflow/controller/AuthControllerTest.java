package com.carbonflow.controller;

import com.carbonflow.config.JwtTokenProvider;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.AuthRequests;
import com.carbonflow.model.User;
import com.carbonflow.model.enums.Role;
import com.carbonflow.repository.DataStore;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Phase 2 authentication hardening: credentials are stored as BCrypt
 * hashes and verified with a constant-time comparison; unknown accounts and
 * wrong passwords are indistinguishable.
 */
class AuthControllerTest {

    private static final String SECRET = "auth-controller-test-secret-0123456789abcdef";

    private DataStore dataStore;
    private JwtTokenProvider tokenProvider;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        dataStore = new DataStore();
        tokenProvider = new JwtTokenProvider(SECRET, 900_000L);
        controller = new AuthController(dataStore, tokenProvider, new BCryptPasswordEncoder(10));
    }

    private static AuthRequests.LoginRequest request(String email, String password) {
        AuthRequests.LoginRequest req = new AuthRequests.LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    @Test
    void acceptsValidCredentialsAndIssuesAToken() {
        ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> response =
                controller.login(request("admin@acmeglobal.com", "Password123!"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().isSuccess());

        AuthRequests.LoginResponse body = response.getBody().getData();
        assertNotNull(body.getAccessToken());
        assertEquals(Role.COMPANY_ADMIN, body.getUser().getRole());
        assertEquals("admin@acmeglobal.com", body.getUser().getEmail());

        Claims claims = tokenProvider.parseToken(body.getAccessToken());
        assertEquals("usr-acme-admin", claims.getSubject());
        assertEquals("COMPANY_ADMIN", claims.get("role", String.class));
    }

    @Test
    void rejectsWrongPassword() {
        ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> response =
                controller.login(request("admin@acmeglobal.com", "wrong-password"));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("INVALID_CREDENTIALS", response.getBody().getError().getCode());
    }

    @Test
    void unknownAccountIsIndistinguishableFromWrongPassword() {
        ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> wrongPassword =
                controller.login(request("admin@acmeglobal.com", "wrong-password"));
        ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> unknownUser =
                controller.login(request("nobody@nowhere.example", "whatever"));

        assertEquals(wrongPassword.getStatusCode(), unknownUser.getStatusCode());
        assertEquals(wrongPassword.getBody().getError().getCode(),
                unknownUser.getBody().getError().getCode());
        assertEquals(wrongPassword.getBody().getError().getMessage(),
                unknownUser.getBody().getError().getMessage());
    }

    @Test
    void rejectsMissingCredentials() {
        ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> response =
                controller.login(request(null, "Password123!"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("INVALID_REQUEST", response.getBody().getError().getCode());
    }

    @Test
    void timingEqualizerHashIsARealVerifiableBcryptHash() {
        // If BCryptPasswordEncoder could not verify this $2b$ hash, unknown-user
        // logins would silently skip the comparison and re-open the timing gap.
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        assertTrue(encoder.matches("carbonflow-login-timing-equalizer", AuthController.TIMING_EQUALIZER_HASH));
        assertFalse(encoder.matches("different-password", AuthController.TIMING_EQUALIZER_HASH));
    }

    @Test
    void seedPasswordsAreNeverStoredInPlaintext() {
        for (User user : dataStore.users.values()) {
            String stored = user.getPasswordHash();
            assertTrue(stored.startsWith("$2a$") || stored.startsWith("$2b$"),
                    "password hash for " + user.getEmail() + " must be BCrypt");
            assertFalse(stored.contains("Password123!"));
        }
        assertEquals(4, dataStore.users.size());
    }
}

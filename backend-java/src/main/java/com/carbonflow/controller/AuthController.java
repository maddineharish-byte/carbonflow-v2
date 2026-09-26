package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.AuthRequests.AuthSession;
import com.carbonflow.dto.AuthRequests.LoginRequest;
import com.carbonflow.dto.AuthRequests.LogoutResponse;
import com.carbonflow.dto.AuthRequests.Profile;
import com.carbonflow.dto.AuthRequests.RefreshResponse;
import com.carbonflow.dto.AuthRequests.RegisterRequest;
import com.carbonflow.dto.AuthRequests.RegisterResponse;
import com.carbonflow.dto.AuthRequests.SwitchTenantRequest;
import com.carbonflow.service.AuthException;
import com.carbonflow.service.AuthService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Authentication endpoints — contract-compatible port of the Node reference
 * backend's {@code /auth} handlers ({@code server/routes.ts}):
 *
 * <ul>
 *   <li>{@code POST /login} — password grant with optional membership selector.</li>
 *   <li>{@code POST /refresh} — single-use rotation; rejects context-override
 *       fields ({@code userId|organizationId|targetOrgId|targetRole}) with 400.</li>
 *   <li>{@code POST /logout} — revokes the whole refresh family; public
 *       (authentication is the refresh token itself, as in Node).</li>
 *   <li>{@code POST /switch-tenant-or-role} — switches within already-assigned
 *       memberships only.</li>
 *   <li>{@code GET /me} — fresh session context without tokens.</li>
 *   <li>{@code POST /register} — public signup (greenfield, ADR-014): creates
 *       a PENDING_ACTIVATION organization and answers 201 without tokens.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /** Context-override fields Node explicitly rejects on refresh. */
    private static final String[] REFRESH_CONTEXT_FIELDS =
            {"userId", "organizationId", "targetOrgId", "targetRole"};

    private final AuthService authService;
    private final ObjectMapper objectMapper;

    public AuthController(AuthService authService, ObjectMapper objectMapper) {
        this.authService = authService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthSession>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(authService.login(request)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<RefreshResponse>> refresh(@RequestBody(required = false) JsonNode body) {
        Map<String, Object> fields = toFields(body);
        for (String field : REFRESH_CONTEXT_FIELDS) {
            if (fields.containsKey(field)) {
                throw new AuthException("VALIDATION_ERROR",
                        "Refresh requests cannot change the authorization context.", HttpStatus.BAD_REQUEST);
            }
        }
        return ResponseEntity.ok(ApiResponse.ok(
                authService.refresh(readRefreshToken(body)),
                "Refresh token rotated successfully."));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<LogoutResponse>> logout(@RequestBody(required = false) JsonNode body) {
        authService.logout(readRefreshToken(body));
        return ResponseEntity.ok(ApiResponse.ok(
                new LogoutResponse(true), "Refresh session revoked successfully."));
    }

    @PostMapping("/switch-tenant-or-role")
    public ResponseEntity<ApiResponse<AuthSession>> switchTenant(
            @RequestBody(required = false) SwitchTenantRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                authService.switchTenant(request, TenantContext.get()),
                "Tenant and role switched successfully."));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Profile>> me() {
        return ResponseEntity.ok(ApiResponse.ok(authService.me(TenantContext.get())));
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                authService.register(request),
                "Registration received. The organization must be approved by a platform "
                        + "administrator before users can sign in."));
    }

    /**
     * Reads the {@code refreshToken} field only when the body is a JSON object
     * with a textual value (Node treats anything else as absent → 400
     * REFRESH_TOKEN_REQUIRED from the service format check).
     */
    private String readRefreshToken(JsonNode body) {
        if (body != null && body.isObject()) {
            JsonNode token = body.get("refreshToken");
            if (token != null && token.isTextual()) {
                return token.asText();
            }
        }
        return null;
    }

    private Map<String, Object> toFields(JsonNode body) {
        if (body == null || !body.isObject()) {
            return Map.of();
        }
        return objectMapper.convertValue(body, new TypeReference<Map<String, Object>>() {
        });
    }
}

package com.carbonflow.controller;

import com.carbonflow.config.JwtTokenProvider;
import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.AuthRequests;
import com.carbonflow.model.User;
import com.carbonflow.repository.DataStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final DataStore dataStore;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(DataStore dataStore, JwtTokenProvider jwtTokenProvider) {
        this.dataStore = dataStore;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> login(@RequestBody AuthRequests.LoginRequest request) {
        if (request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "Email and password are required."));
        }

        User user = dataStore.users.values().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(request.getEmail().trim()))
                .findFirst()
                .orElse(null);

        if (user == null || !user.getPasswordHash().equals(request.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("INVALID_CREDENTIALS", "Invalid email or password."));
        }

        String token = jwtTokenProvider.generateToken(user);
        AuthRequests.UserInfo userInfo = new AuthRequests.UserInfo(user.getId(), user.getEmail(), user.getFullName(), user.getOrganizationId(), user.getRole());
        return ResponseEntity.ok(ApiResponse.ok(new AuthRequests.LoginResponse(token, userInfo), "Login successful."));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<AuthRequests.UserInfo>> getCurrentUser() {
        TenantContext ctx = TenantContext.get();
        if (ctx == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("UNAUTHORIZED", "Not authenticated."));
        }

        User user = dataStore.users.get(ctx.getUserId());
        if (user == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("USER_NOT_FOUND", "User record missing."));
        }

        AuthRequests.UserInfo userInfo = new AuthRequests.UserInfo(user.getId(), user.getEmail(), user.getFullName(), user.getOrganizationId(), user.getRole());
        return ResponseEntity.ok(ApiResponse.ok(userInfo));
    }
}

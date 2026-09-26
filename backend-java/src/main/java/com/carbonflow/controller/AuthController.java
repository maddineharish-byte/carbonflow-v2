package com.carbonflow.controller;

import com.carbonflow.config.JwtTokenProvider;
import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.AuthRequests;
import com.carbonflow.model.User;
import com.carbonflow.repository.DataStore;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    /**
     * Valid BCrypt hash of a random throwaway string. When an account does not
     * exist, login still performs one full BCrypt verification against this
     * value so that response timing cannot distinguish an unknown account from
     * a wrong password (user-enumeration hardening).
     */
    static final String TIMING_EQUALIZER_HASH =
            "$2b$10$9zl/RKI67B2GfnKZhi2nzu9keezzNYt/acc46V5nG0hb5SGklfToa";

    private final DataStore dataStore;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    public AuthController(DataStore dataStore, JwtTokenProvider jwtTokenProvider, PasswordEncoder passwordEncoder) {
        this.dataStore = dataStore;
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthRequests.LoginResponse>> login(@Valid @RequestBody AuthRequests.LoginRequest request) {
        if (request.getEmail() == null || request.getPassword() == null) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_REQUEST", "Email and password are required."));
        }

        User user = dataStore.users.values().stream()
                .filter(u -> u.getEmail().equalsIgnoreCase(request.getEmail().trim()))
                .findFirst()
                .orElse(null);

        // Constant-time password verification against the stored BCrypt hash.
        // A wrong password and an unknown account perform identical work and
        // return the identical response (no user enumeration).
        boolean passwordMatches;
        if (user == null) {
            passwordEncoder.matches(request.getPassword(), TIMING_EQUALIZER_HASH);
            passwordMatches = false;
        } else {
            passwordMatches = passwordEncoder.matches(request.getPassword(), user.getPasswordHash());
        }
        if (!passwordMatches) {
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

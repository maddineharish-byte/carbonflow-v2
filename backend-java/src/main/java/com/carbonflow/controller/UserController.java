package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.dto.UserRequests.CreateUserRequest;
import com.carbonflow.dto.UserRequests.UpdateUserRequest;
import com.carbonflow.dto.UserRequests.UserView;
import com.carbonflow.service.UsersService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tenant user administration (greenfield, ADR-014): list, create, update and
 * enable/disable members of the caller's organization. Every operation is
 * scoped by the authenticated tenant context — cross-tenant ids answer 404.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UsersService usersService;

    public UserController(UsersService usersService) {
        this.usersService = usersService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_users.read')")
    public ResponseEntity<ApiResponse<List<UserView>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(usersService.list(TenantContext.get())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_users.create')")
    public ResponseEntity<ApiResponse<UserView>> create(@Valid @RequestBody CreateUserRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                usersService.create(TenantContext.get(), request),
                "User created successfully."));
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasAuthority('PERMISSION_users.update')")
    public ResponseEntity<ApiResponse<UserView>> update(@PathVariable String userId,
                                                        @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                usersService.update(TenantContext.get(), userId, request),
                "User updated successfully."));
    }

    @PostMapping("/{userId}/disable")
    @PreAuthorize("hasAuthority('PERMISSION_users.disable')")
    public ResponseEntity<ApiResponse<UserView>> disable(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok(
                usersService.disable(TenantContext.get(), userId),
                "User disabled successfully."));
    }

    @PostMapping("/{userId}/enable")
    @PreAuthorize("hasAuthority('PERMISSION_users.update')")
    public ResponseEntity<ApiResponse<UserView>> enable(@PathVariable String userId) {
        return ResponseEntity.ok(ApiResponse.ok(
                usersService.enable(TenantContext.get(), userId),
                "User enabled successfully."));
    }
}

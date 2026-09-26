package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.ActivityData;
import com.carbonflow.repository.DataStore;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/activity-data")
public class ActivityDataController {

    private final DataStore dataStore;

    public ActivityDataController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_activity_data.read')")
    public ResponseEntity<ApiResponse<List<ActivityData>>> getActivityData() {
        TenantContext ctx = TenantContext.get();
        List<ActivityData> list = dataStore.activityData.values().stream()
                .filter(a -> a.getOrganizationId().equals(ctx.getOrganizationId()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_activity_data.create')")
    public ResponseEntity<ApiResponse<ActivityData>> createActivityData(@RequestBody ActivityData req) {
        TenantContext ctx = TenantContext.get();
        req.setId("act-" + UUID.randomUUID().toString().substring(0, 8));
        req.setOrganizationId(ctx.getOrganizationId());
        req.setStatus("RAW");
        req.setCreatedAt(Instant.now());
        dataStore.activityData.put(req.getId(), req);
        return ResponseEntity.ok(ApiResponse.ok(req, "Activity data logged successfully."));
    }
}

package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ActivityRequests.ActivityCreateRequest;
import com.carbonflow.dto.ActivityRequests.ActivityUpdateRequest;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.ActivityData;
import com.carbonflow.service.ActivityDataService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Activity data module (Phase 6): the canonical entry point of the carbon
 * flow — {@code GET/POST /activity-data} (Node production contract) plus the
 * greenfield {@code PUT /activity-data/:id} and
 * {@code POST /activity-data/:id/submit} (API.md §2.3).
 *
 * <p>Organization and user id always come from the authenticated tenant
 * context (ADR-010/ADR-015): client-supplied organization ids are never
 * read. {@code @PreAuthorize} enforces the §2.3 permission of each route
 * before the service runs.
 *
 * <p>Bodies are {@code required = false}: a missing body behaves exactly like
 * an empty payload ({@code req.body || {}} in Node) and reaches the same
 * contract validation error instead of a generic INVALID_JSON.
 */
@RestController
@RequestMapping("/api/v1/activity-data")
public class ActivityDataController {

    private final ActivityDataService activities;

    public ActivityDataController(ActivityDataService activities) {
        this.activities = activities;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_activity_data.read')")
    public ResponseEntity<ApiResponse<List<ActivityData>>> list(
            @RequestParam(name = "periodId", required = false) String periodId,
            @RequestParam(name = "facilityId", required = false) String facilityId,
            @RequestParam(name = "scope", required = false) String scope) {
        return ok(activities.list(org(), periodId, facilityId, scope));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERMISSION_activity_data.create')")
    public ResponseEntity<ApiResponse<ActivityData>> create(
            @RequestBody(required = false) ActivityCreateRequest request) {
        ActivityData created = activities.create(org(), userId(),
                request == null ? new ActivityCreateRequest() : request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(created, "Activity data registered."));
    }

    @PutMapping("/{activityDataId}")
    @PreAuthorize("hasAuthority('PERMISSION_activity_data.update')")
    public ResponseEntity<ApiResponse<ActivityData>> update(
            @PathVariable String activityDataId,
            @RequestBody(required = false) ActivityUpdateRequest request) {
        return ok(activities.update(org(), activityDataId,
                request == null ? new ActivityUpdateRequest() : request));
    }

    @PostMapping("/{activityDataId}/submit")
    @PreAuthorize("hasAuthority('PERMISSION_activity_data.submit')")
    public ResponseEntity<ApiResponse<ActivityData>> submit(@PathVariable String activityDataId) {
        return ok(activities.submit(org(), userId(), activityDataId));
    }

    private static String org() {
        return TenantContext.get().getOrganizationId();
    }

    private static String userId() {
        return TenantContext.get().getUserId();
    }

    private static <T> ResponseEntity<ApiResponse<T>> ok(T data) {
        return ResponseEntity.ok(ApiResponse.ok(data));
    }
}

package com.carbonflow.controller;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.ApiResponse;
import com.carbonflow.model.AuditRoom;
import com.carbonflow.model.enums.AuditStatus;
import com.carbonflow.repository.DataStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/audit-rooms")
public class AuditController {

    private final DataStore dataStore;

    public AuditController(DataStore dataStore) {
        this.dataStore = dataStore;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERMISSION_audits.read')")
    public ResponseEntity<ApiResponse<List<AuditRoom>>> getAuditRooms() {
        TenantContext ctx = TenantContext.get();
        List<AuditRoom> list = dataStore.auditRooms.values().stream()
                .filter(r -> r.getOrganizationId().equals(ctx.getOrganizationId()))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(list));
    }

    @PatchMapping("/{roomId}/checklist/{itemId}")
    @PreAuthorize("hasAuthority('PERMISSION_audits.review')")
    public ResponseEntity<ApiResponse<AuditRoom.ChecklistItem>> toggleChecklistItem(
            @PathVariable String roomId,
            @PathVariable String itemId,
            @RequestBody Map<String, Boolean> body) {
        TenantContext ctx = TenantContext.get();
        AuditRoom room = dataStore.auditRooms.get(roomId);
        if (room == null || !room.getOrganizationId().equals(ctx.getOrganizationId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("NOT_FOUND", "Audit room not found."));
        }

        AuditRoom.ChecklistItem item = room.getChecklist().stream()
                .filter(i -> i.getId().equals(itemId))
                .findFirst()
                .orElse(null);

        if (item == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("NOT_FOUND", "Checklist item not found."));
        }

        Boolean completed = body.getOrDefault("completed", !item.isCompleted());
        item.setCompleted(completed);
        return ResponseEntity.ok(ApiResponse.ok(item, "Checklist item updated."));
    }

    @PostMapping("/{roomId}/transition")
    // State transitions carry governance weight: submit, approve and lock rights
    // are required. Fine-grained per-target-state checks arrive with Phase 5.
    @PreAuthorize("hasAnyAuthority('PERMISSION_audits.submit','PERMISSION_audits.approve','PERMISSION_audits.lock')")
    public ResponseEntity<ApiResponse<AuditRoom>> transitionStatus(
            @PathVariable String roomId,
            @RequestBody Map<String, String> body) {
        TenantContext ctx = TenantContext.get();
        AuditRoom room = dataStore.auditRooms.get(roomId);
        if (room == null || !room.getOrganizationId().equals(ctx.getOrganizationId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail("NOT_FOUND", "Audit room not found."));
        }

        String targetStatusStr = body.get("status");
        AuditStatus targetStatus;
        try {
            targetStatus = AuditStatus.valueOf(targetStatusStr);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.fail("INVALID_STATUS", "Invalid audit status."));
        }

        // Checklist Guard: If transitioning to AUDITOR_APPROVED or SEALED, all mandatory checklist items must be completed
        if (targetStatus == AuditStatus.AUDITOR_APPROVED || targetStatus == AuditStatus.SEALED) {
            boolean hasPendingMandatory = room.getChecklist().stream()
                    .anyMatch(i -> i.isMandatory() && !i.isCompleted());
            if (hasPendingMandatory) {
                return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                        .body(ApiResponse.fail("MANDATORY_CHECKLIST_INCOMPLETE", "Cannot approve audit room while mandatory compliance checklist items remain incomplete."));
            }
        }

        room.setStatus(targetStatus);
        return ResponseEntity.ok(ApiResponse.ok(room, "Audit status transitioned to " + targetStatus));
    }
}

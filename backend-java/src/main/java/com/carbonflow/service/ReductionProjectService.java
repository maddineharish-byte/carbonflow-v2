package com.carbonflow.service;

import com.carbonflow.dto.ReportingRequests.ReductionProjectRequest;
import com.carbonflow.model.ReductionProject;
import com.carbonflow.repository.CarbonTargetRepository;
import com.carbonflow.repository.ReductionProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Reduction projects (API.md §2.8; Node parity plus documented deviations).
 *
 * <p>Create is Node-parity (field set, {@code status} default
 * {@code PLANNED}, missing numbers default to 0, {@code ownerId} = caller,
 * message "Reduction project created.") with Java-side hardenings: ISO date
 * validation, date-order validation, status CHECK validation, non-negative
 * values, and <b>tenant validation of {@code facilityId}/{@code targetId}</b>
 * ({@code 404 FACILITY_NOT_FOUND}/{@code CARBON_TARGET_NOT_FOUND} — Node
 * stored foreign ids without checking). PUT is greenfield, justified by the
 * frozen {@code reduction_projects.update} code: partial merge where
 * {@code null} = "leave unchanged" and a blank string clears the field;
 * {@code id}/{@code organizationId}/{@code ownerId}/{@code createdAt} are
 * immutable. No DELETE exists (no delete permission code).
 *
 * <p>Projects are planning records only — they never write emissions and no
 * carbon value is derived from them.
 */
@Service
public class ReductionProjectService {

    private static final int MAX_NAME = 255;
    private static final Set<String> STATUSES =
            Set.of("PLANNED", "IN_PROGRESS", "COMPLETED", "CANCELLED");

    private final ReductionProjectRepository projects;
    private final CarbonTargetRepository targets;
    private final ScopeService scope;

    public ReductionProjectService(ReductionProjectRepository projects,
                                   CarbonTargetRepository targets,
                                   ScopeService scope) {
        this.projects = projects;
        this.targets = targets;
        this.scope = scope;
    }

    public List<ReductionProject> list(String organizationId) {
        return projects.list(organizationId);
    }

    public ReductionProject create(String organizationId, String userId,
                                   ReductionProjectRequest request) {
        if (request == null) {
            throw validation("Project payload is required.");
        }
        String name = requireName(request.getName());
        LocalDate startDate = requireDate(request.getStartDate(), "startDate");
        LocalDate endDate = requireDate(request.getEndDate(), "endDate");
        requireOrder(startDate, endDate);
        // Node parity: missing numbers become 0; negatives are rejected.
        BigDecimal baselineT = requireNonNegative(request.getBaselineT(), BigDecimal.ZERO);
        BigDecimal expectedT =
                requireNonNegative(request.getExpectedReductionT(), BigDecimal.ZERO);
        BigDecimal actualT =
                requireNonNegative(request.getActualReductionT(), BigDecimal.ZERO);
        String status = request.getStatus() == null || request.getStatus().isBlank()
                ? "PLANNED" : requireStatus(request.getStatus());
        String facilityId = resolveFacility(organizationId, request.getFacilityId());
        String targetId = resolveTarget(organizationId, request.getTargetId());

        ReductionProject created = projects.insert(organizationId, targetId, facilityId,
                name, normalizeDescription(request.getDescription()), baselineT, expectedT,
                actualT, startDate, endDate, status, userId);
        return created;
    }

    /** Partial update: {@code null} = unchanged; blank string clears a field. */
    public ReductionProject update(String organizationId, String projectId,
                                   ReductionProjectRequest request) {
        String id = ScopeService.requireUuid(projectId, "Reduction project");
        ReductionProject existing = projects.findById(organizationId, id)
                .orElseThrow(() -> ScopeService.notFound("Reduction project"));
        if (request == null) {
            throw validation("Project payload is required.");
        }

        String name = request.getName() == null
                ? existing.getName() : requireName(request.getName());
        LocalDate startDate = request.getStartDate() == null
                ? existing.getStartDate() : requireDate(request.getStartDate(), "startDate");
        LocalDate endDate = request.getEndDate() == null
                ? existing.getEndDate() : requireDate(request.getEndDate(), "endDate");
        requireOrder(startDate, endDate);
        BigDecimal baselineT = request.getBaselineT() == null
                ? existing.getBaselineT()
                : requireNonNegative(request.getBaselineT(), BigDecimal.ZERO);
        BigDecimal expectedT = request.getExpectedReductionT() == null
                ? existing.getExpectedReductionT()
                : requireNonNegative(request.getExpectedReductionT(), BigDecimal.ZERO);
        BigDecimal actualT = request.getActualReductionT() == null
                ? existing.getActualReductionT()
                : requireNonNegative(request.getActualReductionT(), BigDecimal.ZERO);
        String status = request.getStatus() == null
                ? existing.getStatus() : requireStatus(request.getStatus());
        String description = request.getDescription() == null
                ? existing.getDescription() : normalizeDescription(request.getDescription());
        String facilityId = request.getFacilityId() == null
                ? existing.getFacilityId()
                : resolveFacility(organizationId, request.getFacilityId());
        String targetId = request.getTargetId() == null
                ? existing.getTargetId()
                : resolveTarget(organizationId, request.getTargetId());

        int updated = projects.update(organizationId, id, targetId, facilityId, name,
                description, baselineT, expectedT, actualT, startDate, endDate, status);
        if (updated != 1) {
            throw ScopeService.notFound("Reduction project");
        }
        return projects.findById(organizationId, id)
                .orElseThrow(() -> ScopeService.notFound("Reduction project"));
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private static AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw validation("Project name is required.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME) {
            throw validation("Project name must be " + MAX_NAME + " characters or fewer.");
        }
        return trimmed;
    }

    private static LocalDate requireDate(String value, String field) {
        if (value == null || value.isBlank()) {
            throw validation(field + " is required.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw validation(field + " must be an ISO date (YYYY-MM-DD).");
        }
    }

    private static void requireOrder(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw validation("Project startDate must not be after endDate.");
        }
    }

    private static BigDecimal requireNonNegative(BigDecimal value, BigDecimal fallback) {
        BigDecimal effective = value == null ? fallback : value;
        if (effective.signum() < 0) {
            throw validation("Project values must be zero or greater.");
        }
        return effective;
    }

    private static String requireStatus(String status) {
        String value = status.trim();
        if (!STATUSES.contains(value)) {
            throw validation("Project status must be one of: PLANNED, IN_PROGRESS, "
                    + "COMPLETED, CANCELLED.");
        }
        return value;
    }

    /** Blank → no link; otherwise tenant-resolved (404, anti-enumeration). */
    private String resolveFacility(String organizationId, String facilityId) {
        if (facilityId == null || facilityId.isBlank()) {
            return null;
        }
        return scope.requireFacility(organizationId, facilityId.trim()).getId();
    }

    private String resolveTarget(String organizationId, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return null;
        }
        String id = ScopeService.requireUuid(targetId, "Carbon target");
        return targets.findById(organizationId, id)
                .orElseThrow(() -> ScopeService.notFound("Carbon target"))
                .getId();
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

package com.carbonflow.service;

import com.carbonflow.dto.ActivityRequests.ActivityCreateRequest;
import com.carbonflow.dto.ActivityRequests.ActivityUpdateRequest;
import com.carbonflow.model.ActivityData;
import com.carbonflow.model.Facility;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.model.enums.GHGScope;
import com.carbonflow.repository.ActivityDataRepository;
import com.carbonflow.repository.CalculationRepository;
import com.carbonflow.repository.FacilityRepository;
import com.carbonflow.repository.ReportingPeriodRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Canonical activity-data module (Phase 6): create / read / update / submit
 * against PostgreSQL, ported from {@code server/activity-repository.ts} and
 * {@code server/routes.ts} (production contract).
 *
 * <p><b>Canonical flow position:</b> activity data is the entry point —
 * validation happens here, factor selection and unit normalization happen at
 * calculation time, and every write is tenant-scoped and audit-lock aware.
 *
 * <p><b>Deviation (ADR-017):</b> unlike Node (which does not know about
 * audit locks), every mutating call rejects activities whose reporting period
 * is locked with {@code 409 AUDIT_LOCKED}. Batch runs skip them instead.
 *
 * <p><b>Deviation (ADR-017):</b> quantity scale/magnitude is validated
 * ({@code NUMERIC(16,4)}) instead of silently rounded by the database, and
 * text fields are type-checked like Node instead of being coerced by Jackson.
 */
@Service
public class ActivityDataService {

    /** Node's strict calendar-date contract ({@code calc/activity-repository}). */
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private static final Set<String> SUPPORTED_SCOPES =
            Set.of("SCOPE_1", "SCOPE_2", "SCOPE_3");

    /** Editable states — engine-owned CALCULATED/LOCKED are rejected (ADR-017). */
    private static final Set<String> EDITABLE_STATUSES =
            Set.of("DRAFT", "SUBMITTED", "VALIDATED");

    private static final int MAX_CATEGORY = 100;
    private static final int MAX_ACTIVITY_TYPE = 100;
    private static final int MAX_UNIT = 50;
    private static final int MAX_SOURCE = 150;
    private static final int MAX_NOTES = 10000;
    private static final int MAX_QUANTITY_SCALE = 4;
    private static final int MAX_QUANTITY_INTEGER_DIGITS = 12;

    private final ActivityDataRepository activities;
    private final FacilityRepository facilities;
    private final ReportingPeriodRepository reportingPeriods;
    private final CalculationRepository calculations;
    private final ScopeService scope;
    private final AccountingLockGuard locks;

    public ActivityDataService(ActivityDataRepository activities,
                               FacilityRepository facilities,
                               ReportingPeriodRepository reportingPeriods,
                               CalculationRepository calculations,
                               ScopeService scope,
                               AccountingLockGuard locks) {
        this.activities = activities;
        this.facilities = facilities;
        this.reportingPeriods = reportingPeriods;
        this.calculations = calculations;
        this.scope = scope;
        this.locks = locks;
    }

    // ==========================================
    // Read
    // ==========================================

    /**
     * Node's {@code GET /activity-data} (production path): optional
     * period/facility/scope filters, each activity enriched with its latest
     * calculation.
     *
     * <p>Invalid filters answer {@code 400 VALIDATION_ERROR} with Node's exact
     * production message — the reference's catch block reports validation
     * failures with {@code 'Activity persistence is temporarily unavailable.'}
     * (routes.ts line 591); text parity beats aesthetics here, and ADR-017
     * records it.
     */
    public List<ActivityData> list(String organizationId, String periodId,
                                   String facilityId, String scopeFilter) {
        try {
            validateFilterUuid(periodId);
            validateFilterUuid(facilityId);
            // Node passes an empty filter as-is and its truthiness check drops
            // it — normalize before the SQL cast so "" means "no filter".
            String period = (periodId == null || periodId.isEmpty()) ? null : periodId;
            String facility = (facilityId == null || facilityId.isEmpty()) ? null : facilityId;
            if (scopeFilter != null && !SUPPORTED_SCOPES.contains(scopeFilter)) {
                throw invalidList();
            }
            List<ActivityData> rows = activities.list(organizationId, period, facility, scopeFilter);
            Map<String, com.carbonflow.model.Calculation> latest =
                    calculations.latestByActivities(organizationId,
                            rows.stream().map(ActivityData::getId).toArray(String[]::new));
            for (ActivityData row : rows) {
                row.setCalculation(latest.get(row.getId()));
            }
            return rows;
        } catch (AuthException e) {
            throw e;
        } catch (DataAccessException e) {
            throw unavailableList();
        }
    }

    /**
     * Tenant-scoped fetch: malformed, unknown and cross-tenant ids collapse
     * into one {@code 404 ACTIVITY_NOT_FOUND} (anti-enumeration; Node's
     * production message for the same situation).
     */
    public ActivityData get(String organizationId, String activityDataId) {
        return activities.findById(organizationId, requireUuid(activityDataId))
                .orElseThrow(ActivityDataService::notFound);
    }

    // ==========================================
    // Create
    // ==========================================

    /**
     * Node's {@code POST /activity-data} (production contract): validate →
     * verify facility+period belong to the tenant → lock check → insert as
     * {@code SUBMITTED}.
     *
     * <p>Errors: {@code 400 VALIDATION_ERROR 'Activity data is invalid.'} ·
     * {@code 400 INVALID_ACTIVITY_RELATIONSHIP} (Node's exact message) ·
     * {@code 409 AUDIT_LOCKED} (deviation) · {@code 503
     * ACTIVITY_PERSISTENCE_UNAVAILABLE} on storage failure.
     */
    @Transactional
    public ActivityData create(String organizationId, String userId,
                               ActivityCreateRequest request) {
        String reportingPeriodId = requireUuidField(request.getReportingPeriodId());
        String facilityId = requireUuidField(request.getFacilityId());
        String scopeValue = requireScope(request.getScope());
        // Node: status may only be absent or the literal SUBMITTED; stored status is SUBMITTED.
        if (request.getStatus() != null && !"SUBMITTED".equals(request.getStatus())) {
            throw invalidActivity();
        }
        String category = requireText(request.getCategory(), "category", MAX_CATEGORY);
        String activityType = requireText(request.getActivityType(), "activityType", MAX_ACTIVITY_TYPE);
        String unit = requireText(request.getUnit(), "unit", MAX_UNIT);
        String source = requireText(request.getSource(), "source", MAX_SOURCE);
        BigDecimal quantity = requireQuantity(request.getQuantity());
        LocalDate startDate = requireDate(request.getStartDate());
        LocalDate endDate = requireDate(request.getEndDate());
        if (endDate.isBefore(startDate)) {
            throw invalidActivity();
        }
        String notes = optionalNotes(request.getNotes());
        String departmentId = optionalDepartmentId(request.getDepartmentId());

        requireTenantRelationship(organizationId, facilityId, reportingPeriodId);
        if (departmentId != null) {
            scope.requireDepartment(organizationId, departmentId);
        }
        locks.requireUnlockedPeriod(organizationId, reportingPeriodId);

        try {
            return activities.insert(organizationId, reportingPeriodId, facilityId,
                    departmentId, scopeValue, category, activityType, quantity, unit,
                    startDate, endDate, source, "SUBMITTED", notes, userId);
        } catch (DataIntegrityViolationException e) {
            // V5 composite FKs: facility/period pair not owned by this tenant.
            throw invalidRelationship();
        } catch (DataAccessException e) {
            throw unavailablePersistence();
        }
    }

    // ==========================================
    // Update (greenfield — Node has no PUT route)
    // ==========================================

    /**
     * Partial update: {@code null} leaves a field unchanged, an empty string
     * clears the nullable text fields. Tenant anchors ({@code facility_id},
     * {@code reporting_period_id}) are immutable — a persisted calculation
     * copied them at execution time.
     *
     * <p>Errors: {@code 404 ACTIVITY_NOT_FOUND} · {@code 400
     * VALIDATION_ERROR 'Activity data is invalid.'} · {@code 409
     * AUDIT_LOCKED}.
     */
    @Transactional
    public ActivityData update(String organizationId, String activityDataId,
                               ActivityUpdateRequest request) {
        ActivityData existing = get(organizationId, activityDataId);

        ActivityData candidate = new ActivityData();
        candidate.setDepartmentId(existing.getDepartmentId());
        candidate.setScope(existing.getScope());
        candidate.setStatus(existing.getStatus());
        candidate.setCategory(existing.getCategory());
        candidate.setActivityType(existing.getActivityType());
        candidate.setQuantity(existing.getQuantity());
        candidate.setUnit(existing.getUnit());
        candidate.setStartDate(existing.getStartDate());
        candidate.setEndDate(existing.getEndDate());
        candidate.setSource(existing.getSource());
        candidate.setNotes(existing.getNotes());

        if (request.getScope() != null) {
            candidate.setScope(GHGScope.valueOf(requireScope(request.getScope())));
        }
        if (request.getStatus() != null) {
            if (!EDITABLE_STATUSES.contains(request.getStatus())) {
                throw invalidActivity();
            }
            candidate.setStatus(request.getStatus());
        }
        if (request.getCategory() != null) {
            candidate.setCategory(requireText(request.getCategory(), "category", MAX_CATEGORY));
        }
        if (request.getActivityType() != null) {
            candidate.setActivityType(requireText(request.getActivityType(), "activityType", MAX_ACTIVITY_TYPE));
        }
        if (request.getUnit() != null) {
            candidate.setUnit(requireText(request.getUnit(), "unit", MAX_UNIT));
        }
        if (request.getQuantity() != null) {
            candidate.setQuantity(requireQuantity(request.getQuantity()));
        }
        if (request.getStartDate() != null) {
            candidate.setStartDate(requireDate(request.getStartDate()));
        }
        if (request.getEndDate() != null) {
            candidate.setEndDate(requireDate(request.getEndDate()));
        }
        if (request.getSource() != null) {
            candidate.setSource(requireText(request.getSource(), "source", MAX_SOURCE));
        }
        if (request.getNotes() != null) {
            candidate.setNotes(optionalNotes(request.getNotes()));
        }
        String departmentId = existing.getDepartmentId();
        if (request.getDepartmentId() != null) {
            departmentId = request.getDepartmentId().isEmpty()
                    ? null
                    : requireUuidField(request.getDepartmentId());
        }

        if (candidate.getEndDate().isBefore(candidate.getStartDate())) {
            throw invalidActivity();
        }
        if (departmentId != null && !departmentId.equals(existing.getDepartmentId())) {
            scope.requireDepartment(organizationId, departmentId);
        }
        locks.requireUnlockedPeriod(organizationId, existing.getReportingPeriodId());

        try {
            activities.update(organizationId, activityDataId, departmentId,
                    candidate.getScope().name(), candidate.getCategory(),
                    candidate.getActivityType(), candidate.getQuantity(), candidate.getUnit(),
                    candidate.getStartDate(), candidate.getEndDate(), candidate.getSource(),
                    candidate.getStatus(), candidate.getNotes());
        } catch (DataAccessException e) {
            throw unavailablePersistence();
        }
        return get(organizationId, activityDataId);
    }

    /**
     * Greenfield {@code POST /activity-data/:id/submit}: marks the record
     * {@code SUBMITTED} (idempotent) and stamps the submitter. The reporting
     * period must be unlocked; a malformed/foreign/unknown id is a single
     * {@code 404 ACTIVITY_NOT_FOUND}.
     */
    @Transactional
    public ActivityData submit(String organizationId, String userId, String activityDataId) {
        ActivityData existing = get(organizationId, activityDataId);
        locks.requireUnlockedPeriod(organizationId, existing.getReportingPeriodId());
        try {
            activities.updateStatusAndSubmitter(organizationId,
                    requireUuid(activityDataId), "SUBMITTED", userId);
        } catch (DataAccessException e) {
            throw unavailablePersistence();
        }
        return get(organizationId, activityDataId);
    }

    // ==========================================
    // Platform self-test diagnostics
    // ==========================================

    public int countForTenant(String organizationId) {
        return activities.countByOrganization(organizationId);
    }

    public int countTenantRelationshipViolations(String organizationId) {
        return activities.countTenantRelationshipViolations(organizationId);
    }

    // ==========================================
    // Validation helpers (Node parity)
    // ==========================================

    private void requireTenantRelationship(String organizationId, String facilityId,
                                           String reportingPeriodId) {
        Facility facility = facilities.findById(organizationId, facilityId).orElse(null);
        ReportingPeriod period = reportingPeriods.findById(organizationId, reportingPeriodId).orElse(null);
        if (facility == null || period == null) {
            throw invalidRelationship();
        }
    }

    private static void validateFilterUuid(String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        if (!UuidContract.isNodeUuid(value)) {
            throw invalidList();
        }
    }

    private static String requireUuidField(String value) {
        if (value == null || value.isBlank()) {
            throw invalidActivity();
        }
        String trimmed = value.trim();
        if (!UuidContract.isNodeUuid(trimmed)) {
            throw invalidActivity();
        }
        return trimmed;
    }

    private static String requireScope(String value) {
        if (value == null || !SUPPORTED_SCOPES.contains(value)) {
            throw invalidActivity();
        }
        return value;
    }

    /** Node's {@code requireText}: must be a string, non-blank, bounded. */
    private static String requireText(Object value, String field, int maximumLength) {
        if (!(value instanceof String text) || text.isBlank()
                || text.trim().length() > maximumLength) {
            throw invalidActivity();
        }
        return text.trim();
    }

    /**
     * Node's quantity contract: {@code Number(value)} must be finite and
     * non-negative — plus the documented scale/magnitude validation against
     * the {@code NUMERIC(16,4)} column (ADR-017: reject instead of letting
     * the database round silently).
     *
     * <p>Bounded deviations: an explicit JSON {@code null} is rejected rather
     * than coerced to {@code 0} like {@code Number(null)}, and booleans are
     * not coerced to 1/0.
     */
    private static BigDecimal requireQuantity(Object value) {
        BigDecimal quantity = null;
        if (value instanceof Number number) {
            quantity = BigDecimal.valueOf(number.doubleValue());
        } else if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.isEmpty()) {
                quantity = BigDecimal.ZERO;
            } else {
                try {
                    quantity = new BigDecimal(trimmed);
                } catch (NumberFormatException e) {
                    throw invalidActivity();
                }
            }
        }
        if (quantity == null || quantity.signum() < 0) {
            throw invalidActivity();
        }
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (normalized.scale() > MAX_QUANTITY_SCALE
                || normalized.precision() - normalized.scale() > MAX_QUANTITY_INTEGER_DIGITS) {
            throw invalidActivity();
        }
        return quantity;
    }

    private static LocalDate requireDate(String value) {
        if (value == null || !DATE_PATTERN.matcher(value).matches()) {
            throw invalidActivity();
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw invalidActivity();
        }
    }

    private static String optionalNotes(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text && text.isEmpty()) {
            return null;
        }
        return requireText(value, "notes", MAX_NOTES);
    }

    private static String optionalDepartmentId(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        return requireUuidField(value);
    }

    private static String requireUuid(String activityDataId) {
        if (activityDataId == null || activityDataId.isBlank()) {
            throw notFound();
        }
        try {
            UUID.fromString(activityDataId.trim());
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
        return activityDataId.trim();
    }

    private static AuthException notFound() {
        return new AuthException("ACTIVITY_NOT_FOUND", "Activity data not found.",
                HttpStatus.NOT_FOUND);
    }

    private static AuthException invalidActivity() {
        return new AuthException("VALIDATION_ERROR", "Activity data is invalid.",
                HttpStatus.BAD_REQUEST);
    }

    private static AuthException invalidRelationship() {
        return new AuthException("INVALID_ACTIVITY_RELATIONSHIP",
                "Facility and reporting period must belong to the authenticated organization.",
                HttpStatus.BAD_REQUEST);
    }

    private static AuthException invalidList() {
        // Node production routes.ts line 591 — exact text (ADR-017 parity note).
        return new AuthException("VALIDATION_ERROR",
                "Activity persistence is temporarily unavailable.", HttpStatus.BAD_REQUEST);
    }

    private static AuthException unavailableList() {
        return new AuthException("ACTIVITY_PERSISTENCE_UNAVAILABLE",
                "Activity persistence is temporarily unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static AuthException unavailablePersistence() {
        return new AuthException("ACTIVITY_PERSISTENCE_UNAVAILABLE",
                "Activity persistence is temporarily unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
    }
}

package com.carbonflow.service;

import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.ReportingPeriod;
import com.carbonflow.repository.ReportingPeriodRepository;
import com.carbonflow.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

/**
 * Reporting-period management (organization scoped, tenant isolated).
 *
 * <p>Node parity: list/create port {@code listReportingPeriods}
 * ({@code ORDER BY start_date DESC}) and the Node routes' validation matrix
 * — required fields answer {@code VALIDATION_ERROR} with Node's message, an
 * inverted range answers {@code INVALID_DATE_RANGE} with Node's dev-path
 * message (ADR-015 records that Node's production path answers
 * {@code VALIDATION_ERROR} for the same condition).
 *
 * <p><b>Business rules inspected before enforcing:</b> overlapping periods
 * are <i>not</i> restricted — neither the schema (only {@code end_date >=
 * start_date}), the Node backend, nor the documentation defines an overlap
 * rule (ADR-015). Lifecycle is the V1 {@code status} check
 * ({@code OPEN/UNDER_AUDIT/LOCKED}); any valid value may be set (no
 * transition graph is evidenced yet — the audit workflow of a later phase
 * may constrain it). There is no {@code reporting_periods.delete}
 * permission in the frozen 44-code matrix, so no delete endpoint exists
 * (405 by routing).
 */
@Service
public class ReportingPeriodService {

    static final Set<String> STATUSES = Set.of("OPEN", "UNDER_AUDIT", "LOCKED");

    private static final int MAX_NAME = 100;

    private final ReportingPeriodRepository repository;
    private final ScopeService scope;

    public ReportingPeriodService(ReportingPeriodRepository repository, ScopeService scope) {
        this.repository = repository;
        this.scope = scope;
    }

    public List<ReportingPeriod> list(String organizationId) {
        return repository.list(organizationId);
    }

    public ReportingPeriod get(String organizationId, String periodId) {
        return scope.requireReportingPeriod(organizationId, periodId);
    }

    public ReportingPeriod create(String organizationId, ScopeRequests.ReportingPeriodRequest request) {
        if (isBlank(request.getName()) || isBlank(request.getStartDate())
                || isBlank(request.getEndDate())) {
            throw validation("Period name, startDate, and endDate are required.");
        }
        String name = request.getName().trim();
        if (name.length() > MAX_NAME) {
            throw validation("Period name must be " + MAX_NAME + " characters or fewer.");
        }
        LocalDate start = parseDate(request.getStartDate());
        LocalDate end = parseDate(request.getEndDate());
        requireOrdered(start, end);
        String status = validateStatus(request.getStatus() == null || request.getStatus().isBlank()
                ? "OPEN" : request.getStatus().trim());
        return repository.insert(organizationId, name, start, end, status);
    }

    /** Partial update: name and/or the date pair (always together) and/or status. */
    public ReportingPeriod update(String organizationId, String periodId,
                                  ScopeRequests.ReportingPeriodRequest request) {
        ReportingPeriod existing = scope.requireReportingPeriod(organizationId, periodId);

        String name = existing.getName();
        if (request.getName() != null) {
            if (request.getName().isBlank()) {
                throw validation("Period name, startDate, and endDate are required.");
            }
            name = request.getName().trim();
            if (name.length() > MAX_NAME) {
                throw validation("Period name must be " + MAX_NAME + " characters or fewer.");
            }
        }
        LocalDate start = existing.getStartDate();
        LocalDate end = existing.getEndDate();
        boolean touchesDates = request.getStartDate() != null || request.getEndDate() != null;
        if (touchesDates) {
            if (request.getStartDate() == null || request.getEndDate() == null) {
                throw validation("startDate and endDate must be updated together.");
            }
            start = parseDate(request.getStartDate());
            end = parseDate(request.getEndDate());
            requireOrdered(start, end);
        }
        String status = existing.getStatus();
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            status = validateStatus(request.getStatus().trim());
        }
        repository.update(organizationId, existing.getId(), name, start, end, status);
        return scope.requireReportingPeriod(organizationId, existing.getId());
    }

    // ------------------------------------------------------------------

    private LocalDate parseDate(String raw) {
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw validation("Reporting period data is invalid.");
        }
    }

    private void requireOrdered(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new AuthException("INVALID_DATE_RANGE",
                    "endDate must be on or after startDate.", HttpStatus.BAD_REQUEST);
        }
    }

    private String validateStatus(String status) {
        if (!STATUSES.contains(status)) {
            throw validation("status must be one of "
                    + String.join(", ", STATUSES.stream().sorted().toList()) + ".");
        }
        return status;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}

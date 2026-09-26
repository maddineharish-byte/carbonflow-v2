package com.carbonflow.service;

import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.Facility;
import com.carbonflow.repository.FacilityRepository;
import com.carbonflow.service.AuthException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * Facility management (organization scoped, tenant isolated).
 *
 * <p>Node parity: list/create port {@code server/scope-repository.ts}
 * ({@code listFacilities}/{@code createFacility}) and the Node routes'
 * error matrix — {@code 409 DUPLICATE_FACILITY_CODE} for the V4 unique
 * {@code (organization_id, facility_code)} index, {@code 400
 * INVALID_FACILITY_RELATIONSHIP} for a foreign-key violation. Documented
 * deviations (ADR-015): a missing {@code facilityType} defaults to
 * {@code MANUFACTURING} (schema default and Node's dev path — Node's
 * production path contains a typo'd default that rejects the omission), and
 * {@code legalEntityId} is tenant-validated up front (Node's production path
 * never checked tenancy, which would have allowed cross-tenant linkage).
 *
 * <p><b>Deletion safety (FK inspection):</b> {@code departments} and
 * {@code boundary_facilities} cascade away with the facility;
 * {@code facilities.legal_entity_id} is reversed (the facility holds the FK);
 * {@code activity_data} and {@code emission_records} reference facilities
 * {@code ON DELETE RESTRICT} (V1/V6) — historical data therefore blocks hard
 * deletion and surfaces as {@code 409 RESOURCE_IN_USE}. The schema has no
 * status/active column, so delete is the only lifecycle operation available
 * (no soft-delete column invented).
 *
 * <p>Global product requirement: country/grid region are free-form; no
 * currency, time zone or regulatory default is applied anywhere.
 */
@Service
public class FacilityService {

    static final Set<String> FACILITY_TYPES =
            Set.of("MANUFACTURING", "OFFICE", "DATA_CENTER", "WAREHOUSE", "RETAIL", "LOGISTICS");

    private static final int MAX_NAME = 255;
    private static final int MAX_CODE = 50;
    private static final int MAX_COUNTRY = 10;
    private static final int MAX_STATE = 100;
    private static final int MAX_GRID = 100;

    private final FacilityRepository repository;
    private final ScopeService scope;

    public FacilityService(FacilityRepository repository, ScopeService scope) {
        this.repository = repository;
        this.scope = scope;
    }

    public List<Facility> list(String organizationId) {
        return repository.list(organizationId);
    }

    public Facility get(String organizationId, String facilityId) {
        return scope.requireFacility(organizationId, facilityId);
    }

    public Facility create(String organizationId, ScopeRequests.FacilityRequest request) {
        ValidatedFacility v = validate(request);
        String legalEntityId = resolveLegalEntity(organizationId, request.getLegalEntityId());
        try {
            return repository.insert(organizationId, legalEntityId, v.name, v.facilityCode,
                    v.facilityType, v.country, v.stateProvince, v.gridRegion, v.floorAreaM2);
        } catch (DuplicateKeyException e) {
            throw new AuthException("DUPLICATE_FACILITY_CODE",
                    "Facility code already exists.", HttpStatus.CONFLICT);
        } catch (DataIntegrityViolationException e) {
            throw new AuthException("INVALID_FACILITY_RELATIONSHIP",
                    "Facility relationship is invalid.", HttpStatus.BAD_REQUEST);
        }
    }

    /** Full-replace update: same required-field contract as create. */
    public Facility update(String organizationId, String facilityId,
                           ScopeRequests.FacilityRequest request) {
        Facility existing = scope.requireFacility(organizationId, facilityId);
        ValidatedFacility v = validate(request);
        String legalEntityId = resolveLegalEntity(organizationId, request.getLegalEntityId());
        try {
            repository.update(organizationId, existing.getId(), legalEntityId, v.name,
                    v.facilityCode, v.facilityType, v.country, v.stateProvince, v.gridRegion,
                    v.floorAreaM2);
        } catch (DuplicateKeyException e) {
            throw new AuthException("DUPLICATE_FACILITY_CODE",
                    "Facility code already exists.", HttpStatus.CONFLICT);
        } catch (DataIntegrityViolationException e) {
            throw new AuthException("INVALID_FACILITY_RELATIONSHIP",
                    "Facility relationship is invalid.", HttpStatus.BAD_REQUEST);
        }
        return scope.requireFacility(organizationId, existing.getId());
    }

    public void delete(String organizationId, String facilityId) {
        Facility existing = scope.requireFacility(organizationId, facilityId);
        try {
            repository.delete(organizationId, existing.getId());
        } catch (DataIntegrityViolationException e) {
            // ON DELETE RESTRICT from activity_data / emission_records (V1/V6).
            throw new AuthException("RESOURCE_IN_USE",
                    "Facility is referenced by existing records and cannot be deleted.",
                    HttpStatus.CONFLICT);
        }
    }

    // ------------------------------------------------------------------

    private record ValidatedFacility(String name, String facilityCode, String facilityType,
                                     String country, String stateProvince, String gridRegion,
                                     Double floorAreaM2) {
    }

    private ValidatedFacility validate(ScopeRequests.FacilityRequest request) {
        if (isBlank(request.getName()) || isBlank(request.getFacilityCode())
                || isBlank(request.getCountry()) || isBlank(request.getGridRegion())) {
            throw validation("Facility name, code, country, and grid region are required.");
        }
        String name = request.getName().trim();
        String code = request.getFacilityCode().trim();
        String country = request.getCountry().trim();
        String gridRegion = request.getGridRegion().trim();
        if (name.length() > MAX_NAME) {
            throw validation("Facility name must be " + MAX_NAME + " characters or fewer.");
        }
        if (code.length() > MAX_CODE) {
            throw validation("Facility code must be " + MAX_CODE + " characters or fewer.");
        }
        if (country.length() > MAX_COUNTRY) {
            throw validation("Country must be " + MAX_COUNTRY + " characters or fewer.");
        }
        String facilityType = request.getFacilityType() == null || request.getFacilityType().isBlank()
                ? "MANUFACTURING" : request.getFacilityType().trim().toUpperCase();
        if (!FACILITY_TYPES.contains(facilityType)) {
            throw validation("facilityType must be one of "
                    + String.join(", ", FACILITY_TYPES.stream().sorted().toList()) + ".");
        }
        String stateProvince = trimmedOrNull(request.getStateProvince());
        if (stateProvince != null && stateProvince.length() > MAX_STATE) {
            throw validation("stateProvince must be " + MAX_STATE + " characters or fewer.");
        }
        if (gridRegion.length() > MAX_GRID) {
            throw validation("gridRegion must be " + MAX_GRID + " characters or fewer.");
        }
        Double floorArea = request.getFloorAreaM2();
        if (floorArea != null && (!Double.isFinite(floorArea) || floorArea < 0)) {
            throw validation("floorAreaM2 must be a non-negative number.");
        }
        return new ValidatedFacility(name, code, facilityType, country, stateProvince,
                gridRegion, floorArea);
    }

    /** {@code legalEntityId} must exist inside the caller's tenant (404 otherwise). */
    private String resolveLegalEntity(String organizationId, String legalEntityId) {
        if (legalEntityId == null || legalEntityId.isBlank()) {
            return null;
        }
        return scope.requireLegalEntity(organizationId, legalEntityId).getId();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}

package com.carbonflow.service;

import com.carbonflow.dto.ScopeRequests;
import com.carbonflow.model.LegalEntity;
import com.carbonflow.repository.LegalEntityRepository;
import com.carbonflow.service.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Legal-entity management (organization scoped).
 *
 * <p>Node parity: {@code GET /legal-entities} mirrors
 * {@code listLegalEntities} (org predicate, {@code ORDER BY name});
 * {@code POST /legal-entities} follows the documented contract in
 * docs/API.md §2.2 ({@code organization.update}) — the Node backend never
 * implemented the write side (ADR-015 records the greenfield semantics).
 *
 * <p><b>Deletion safety (FK inspection):</b> the only foreign key into
 * {@code legal_entities} is {@code facilities.legal_entity_id} with
 * {@code ON DELETE SET NULL} (V1) — deleting a legal entity never deletes a
 * facility; it simply leaves the facility unlinked. No other table
 * references legal entities, so hard deletion is safe and is implemented as
 * a tenant-guarded row delete.
 *
 * <p>The schema defines no uniqueness on name or registration number, so
 * duplicates are accepted (no constraint invented — ADR-015).
 */
@Service
public class LegalEntityService {

    private static final int MAX_NAME = 255;
    private static final int MAX_JURISDICTION = 100;
    private static final int MAX_REGISTRATION = 100;

    private final LegalEntityRepository repository;
    private final ScopeService scope;

    public LegalEntityService(LegalEntityRepository repository, ScopeService scope) {
        this.repository = repository;
        this.scope = scope;
    }

    public List<LegalEntity> list(String organizationId) {
        return repository.list(organizationId);
    }

    public LegalEntity get(String organizationId, String legalEntityId) {
        return scope.requireLegalEntity(organizationId, legalEntityId);
    }

    public LegalEntity create(String organizationId, ScopeRequests.LegalEntityRequest request) {
        String name = requireText(request.getName(), "Legal entity name and jurisdiction are required.");
        String jurisdiction = requireText(request.getJurisdiction(),
                "Legal entity name and jurisdiction are required.");
        length(name, MAX_NAME, "Legal entity name");
        length(jurisdiction, MAX_JURISDICTION, "Jurisdiction");
        String registration = trimmedOrNull(request.getRegistrationNumber());
        if (registration != null) {
            length(registration, MAX_REGISTRATION, "Registration number");
        }
        Double ownership = request.getOwnershipPercentage() == null
                ? 100.0 : request.getOwnershipPercentage();
        if (!Double.isFinite(ownership) || ownership < 0 || ownership > 100) {
            throw validation("ownershipPercentage must be between 0 and 100.");
        }
        return repository.insert(organizationId, name, jurisdiction, registration, ownership);
    }

    /** Partial update: only non-null fields are validated and written. */
    public LegalEntity update(String organizationId, String legalEntityId,
                              ScopeRequests.LegalEntityRequest request) {
        LegalEntity existing = scope.requireLegalEntity(organizationId, legalEntityId);

        String name = existing.getName();
        if (request.getName() != null) {
            name = requireText(request.getName(), "Legal entity name and jurisdiction are required.");
            length(name, MAX_NAME, "Legal entity name");
        }
        String jurisdiction = existing.getJurisdiction();
        if (request.getJurisdiction() != null) {
            jurisdiction = requireText(request.getJurisdiction(),
                    "Legal entity name and jurisdiction are required.");
            length(jurisdiction, MAX_JURISDICTION, "Jurisdiction");
        }
        String registration = existing.getRegistrationNumber();
        if (request.getRegistrationNumber() != null) {
            registration = trimmedOrNull(request.getRegistrationNumber());
            if (registration != null) {
                length(registration, MAX_REGISTRATION, "Registration number");
            }
        }
        Double ownership = existing.getOwnershipPercentage();
        if (request.getOwnershipPercentage() != null) {
            ownership = request.getOwnershipPercentage();
            if (!Double.isFinite(ownership) || ownership < 0 || ownership > 100) {
                throw validation("ownershipPercentage must be between 0 and 100.");
            }
        }
        repository.update(organizationId, existing.getId(), name, jurisdiction,
                registration, ownership);
        return scope.requireLegalEntity(organizationId, existing.getId());
    }

    public void delete(String organizationId, String legalEntityId) {
        LegalEntity existing = scope.requireLegalEntity(organizationId, legalEntityId);
        repository.delete(organizationId, existing.getId());
    }

    // ------------------------------------------------------------------

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw validation(message);
        }
        return value.trim();
    }

    private String trimmedOrNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void length(String value, int max, String field) {
        if (value.length() > max) {
            throw validation(field + " must be " + max + " characters or fewer.");
        }
    }

    private AuthException validation(String message) {
        return new AuthException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}

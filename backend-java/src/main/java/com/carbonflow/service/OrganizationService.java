package com.carbonflow.service;

import com.carbonflow.config.TenantContext;
import com.carbonflow.dto.OrganizationRequests.UpdateOrganizationRequest;
import com.carbonflow.model.Organization;
import com.carbonflow.repository.OrganizationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * {@code GET/PUT /organizations/current} — the Node contract from
 * {@code server/routes.ts}: 404 {@code ORG_NOT_FOUND} when the tenant row is
 * missing, partial updates (only provided fields), message
 * {@code "Organization updated successfully."}.
 *
 * <p>Deviation (documented): consolidation approach and base year are
 * validated against the V1 CHECK constraints so invalid input answers 400
 * instead of surfacing as a database failure.
 */
@Service
public class OrganizationService {

    /** The three values of the V1 CHECK constraint on organization_settings. */
    static final Set<String> CONSOLIDATION_APPROACHES =
            Set.of("OPERATIONAL_CONTROL", "FINANCIAL_CONTROL", "EQUITY_SHARE");

    private final OrganizationRepository organizationRepository;

    public OrganizationService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Transactional(readOnly = true)
    public Organization current(TenantContext context) {
        try {
            return organizationRepository.findById(context.getOrganizationId())
                    .orElseThrow(() -> new AuthException("ORG_NOT_FOUND",
                            "Organization not found.", HttpStatus.NOT_FOUND));
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("Organization persistence is temporarily unavailable.", e);
        }
    }

    @Transactional
    public Organization update(TenantContext context, UpdateOrganizationRequest request) {
        try {
            Organization organization = organizationRepository.findById(context.getOrganizationId())
                    .orElseThrow(() -> new AuthException("ORG_NOT_FOUND",
                            "Organization not found.", HttpStatus.NOT_FOUND));
            if (request != null) {
                if (!isBlank(request.getName())) {
                    organization.setName(request.getName().trim());
                }
                if (!isBlank(request.getCountry())) {
                    organization.setCountry(request.getCountry().trim());
                }
                if (!isBlank(request.getIndustry())) {
                    organization.setIndustry(request.getIndustry().trim());
                }
                if (!isBlank(request.getConsolidationApproach())) {
                    String approach = request.getConsolidationApproach().trim();
                    if (!CONSOLIDATION_APPROACHES.contains(approach)) {
                        throw new AuthException("VALIDATION_ERROR",
                                "consolidationApproach must be one of OPERATIONAL_CONTROL, FINANCIAL_CONTROL, EQUITY_SHARE.",
                                HttpStatus.BAD_REQUEST);
                    }
                    organization.setConsolidationApproach(approach);
                }
                if (request.getBaseYear() != null) {
                    int baseYear = request.getBaseYear();
                    if (baseYear < 1900 || baseYear > 2200) {
                        throw new AuthException("VALIDATION_ERROR",
                                "baseYear must be between 1900 and 2200.", HttpStatus.BAD_REQUEST);
                    }
                    organization.setBaseYear(baseYear);
                }
            }
            organizationRepository.updateProfile(organization);
            return organizationRepository.findById(context.getOrganizationId())
                    .orElseThrow(() -> new AuthException("ORG_NOT_FOUND",
                            "Organization not found.", HttpStatus.NOT_FOUND));
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("Organization persistence is temporarily unavailable.", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

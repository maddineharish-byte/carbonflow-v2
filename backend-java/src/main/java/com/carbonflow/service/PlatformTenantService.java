package com.carbonflow.service;

import com.carbonflow.config.TenantContext;
import com.carbonflow.model.Organization;
import com.carbonflow.model.enums.OrganizationStatus;
import com.carbonflow.repository.OrganizationRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Platform tenant administration ({@code /platform/tenants}, greenfield,
 * gated by {@code platform.tenants.read|manage} — ADR-014).
 *
 * <p>These are the only endpoints that cross tenant boundaries: they are
 * authorized by the platform permissions (PLATFORM_ADMIN only), never by a
 * tenant-scoped predicate. Transitions:
 *
 * <pre>
 * PENDING_ACTIVATION --approve--> ACTIVE
 * PENDING_ACTIVATION --reject---> REJECTED
 * ACTIVE             --suspend--> SUSPENDED
 * SUSPENDED/REJECTED --approve--> ACTIVE
 * </pre>
 *
 * Every transition records actor and timestamp (V8 columns).
 */
@Service
public class PlatformTenantService {

    private final OrganizationRepository organizationRepository;

    public PlatformTenantService(OrganizationRepository organizationRepository) {
        this.organizationRepository = organizationRepository;
    }

    @Transactional(readOnly = true)
    public List<Organization> list(String status) {
        try {
            if (status == null || status.isBlank()) {
                return organizationRepository.list(null);
            }
            String normalized = status.trim().toUpperCase();
            try {
                OrganizationStatus.fromDb(normalized);
            } catch (IllegalStateException e) {
                throw new AuthException("VALIDATION_ERROR",
                        "status must be one of PENDING_ACTIVATION, ACTIVE, REJECTED, SUSPENDED.",
                        HttpStatus.BAD_REQUEST);
            }
            return organizationRepository.list(normalized);
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("Tenant persistence is temporarily unavailable.", e);
        }
    }

    @Transactional
    public Organization transition(String organizationId, OrganizationStatus target,
                                   TenantContext context, String note) {
        try {
            Organization organization = organizationRepository.findById(organizationId)
                    .orElseThrow(() -> new AuthException("ORGANIZATION_NOT_FOUND",
                            "Organization does not exist or access denied.", HttpStatus.NOT_FOUND));
            organizationRepository.updateStatus(organization.getId(), target,
                    context.getUserId(), note == null || note.isBlank() ? null : note.trim());
            return organizationRepository.findById(organization.getId())
                    .orElseThrow(() -> new AuthException("ORGANIZATION_NOT_FOUND",
                            "Organization does not exist or access denied.", HttpStatus.NOT_FOUND));
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("Tenant persistence is temporarily unavailable.", e);
        }
    }
}

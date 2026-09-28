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
 * tenant-scoped predicate. Transitions (task 7.7 now <b>enforces</b> this
 * state machine — previously documented but unchecked, and the SQL write is
 * guarded by the expected from-status so a concurrent transition answers 409
 * instead of overwriting):
 *
 * <pre>
 * PENDING_ACTIVATION --approve--> ACTIVE
 * PENDING_ACTIVATION --reject---> REJECTED
 * ACTIVE             --suspend--> SUSPENDED
 * SUSPENDED/REJECTED --approve--> ACTIVE
 * </pre>
 *
 * Anything else answers 409 {@code INVALID_STATUS_TRANSITION}; malformed,
 * unknown and (for detail reads) inaccessible organization ids collapse into
 * the same 404 so the response never encodes whether an id exists.
 *
 * Every transition records actor and timestamp (V8 columns), exposed by the
 * list and detail endpoints.
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

    /** Tenant detail (task 7.7): full row incl. the V8 audit columns. */
    @Transactional(readOnly = true)
    public Organization get(String organizationId) {
        return findOr404(organizationId);
    }

    @Transactional
    public Organization transition(String organizationId, OrganizationStatus target,
                                   TenantContext context, String note) {
        try {
            Organization organization = findOr404(organizationId);
            OrganizationStatus from = organization.getStatus();
            if (!isAllowed(from, target)) {
                throw new AuthException("INVALID_STATUS_TRANSITION",
                        "Organization status transition from " + from.name() + " to "
                                + target.name() + " is not allowed.",
                        HttpStatus.CONFLICT);
            }
            int updated = organizationRepository.updateStatus(organization.getId(), target, from,
                    context.getUserId(), note == null || note.isBlank() ? null : note.trim());
            if (updated != 1) {
                // The status moved between read and write — same contract as a
                // stale from-state: refuse, never overwrite silently.
                throw new AuthException("INVALID_STATUS_TRANSITION",
                        "Organization status transition from " + from.name() + " to "
                                + target.name() + " is not allowed.",
                        HttpStatus.CONFLICT);
            }
            return organizationRepository.findById(organization.getId())
                    .orElseThrow(() -> new AuthException("ORGANIZATION_NOT_FOUND",
                            "Organization does not exist or access denied.", HttpStatus.NOT_FOUND));
        } catch (DataAccessException e) {
            throw new AuthPersistenceException("Tenant persistence is temporarily unavailable.", e);
        }
    }

    /**
     * The documented state machine (see class javadoc). Node has no platform
     * routes at all — these endpoints are Java-only greenfield (ADR-014), so
     * this table is the contract, mirrored 1:1 in docs/API.md §2.10.
     */
    private static boolean isAllowed(OrganizationStatus from, OrganizationStatus to) {
        return switch (from) {
            case PENDING_ACTIVATION -> to == OrganizationStatus.ACTIVE
                    || to == OrganizationStatus.REJECTED;
            case ACTIVE -> to == OrganizationStatus.SUSPENDED;
            case SUSPENDED, REJECTED -> to == OrganizationStatus.ACTIVE;
        };
    }

    /**
     * Malformed ids are answered exactly like unknown ones (404) — the
     * platform role legitimately sees all tenants, but the response still
     * never distinguishes "malformed" from "absent", and a non-UUID can
     * never reach SQL as a cast error.
     */
    private Organization findOr404(String organizationId) {
        if (!UuidContract.isNodeUuid(organizationId)) {
            throw new AuthException("ORGANIZATION_NOT_FOUND",
                    "Organization does not exist or access denied.", HttpStatus.NOT_FOUND);
        }
        return organizationRepository.findById(organizationId.trim())
                .orElseThrow(() -> new AuthException("ORGANIZATION_NOT_FOUND",
                        "Organization does not exist or access denied.", HttpStatus.NOT_FOUND));
    }
}

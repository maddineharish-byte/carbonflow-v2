package com.carbonflow.service;

import com.carbonflow.model.CarbonAudit;
import com.carbonflow.repository.AuditRepository;
import com.carbonflow.repository.ReportingPeriodRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Phase 5 → Phase 6 integration point: accounting writes must respect the
 * audit freeze.
 *
 * <p><b>Rule (documented deviation, ADR-017):</b> activity data and
 * calculations belonging to a locked reporting period are rejected with
 * {@code 409 AUDIT_LOCKED} instead of silently mutating audited history. The
 * Node reference does not enforce this in its accounting routes — Phase 5's
 * Java backend owns the audit machine, and once a period is frozen (audit
 * {@code LOCKED}, or the period row itself locked) no accounting API may
 * change what the audit certified. Batch runs skip locked-period activities
 * for the same reason (they are reported as unprocessed).
 *
 * <p>Both lock signals are checked through existing Phase 4/5 read methods —
 * no Phase 5 code was modified:
 * <ul>
 *   <li>{@code reporting_periods.status = 'LOCKED'} (the audit machine's
 *       transactional freeze, or an explicit period lock),</li>
 *   <li>any {@code carbon_audits} row for the period in {@code LOCKED} state
 *       (defense in depth if the two ever diverge).</li>
 * </ul>
 */
@Service
public class AccountingLockGuard {

    private final ReportingPeriodRepository reportingPeriods;
    private final AuditRepository audits;

    public AccountingLockGuard(ReportingPeriodRepository reportingPeriods,
                               AuditRepository audits) {
        this.reportingPeriods = reportingPeriods;
        this.audits = audits;
    }

    /** @throws AuthException 409 AUDIT_LOCKED when the period is frozen. */
    public void requireUnlockedPeriod(String organizationId, String periodId) {
        if (isLocked(organizationId, periodId)) {
            throw locked();
        }
    }

    /** Non-throwing form — used by batch runs to skip (not fail) locked periods. */
    public boolean isLocked(String organizationId, String periodId) {
        if (periodId == null) {
            return false;
        }
        boolean periodLocked = reportingPeriods.findById(organizationId, periodId)
                .map(period -> "LOCKED".equals(period.getStatus()))
                .orElse(false);
        if (periodLocked) {
            return true;
        }
        for (CarbonAudit audit : audits.list(organizationId)) {
            if (periodId.equals(audit.getReportingPeriodId())
                    && "LOCKED".equals(audit.getStatus())) {
                return true;
            }
        }
        return false;
    }

    public static AuthException locked() {
        return new AuthException("AUDIT_LOCKED",
                "Reporting period is locked and cannot be modified.", HttpStatus.CONFLICT);
    }
}

package com.carbonflow.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The canonical CarbonFlow audit state machine — the <b>only</b> audit-state
 * model in the Java backend (ADR-005 + V7 ten-state constraint +
 * docs/AUDIT_WORKFLOW.md).
 *
 * <pre>
 * DRAFT → SUBMITTED → DATA_COLLECTION → VALIDATION → REVIEW
 * REVIEW → APPROVED | CORRECTION_REQUESTED | REJECTED
 * CORRECTION_REQUESTED → DATA_COLLECTION
 * REJECTED → DATA_COLLECTION
 * APPROVED → AUDIT_READY → LOCKED (terminal)
 * </pre>
 *
 * <p>The server decides whether a transition is legal: clients submit a
 * target state, never a guarantee. Skipped states, unknown targets and any
 * transition out of LOCKED are rejected with the Node reference's
 * {@code INVALID_TRANSITION} message so the contract stays identical.
 *
 * <p>Each transition also names the single frozen-matrix permission code
 * that authorizes it (API.md §2.6 lists the endpoint-level union of all
 * four). CARBON_ACCOUNTANT holds only {@code audits.read} in the frozen
 * matrix, so it cannot perform transitions even where the workflow doc's
 * advisory role column mentions it (documented in ADR-016).
 */
public final class AuditStateMachine {

    private AuditStateMachine() {
    }

    /** Every legal source→targets pair. LOCKED is absent: it is terminal. */
    private static final Map<String, List<String>> TRANSITIONS = Map.of(
            "DRAFT", List.of("SUBMITTED"),
            "SUBMITTED", List.of("DATA_COLLECTION"),
            "DATA_COLLECTION", List.of("VALIDATION"),
            "VALIDATION", List.of("REVIEW"),
            "REVIEW", List.of("APPROVED", "CORRECTION_REQUESTED", "REJECTED"),
            "CORRECTION_REQUESTED", List.of("DATA_COLLECTION"),
            "REJECTED", List.of("DATA_COLLECTION"),
            "APPROVED", List.of("AUDIT_READY"),
            "AUDIT_READY", List.of("LOCKED"));

    /** The ten V7 states; anything else is an unknown target. */
    public static final Set<String> STATES = Set.of(
            "DRAFT", "SUBMITTED", "DATA_COLLECTION", "VALIDATION", "REVIEW",
            "APPROVED", "AUDIT_READY", "LOCKED", "CORRECTION_REQUESTED", "REJECTED");

    /** Frozen-matrix permission required for a specific transition. */
    private static final Map<String, String> REQUIRED_PERMISSION = Map.ofEntries(
            Map.entry("DRAFT->SUBMITTED", "audits.submit"),
            Map.entry("SUBMITTED->DATA_COLLECTION", "audits.submit"),
            Map.entry("DATA_COLLECTION->VALIDATION", "audits.submit"),
            Map.entry("VALIDATION->REVIEW", "audits.submit"),
            Map.entry("REVIEW->APPROVED", "audits.approve"),
            Map.entry("REVIEW->CORRECTION_REQUESTED", "audits.review"),
            Map.entry("REVIEW->REJECTED", "audits.review"),
            Map.entry("CORRECTION_REQUESTED->DATA_COLLECTION", "audits.submit"),
            Map.entry("REJECTED->DATA_COLLECTION", "audits.submit"),
            Map.entry("APPROVED->AUDIT_READY", "audits.lock"),
            Map.entry("AUDIT_READY->LOCKED", "audits.lock"));

    /** Targets permitted from {@code current} (empty for terminal/unknown). */
    public static List<String> allowedTargets(String current) {
        if (current == null) {
            return List.of();
        }
        return TRANSITIONS.getOrDefault(current, List.of());
    }

    public static boolean isAllowed(String current, String target) {
        return allowedTargets(current).contains(target);
    }

    /**
     * The one permission code that authorizes this transition, or
     * {@code null} when the transition itself is illegal (call
     * {@link #isAllowed} first).
     */
    public static String requiredPermission(String current, String target) {
        return REQUIRED_PERMISSION.get(current + "->" + target);
    }

    /** True when the given target must pass checklist + findings gates. */
    public static boolean requiresGovernedPrerequisites(String target) {
        return "APPROVED".equals(target) || "AUDIT_READY".equals(target)
                || "LOCKED".equals(target);
    }

    /**
     * Node-identical rejection message ({@code routes.ts} transition
     * handler): {@code Cannot transition audit from 'X' to 'Y'. Valid
     * targets: A, B.}
     */
    public static String invalidTransitionMessage(String current, String target) {
        return "Cannot transition audit from '" + current + "' to '" + target
                + "'. Valid targets: " + String.join(", ", allowedTargets(current)) + ".";
    }
}

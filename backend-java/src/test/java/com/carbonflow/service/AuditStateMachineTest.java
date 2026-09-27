package com.carbonflow.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema/contract parity for the canonical audit state machine — an
 * exhaustive 10×10 source→target check against the V1 + V7 ten-state
 * constraint and docs/AUDIT_WORKFLOW.md, plus the Node rejection-message
 * contract and the frozen-matrix permission attached to every edge.
 */
class AuditStateMachineTest {

    /** The eleven legal edges of the canonical workflow. */
    private static final Map<String, Set<String>> EXPECTED = new HashMap<>();

    static {
        EXPECTED.put("DRAFT", Set.of("SUBMITTED"));
        EXPECTED.put("SUBMITTED", Set.of("DATA_COLLECTION"));
        EXPECTED.put("DATA_COLLECTION", Set.of("VALIDATION"));
        EXPECTED.put("VALIDATION", Set.of("REVIEW"));
        EXPECTED.put("REVIEW",
                Set.of("APPROVED", "CORRECTION_REQUESTED", "REJECTED"));
        EXPECTED.put("CORRECTION_REQUESTED", Set.of("DATA_COLLECTION"));
        EXPECTED.put("REJECTED", Set.of("DATA_COLLECTION"));
        EXPECTED.put("APPROVED", Set.of("AUDIT_READY"));
        EXPECTED.put("AUDIT_READY", Set.of("LOCKED"));
        EXPECTED.put("LOCKED", Set.of());
    }

    private static final Set<String> TEN_STATES = Set.of("DRAFT", "SUBMITTED",
            "DATA_COLLECTION", "VALIDATION", "REVIEW", "APPROVED",
            "AUDIT_READY", "LOCKED", "CORRECTION_REQUESTED", "REJECTED");

    @Test
    void exhaustiveEdgeParityWithTheV7TenStateConstraint() {
        assertEquals(TEN_STATES, AuditStateMachine.STATES,
                "exactly the ten V7 states — no second state model");
        assertEquals(TEN_STATES.size(), 10);

        int legalEdges = 0;
        for (String source : TEN_STATES) {
            for (String target : TEN_STATES) {
                boolean expected = EXPECTED.get(source).contains(target);
                assertEquals(expected,
                        AuditStateMachine.isAllowed(source, target),
                        source + " -> " + target);
                if (expected) {
                    legalEdges++;
                }
            }
        }
        assertEquals(11, legalEdges, "eleven canonical edges");
    }

    @Test
    void lockedIsTerminalAndSkippedStatesAreRejected() {
        assertTrue(AuditStateMachine.allowedTargets("LOCKED").isEmpty(),
                "LOCKED has no outgoing transitions");
        assertFalse(AuditStateMachine.isAllowed("LOCKED", "LOCKED"));
        assertFalse(AuditStateMachine.isAllowed("DRAFT", "REVIEW"),
                "no skipped states");
        assertFalse(AuditStateMachine.isAllowed("DRAFT", "APPROVED"));
        assertFalse(AuditStateMachine.isAllowed("REVIEW", "AUDIT_READY"));
        assertFalse(AuditStateMachine.isAllowed("SUBMITTED", "DRAFT"),
                "no backwards transitions");
        assertFalse(AuditStateMachine.isAllowed("DRAFT", "SEALED"),
                "unknown targets are rejected");
        assertFalse(AuditStateMachine.isAllowed(null, "SUBMITTED"));
        assertTrue(AuditStateMachine.allowedTargets(null).isEmpty());
    }

    @Test
    void everyLegalEdgeCarriesAFrozenMatrixPermission() {
        for (String source : TEN_STATES) {
            for (String target : EXPECTED.get(source)) {
                assertNotNull(AuditStateMachine.requiredPermission(source, target),
                        source + " -> " + target + " must name a permission");
            }
        }
        assertEquals("audits.submit",
                AuditStateMachine.requiredPermission("DRAFT", "SUBMITTED"));
        assertEquals("audits.submit",
                AuditStateMachine.requiredPermission("REJECTED", "DATA_COLLECTION"));
        assertEquals("audits.approve",
                AuditStateMachine.requiredPermission("REVIEW", "APPROVED"));
        assertEquals("audits.review",
                AuditStateMachine.requiredPermission("REVIEW", "REJECTED"));
        assertEquals("audits.review",
                AuditStateMachine.requiredPermission(
                        "REVIEW", "CORRECTION_REQUESTED"));
        assertEquals("audits.lock",
                AuditStateMachine.requiredPermission("APPROVED", "AUDIT_READY"));
        assertEquals("audits.lock",
                AuditStateMachine.requiredPermission("AUDIT_READY", "LOCKED"));

        // Illegal edges resolve to no permission at all (fail closed).
        assertNull(AuditStateMachine.requiredPermission("DRAFT", "LOCKED"));
        assertNull(AuditStateMachine.requiredPermission("LOCKED", "REVIEW"));
        assertNull(AuditStateMachine.requiredPermission("DRAFT", "SEALED"));
    }

    @Test
    void governedPrerequisitesCoverExactlyApprovalReadinessAndLock() {
        assertTrue(AuditStateMachine.requiresGovernedPrerequisites("APPROVED"));
        assertTrue(AuditStateMachine.requiresGovernedPrerequisites("AUDIT_READY"));
        assertTrue(AuditStateMachine.requiresGovernedPrerequisites("LOCKED"));
        assertFalse(AuditStateMachine.requiresGovernedPrerequisites("REVIEW"));
        assertFalse(AuditStateMachine.requiresGovernedPrerequisites("DRAFT"));
        assertFalse(AuditStateMachine.requiresGovernedPrerequisites("SEALED"));
    }

    @Test
    void rejectionMessagesMatchTheNodeReferenceByteForByte() {
        assertEquals("Cannot transition audit from 'DRAFT' to 'REVIEW'. "
                        + "Valid targets: SUBMITTED.",
                AuditStateMachine.invalidTransitionMessage("DRAFT", "REVIEW"));
        assertEquals("Cannot transition audit from 'REVIEW' to 'AUDIT_READY'. "
                        + "Valid targets: APPROVED, CORRECTION_REQUESTED, REJECTED.",
                AuditStateMachine.invalidTransitionMessage(
                        "REVIEW", "AUDIT_READY"));
        assertEquals("Cannot transition audit from 'LOCKED' to 'SUBMITTED'. "
                        + "Valid targets: .",
                AuditStateMachine.invalidTransitionMessage(
                        "LOCKED", "SUBMITTED"));
        assertEquals("Cannot transition audit from 'DRAFT' to 'SEALED'. "
                        + "Valid targets: SUBMITTED.",
                AuditStateMachine.invalidTransitionMessage("DRAFT", "SEALED"));
    }

    @Test
    void allowedTargetsMatchesTheExpectedSetsExactly() {
        for (String source : TEN_STATES) {
            assertEquals(EXPECTED.get(source),
                    new HashSet<>(AuditStateMachine.allowedTargets(source)),
                    "targets from " + source);
        }
        // Ordering of REVIEW's targets follows the workflow documentation.
        assertEquals(List.of("APPROVED", "CORRECTION_REQUESTED", "REJECTED"),
                AuditStateMachine.allowedTargets("REVIEW"));
    }
}

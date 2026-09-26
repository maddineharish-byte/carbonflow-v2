-- CarbonFlow Flyway Migration V7: Audit status alignment (10-state machine)
--
-- The application state machine (docs/AUDIT_WORKFLOW.md, server/routes.ts,
-- server/types.ts) allows ten audit states, while the V1 CHECK constraint only
-- allowed eight. Persisting an audit in CORRECTION_REQUESTED or REJECTED would
-- therefore fail at the database level.
--
-- This migration extends carbon_audits.status to the full ten-state machine:
--
--   DRAFT -> SUBMITTED -> DATA_COLLECTION -> VALIDATION -> REVIEW
--   REVIEW -> APPROVED | CORRECTION_REQUESTED | REJECTED
--   CORRECTION_REQUESTED -> DATA_COLLECTION
--   REJECTED -> DATA_COLLECTION
--   APPROVED -> AUDIT_READY -> LOCKED (terminal)
--
-- The legacy constraint is located by definition (not by assumed name) so the
-- migration works regardless of how PostgreSQL auto-named the inline CHECK.

DO $$
DECLARE
    constraint_name TEXT;
BEGIN
    SELECT conname INTO constraint_name
    FROM pg_constraint
    WHERE conrelid = 'carbon_audits'::regclass
      AND contype = 'c'
      AND pg_get_constraintdef(oid) LIKE '%AUDIT_READY%';

    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE carbon_audits DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE carbon_audits
    ADD CONSTRAINT carbon_audits_status_check
        CHECK (status IN (
            'DRAFT',
            'SUBMITTED',
            'DATA_COLLECTION',
            'VALIDATION',
            'REVIEW',
            'APPROVED',
            'AUDIT_READY',
            'LOCKED',
            'CORRECTION_REQUESTED',
            'REJECTED'
        ));

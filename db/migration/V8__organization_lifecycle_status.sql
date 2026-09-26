-- CarbonFlow V8: Organization lifecycle status (Phase 3 — Identity & Tenant Core)
--
-- ADR-014: organizations created through public registration start as
-- PENDING_ACTIVATION and become ACTIVE only after a platform administrator
-- approves them (docs/API.md: "POST /api/v1/auth/register — Register user &
-- organization"). Existing rows default to ACTIVE, so this migration is
-- backward-compatible with data created before the lifecycle existed.

ALTER TABLE organizations
    ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN status_changed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN status_changed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN status_note TEXT;

ALTER TABLE organizations
    ADD CONSTRAINT chk_organizations_status
        CHECK (status IN ('PENDING_ACTIVATION', 'ACTIVE', 'REJECTED', 'SUSPENDED'));

CREATE INDEX idx_organizations_status ON organizations(status);

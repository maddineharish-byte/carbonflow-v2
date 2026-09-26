-- CarbonFlow V3: Refresh-token persistence alignment
-- Adds the authorization, family, and replacement context required by TASK-003.
-- This migration intentionally fails closed if existing token rows require backfill.

BEGIN;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM refresh_tokens) THEN
        RAISE EXCEPTION
            'V3 refresh-token alignment requires a reviewed backfill plan: refresh_tokens is not empty';
    END IF;
END $$;

ALTER TABLE refresh_tokens
    ADD COLUMN organization_id UUID,
    ADD COLUMN role_id UUID,
    ADD COLUMN family_id UUID,
    ADD COLUMN replaced_by_token_id UUID;

ALTER TABLE refresh_tokens
    ALTER COLUMN organization_id SET NOT NULL,
    ALTER COLUMN role_id SET NOT NULL,
    ALTER COLUMN family_id SET NOT NULL;

ALTER TABLE refresh_tokens
    ADD CONSTRAINT refresh_tokens_organization_id_fkey
        FOREIGN KEY (organization_id)
        REFERENCES organizations(id)
        ON DELETE CASCADE,
    ADD CONSTRAINT refresh_tokens_role_id_fkey
        FOREIGN KEY (role_id)
        REFERENCES roles(id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT refresh_tokens_replaced_by_token_id_fkey
        FOREIGN KEY (replaced_by_token_id)
        REFERENCES refresh_tokens(id)
        ON DELETE SET NULL,
    ADD CONSTRAINT uq_refresh_tokens_replaced_by
        UNIQUE (replaced_by_token_id),
    ADD CONSTRAINT chk_refresh_tokens_replacement_not_self
        CHECK (replaced_by_token_id IS NULL OR replaced_by_token_id <> id),
    ADD CONSTRAINT chk_refresh_tokens_replacement_revoked
        CHECK (replaced_by_token_id IS NULL OR revoked_at IS NOT NULL);

CREATE INDEX idx_refresh_tokens_family_id
    ON refresh_tokens(family_id);

COMMIT;

-- =========================================================================
-- CarbonFlow Flyway Migration V9: remove hardcoded country and currency
-- defaults (Phase 10.12, Globalization & Internationalization)
-- =========================================================================
--
-- V1 gave two columns a country-specific DEFAULT:
--
--   organizations.country          VARCHAR(10) NOT NULL DEFAULT 'US'
--   organization_settings.currency VARCHAR(10) NOT NULL DEFAULT 'USD'
--
-- Both are defects for a platform that must serve every jurisdiction, for
-- different reasons:
--
--   country   - The column stays NOT NULL (it is genuinely required data), so
--               the DEFAULT is removed rather than the constraint. Every
--               production INSERT already supplies it explicitly
--               (OrganizationRepository.insert, OrganizationRepository
--               .updateProfile), so removing the DEFAULT changes no working
--               path: a future INSERT that forgets country now fails loudly
--               at the database instead of silently becoming a US entity.
--
--   currency  - organization_settings is written by OrganizationRepository
--               .updateProfile, which inserts (organization_id,
--               consolidation_approach, base_year) and therefore never named
--               currency. The DEFAULT is what supplied it. The same statement's
--               ON CONFLICT DO UPDATE branch does not touch currency either,
--               so the value was written once as USD and could never be
--               changed afterwards by any code path -- there is no currency
--               field on any request DTO. A tenant reporting in EUR, JPY or
--               INR had no way to say so.
--
--               The column is therefore made NULLABLE and the DEFAULT is
--               dropped. NULL is the honest state: "reporting currency not
--               captured". That is materially different from 'USD', which is a
--               confident, wrong assertion about a tenant nobody asked. No
--               consumer can misread NULL as a currency.
--
--               Adding the currency to the organization profile API is a
--               product decision about which monetary figures CarbonFlow will
--               actually report (abatement cost, carbon price, intensity
--               denominators) and is tracked as a Phase 10.12 follow-up in
--               docs/GLOBALIZATION.md, not invented here.
--
-- Neither change touches any numeric column, unit, GWP set or conversion
-- factor, so calculation precision is untouched.
-- =========================================================================

-- 1. country: drop the hardcoded 'US' default, keep NOT NULL (fail loud).
ALTER TABLE organizations
    ALTER COLUMN country DROP DEFAULT;

-- 2. currency: drop the hardcoded 'USD' default and allow the honest NULL.
--    EXISTS-style guard removed: ALTER COLUMN ... DROP DEFAULT is idempotent
--    in PostgreSQL (it succeeds whether or not a default is present), so this
--    is safe on a database that was baselined rather than migrated from V1.
ALTER TABLE organization_settings
    ALTER COLUMN currency DROP DEFAULT;

ALTER TABLE organization_settings
    ALTER COLUMN currency DROP NOT NULL;

-- 3. Comment the two columns so the next reader inherits the intent instead
--    of re-deriving it from the migration history.
COMMENT ON COLUMN organizations.country IS
    'ISO 3166-1 alpha-2 country of the reporting entity. No default by design: '
    'a platform serving every jurisdiction must not invent one. Supplied '
    'explicitly at insert/update; a missing value is a database error.';

COMMENT ON COLUMN organization_settings.currency IS
    'ISO 4217 reporting currency for monetary GHG figures. NULL means "not '
    'captured" and is NOT a currency; it must never be defaulted to a single '
    'national currency. No write path exists yet (Phase 10.12 follow-up in '
    'docs/GLOBALIZATION.md).';
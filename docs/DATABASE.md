# CarbonFlow — Database Architecture & Data Dictionary

## 1. Design Principles
1. **UUID Primary Keys**: All relational tables use RFC 4122 v4 UUID primary keys (`gen_random_uuid()`).
2. **Strict Foreign Key Constraints**: All domain entities reference parent tenants via `organization_id` with `ON DELETE RESTRICT` or `CASCADE` where appropriate.
3. **Audit Columns**: Core tables include `created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP` and `updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP`.
4. **Controlled Values over Database ENUMs**: Portable `CHECK` constraints or referenced lookup tables are utilized rather than database-level enums to simplify migration, validation, and serialization.
5. **No Double-Counting**: Emission records maintain a status flag (`ACTIVE`, `SUPERSEDED`, `VOIDED`) ensuring queries sum only verified active calculations.

---

## 2. Core Relational Domains & Entities

### 2.1 Identity & Security Domain
- `organizations`: Tenant root (`id`, `name`, `tax_id`, `created_at`, `updated_at`).
- `organization_settings`: Organization configurations (`id`, `organization_id`, `default_gwp_set_id`, `base_year`, `consolidation_approach`).
- `users`: User identity (`id`, `email`, `password_hash`, `full_name`, `is_active`, `created_at`).
- `roles`: Role definitions (`id`, `name`, `description`).
- `permissions`: Canonical permissions (`id`, `code`, `description`).
- `role_permissions`: Role-to-permission mapping (`role_id`, `permission_id`).
- `organization_memberships`: User-to-tenant mapping (`id`, `organization_id`, `user_id`, `role_id`, `is_active`).
- `refresh_tokens`: PostgreSQL-backed refresh-token records (`id`, `user_id`, `token_hash`, `expires_at`, `revoked_at`, `organization_id`, `role_id`, `family_id`, `replaced_by_token_id`). The active Node runtime uses this table as the sole refresh-token source of truth.
- `audit_logs`: System-level security trail (`id`, `organization_id`, `user_id`, `action`, `resource_type`, `resource_id`, `details`, `ip_address`, `timestamp`).

### 2.2 Organization & Hierarchy Domain
- `legal_entities`: Legal entities under tenant (`id`, `organization_id`, `name`, `jurisdiction`, `registration_number`).
- `facilities`: Physical sites (`id`, `organization_id`, `legal_entity_id`, `name`, `facility_code`, `country`, `state_province`, `grid_region`).
- `departments`: Operational subdivisions (`id`, `organization_id`, `facility_id`, `name`).
- `reporting_periods`: Temporal accounting periods (`id`, `organization_id`, `name`, `start_date`, `end_date`, `status`).
- `organizational_boundaries`: Scope & boundary rules (`id`, `organization_id`, `reporting_period_id`, `consolidation_approach`, `description`).
- `boundary_legal_entities`: Join table associating entities with boundaries.
- `boundary_facilities`: Join table associating facilities with boundaries.

### 2.3 Reference Data Domain
- `gwp_sets`: Global Warming Potential reference sets (`id`, `code`, `name`, `assessment_report`, `publication_year`).
- `gwp_values`: Gas-specific GWP values (`id`, `gwp_set_id`, `gas`, `gwp_100yr`).
- `calculation_methodologies`: GHG Protocol, ISO 14064, DEFRA, US EPA (`id`, `code`, `name`, `version`).
- `emission_factors`: Factor headers (`id`, `scope`, `category`, `activity_type`, `unit`, `fuel_or_activity`).
- `emission_factor_versions`: Versioned factor values (`id`, `emission_factor_id`, `version_number`, `co2_factor`, `ch4_factor`, `n2o_factor`, `co2e_factor`, `source`, `source_year`, `geography`, `effective_start`, `effective_end`, `status`).

### 2.4 Activity Data & Requests Domain
- `activity_data`: Operational inputs (`id`, `organization_id`, `reporting_period_id`, `facility_id`, `department_id`, `scope`, `category`, `activity_type`, `quantity`, `unit`, `start_date`, `end_date`, `source`, `status`, `submitted_by`).
- `data_requests`: Internal requests for data collection (`id`, `organization_id`, `reporting_period_id`, `facility_id`, `category`, `assignee_id`, `due_date`, `status`, `notes`).

### 2.5 Calculations & Emissions Domain
- `calculations`: Mathematical calculation execution snapshot (`id`, `organization_id`, `activity_data_id`, `reporting_period_id`, `factor_version_id`, `gwp_set_id`, `original_quantity`, `original_unit`, `normalized_quantity`, `normalized_unit`, `conversion_factor`, `factor_id`, `factor_value`, `factor_unit`, `factor_source`, `factor_version_number`, `gwp_name`, `total_co2e_kg`, `total_co2e_tonnes`, `calculation_hash`, `calculated_at`, `calculated_by`).
- `calculation_gas_results`: Individual greenhouse gas breakdown (`id`, `calculation_id`, `gas`, `raw_gas_emission_kg`, `gwp_applied`, `co2e_kg`), unique per calculation/gas.
- `emission_records`: Ledger entries (`id`, `organization_id`, `reporting_period_id`, `facility_id`, `calculation_id`, `scope`, `category`, `scope2_type`, `co2e_tonnes`, `status`).

### 2.6 Audit & Review Domain
- `carbon_audits`: Audit instances (`id`, `organization_id`, `reporting_period_id`, `status`, `initiated_by`, `approved_by`, `locked_at`, `notes`).
- `audit_checklist_items`: Mandatory audit readiness items (`id`, `audit_id`, `code`, `title`, `is_mandatory`, `is_satisfied`, `verified_by`, `verified_at`).
- `review_records`: Stage reviews (`id`, `audit_id`, `reviewer_id`, `stage`, `decision`, `comments`, `created_at`).
- `review_findings`: Formal discrepancies or notes (`id`, `audit_id`, `activity_data_id`, `severity`, `title`, `description`, `status`, `created_by`, `resolved_by`).
- `review_comments`: Audit conversation threads (`id`, `audit_id`, `user_id`, `comment_text`, `created_at`).
- `correction_requests`: Formal request for data fix (`id`, `audit_id`, `activity_data_id`, `reason`, `requested_by`, `is_resolved`).
- `audit_approvals`: Formal approval sign-offs (`id`, `audit_id`, `approver_id`, `role`, `timestamp`, `signature_hash`).
- `audit_lock_events`: Tamper-proof period freeze record (`id`, `audit_id`, `locked_by`, `locked_at`, `inventory_hash`).

### 2.7 Evidence Vault Domain
- `evidence_records`: Evidence metadata (`id`, `organization_id`, `file_name`, `file_size_bytes`, `mime_type`, `sha256_hash`, `storage_path`, `uploaded_by`, `created_at`).
- `evidence_versions`: File update versions (`id`, `evidence_record_id`, `version_number`, `sha256_hash`, `storage_path`, `created_at`).
- `evidence_links`: Polymorphic association (`id`, `evidence_record_id`, `entity_type`, `entity_id`).
- `evidence_requests`: Requests for supporting documentation (`id`, `organization_id`, `reporting_period_id`, `title`, `assigned_to`, `status`).

### 2.8 Inventory, Targets & Reduction Domain
- `inventory_snapshots`: Immutable aggregated GHG report (`id`, `organization_id`, `reporting_period_id`, `audit_id`, `scope1_co2e_t`, `scope2_location_co2e_t`, `scope2_market_co2e_t`, `biogenic_co2e_t`, `status`, `snapshot_hash`, `created_at`).
- `carbon_targets`: Long-term reduction goals (`id`, `organization_id`, `name`, `baseline_period_id`, `target_period_id`, `baseline_value_t`, `target_value_t`, `reduction_percentage`, `status`, `owner_id`).
- `reduction_projects`: Decarbonization initiatives (`id`, `organization_id`, `target_id`, `name`, `description`, `facility_id`, `baseline_t`, `expected_reduction_t`, `actual_reduction_t`, `start_date`, `end_date`, `status`, `owner_id`).

---

## 2.9 Runtime Persistence Status — Through TASK 2.5
- Production uses PostgreSQL as the source of truth for refresh tokens, identity/scope, activity data, evidence metadata/links, calculations, calculation gas results, emission records, and reference factor/GWP reads.
- PostgreSQL 18.6 is configured through environment variables. V1-V6 are present; V5 and V6 have been applied and verified against `carbonflow_dev`.
- V3 adds persistent refresh-token organization, role, family, replacement, and revocation state. V4 adds facility-code uniqueness and nonnegative facility/ownership checks.
- V5 adds composite tenant foreign keys for activity/facility and activity/reporting-period relationships.
- V6 adds calculation snapshot columns (`factor_id`, `factor_unit`, `factor_source`, `factor_version_number`, `gwp_name`, `conversion_factor`), nonnegative checks, calculation/emission tenant composite foreign keys, tenant uniqueness indexes, and a per-calculation gas-result uniqueness index.
- Activity production reads, creation, filtering, lookup for calculations, and supported status updates use `activity_data` through `activity-repository.ts`.
- Evidence metadata and tenant-owned links use `evidence_records` and `evidence_links` through `evidence-repository.ts`. Metadata and optional links are created transactionally. Actual file bytes remain in the private adapter behind `server/storage.ts` and are never exposed as filesystem paths or public URLs.
- Production calculation execution resolves factors and GWP sets from PostgreSQL, preserves the factor/version/source/GWP snapshot, and commits calculations, gas results, emission records, supersession, and activity status in one transaction through `calculation-repository.ts`.
- Audits, inventory, targets, reduction projects, and organization settings remain on their existing non-PostgreSQL implementation paths and are outside TASK 2.5.

## 2.10 UUID/String Identity Compatibility
- Current Node seed identifiers are strings such as `org-tenant-a-1111`, `user-acme-admin-1`, and non-UUID membership IDs; membership authorization uses a `RoleName` string.
- PostgreSQL V1 uses UUID primary and foreign keys. `organization_memberships.role_id` is a UUID foreign key to `roles.id`; `roles.name` is unique and V2 seeds nine canonical role names with fixed UUIDs.
- ADR-006 selects Option A: map development semantic identities to UUID-backed PostgreSQL records. Stable semantic keys resolve users and organizations, while role names resolve canonical role rows. No legacy mapping table or broad fixture identifier replacement is required.
- Production identity, tenant context, and refresh validation are PostgreSQL-backed. Development semantic fixtures remain an explicit compatibility path for unmigrated domains.

## 2.11 TASK-004A-TEST-MIGRATION

The PostgreSQL refresh-token tests now use the actual `carbonflow_dev` database. Test fixtures use unique `@example.test` identities, and cleanup removes only the fixture user; PostgreSQL cascades remove that user's memberships and refresh tokens. Seeded roles, organizations, and unrelated data are not truncated.
**Selected design: store both `organization_id UUID` and `role_id UUID` on each refresh-token record.**

The refresh path must locate the token by hash and then verify that:

1. the referenced user exists and is active;
2. an active `organization_memberships` row exists for the recorded `user_id` and `organization_id`;
3. that membership's `role_id` equals the token's recorded `role_id`;
4. the referenced role is a canonical `roles` row.

This is the smallest design that preserves the current record's issued organization and role context. A membership-only foreign key is insufficient because changing a membership's role would otherwise change the meaning of an already-issued token. Separate `organization_id` and `role_id` foreign keys prevent orphan references, while the repository-level membership join preserves the current authorization rule. The database does not currently enforce the three-column relationship with one composite constraint; the refresh transaction must perform that check and must not issue a token when it fails.

The proposed foreign keys are:

- `organization_id -> organizations(id) ON DELETE CASCADE`
- `role_id -> roles(id) ON DELETE RESTRICT`
- existing `user_id -> users(id) ON DELETE CASCADE`

No `membership_id` and no legacy mapping table are added. `membership_id` alone would not preserve issued role binding across membership role changes. Adding both `membership_id` and a role snapshot would be larger than necessary for the current model.

### Token-family decision

**Selected design: store `family_id UUID NOT NULL` directly on `refresh_tokens`.**

- A new token receives the same family UUID as the token it replaces.
- Login and tenant/role switching create a new family UUID.
- Logout updates every non-revoked row for the presented family.
- A separate token-family table is not required by the current lifecycle and is rejected as speculative.
- Required index: `refresh_tokens(family_id)` for family lookup and revocation.

### Replacement relationship decision

**Selected design: `replaced_by_token_id UUID NULL REFERENCES refresh_tokens(id) ON DELETE SET NULL`.**

- New tokens have `replaced_by_token_id IS NULL`.
- During rotation, token B is inserted first and token A is updated with B's UUID in the same transaction.
- A may reference only a different token.
- A token that has a replacement must also have `revoked_at IS NOT NULL`.
- Replacement is unique so a replacement token cannot be claimed as the successor of two predecessor rows.

This preserves the current `replacedByTokenId` lineage and makes replay/replacement inspection durable without creating a second token table.

### Required constraints and indexes

Existing constraints retained:

- `refresh_tokens.id` UUID primary key
- `refresh_tokens.user_id` UUID not-null foreign key to `users(id)`
- `refresh_tokens.token_hash` not-null unique
- `expires_at` not null

Proposed additions:

- `organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE`
- `role_id UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT`
- `family_id UUID NOT NULL`
- `replaced_by_token_id UUID NULL REFERENCES refresh_tokens(id) ON DELETE SET NULL`
- unique constraint on non-null `replaced_by_token_id` (PostgreSQL permits multiple nulls)
- check `replaced_by_token_id IS NULL OR replaced_by_token_id <> id`
- check `replaced_by_token_id IS NULL OR revoked_at IS NOT NULL`
- index `refresh_tokens(family_id)`

No raw-token column is permitted. No expiry/revocation partial index is required by the current hash-first lookup and family-revocation operations; one should not be added speculatively.

### Atomic rotation design — implemented

For a presented token A, one database transaction must:

1. Begin a transaction.
2. Hash the opaque token in application memory and locate A by unique `token_hash` using `SELECT ... FOR UPDATE`.
3. Reject unknown hashes without issuing a token.
4. Reject A if `revoked_at` or `replaced_by_token_id` is set; this is replay rejection.
5. Reject and persist revocation if `expires_at <= CURRENT_TIMESTAMP`.
6. Join the active user and active membership using A's recorded `user_id`, `organization_id`, and `role_id`; reject and revoke A if the user or exact membership is no longer active.
7. Generate B's UUID, opaque token, HMAC-SHA256 hash, expiry, and the same family UUID.
8. Insert B with the same user, organization, role, and family context.
9. Update A with `revoked_at = CURRENT_TIMESTAMP` and `replaced_by_token_id = B.id`.
10. Commit before returning the new access/refresh pair.

A concurrent request using A waits on the row lock. After the first transaction commits, the second observes A's non-null `revoked_at` and replacement and returns the existing `REFRESH_TOKEN_REVOKED` result without issuing B. No memory fallback is permitted.

### Logout and family revocation — implemented

1. Begin a transaction.
2. Locate the presented token by its unique hash and obtain its `family_id`.
3. Update all rows in that family where `revoked_at IS NULL` to set `revoked_at = CURRENT_TIMESTAMP`.
4. Commit before reporting logout success.

The family index supports the update predicate. Row locks serialize this update with concurrent rotation. A rotation that commits before the family update has its successor revoked as part of the same family update; a rotation that waits observes revocation and is rejected. After a backend restart, the family and all revocation timestamps remain in PostgreSQL.

### Applied V3 shape

`V3__refresh_token_persistence_alignment.sql` is implemented and applied. Its applied shape is:

```sql
-- Applied V3 migration (see repository migration for authoritative checksum).
BEGIN;
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM refresh_tokens) THEN
    RAISE EXCEPTION 'V3 requires a reviewed backfill plan before refresh_tokens is non-empty';
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
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE CASCADE,
  ADD CONSTRAINT refresh_tokens_role_id_fkey
    FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE RESTRICT,
  ADD CONSTRAINT refresh_tokens_replaced_by_token_id_fkey
    FOREIGN KEY (replaced_by_token_id) REFERENCES refresh_tokens(id) ON DELETE SET NULL,
  ADD CONSTRAINT uq_refresh_tokens_replaced_by
    UNIQUE (replaced_by_token_id),
  ADD CONSTRAINT chk_refresh_tokens_replacement_not_self
    CHECK (replaced_by_token_id IS NULL OR replaced_by_token_id <> id),
  ADD CONSTRAINT chk_refresh_tokens_replacement_revoked
    CHECK (replaced_by_token_id IS NULL OR revoked_at IS NOT NULL);

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens(family_id);
COMMIT;
```

The initial disposable target required no refresh-token backfill. V1 and V2 remain immutable. A deployed database that already contains token rows still requires a separately reviewed backfill before applying V3. Rollback should drop only V3-added columns, indexes, and constraints; it cannot restore deleted token history and must not be attempted after persistent sessions have been issued without a data-retention decision.

### Restart persistence guarantees

PostgreSQL guarantees that valid refresh tokens, revocation timestamps, replacement lineage, family revocations, activity data, evidence metadata, evidence links, calculation snapshots, gas results, and emission records survive a Node restart. Child-process tests verify refresh-token restart/concurrency, TASK 2.4 activity/evidence restart behavior, and TASK 2.5 calculation/emission restart behavior.

## 3. Database Indexes for High-Performance Queries
```sql
CREATE INDEX idx_memberships_tenant_user ON organization_memberships (organization_id, user_id);
CREATE INDEX idx_activity_tenant_period ON activity_data (organization_id, reporting_period_id, status);
CREATE INDEX idx_calculations_activity ON calculations (activity_data_id);
CREATE INDEX idx_calculations_tenant_activity ON calculations (organization_id, activity_data_id, calculated_at DESC);
CREATE UNIQUE INDEX uq_calculations_tenant_id ON calculations (organization_id, id);
CREATE INDEX idx_emissions_tenant_period ON emission_records (organization_id, reporting_period_id, status, scope);
CREATE UNIQUE INDEX uq_emission_records_tenant_id ON emission_records (organization_id, id);
CREATE UNIQUE INDEX uq_calculation_gas_results_calculation_gas ON calculation_gas_results (calculation_id, gas);
CREATE INDEX idx_evidence_tenant ON evidence_records (organization_id);
CREATE INDEX idx_audits_tenant_period ON carbon_audits (organization_id, reporting_period_id);
CREATE INDEX idx_inventory_tenant_period ON inventory_snapshots (organization_id, reporting_period_id);
```

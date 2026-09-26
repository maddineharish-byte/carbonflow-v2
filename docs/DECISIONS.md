# CarbonFlow — Architectural Decision Records (ADR)

## ADR-001: Deterministic Decimal Arithmetic over Floating Point
- **Context**: Greenhouse gas accounting data is subjected to rigorous third-party financial and sustainability assurance (ISAE 3410). IEEE 754 floating point arithmetic introduces binary rounding errors.
- **Decision**: All calculations use high-precision decimal arithmetic (Java `BigDecimal` standard, implemented via `Decimal.js` in TypeScript runtime).
- **Consequences**: Internal scale is fixed at 8 decimal places with `ROUND_HALF_UP`. Tonnes $CO_2e$ are reported to 4 decimal places. Calculations are 100% reproducible.

---

## ADR-002: Scope 2 Dual-Reporting Model
- **Context**: Under GHG Protocol Corporate Standard (Scope 2 Guidance), companies must report both Location-based (grid average) and Market-based (contractual instruments) emissions.
- **Decision**: Location and Market emission records are maintained as distinct line items. The system strictly forbids summing them ($Location + Market \neq Total$). If market-based factors are unavailable, the record is flagged explicitly rather than copying location data.
- **Consequences**: Executive dashboards and reports display Dual Metrics side-by-side.

---

## ADR-003: Versioned Emission Factor Immutability
- **Context**: Emission factor updates (e.g. annual eGRID or DEFRA releases) must not silently alter historical carbon inventory reports that have already been audited or reported to regulatory bodies.
- **Decision**: Emission factors are versioned. Each calculation links to a specific immutable `factor_version_id`.
- **Consequences**: Updating a factor creates a new version. Existing calculations and inventory snapshots retain historical factor values.

---

## ADR-004: Strict Tenant Context Derivation
- **Context**: In multi-tenant SaaS, trusting client-provided headers such as `X-Organization-Id` enables tenancy spoofing if an attacker tampers with headers.
- **Decision**: The backend derives organization identity exclusively from cryptographically verified user tokens and active memberships in `organization_memberships`. All DB queries enforce `WHERE organization_id = :tenantId`.
- **Consequences**: Complete tenant isolation. Cross-tenant access fails with 403 Forbidden.

---

## ADR-005: 8-State Governed Audit State Machine
- **Context**: Moving unverified carbon accounting data directly into reports creates regulatory and assurance risk.
- **Decision**: Implement an 8-state governed workflow (`DRAFT`, `SUBMITTED`, `DATA_COLLECTION`, `VALIDATION`, `REVIEW`, `APPROVED`, `AUDIT_READY`, `LOCKED`) with mandatory checklist validation before approval and locking.
- **Consequences**: Enforces segregation of duties between data owners, accountants, sustainability managers, and reviewers.

---

## ADR-006: TASK-004A-PREP PostgreSQL Identity Compatibility Decision
- **Status**: ACCEPTED for identity compatibility; TASK-004A persistence remains blocked by refresh-token schema alignment.
- **Context**: The active Node seed data uses arbitrary string identifiers such as `org-tenant-a-1111`, `user-acme-admin-1`, and `mem-a-1`, and stores membership roles as `RoleName` strings. PostgreSQL 18.6 was authenticated at `localhost:5432/carbonflow_dev`, and the exact V1/V2 files were directly applied to the disposable database. Actual V1 inspection confirmed UUID primary and foreign keys, including `organization_memberships.role_id -> roles.id`; actual V2 inspection confirmed nine UUID-backed role records but no organizations, users, permissions, memberships, or refresh tokens. The current V1 `refresh_tokens` table contains only `id`, `user_id`, `token_hash`, `expires_at`, `revoked_at`, and `created_at`, while the active TASK-003 record also requires organization, role, family, and replacement metadata.
- **Decision**: Choose **Option A — map existing development identities to UUID-backed PostgreSQL records while preserving semantic identities**. Resolve development users by their stable semantic identity (for example, email), organizations by an agreed stable business key, and membership roles by the unique `roles.name`; never attempt to insert development string IDs into UUID columns and do not add a legacy mapping table. PostgreSQL UUIDs become the persistence-layer identities while existing Node semantic identity and authorization behavior remain unchanged until a later implementation task explicitly replaces them.
- **Alternatives rejected**:
  1. **Option B — replace all development seed identifiers with UUIDs**: rejected as broader and riskier because it changes fixtures and many dependent references without helping refresh-token persistence.
  2. **Option C — explicit legacy-to-UUID mapping layer**: rejected as unnecessary for development-only identities because stable semantic keys already exist and the PostgreSQL schema supplies the canonical UUID records.
  3. **Option D**: no repository evidence requires a different identity strategy.
- **Preservation requirements**: Every lookup must preserve active user and membership checks, tenant membership, role authorization, TASK-001 tenant/role switching, the TASK-002 authentication boundary, and TASK-003 refresh authorization. Role strings must be translated through the canonical PostgreSQL role relation, never accepted as arbitrary database role IDs.
- **Consequences**: TASK-003 remains unchanged and memory-backed. TASK-004A-SCHEMA-PREP defines the missing persistence relationships in ADR-007; no `V3` was created in TASK-004A-PREP.

---

## ADR-007: Refresh-Token Persistence Schema Alignment
- **Status**: ACCEPTED and implemented; TASK-004A cleanup completed. PostgreSQL is the sole production refresh-token source of truth.
- **Context**: The actual V1 `refresh_tokens` table stores only token ID, user, hash, expiry, revocation, and creation time. TASK-003 additionally binds every token to an organization, role, family, and replacement. V3 and the PostgreSQL repository now implement those requirements.
- **Decision**: Extend `refresh_tokens` with non-null `organization_id UUID`, non-null `role_id UUID`, non-null `family_id UUID`, and nullable self-referencing `replaced_by_token_id UUID`. Resolve role authorization by joining the recorded user and organization to an active membership whose `role_id` equals the token's recorded role. Keep the family UUID directly on token rows; do not add a family table. Use `ON DELETE CASCADE` for organization, `ON DELETE RESTRICT` for role, and `ON DELETE SET NULL` for replacement. Retain unique token hashes.
- **Authorization alternatives**:
  1. `organization_id + role_id` — selected. It preserves the issued tenant and role even if a membership's role later changes; the active membership is still checked on every refresh.
  2. `membership_id` — rejected as the sole context because mutable membership role data would not preserve the role bound at issuance.
  3. `membership_id + role snapshot` — rejected as redundant for the current model.
- **Rotation decision**: Lock the presented token by unique hash using `SELECT ... FOR UPDATE`; validate expiry, revocation/replacement, active user, and exact active membership; insert the successor; revoke and link the predecessor; commit before responding. A concurrent second request waits and then observes the committed replacement/revocation and is rejected as replay.
- **Constraints and indexes**: Add foreign keys for organization, role, and replacement; unique non-null replacement; non-self replacement check; replacement-implies-revoked check; and an index on `family_id`. No raw token, family table, or speculative expiry index is allowed.
- **Logout decision**: Resolve the presented token's family and update every non-revoked family row in one transaction. PostgreSQL persistence must preserve valid, revoked, rotated, replacement, and family-revocation state across Node restarts.
- **Migration decision**: V1 and V2 remain unchanged. A future `V3__refresh_token_persistence_alignment.sql` must fail closed if token rows exist without a reviewed backfill plan. The current zero-row target needs no data backfill, but emptiness is a verified precondition rather than the design's safety mechanism.
- **Consequences**: V3, the repository, restart/concurrency tests, and legacy cleanup are complete. PostgreSQL is the sole production refresh-token source; no memory fallback remains.

---

## ADR-008: Calculation and Emission Snapshot Persistence
- **Status**: ACCEPTED and implemented in TASK 2.5.
- **Context**: Calculation results are accounting evidence. Factor versions, GWP values, unit normalization, and Scope 2 dual-reporting semantics must remain reproducible even when reference data changes later. The established engine has no methodology input or methodology version field.
- **Decision**: Keep `server/calc.ts` as the sole formula authority. Production routes resolve factor versions and GWP sets from PostgreSQL, then `calculation-repository.ts` persists the calculation, gas results, emission record, prior active-emission supersession, and activity status in one transaction. V6 adds snapshot columns for factor ID/unit/source/version, GWP name, and conversion factor plus composite tenant foreign keys.
- **Consequences**: Historical calculations retain their original factor/version/GWP snapshot and do not depend on a later active factor. Recalculation creates a new immutable calculation and supersedes prior active emission records without double counting. Scope 2 location and market records remain distinct. No methodology field was invented.

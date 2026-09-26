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

---

## ADR-009: Plain JDBC (Spring JDBC) as the Java Persistence Technology
- **Status**: ACCEPTED (Phase 2 — Foundation & Decisions; decided with stakeholder sign-off).
- **Context**: The Java backend (`backend-java/`) had no persistence layer at all — an in-memory `ConcurrentHashMap` store. The specification requires evaluating requirements before introducing any ORM. The PostgreSQL schema (V1–V6, 37 tables) is hand-tuned: composite tenant foreign keys (V5/V6), tenant-scoped unique indexes, CHECK constraints, `NUMERIC` precision, and `SELECT … FOR UPDATE` row locks. The active Node reference backend uses raw parameterized SQL with mandatory `WHERE organization_id = $1` predicates.
- **Decision**: Adopt `spring-boot-starter-jdbc` with explicit SQL through `JdbcTemplate`, PostgreSQL as the single source of truth, and Flyway for migrations. No ORM (JPA/Hibernate) or second persistence abstraction is introduced. Repository classes arrive in Phase 3; when they do, every query must carry the tenant predicate and reuse the proven SQL semantics of `server/*-repository.ts`.
- **Alternatives rejected**:
  1. **Spring Data JPA / Hibernate** — rejected: mapping 37 tables with composite tenant FKs and CHECK constraints invites Hibernate/Flyway divergence (`ddl-auto` must stay off), diverges from the proven reference SQL, and adds an ORM the specification explicitly says must first be justified.
  2. **MyBatis** — rejected: another abstraction without a need the plain API does not cover.
- **Consequences**: Full SQL fidelity and direct reuse of reference queries; more boilerplate (`RowMapper`s) than entities. Introducing an ORM later requires a new ADR.

---

## ADR-010: Authoritative API Contract and Toolchain Alignment
- **Status**: ACCEPTED (Phase 2).
- **Context**: Two contracts coexisted — the Node backend exposes 39 endpoints (the React frontend is wired to them) while the Java prototype exposes 17 with divergent paths (`/audit-rooms` vs `/audits`, `/factors` vs `/reference/emission-factors`) and no refresh/logout/switch endpoints. `pom.xml` declared Java 17 while the target toolchain and installed JDK are Java 21.
- **Decision**:
  1. **The Node contract is authoritative**: paths, the `{success,data,error:{code,message}}` envelope, 201-on-create, and snake_case error codes are what the Java backend must reproduce. Java endpoints converge module-by-module in Phases 3–7; the frontend is not re-wired until Phase 8.
  2. **`pom.xml` `java.version` raised 17 → 21** to match the non-negotiable target and the verified toolchain (OpenJDK 21.0.12.1, Maven 3.9.16).
  3. **Access-token TTL aligned to 15 minutes** (900000 ms), matching `server/auth.ts`; refresh-token rotation arrives with Phase 3 (Identity & Tenant Core).
- **Consequences**: One contract going forward; `backend-java/README.md` claims of "100% API contract match" are withdrawn as false (17 ≠ 39 endpoints).

---

## ADR-011: RBAC Source of Truth Is the Code Matrix, Not the Database Tables
- **Status**: ACCEPTED (Phase 2).
- **Context**: The canonical model is 9 roles × 44 permission codes (`server/types.ts`, `server/rbac.ts`, `docs/RBAC.md`, seeded into `roles` by V2). The `permissions` and `role_permissions` tables exist but are seeded empty and queried by no code in either backend — DB-backed RBAC is documented, not implemented. The Java prototype carried a wrong 5-role enum including the forbidden `SUPER_ADMIN` and enforced nothing (zero `@PreAuthorize`).
- **Decision**: Port the matrix verbatim into `com.carbonflow.security` (`Permission` enum = 44 codes, `RolePermissions` = exact per-role sets, canonical 9-role `Role` enum). The JWT filter grants one authority per permission (`PERMISSION_<code>`) plus `ROLE_<role>` on every request — derived from the store's current role, never from token claims. Endpoints declare their required permission via `@PreAuthorize`. Parity is enforced by `RolePermissionsParityTest`, which compares the Java matrix set-for-set against a matrix exported from `server/rbac.ts`. The database tables remain unused until a role-administration feature (Phase 7) needs them; making RBAC DB-driven then requires a new ADR plus seeding.
- **Consequences**: Authorization decisions are interchangeable with the Node backend; the 16 pre-existing Java endpoints are now permission-gated (including the audit transition, which Node leaves ungated).

---

## ADR-012: Flyway as Migration Runner, Shared Migration Folder, Baseline Strategy
- **Status**: ACCEPTED; execution against a live database is BLOCKED in this environment (no database credentials, `psql` not installed) and must be verified when credentials are available.
- **Context**: `db/migration/V1–V6` already follow the Flyway naming convention but no Flyway dependency, configuration, or runner existed anywhere; the schema was applied manually (docs/TASKS.md). Additionally the `carbon_audits.status` CHECK (8 states) disagrees with the application's 10-state audit machine (`docs/AUDIT_WORKFLOW.md`, `server/types.ts`), so persisting `CORRECTION_REQUESTED`/`REJECTED` would fail at the database.
- **Decision**:
  1. Flyway (`flyway-core` + `flyway-database-postgresql`) runs at Java application startup; `spring.flyway.locations=classpath:db/migration`.
  2. The **repository-root `db/migration` folder stays the single source of truth**: `pom.xml` packages it onto the classpath, so Java and any deployment tooling apply identical files.
  3. `baseline-on-migrate=true` with `baseline-version=6`: an empty database applies V1…Vn normally; a database already migrated manually through V6 (no `flyway_schema_history`) is baselined at 6 and receives only newer migrations. A *partially* migrated database must be baselined manually.
  4. **V7** (`V7__audit_status_correction_rejection.sql`) extends `carbon_audits.status` to the full 10-state machine. It locates the legacy constraint by *definition* (not assumed name) before dropping it, then adds `carbon_audits_status_check`.
- **Consequences**: The Java backend owns schema evolution from Phase 2 on. V7 only widens a CHECK — backward-compatible with the Node backend (which still keeps audits in memory), so applying it to a Node-era database changes no Node behavior.

---

## ADR-013: Authentication and Authorization Hardening Baseline
- **Status**: ACCEPTED (Phase 2 — partial; refresh rotation, logout invalidation, login lockout, and tenant/role switching remain Phase 3 scope).
- **Context**: Phase 1 recorded five Java CRITICAL findings — plaintext password comparison (`AuthController`), zero authorization, a committed HS256 secret in two places, CORS `*` with credentials, and no persistence — plus unauthenticated self-test access and silent token failures.
- **Decision**:
  1. **BCrypt cost 10** (identical to the Node backend's bcryptjs cost 10) for stored hashes and verification. Unknown-account logins still perform one full BCrypt verification against a precomputed dummy hash so response timing cannot enumerate users.
  2. **Fail-closed JWT configuration**: the signing secret comes only from `CARBONFLOW_JWT_SECRET`; startup fails fast when it is missing, blank, or under 256 bits. No default/committed secret remains.
  3. **CORS** from `CORS_ORIGINS` as an explicit origin allow-list (credentials allowed only for listed origins — never `*`).
  4. **Every `/api/v1/**` route requires authentication**; per-endpoint permissions via `@PreAuthorize` (ADR-011); login and `/api/health` are the only public routes; `GET /api/v1/test-suite/run` is restricted to `platform.tenants.manage`.
  5. **Envelope-complete error handling**: security chain returns `{success:false,error:{code,message}}` for 401/403; a `@ControllerAdvice` maps JSON/validation/type/method/not-found/denied/unexpected failures onto the same envelope, logging full detail server-side only (no stack traces to clients).
  6. **Spring wiring trap fixed**: the in-memory store is `@Component`, not `@Repository`, and `PersistenceExceptionTranslationAutoConfiguration` is excluded — otherwise `spring-boot-starter-jdbc` causes `@Repository` beans to be CGLIB-proxied without constructor invocation, nulling public fields that controllers read directly.
- **Consequences**: Phase 1 CRITICAL #1–#5 are resolved in the Java codebase and covered by 36 tests. Residual items explicitly deferred: `?token=` query-string acceptance (log/history leak), no login throttling, demo seed credentials, refresh tokens, DB TLS — tracked for Phases 3, 9, and 10.

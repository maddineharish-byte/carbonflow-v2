# CarbonFlow — Project Task Breakdown & Implementation Roadmap

## Vibe Coding TASK-001 — Secure Tenant / Role Switching
- [x] Require an authenticated JWT session for `POST /api/v1/auth/switch-tenant-or-role`.
- [x] Validate the requested organization and role against active user memberships without creating or mutating authorization state.
- [x] Reject unauthorized organization/role combinations and `PLATFORM_ADMIN` self-selection.
- [x] Sanitize auth responses and expose only active membership options to the frontend.
- [x] Add HTTP security tests covering authentication, membership validation, privilege escalation, inactive principals, and cross-tenant access.
- [x] TASK-001 implementation and security tests completed on 2026-09-24. The repository's broader persistence, refresh-token, and unrelated security work remains incomplete.
- [x] Verification evidence: `npm test` passed 10/10 HTTP security tests and `npm run lint` passed. The existing npm production build remains blocked by the pre-existing Recharts `react-is` resolution failure.

## Vibe Coding TASK-002 — Remove Automatic Administrator Bootstrap
- [x] Remove automatic frontend administrator login and default organization selection.
- [x] Add explicit login, session restoration through `/auth/me`, and logout state clearing.
- [x] Reject invalid stored sessions and return to the login boundary.
- [x] Add HTTP session tests and React authentication-boundary tests.
- [x] TASK-002 implementation and tests completed on 2026-09-24. The refresh-token lifecycle is addressed by TASK-003; broader authentication hardening remains future work.
- [x] Verification evidence: `npm test` passed 18 HTTP/session tests and 4 React boundary tests; `npm run lint` passed. The existing npm production build remains blocked by the pre-existing Recharts `react-is` resolution failure.

## Vibe Coding TASK-003 — Server-Side Refresh-Token Rotation and Revocation
- [x] Add refresh-token context, family, replacement, and revocation metadata to the active in-memory store.
- [x] Implement `POST /api/v1/auth/refresh` with keyed token hashing, expiration, active-user/membership validation, single-use rotation, and replay rejection.
- [x] Implement `POST /api/v1/auth/logout` to revoke the presented refresh-token family.
- [x] Store the refresh token in the existing browser storage approach and add one-retry/single-flight access-token recovery.
- [x] Add HTTP lifecycle and frontend API recovery tests, including concurrent refresh handling.
- [x] TASK-003 implementation and tests completed on 2026-09-24. Refresh state remains in-memory and is lost on server restart; cookie migration and persistent refresh storage remain future work.
- [x] Verification evidence: `npm test` passed 31 HTTP security/session/refresh tests and 8 frontend tests; `npm run lint` passed; the existing runtime suite passed 9/9; and the server-only esbuild bundle passed. The existing npm production build remains blocked by the pre-existing Recharts `react-is` resolution failure.

## Vibe Coding TASK-004A-PREP — Establish Verified PostgreSQL Runtime & Identity Compatibility
- [x] Inspect the active Node runtime, PostgreSQL artifacts, environment configuration, tests, and separate Java backend.
- [x] Confirm the active Node runtime remains memory-backed and TASK-003 refresh-token behavior is unchanged.
- [x] Add empty PostgreSQL environment placeholders without credentials or guessed defaults.
- [x] Authenticate to the disposable PostgreSQL 18.6 target and inspect the actual schema and seed data.
- [x] Apply and verify the exact repository V1 and V2 files through a direct controlled mechanism, without claiming Flyway execution.
- [x] Select Option A in ADR-006: map development semantic identities to UUID-backed PostgreSQL records without replacing Node fixtures.
- [x] Identify the independent blocker: the existing `refresh_tokens` schema cannot represent TASK-003 organization, role, family, or replacement context.
- TASK-004A-PREP is COMPLETE. TASK-004A persistence remains BLOCKED pending a separate schema-alignment task. No V3, refresh-token persistence, auth, frontend, test, or Java implementation was performed.

## Vibe Coding TASK-004A-SCHEMA-PREP — Refresh-Token Persistence Schema Alignment
- [x] Inspect the active Node auth model, TASK-001/002/003 tests, existing migration artifacts, and the actual PostgreSQL identity/refresh-token schema.
- [x] Record the actual persistence gap without modifying the runtime or migrations.
- [x] Select organization UUID plus role UUID as the authorization context; reject membership-only context because it cannot preserve issued role binding across membership role changes.
- [x] Keep family UUIDs directly on refresh-token rows and reject a separate family table.
- [x] Design self-referencing replacement lineage, required constraints, and a family index.
- [x] Design row-locked atomic rotation and transaction-safe family revocation.
- [x] Define a future V3 shape and fail-closed handling if token rows exist without a reviewed backfill.
- [x] Mark the design **DESIGNED — NOT IMPLEMENTED** at schema-review completion.
- [x] TASK-004A implementation created and applied V3 and added the PostgreSQL refresh repository.
- [x] Migrate TASK-003 refresh-token assertions to actual PostgreSQL-backed queries and controlled PostgreSQL expiration updates.
- [x] Verify the migrated TASK-001, TASK-002, TASK-003, frontend, lint, and type-check suites.
- [x] Add real backend restart and cross-process concurrency tests using separate Node child processes and dynamic ports.
- [x] Verify restart persistence, single-success cross-process rotation, replacement state, and family-row counts in PostgreSQL.
- [x] Verify no test backend process remains after test cleanup.
- [x] Remove the legacy in-memory refresh-token implementation in a separate cleanup task.
- TASK-004A-CLEANUP is COMPLETE. PostgreSQL is the sole refresh-token persistence source; no legacy refresh-token implementation remains.

## Post-TASK-004A Phase 2 — P1 Persistence
- [x] TASK 2.1 — Define repository/service boundaries, tenant-scoping rules, transaction ownership, error conventions, identity mapping, and migration order in `docs/PERSISTENCE-ARCHITECTURE.md`.
- [x] TASK 2.2 — Migrate identity and authorization persistence. Production login, `/auth/me`, tenant/role switching, and refresh identity validation now use PostgreSQL-backed users, organizations, memberships, and roles; development fixtures retain an explicit non-production compatibility path until domain routes migrate.
- [x] TASK 2.3 — Migrate scope/facilities/reporting periods. Production reads/writes for facilities, legal entities, and reporting periods now use PostgreSQL; V4 adds scope integrity constraints; tenant isolation, duplicate/FK validation, restart persistence, and production no-fallback tests pass.
- [x] TASK 2.4 — Migrate activity and evidence metadata. Production activity reads, creates, filters, lookups, and supported status updates use PostgreSQL; evidence metadata and tenant-owned links use PostgreSQL while file bytes remain behind `server/storage.ts`. V5 enforces activity facility/reporting-period tenant integrity. Real PostgreSQL tests cover cross-tenant access, invalid relationships, transactional link rollback, storage cleanup, missing files, and child-process restart persistence.
- [x] TASK 2.5 — Migrate calculations and emission records. Production calculation execution, calculation snapshots/gas results, emission-ledger reads, reference factor/GWP reads, and recalculation supersession now use PostgreSQL through `calculation-repository.ts`; V6 adds calculation snapshot columns, tenant composite foreign keys, uniqueness, and nonnegative checks. Real PostgreSQL tests cover accounting semantics, Scope 2 dual reporting, snapshots/factor versions, transactions, tenant isolation, restart persistence, and no-fallback behavior.
- [ ] TASK 2.6 — Migrate audits, inventory, targets, and projects.

## Post-TASK-004A Phase 1 — P0 Production Blockers
- [x] Fix the frontend Recharts/`react-is` build failure; `npm run build` now succeeds.
- [x] Remove the unauthenticated `/api/v1/test-suite/run` production route and add regression coverage.
- [x] Add fail-closed production configuration validation for PostgreSQL and signing secrets.
- [x] Add security headers, explicit CORS configuration, bounded request parsing, rate limiting, and safe JSON error responses.
- [x] Add evidence signature validation, secure path containment, concurrent-upload coverage, CSV formula neutralization, and explicit missing-file errors.
- [x] Reject inactive users at login before token issuance.
- [x] Add configuration, middleware, storage, and inactive-login regression tests.
- [ ] Shared/multi-process rate-limit store and production deployment configuration remain future operational work.

## Phase 1: Specifications & Documentation (Completed)
- [x] Create PRD (`/docs/PRD.md`)
- [x] Create Architecture specification (`/docs/ARCHITECTURE.md`)
- [x] Create Database schema and indexing guide (`/docs/DATABASE.md`)
- [x] Create API contracts (`/docs/API.md`)
- [x] Create Security & tenant isolation architecture (`/docs/SECURITY.md`)
- [x] Create RBAC matrix & permissions (`/docs/RBAC.md`)
- [x] Create Calculation & unit conversion specification (`/docs/CALCULATIONS.md`)
- [x] Create Audit workflow state machine specification (`/docs/AUDIT_WORKFLOW.md`)
- [x] Create UI/UX design architecture (`/docs/UI_UX.md`)
- [x] Create Test plan (`/docs/TEST_PLAN.md`)
- [x] Create Architectural decisions record (`/docs/DECISIONS.md`)
- [x] Create Execution guide (`/docs/EXECUTION.md`)
- [x] Create Project README (`/docs/README.md`)

## Phase 2: Database Schema & Migration Scripts
- [x] Create Flyway migration script `V1__carbonflow_initial_schema.sql` covering UUIDs, constraints, and tables.
- [x] Create Flyway seed script `V2__seed_reference_data.sql` populating GWP reference sets (AR4, AR5, AR6), emission factor library (Scope 1 combustion & mobile, Scope 2 location & market, refrigerants), and default roles.

## Phase 3: Backend Core Engine & Security
- [x] Implement multi-tenant in-memory relational store with seed data for instant local execution.
- [x] Implement deterministic calculation service using `Decimal.js` (mimicking `BigDecimal`).
- [x] Implement JWT authentication, password hashing, and refresh token rotation.
- [x] Implement RBAC middleware verifying canonical permissions for all 9 roles.
- [x] Implement tenant context isolation ensuring cross-tenant data requests are rejected.
- [x] Implement audit state machine and mandatory checklist validation.
- [x] Implement evidence vault with SHA-256 hash generation and 25MB validation.
- [x] Implement inventory snapshots and carbon target tracking.
- [x] Implement automated integration and security test suite validating Tenant A vs Tenant B isolation.

## Phase 4: Frontend Implementation
- [x] Build navigation and role switcher (testing all 9 roles live).
- [x] Build Executive Dashboard with Scope 1 / Scope 2 Dual Reporting.
- [x] Build Activity Data collection and batch calculation trigger.
- [x] Build Calculation Studio inspecting formula traces, unit normalization, and GWP sets.
- [x] Build Audit & Assurance Desk with 8-state workflow and checklist verification.
- [x] Build Evidence Vault with file upload, SHA-256 hash verification, and link associations.
- [x] Build Inventory Snapshots manager and period locking.
- [x] Build Carbon Targets and Reduction Projects tracker.
- [x] Build Factor Library viewer and reporting export center.

## Phase 5: Verification & Quality Assurance
- [x] Run full automated test suite verifying tenant security, precision math, and audit state transitions.
- [x] Verify production build and compilation.

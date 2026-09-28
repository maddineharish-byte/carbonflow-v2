# CarbonFlow — Quality Assurance & Test Plan

## 1. Scope of Testing

Testing covers the entire carbon accounting and assurance preparation lifecycle:
1. **Multi-Tenant Security Tests**: Proving Tenant A cannot access, query, or mutate Tenant B records across organizations, facilities, reporting periods, activity data, calculations, evidence, audits, and inventory.
2. **Deterministic Calculation Precision Tests**: Verifying unit normalization, factor application, GWP resolution, and exact decimal results with zero floating-point drift.
3. **Dual-Reporting Non-Aggregation Tests**: Verifying that Scope 2 Location and Scope 2 Market remain strictly segregated and never summed together.
4. **Audit Workflow State Machine Tests**: Enforcing transition rules, rejection and correction paths, and mandatory checklist completion prerequisites before locking.
5. **Evidence Integrity Tests**: Verifying file size limits (≤ 25MB), allowed MIME types, and SHA-256 hash generation.

---

## 2. Automated Test Matrix

| Test ID | Test Category | Target Subsystem | Expected Outcome |
| :--- | :--- | :--- | :--- |
| `SEC-TEN-01` | Multi-Tenancy | `GET /api/v1/facilities` | Tenant B user receives empty or 403 when requesting Tenant A facilities. |
| `SEC-TEN-02` | Multi-Tenancy | `GET /api/v1/activity-data` | Tenant B cannot see Tenant A activity data even with spoofed IDs. |
| `SEC-TEN-03` | Multi-Tenancy | `GET /api/v1/evidence/:id` | Tenant B cannot download Tenant A's private evidence file. |
| `SEC-TEN-04` | Multi-Tenancy | `POST /api/v1/audits/:id/transition` | Cross-tenant audit transition attempts return 403 Forbidden. |
| `SEC-TEN-05` | Multi-Tenancy | `GET /api/v1/inventory` | Tenant B cannot view Tenant A's inventory snapshots. |
| `CALC-PRC-01` | Precision Math | Calculation Engine | 50,000 kWh natural gas produces exact deterministic decimal value without rounding drift. |
| `CALC-PRC-02` | Precision Math | `GET /api/v1/test-suite/run` | Unit normalization ratios are exact (1,000 Gallons = 3,785.411784 Litres) via the shared conversion service. |
| `CALC-S2D-01` | Dual-Reporting | `GET /api/v1/test-suite/run` | Every Scope 2 ledger row is classified location/market; both perspective totals are reported side by side, never merged. |
| `CALC-S2D-02` | Dual-Reporting | Scope 2 Ledger | Location and Market calculations generate distinct records; totals report both side-by-side. |
| `AUD-WFL-01` | Governance | Audit State Machine | Attempting to move from `REVIEW` to `APPROVED` with incomplete checklist items fails with structured 400 error. |
| `AUD-WFL-02` | Governance | Correction Path | `REVIEW` -> `CORRECTION_REQUESTED` moves state cleanly back to `DATA_COLLECTION`. |
| `EVD-SEC-01` | Integrity | Evidence Vault | Upload generates correct SHA-256 hash; unauthorized mime-type is rejected. |
| `SEC-CSV-01` | Injection | `GET /api/v1/reports/export-csv` | Cells beginning `=`, `+`, `-`, `@` are apostrophe-prefixed and quoted (embedded quotes doubled) — opening the file never executes a formula. |
| `SEC-TEN-06` | Multi-Tenancy | Phase 7 analytics / inventory / targets / projects / reports | Foreign ids collapse to byte-identical 404 bodies vs malformed ids; filters and listings never leak rows across tenants. |
| `SEC-PLT-01` | Authorization | `/api/v1/platform/tenants/*` | Company roles receive 403 (platform scope only); an out-of-from-state transition answers 409 `INVALID_STATUS_TRANSITION` without touching the row. |

---

## 3. Automated Execution

### TASK-001 Security Boundary Tests
- `npm test` runs the HTTP security suite in `server/auth-switch.test.ts`.
- Tests cover unauthenticated requests, legitimate assigned switches, unauthorized organizations and roles, membership mutation attempts, elevated-role attempts, inactive memberships/users, response redaction, and cross-tenant resource access.
- The suite mounts the real API router behind Express and exercises requests over HTTP; it does not use a second backend or mock authentication system.

The former runtime demo endpoint is not exposed in production; PostgreSQL integration tests replace it as persistence evidence.

### TASK-002 Authentication Boundary Tests
- `npm test` runs both the HTTP/session suite and the React authentication-boundary suite.
- HTTP tests cover fresh sessions, valid login, invalid credentials, invalid stored tokens, protected endpoints, re-login, logout token clearing, and TASK-001 switching.
- React server-render tests verify that loading and unauthenticated states do not render protected application content, while authenticated state does.
- Browser E2E was not executed because no desktop browser connection was available.
### TASK-003 Refresh-Token Lifecycle Tests
- `npm test` runs the existing TASK-001/TASK-002 suites plus the HTTP refresh lifecycle suite and frontend API recovery suite.
- HTTP tests cover login token pairs, keyed-hash storage, valid rotation, old-token replay rejection, expiration, unknown tokens, inactive users, inactive memberships, organization/role context protection, logout revocation, expired access-token recovery, and concurrent server-side rotation.
- Frontend API tests cover one retry after a 401, single-flight concurrent refresh, refresh-failure session clearing, no refresh on 403, and no infinite retry loop.
- Browser E2E was not executed because no desktop browser connection was available.
- Current automated result: 31 HTTP security/session/refresh tests and 8 frontend API/boundary tests passed; the existing runtime suite passed 9/9.
### TASK-2.4 Activity and Evidence Persistence — COMPLETE
- `server/activity-evidence-persistence.test.ts` runs against the actual configured PostgreSQL database through production HTTP child processes.
- Coverage includes activity create/read/list, period/facility/scope filters, calculation lookup and status update, invalid numeric/date/status input, foreign facility/reporting-period rejection, and cross-tenant read/update/create rejection.
- Evidence coverage includes metadata create/list/lookup, valid activity link creation, cross-tenant metadata/download/link rejection, transactional rollback, stored-file cleanup after failed persistence, missing-file handling, SHA-256 metadata, and API omission of internal storage paths.
- The test terminates Process A after writes and starts Process B, which retrieves the activity, linked evidence metadata, and file bytes from PostgreSQL/private storage.
- V5 is applied and its composite activity tenant foreign keys were verified in `carbonflow_dev`. Evidence bytes remain covered by `server/storage.test.ts`; metadata and links are verified in PostgreSQL.
- Targeted TASK 2.4 result: 1/1 passed. Full `test:persistence` result before TASK 2.5: 4/4 passed (identity/scope, refresh restart, refresh cross-process concurrency, TASK 2.4).
- `npm test` result: 44/44 backend/security tests and 8/8 frontend tests passed. `npm run lint`, `npx tsc --noEmit`, and `npm run build` also passed.
- Test fixtures use unique UUID-backed organizations/users and targeted cleanup; no production or shared seed records are truncated.

### TASK-2.5 Calculation and Emission Persistence — COMPLETE
- `server/calculation-emission-persistence.test.ts` runs against the actual configured PostgreSQL database through production HTTP child processes.
- Calculation coverage includes run and batch execution, PostgreSQL lookup/listing, activity relationships, gas-result snapshots, factor ID/version/unit/source/year snapshots, GWP set/name snapshots, conversion factors, unit normalization, invalid factor/GWP references, transaction rollback, activity status, and historical factor-version independence.
- Accounting coverage derives expected Scope 1 natural-gas results from the PostgreSQL factor and GWP rows, verifies MWh-to-kWh conversion, verifies Scope 2 location and market records remain distinct, and verifies missing market-based data is reported as zero rather than copied from location-based results.
- Emission coverage includes persistence, active/superseded lifecycle, lookup/listing, period/scope/status filters, calculation/activity relationships, and recalculation without double-counting.
- Tenant coverage rejects cross-tenant calculation reads, cross-tenant activity execution, cross-tenant calculation/activity references, cross-tenant facility references, cross-tenant reporting-period references, and cross-tenant emission reads.
- The test terminates Process A after writes and starts Process B, which retrieves calculation history and active emission records from PostgreSQL.
- V6 is applied and its calculation snapshot columns, composite tenant foreign keys, uniqueness indexes, and checks were verified in `carbonflow_dev`. V1-V5 were not modified.
- Targeted TASK 2.5 result: 1/1 passed. Full `test:persistence` result: 5/5 passed (identity/scope, refresh restart, refresh cross-process concurrency, TASK 2.4, TASK 2.5).
- `npm test` result: 44/44 backend/security tests and 8/8 frontend tests passed. `npm run lint`, `npx tsc --noEmit`, and `npm run build` also passed.
- Methodology/version selection is not applicable: the established calculation engine has no methodology input or persisted methodology field, and no methodology was invented during migration.

### TASK-6 Carbon Accounting Core - COMPLETE (Phase 6, ADR-017)
- The Java backend is now the production owner of the canonical flow (activity data -> validation -> factor selection -> unit normalization -> calculation -> gas-level results -> emission records -> provenance/snapshot); `mvn clean verify` runs **210 tests (176 baseline + 34 new across 8 new classes)** against embedded PostgreSQL (never H2).
- `AccountingTestBase` (extends `AuditTestBase`) provides tenant-seeded facilities/periods/activities and per-test UUID suffixes; all expected values are computed from the seeded factor/GWP rows, not hardcoded assumptions.
- `ActivityDataControllerTest` (9): create contract (201 "Activity data registered.", stored `SUBMITTED`, invalid payload 400, foreign anchors 400 `INVALID_ACTIVITY_RELATIONSHIP`, frozen period 409 `AUDIT_LOCKED`), list filters (period/facility/scope, empty string = no filter, unknown scope 400 with Node's production text), latest-calculation enrichment, PUT partial update (notes/quantity, null clears, immutable tenant anchors, editable `DRAFT|SUBMITTED|VALIDATED` only), submit idempotence, and per-route RBAC (`activity_data.read/create/update/submit`) with cross-tenant 404 anti-enumeration.
- `CalculationRunTest` (5): the exact-value happy path (1,000 kWh natural gas -> gas rows 182.54 / 0.24 x 27.9 / 0.1 x 273 kgCO2e, total 0.216536 t, factorValue 0.18288, hash/provenance fields present), the full error matrix (`activityDataId is required.`, `Calculation input is invalid.`, 404 `ACTIVITY_NOT_FOUND`, `FACTOR_NOT_FOUND`, `GWP_SET_NOT_FOUND`, unsupported-unit message, 503 `CALCULATION_PERSISTENCE_UNAVAILABLE`), relationship rollback (duplicate deterministic hash -> `INVALID_CALCULATION_RELATIONSHIP` with the prior record still ACTIVE), id/tenant collapse to 404, and the 409 lock.
- `CalculationBatchTest` (4): non-string/malformed `reportingPeriodId` contract messages, skip semantics (missing factor/GWP, unsupported unit, frozen period counted in `total` but not `processed`), abort-on-relationship with earlier per-item successes surviving, and the `{processed, total}` + "Batch calculation completed for N items." envelope.
- `ScopeDualReportingTest` (3): location-based and market-based records hold **different** values for the same meter (0.399987 t vs 0.459496 t - asserted numerically, never equal), DUAL_REPORTING retains both rows, and the 4-decimal summary reports the five totals side by side (0.2165 / 0.4 / 0.4595 / 0.6165 / 0.676) with location+market never summed.
- `EmissionLedgerTest` (3): active/superseded lifecycle on recalculation (no double counting), period-filtered listing with malformed `periodId` -> 400 "Emission filter is invalid.", and summary exclusion of unclassified rows from Scope 2 sums.
- `AccountingLockTest` (2): after the Phase 5 governed lock, activity writes and `calculations/run` answer 409 `AUDIT_LOCKED` while reads stay open; batch reports the frozen activities as unprocessed (checklist verified before APPROVED, same gate as `AuditLockTest`).
- `ReferenceDataTest` (3): `GET /reference/gwp-sets` needs only authentication and carries the exact seeded AR6/AR5/AR4 values on the wire (stripped decimals), while `emission-factors` and `methodologies` require `emission_factors.read`.
- `UnitConversionServiceTest` (5, plain JUnit): every conversion-table row, same-unit identity (x1), case-insensitive aliases, and the exact unsupported-pair error text.
- `SecurityChainIntegrationTest` strengthened: the platform self-test suite must report `total=7, failed=0` against live PostgreSQL (tenant isolation re-pointed at the real predicates, deterministic arithmetic, exact unit ratios, Scope 2 classification invariant, audit-state guard, evidence seal).
- Baseline: no Phase 2-5 test was deleted or weakened; `npx tsc --noEmit` and `npm test` (frozen Node oracle) stay green. The TASK-2.5 methodology note still holds for the engine (calculations carry no methodology field) - Phase 6 only added the additive reference read.

### TASK-7 Reporting, Portfolio, Targets & Platform Administration - COMPLETE (Phase 7, ADR-018/019/020)
- `mvn clean verify` runs **243 tests (210 baseline + 33 new across 7 new classes)** against embedded PostgreSQL (never H2), real SQL + Flyway. No Phase 2-6 test was deleted, weakened or had assertions reduced; `npx tsc --noEmit` (0 errors) and `npm test` (44 Node oracle + 8 frontend = 52) stay green.
- `AnalyticsTest` (8): dashboard rebuilt onto the `DashboardSummary` contract with fabricated demo series removed (scope totals from persisted records, location-basis `totalTonnes`, 2dp HALF_UP, both Scope 2 perspectives never summed), deterministic trend insights (`modelUsed = "carbonflow-deterministic-analytics"`, insufficient-data below two periods, no LLM), period summary (4dp totals both bases, source counts, coverage, `governance.locked` from the real accounting lock), breakdown dimensions (row content + deterministic ordering + 400s), cross-tenant 404s (`REPORTING_PERIOD_NOT_FOUND`, malformed == foreign byte-identical), and 401 coverage.
- `InventorySnapshotTest` (5): reproducible snapshot hash (`sha256(org|period|s1|location|market)` over stored 6dp values, recomputed independently in-test; no timestamps), ACTIVE→`REVERTED` supersession with one ACTIVE per period, snapshot lock transitions (409 `INVENTORY_SNAPSHOT_LOCKED`/`_REVERTED`), cross-tenant/validation 404s, 401s.
- `TargetTest` (4): Node-parity create (status forced `ON_TRACK`, `ownerId` = caller) with the full 400 message chain, read-time progress computed against in-test SQL on **both** bases (null cases included, status never auto-flipped), partial PUT merge + immutable identity + tenant 404s, 401s.
- `ReductionProjectTest` (3): Node defaults (`Number(x || 0)`, `status = PLANNED`), validation chain (ISO dates, order, status enum, non-negative, facility/target tenant links → 404), partial PUT + cross-tenant safety, proof that projects never write emission records, 401s.
- `ReportExportTest` (3): CSV formula-injection neutralised in **every** cell (`=`, `+`, `-`, `@` → apostrophe; quotes doubled; all cells quoted), the four validated filters (`periodId`/`facilityId`/`scope`/`scope2Type`; malformed → 400 `VALIDATION_ERROR`, foreign → 200 header-only export), deterministic output (identical bytes on repeat, `\n` endings, ISO-8601 UTC timestamps), tenant isolation and 401.
- `PlatformAdminTest` (3): `GET /platform/tenants/:id` exposing the V8 audit columns (`statusChangedAt` ISO-8601 UTC, `statusChangedBy` = acting platform admin, `statusNote`), the from-state state machine (409 `INVALID_STATUS_TRANSITION` with status unchanged after every refusal; `SUSPENDED|REJECTED --approve--> ACTIVE` reactivation), malformed/unknown ids → identical 404 bodies, and the frozen RBAC boundary (company roles 403, anonymous 401) — with the pre-existing `PlatformTenantTest` (9) still green.
- `Phase7IntegrationTest` (7): the cross-cutting security battery — malformed ids never 500 on any new endpoint; cross-tenant probes collapse to byte-identical 404s with empty listings and header-only exports; pending/suspended/rejected organizations cannot authenticate (`ORGANIZATION_NOT_ACTIVE` + per-status messages); deactivated users lose every surface on the next request (`USER_DEACTIVATED`); the frozen role matrix gates each surface (DATA_OWNER/REVIEWER/PLATFORM_ADMIN per `expected-role-permissions.json`, platform ≠ company admin); locked periods stay readable through reporting (summary/breakdown/export/snapshot/targets) while accounting stays frozen (409 `AUDIT_LOCKED`, ledger counts unchanged, Scope 2 perspectives distinct at 4dp/2dp); and a source+response scan proving no hardcoded geography/currency/timezone (`Asia/Kolkata`, `INR`, ₹) or demo data (`period-2024`, LLM names) in production code or payloads.
- Methodology note unchanged: reporting/targets/projects consume persisted emission records only; no second calculation engine, no invented carbon values, no hardcoded reporting dates.

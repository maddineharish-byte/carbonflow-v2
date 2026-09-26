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
| `CALC-S2D-02` | Dual-Reporting | Scope 2 Ledger | Location and Market calculations generate distinct records; totals report both side-by-side. |
| `AUD-WFL-01` | Governance | Audit State Machine | Attempting to move from `REVIEW` to `APPROVED` with incomplete checklist items fails with structured 400 error. |
| `AUD-WFL-02` | Governance | Correction Path | `REVIEW` -> `CORRECTION_REQUESTED` moves state cleanly back to `DATA_COLLECTION`. |
| `EVD-SEC-01` | Integrity | Evidence Vault | Upload generates correct SHA-256 hash; unauthorized mime-type is rejected. |

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

# CarbonFlow — Java / Spring Boot Backend (Target Backend)

This directory is the **target backend** for CarbonFlow: Java 21, Spring Boot, Maven, Spring Security (JWT), PostgreSQL, Flyway, REST, Controller → Service → Repository. It **supersedes** the Node/Express backend at cutover (Phase 10); until then the Node backend remains the live implementation and the authoritative API contract (ADR-010).

> **Status: Phase 7 (Reporting, Portfolio, Targets & Platform Administration) complete — Phase 8 (Frontend Integration) next.**
> Historical note: this directory began as a prototype. Phases 1–7 remediated its role model, security, and configuration, moved identity (organizations, users, memberships, refresh tokens), the scope domain (legal entities, facilities, departments, reporting periods, organizational boundaries), the governance/evidence domain (audits, checklist, review desk, evidence vault), the accounting domain (activity data, deterministic calculation engine, emission ledger, reference data), and the reporting layer (dashboard, trend insights, period summary, dimension breakdown, hardened CSV export, inventory snapshots, carbon targets, reduction projects, platform tenant administration) to PostgreSQL; only the platform self-test's in-memory `DataStore` fixtures remain. Treat every claim in this file as the current, verified state — older claims ("matches 100% of the API contract", Dockerfile, "Automated Compliance Verification") were false and have been removed.

---

## Architecture & Technology Stack

| Layer | Choice | Note |
|---|---|---|
| Language / runtime | Java 21 (toolchain verified: OpenJDK 21.0.12.1) | `pom.xml` `java.version` = 21 (ADR-010) |
| Framework | Spring Boot 3.3.3, Maven 3.9+ | REST, Controller → Service → Repository |
| Security | Spring Security 6, stateless JWT (JJWT, HS256) | fail-closed secret, 15-minute access tokens |
| Authorization | RBAC: 9 roles × 44 permissions via `@PreAuthorize` | ported from `server/rbac.ts`, parity-tested (ADR-011) |
| Persistence | Plain JDBC (`spring-boot-starter-jdbc`) + PostgreSQL | **no ORM** (ADR-009); identity (Phase 3), scope (Phase 4), governance + evidence (Phase 5), accounting (Phase 6) and reporting/targets/snapshots/platform administration (Phase 7) are JDBC-backed; only the self-test's in-memory fixtures remain |
| Migrations | Flyway; single source `db/migration` (V1–V8) packaged onto the classpath | baseline strategy in ADR-012; run on every `mvn verify` against an embedded test PostgreSQL; **Phases 4–7 required no new migration** (ADR-015, ADR-016, ADR-017, ADR-018/019/020) |
| Passwords | BCrypt cost 10 | identical to the Node backend's bcryptjs cost 10 |

**Identity, scope, governance, accounting, and reporting are database-backed.** `organizations`, `users`, `organization_memberships`, `refresh_tokens` (ADR-014), `legal_entities`, `facilities`, `departments`, `reporting_periods`, `organizational_boundaries`, `boundary_facilities` (ADR-015), `carbon_audits`, `audit_checklist_items`, `review_findings`, `review_comments`, `correction_requests`, `audit_approvals`, `audit_lock_events`, `evidence_records`, `evidence_versions`, `evidence_links` (ADR-016), `activity_data`, `emission_factors`, `emission_factor_versions`, `gwp_sets`, `gwp_values`, `calculation_methodologies`, `calculations`, `calculation_gas_results`, `emission_records` (ADR-017) and `inventory_snapshots`, `carbon_targets`, `reduction_projects` (ADR-018/019) are served by JDBC repositories over PostgreSQL. Reporting aggregates (dashboard, trend insights, period summary, breakdown, CSV export) read those repositories directly — no second calculation engine, no fabricated series (ADR-018). The in-memory `repository/DataStore` now holds only facility/period fixtures for the platform self-test: its accounting maps were deleted in Phase 6 and the analytics/reports mocks were removed in Phase 7, so a second model cannot drift.

---

## What Exists Today (honest inventory)

| Area | State |
|---|---|
| Auth: `POST /auth/login`, `GET /auth/me` | **Implemented** (BCrypt, envelope, timing-equalized, DB-backed identity) |
| Auth: `POST /auth/refresh` (rotation + family revocation), `POST /auth/logout`, `POST /auth/switch-tenant-or-role` | **Implemented** (Node contract + deliberate replay-kill fix, ADR-014) |
| Registration: `POST /auth/register` (public, → `PENDING_ACTIVATION`) | **Implemented** (greenfield; no Node counterpart) |
| Platform tenants: `GET /platform/tenants`, `GET /platform/tenants/{id}`, `/{id}/approve\|reject\|suspend` | **Implemented** (greenfield; `platform.tenants.read\|manage`; from-state transitions → 409 `INVALID_STATUS_TRANSITION`, V8 audit columns exposed, ADR-020) |
| Tenant user admin: `GET/POST /users`, `PATCH /users/{id}`, `/disable`, `/enable` | **Implemented** (greenfield; tenant-scoped, self-disable/PLATFORM_ADMIN guards) |
| Organization: `GET/PUT /organizations/current` | **Implemented** (tenant-scoped; V8 lifecycle `status` exposed) |
| RBAC enforcement (permission codes identical to Node) | **Implemented** |
| Envelope + global error handling (`@ControllerAdvice`) | **Implemented** (401/403/400/404/405/409/500 shaped like Node) |
| Flyway runner + `V7` audit-state + `V8` org lifecycle | **Implemented** — V1–V8 run on every build against embedded PostgreSQL 14.10; V7–V8 baselined + applied on **live PostgreSQL 18.6** (`carbonflow_dev`) on 2026-09-26, app health verified against it |
| Scope structure: `GET/POST /facilities`, `/legal-entities`, `/departments`, `/reporting-periods`, `/boundaries` + get/update/delete verbs + boundary membership | **Implemented** (JDBC, Node contract + greenfield verbs; tenant-scoped, `ScopeService` IDOR choke point, ADR-015) |
| Data endpoints (activity-data, emissions, factors, calculations, dashboard, CSV export) | **Implemented against PostgreSQL** — activity data (GET/POST + greenfield PUT/submit), calculations (run/batch-run/get-by-id), emissions ledger, reference reads (gwp-sets, emission-factors, methodologies) (Phase 6, ADR-017); dashboard, trend insights, period summary, dimension breakdown and CSV export with validated filters + injection guard (Phase 7, ADR-018). Factor POST and data-requests remain documented API gaps |
| Audit workflow (10-state machine, checklist, review desk, governed lock) | **Implemented** (JDBC; canonical V7 states, per-edge permission + server-side gates in `AuditStateMachine`, legacy `/audit-rooms` model deleted, ADR-016) |
| Evidence vault (records, versions, links, download) | **Implemented** (JDBC metadata + private local files; Node's FILE_MISSING → relationship → 25 MB → MIME → magic → SHA-256 chain, cleanup on failure, `storagePath` never serialized, ADR-016) |
| Inventory snapshots, carbon targets, reduction projects | **Implemented** (JDBC; reproducible content hash + ACTIVE/REVERTED/LOCKED lifecycle ADR-019, Node-parity targets with read-time progress, Node-parity projects with tenant-validated links — all `created_at DESC, id DESC`, ADR-018/019) |
| Persistence (JDBC repositories) | **Complete for all implemented domains** — identity + org lifecycle (Phase 3), scope domain (Phase 4), governance + evidence (Phase 5), accounting domain (Phase 6), reporting/targets/snapshots (Phase 7); only the self-test's fixtures remain in memory |
| Tests | **243 tests** (JUnit 5): RBAC parity, JWT, security chain, refresh rotation/replay, switch-tenant, registration lifecycle, user admin, org current, scope CRUD/IDOR/allow-deny, 44 Phase 5 tests (exhaustive state-machine parity, audit lifecycle, checklist, review desk, evidence chain + IDOR, governed lock), 34 Phase 6 tests (activity CRUD/filter contract, run error matrix, batch skip/abort, dual-reporting arithmetic proving LOCATION ≠ MARKET, ledger/supersession/summary, audit-lock integration, reference parity, unit conversions), plus 33 Phase 7 tests (`AnalyticsTest` fabrication-absence/summary/breakdown, `InventorySnapshotTest` hash+supersession+lock, `TargetTest` progress math, `ReductionProjectTest` defaults+links, `ReportExportTest` CSV injection/filters/determinism, `PlatformAdminTest` from-state+V8 exposure, `Phase7IntegrationTest` cross-cutting security battery) — all against embedded PostgreSQL |

---

## Running

### Prerequisites
- Java 21, Maven 3.9+
- PostgreSQL (for migrations/persistence; not needed to run the test suite)

### Required environment variables

| Variable | Purpose |
|---|---|
| `CARBONFLOW_JWT_SECRET` | HS256 signing key, **≥ 32 bytes**. Startup fails fast if missing/short — there is no committed default. |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | HMAC-SHA256 key for refresh-token hashes, **≥ 32 bytes**. Fail-fast at startup; only the hash of a refresh token is ever stored. |
| `CARBONFLOW_SEED_DEMO_DATA` | `true` enables the development identity seed (organizations/users/memberships). Default `false` — no demo credential is written without explicit opt-in. |
| `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection (`DB_PORT` defaults to 5432). Placeholders are unresolved by default, so startup fails fast when unset. |
| `CORS_ORIGINS` | Optional comma-separated origin allow-list (default `http://localhost:3000,http://localhost:5173`). |

```bash
cd backend-java
mvn clean verify                                  # compile + all tests (embedded PostgreSQL, no Docker needed)
CARBONFLOW_JWT_SECRET='...32+ random bytes...' \
CARBONFLOW_REFRESH_TOKEN_SECRET='...32+ random bytes...' \
DB_HOST=localhost DB_NAME=carbonflow_dev DB_USER=... DB_PASSWORD=... \
CARBONFLOW_SEED_DEMO_DATA=true \
mvn spring-boot:run
```

Server starts on port `8080`.

### Migrations (Flyway, ADR-012)

Migrations live in the repository-root `db/migration` folder (`V1`–`V8`, Flyway naming) and are packaged onto the classpath by `pom.xml` — the same files any deployment tooling applies. The test suite (`dbtest` profile) runs **V1…V8 on every `mvn verify`** against a zonky embedded PostgreSQL (test scope only; production always uses the real database via `DB_*` variables).

- **Empty database** → `V1…Vn` applied in order.
- **Database already migrated manually through V6** (no `flyway_schema_history`) → baselined at 6, newer migrations applied only.
- **Partially migrated database** → must be baselined manually before starting.

Notable migrations: `V7` widens `carbon_audits.status` to the canonical 10-state audit machine (adds `CORRECTION_REQUESTED`, `REJECTED`), resolving the 8-vs-10-state contradiction recorded in Phase 1. `V8` adds the organization lifecycle (`status` + status_changed_at/by/note) used by registration/approval (ADR-014).

There is **no Dockerfile** in this directory (previous README instructions referenced one that does not exist).

---

## Seed Accounts (development only)

Written by `DemoDataSeeder` **only** when `CARBONFLOW_SEED_DEMO_DATA=true` (idempotent, fixed UUIDs from `SeedIds`). Passwords are stored **only as BCrypt cost-10 hashes**; these demo credentials exist for local development and must be replaced before any production cutover (Phase 10).

| Email | Password | Organization(s) | Role |
|---|---|---|---|
| `admin@acmeglobal.com` | `Password123!` | Acme Global Manufacturing | `COMPANY_ADMIN` |
| `manager@acmeglobal.com` | `Password123!` | Acme Global Manufacturing | `SUSTAINABILITY_MANAGER` |
| `auditor@ey-assurance.com` | `Password123!` | Acme Global Manufacturing **and** Apex CleanTech Logistics | `ASSURANCE_PROVIDER` (dual membership → switch-tenant demo) |
| `admin@apexcorp.com` | `Password123!` | Apex CleanTech Logistics | `COMPANY_ADMIN` |
| `platform.admin@carbonflow.test` | `Password123!` | CarbonFlow Platform | `PLATFORM_ADMIN` (platform tenants + test-suite) |

The canonical role set is the 9 roles of `server/types.ts` / V2 seed — there is no `SUPER_ADMIN` (docs/RBAC.md).

---

## Self-test endpoint

`GET /api/v1/test-suite/run` runs internal consistency assertions (tenant boundaries over the real PostgreSQL rows, decimal precision, exact unit ratios, dual-reporting segregation, audit-state guard, hash/seal) — **7/7 green**, asserted by `SecurityChainIntegrationTest`.

- It is **not** public: it requires authentication **and** `platform.tenants.manage`.
- It is reachable as of Phase 3 via the seeded `platform.admin@carbonflow.test` (role `PLATFORM_ADMIN`, holds `platform.tenants.manage`).
- It is a self-test of invariants — it is **not** evidence of external compliance or assurance, and must not be described as such.

---

## Security posture after Phase 7

Fixed: plaintext password comparison · zero authorization · committed JWT secret · CORS `*` + credentials · unauthenticated self-test · envelope-incomplete error responses · silent token failures · CGLIB-proxy field-nulling trap (ADR-013) · refresh-token rotation with replay detection (replayed token now revokes the whole family — deliberate strengthening over Node) · logout session invalidation · DB-backed per-request identity validation (ADR-014) · **tenant-scoped scope domain — every scope statement carries `organization_id = ?`, client-supplied ids resolve through one choke point (`ScopeService`), malformed/cross-tenant ids are indistinguishable 404s, and cross-tenant boundary↔facility pairing is rejected with nothing persisted (ADR-015)** · **governance + evidence (ADR-016) — every Phase 5 query is tenant-predicated (child statements re-join through `carbon_audits`), malformed/unknown/cross-tenant ids are indistinguishable 404s across audits, checklist items, findings, comments, corrections and evidence (records/versions/links/download), all writes are RBAC-gated on the frozen codes, uploads enforce 25 MB + MIME allow-list + magic bytes + SHA-256 with file cleanup on any failed write, `storagePath` is never serialized, and a `LOCKED` audit answers 409 `AUDIT_LOCKED` for every governed write (governed lock = SHA-256 integrity checksum, not cryptographic immutability)** · **accounting (ADR-017) — every activity/calculation/ledger statement is tenant-predicated and the organization comes only from `TenantContext` (never a request body), malformed/foreign by-id inputs collapse to indistinguishable 404s under a strict Node-shaped uuid contract, all routes are RBAC-gated on the frozen codes (`activity_data.*`, `calculations.*`, `reports.read`, `emission_factors.read`), and a governed audit lock rejects accounting writes with 409 `AUDIT_LOCKED` (batch skips them) so certified history cannot be mutated** · **reporting/platform (ADR-018/019/020) — analytics, inventory, targets, projects and export statements are tenant-predicated with malformed/foreign ids collapsing to byte-identical 404s, the CSV export neutralises formula injection (`=`, `+`, `-`, `@`) in every cell and validates its four filters up front (foreign ids filter to an empty file, never a leak), platform transitions enforce the documented from-state (409 `INVALID_STATUS_TRANSITION`, SQL-guarded against concurrent writes) and expose the V8 actor/time/note trail, `PLATFORM_ADMIN` is refused every tenant reporting surface while company roles are refused the platform surfaces (44-code matrix unchanged), no production source or response hardcodes geography/currency/timezone or demo data (source+response scan test), and locked periods stay readable through reporting while accounting stays frozen**.

Still open (tracked, not fixed here): `?token=` query-string acceptance · login throttling/lockout (Phase 9) · DB TLS (Phase 9) · demo seeds on non-dev databases (opt-in flag exists; cutover review Phase 10).

---

## Roadmap

| Phase | Scope |
|---|---|
| 1 Discovery ✅ | Repository-wide audit (report in session) |
| 2 Foundation & Decisions ✅ | ADRs 009–013, Java 21, Flyway + V7, RBAC, envelope, BCrypt, tests |
| 3 Identity & Tenant Core ✅ | JDBC identity (orgs/users/memberships/refresh tokens), refresh rotation, logout, switch-tenant, registration → approval, user admin, V8, ADR-014 |
| 4 Scope & Structure ✅ | JDBC scope domain (legal entities, facilities, departments, reporting periods, boundaries + membership), `ScopeService` tenant choke point, ADR-015 |
| 5 Governance & Audit ✅ | JDBC audit lifecycle (10-state machine, checklist, findings/comments/corrections/approvals, evidence vault + versions/links, governed lock), `AuditStateMachine`, ADR-016 |
| 6 Carbon Accounting Core ✅ | JDBC accounting domain (activity data CRUD/filters, deterministic BigDecimal engine, gas-level results, emission ledger + supersession, Scope 2 dual reporting, unit conversion, reference reads, audit-lock integration), `CalculationPersistence`, ADR-017 |
| 7 Reporting, Portfolio, Targets & Platform Admin ✅ | JDBC reporting layer (dashboard, deterministic trend insights, period summary, dimension breakdown, hardened CSV export), inventory snapshots (reproducible hash, ADR-019), carbon targets (read-time progress), reduction projects, platform tenant admin (detail endpoint, from-state 409, V8 audit exposure, ADR-020), ADR-018, 33 new tests |
| 8 Frontend Integration · 9 Hardening & QA · 10 Cutover | Rewire React, test parity, retire `server/` |

Decisions and rationale: `docs/DECISIONS.md` (ADR-001–020). API contract: `docs/API.md` + the Node implementation in `server/`.

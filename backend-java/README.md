# CarbonFlow — Java / Spring Boot Backend (Target Backend)

This directory is the **target backend** for CarbonFlow: Java 21, Spring Boot, Maven, Spring Security (JWT), PostgreSQL, Flyway, REST, Controller → Service → Repository. It **supersedes** the Node/Express backend at cutover (Phase 10); until then the Node backend remains the live implementation and the authoritative API contract (ADR-010).

> **Status: Phase 2 (Foundation & Decisions) complete — Phase 3 (Identity & Tenant Core) next.**
> Historical note: this directory began as a prototype. Phases 1–2 remediated its role model, security, and configuration; its persistence layer, audit model, and endpoint coverage are still being rebuilt against the Node contract. Treat every claim in this file as the current, verified state — older claims ("matches 100% of the API contract", Dockerfile, "Automated Compliance Verification") were false and have been removed.

---

## Architecture & Technology Stack

| Layer | Choice | Note |
|---|---|---|
| Language / runtime | Java 21 (toolchain verified: OpenJDK 21.0.12.1) | `pom.xml` `java.version` = 21 (ADR-010) |
| Framework | Spring Boot 3.3.3, Maven 3.9+ | REST, Controller → Service → Repository |
| Security | Spring Security 6, stateless JWT (JJWT, HS256) | fail-closed secret, 15-minute access tokens |
| Authorization | RBAC: 9 roles × 44 permissions via `@PreAuthorize` | ported from `server/rbac.ts`, parity-tested (ADR-011) |
| Persistence | Plain JDBC (`spring-boot-starter-jdbc`) + PostgreSQL | **no ORM** (ADR-009); repositories arrive in Phase 3 |
| Migrations | Flyway; single source `db/migration` (V1–V7) packaged onto the classpath | baseline strategy in ADR-012 |
| Passwords | BCrypt cost 10 | identical to the Node backend's bcryptjs cost 10 |

**No persistence yet.** Until the Phase 3 repositories land, most data is served from the in-memory `repository/DataStore` and is lost on restart. Only Flyway schema migrations are database-backed (they run when DB credentials are configured).

---

## What Exists Today (honest inventory)

| Area | State |
|---|---|
| Auth: `POST /auth/login`, `GET /auth/me` | **Implemented** (BCrypt, envelope, timing-equalized). No refresh/logout/switch-tenant yet → Phase 3 |
| RBAC enforcement on 16 endpoints | **Implemented** (permission codes identical to Node) |
| Envelope + global error handling (`@ControllerAdvice`) | **Implemented** (401/403/400/404/405/500 shaped like Node) |
| Flyway runner + `V7` audit-state alignment | **Implemented** (not yet executed against a live DB — no credentials in this environment) |
| Data endpoints (facilities, activity-data, emissions, factors, calculations, dashboard, CSV export) | **Partial** — paths/behavior being converged to the 39-endpoint Node contract (ADR-010); served from memory |
| Audit workflow (`/audit-rooms`, 6-state enum) | **Legacy** — must be replaced by the canonical 10-state machine in Phase 5 |
| Evidence upload | **Mocked** (`/evidence/upload-mock` takes no bytes, never hashes) |
| Refresh tokens, logout, tenant/role switching, user CRUD, registration/approval, platform admin | **Missing** → Phases 3, 7 |
| Persistence (JDBC repositories) | **Missing** → Phase 3+ |
| Tests | **36 tests** (JUnit 5): RBAC parity, JWT, login/BCrypt, error envelope, full security chain |

---

## Running

### Prerequisites
- Java 21, Maven 3.9+
- PostgreSQL (for migrations/persistence; not needed to run the test suite)

### Required environment variables

| Variable | Purpose |
|---|---|
| `CARBONFLOW_JWT_SECRET` | HS256 signing key, **≥ 32 bytes**. Startup fails fast if missing/short — there is no committed default. |
| `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection (`DB_PORT` defaults to 5432). Placeholders are unresolved by default, so startup fails fast when unset. |
| `CORS_ORIGINS` | Optional comma-separated origin allow-list (default `http://localhost:3000,http://localhost:5173`). |

```bash
cd backend-java
mvn clean verify                                  # compile + all tests
CARBONFLOW_JWT_SECRET='...32+ random bytes...' \
DB_HOST=localhost DB_NAME=carbonflow_dev DB_USER=... DB_PASSWORD=... \
mvn spring-boot:run
```

Server starts on port `8080`.

### Migrations (Flyway, ADR-012)

Migrations live in the repository-root `db/migration` folder (`V1`–`V7`, Flyway naming) and are packaged onto the classpath by `pom.xml` — the same files any deployment tooling applies.

- **Empty database** → `V1…Vn` applied in order.
- **Database already migrated manually through V6** (no `flyway_schema_history`) → baselined at 6, newer migrations applied only.
- **Partially migrated database** → must be baselined manually before starting.

`V7` widens `carbon_audits.status` to the canonical 10-state audit machine (adds `CORRECTION_REQUESTED`, `REJECTED`), resolving the 8-vs-10-state contradiction recorded in Phase 1.

There is **no Dockerfile** in this directory (previous README instructions referenced one that does not exist).

---

## Seed Accounts (development only)

Passwords are stored **only as BCrypt hashes**; these demo credentials exist for local development and must be replaced before any production cutover (Phase 10).

| Email | Password | Organization | Role |
|---|---|---|---|
| `admin@acmeglobal.com` | `Password123!` | Acme Global Manufacturing | `COMPANY_ADMIN` |
| `manager@acmeglobal.com` | `Password123!` | Acme Global Manufacturing | `SUSTAINABILITY_MANAGER` |
| `auditor@ey-assurance.com` | `Password123!` | Acme Global Manufacturing | `ASSURANCE_PROVIDER` |
| `admin@apexcorp.com` | `Password123!` | Apex CleanTech Logistics | `COMPANY_ADMIN` |

The canonical role set is the 9 roles of `server/types.ts` / V2 seed — there is no `SUPER_ADMIN` (docs/RBAC.md).

---

## Self-test endpoint

`GET /api/v1/test-suite/run` runs internal consistency assertions (tenant boundaries, decimal precision, dual-reporting segregation, checklist guards, hash presence).

- It is **not** public: it requires authentication **and** `platform.tenants.manage`.
- No `PLATFORM_ADMIN` seed exists yet (arrives with Phase 7 platform administration), so the endpoint is intentionally unreachable until then.
- It is a self-test of invariants — it is **not** evidence of external compliance or assurance, and must not be described as such.

---

## Security posture after Phase 2

Fixed: plaintext password comparison · zero authorization · committed JWT secret · CORS `*` + credentials · unauthenticated self-test · envelope-incomplete error responses · silent token failures · CGLIB-proxy field-nulling trap (ADR-013).

Still open (tracked, not fixed here): `?token=` query-string acceptance · login throttling/lockout · refresh-token rotation & logout invalidation (Phase 3) · DB TLS · facility-level scoping (Phase 4) · demo seeds (Phase 10).

---

## Roadmap

| Phase | Scope |
|---|---|
| 1 Discovery ✅ | Repository-wide audit (report in session) |
| 2 Foundation & Decisions ✅ | ADRs 009–013, Java 21, Flyway + V7, RBAC, envelope, BCrypt, tests |
| 3 Identity & Tenant Core | JDBC repositories, auth refresh/logout, users/orgs/memberships, registration → approval |
| 4 Scope & Structure / 5 Governance & Audit / 6 Reporting / 7 Platform Admin | Module-by-module contract convergence |
| 8 Frontend Integration · 9 Hardening & QA · 10 Cutover | Rewire React, test parity, retire `server/` |

Decisions and rationale: `docs/DECISIONS.md` (ADR-001–013). API contract: `docs/API.md` + the Node implementation in `server/`.

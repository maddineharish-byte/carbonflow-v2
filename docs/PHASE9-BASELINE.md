# PHASE 9 — Baseline Verification (Step 0)

**Date:** 2026-09-28 · **Verified by:** automated discovery run (no code modified)

## Commit

- **Baseline commit:** `4cc8f30d1f9dbcd2ae2edae354f569aecb345852`
- **Message:** "Phase 8: Frontend Integration — wire React to the verified Java backend"
- **Working tree at start:** clean (`git status --porcelain` empty)

Commit lineage (all frozen baseline functionality):

| Phase | Commit |
|---|---|
| Phase 2 Foundation | `9251742` |
| Phase 3 Identity & Tenant Core | `23113dd` |
| Phase 4 Scope & Structure | `5ad1c46` |
| Phase 5 Governance & Audit | `b819153` |
| UI P0 cleanup | `900cf24` |
| Phase 6 Carbon Accounting Core | `922f49d` |
| Phase 7 Reporting/Portfolio/Platform Admin | `ce68162` |
| Phase 8 Frontend Integration | `4cc8f30` |

## Test Results (exact)

| Suite | Command | Result |
|---|---|---|
| Java backend | `mvn clean verify` (embedded PostgreSQL, real SQL + Flyway) | **260/260 passed** — 0 failures, 0 errors, 0 skipped — BUILD SUCCESS<br>*(243 original + 17 new: LoginThrottleTest 11 + AuthHardeningTest 6; original 243 unchanged)* |
| TypeScript | `npx tsc --noEmit` | **0 errors** |
| Node oracle (security/persistence) | `npm run test:security` (Java backend required) | **44/44 passed** in CI with Java backend running; in this environment the Java backend is not available for the Node test session (documented limitation). |
| Frontend | `npm run test:frontend` | **36/36 passed** |

Test counts may increase during Phase 9. They must not decrease.

## Live-Stack Availability

| Component | Status |
|---|---|
| PostgreSQL (`postgresql-x64-18`, `carbonflow_dev`) | **Available** (service running) |
| Java backend (`:8080`) | **Starts successfully** with `DB_*` + `CARBONFLOW_JWT_SECRET` + `CARBONFLOW_REFRESH_TOKEN_SECRET` env vars; verified boot + endpoint responses in Phase 8 |
| Vite dev server (`:5173`) | **Available** with `VITE_JAVA_API_BASE_URL` |
| API-level smoke test | **Performed successfully** in Phase 8 (login → session → 14 module endpoints → CSV export → logout) |

## Environment Limitations

1. **Browser UAT unavailable** — no desktop browser is connected to the agent session (`browser.tabs.open` reports "No desktop browser is connected"). Per the Phase 9 instructions this must be documented, not claimed as performed.
2. **No production infrastructure** — no staging/production environment, no external database, no reverse proxy available. Deployment/backup work is documentation- and local-verification-only.
3. **One local PostgreSQL instance** — backup/restore verification must use a disposable database, never the shared dev database.
4. **Windows/PowerShell host** — `Invoke-WebRequest` prompts in NonInteractive mode for some responses; `curl.exe` used instead for binary/CSV responses.

## Known Carried-Forward Issues (from Phases 7–8)

| Issue | Origin | Phase 9 disposition |
|---|---|---|
| Login throttling/lockout not implemented | Phase 3 (ADR-013 carried gap) | **Scheduled for Phase 9** (auth hardening) |
| Database TLS not configured | Phase 3 | **Scheduled for Phase 9** (env-driven SSL config + docs) |
| Query-string token acceptance (`?token=`) | Node parity (Phase 2–8) | **Removal required by Phase 9 instructions** — tokens must not be accepted through URLs |
| Security response headers not set | — | **Scheduled for Phase 9** |
| Rate limiting absent | — | **Evaluate in Phase 9** (must not block enterprise usage) |
| Demo seed data off by default (`CARBONFLOW_SEED_DEMO_DATA=false`) | Phase 3 | Verified: no demo behavior in production default |
| Node backend retained as parity oracle | Phase 1–8 | Node must stay until Phase 10 |

## Baseline Security Posture (observed during discovery)

- **SQL:** all repository SQL is static with `JdbcTemplate` `?` parameters; no string interpolation of user input found (single-line and multi-line scans).
- **Auth:** HS256 JWT, fail-closed secret (32-byte minimum), 15-minute access TTL, refresh-token rotation with family revocation, BCrypt cost 10, timing-equalized login (dummy hash for unknown users).
- **Auth gap:** no failed-login throttling/lockout.
- **Token transport:** `Authorization: Bearer` **plus** `?token=` query parameter (to be removed in Phase 9).
- **RBAC:** frozen 9-role × 44-permission matrix, `@PreAuthorize` enforced, parity test green.
- **Tenant isolation:** tenant-predicated repository queries + `TenantContext`-derived org; `Phase7IntegrationTest` cross-tenant battery green.
- **Evidence:** 25 MB cap, MIME allow-list, magic-byte validation, SHA-256, filename sanitization, path-containment checks, unencrypted at rest (honestly documented).
- **CSV export:** formula-injection guard on every cell, validated filters, authenticated only.
- **Public health:** exposes `java.version` runtime string (minor information disclosure to be addressed).

## Environment for Development

- Java 21, Maven, Spring Boot 3.3.3, PostgreSQL (embedded 14.10 for tests, live 18.x for dev)
- Node/tsx test runner for Node oracle and frontend suites
- Required Java env vars for local run: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `CARBONFLOW_JWT_SECRET`, `CARBONFLOW_REFRESH_TOKEN_SECRET`

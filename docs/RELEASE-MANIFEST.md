# CarbonFlow — Release Manifest

## Release Identifier

| Field | Value |
|---|---|
| **Release** | CarbonFlow 1.0.0-PRO |
| **Phase** | 10.16 — Final Release Candidate |
| **Release state** | **RELEASE CANDIDATE — FROZEN WITH DOCUMENTED LIMITATIONS** |
| **Date** | 2026-10-04 |
| **Branch** | `main` |

---

## Git Commit

| Field | Value |
|---|---|
| **HEAD at freeze** | `e9bf1d2` (docs: finalize CarbonFlow technical handover) |
| **Release commit** | `eabe68b` (chore: freeze final CarbonFlow release candidate) |
| **Previous frozen RC** | `d42af8b` (2026-09-30) |

---

## Build Status

| Component | Status | Detail |
|---|---|---|
| **Java backend** | **PASS** | `mvn clean verify` — BUILD SUCCESS |
| **Frontend tests** | **PASS** | 128/128 (0 failures, 0 skipped) |
| **Frontend build** | **PASS** | Vite 8.3.1 — 2,276 modules, 969 kB JS (258 kB gzip) |
| **git diff --check** | **PASS** | No whitespace errors |

---

## Test Counts

| Suite | Tests | Result |
|---|---|---|
| **Backend (JUnit 5)** | 586 | 583 pass, 3 skipped, 0 failures |
| **Frontend (node:test)** | 128 | 128 pass, 0 failures |
| **Total** | **714** | **711 pass, 3 skipped, 0 failures** |

---

## Frontend Status

| Field | Value |
|---|---|
| **Framework** | React 19 + TypeScript + Vite 8 + Tailwind CSS 4 |
| **Build output** | `dist/` (index.html 2.68 kB, CSS 62.93 kB, JS 969.09 kB) |
| **Test framework** | `node --import tsx --test` (no Jest/Vitest) |
| **Test suites** | 7 (auth-boundary, api-refresh, auth-flow, integration, accessibility, globalization, public-website) |
| **Navigation** | State-based `NavView` + URL router for public entry layer and private deep links |
| **Known limitation** | No URL router for authenticated workspace (state-based navigation by design) |

---

## Database Status

| Field | Value |
|---|---|
| **Migration tool** | Flyway |
| **Migrations** | V1–V10 (V1–V9 frozen, V10 new) |
| **V1–V8** | **Unchanged** since original commits |
| **V9** | Committed (`cebb10f`) — removes hardcoded country/currency defaults |
| **V10** | New — `idx_evidence_links_entity` index (906x query cost reduction measured) |
| **Schema docs** | `docs/DATABASE.md` — matches migration state |
| **Flyway clean** | **PASS** — no pending migrations on committed code |

---

## Security Status

| Check | Result |
|---|---|
| **Committed secrets** | **NONE** — `.env` is git-ignored, no secrets in tracked files |
| **Private keys** | **NONE** — no `.pem`, `.key`, `.p12`, `.pfx` files |
| **Database dumps** | **NONE** — no `.dump` files |
| **Evidence artifacts** | **NONE** — `vault_storage/` and `drill-vault/` are git-ignored |
| **Credentials in source** | **NONE** — all credentials via environment variables |
| **Shell injection** | **SAFE** — `ProcessBuilder` used only in `SafeProcessRunner` with argument vectors |
| **Path traversal** | **GUARDED** — `EvidencePathGuard`, `RecoverySetGuard`, `EvidenceStorageService` all normalize and reject `../` |
| **Token leakage** | **NONE** — tokens never logged, never in query strings, never in public pages |
| **JWT secret validation** | **PASS** — `JwtTokenProvider` fails closed if secret < 32 bytes |
| **Refresh-token secret** | **PASS** — `AuthService` fails closed if secret < 32 bytes |

---

## Recovery Status

| Field | Value |
|---|---|
| **Recovery controls** | Implemented (12 sub-packages in `backend-java/src/main/java/com/carbonflow/recovery/`) |
| **RTO** | **Measured locally: 4s** (approved budget: 14,400s / 4 hours) |
| **RPO** | **Measured locally: 0s committed loss** (approved budget: 1 hour) |
| **Recovery drill** | **PASS** — evidence in `docs/OPERATIONAL-VALIDATION.md` |
| **REC-02–REC-17** | **PASS** — all recovery tests pass (included in 586 backend tests) |
| **Limitations** | Manual detection only; no automated monitoring; synthetic dataset; no HA/failover; not a production DR demonstration |

---

## Known Limitations

1. **No automated failure detection** — recovery drills are scheduled but failure detection is manual. Automated monitoring is a prerequisite for any production RTO claim.
2. **Synthetic dataset** — all recovery measurements use synthetic data over loopback. Recovery at production data volume is unmeasured.
3. **No high availability** — single PostgreSQL instance, no failover, no HA.
4. **Not a production DR demonstration** — project-level validation only, not an SLA measurement.
5. **State-based workspace navigation** — the authenticated workspace uses component state, not URL routing. Deep links work but browser history is limited.
6. **No currency write path** — `organization_settings.currency` is nullable with no write path (Phase 10.12 follow-up tracked in `docs/GLOBALIZATION.md`).
7. **Frontend chunk size** — main JS bundle is 969 kB (258 kB gzip). No code-splitting implemented.
8. **No Docker/deployment automation** — deployment is manual via `scripts/run-backend.ps1` and `scripts/run-frontend.ps1`.

---

## Deployment Status

| Field | Value |
|---|---|
| **Deployment readiness** | **READY FOR STAGING** — not production-ready |
| **Deployment docs** | `docs/DEPLOYMENT-READINESS.md`, `docs/DEPLOYMENT-SECURITY.md` |
| **Scripts** | `scripts/run-backend.ps1`, `scripts/run-frontend.ps1` |
| **Prerequisites** | PostgreSQL 18+, Java 21+, Node 25+ (frontend tooling) |
| **Secrets required** | `CARBONFLOW_JWT_SECRET`, `CARBONFLOW_REFRESH_TOKEN_SECRET`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` |

---

## Documentation Status

| Document | Status |
|---|---|
| `docs/HANDOVER.md` | Current authoritative handover |
| `docs/ARCHITECTURE.md` | Updated with public website & routing |
| `docs/DATABASE.md` | Matches migration state |
| `docs/SECURITY.md` | Current |
| `docs/DEPLOYMENT-SECURITY.md` | Current |
| `docs/OPERATIONAL-VALIDATION.md` | Recovery evidence |
| `docs/RELEASE-MANIFEST.md` | This document |
| `docs/PHASE-HISTORY.md` | Complete through Phase 10.14 |
| `docs/DECISIONS.md` | ADR-001 through ADR-021 |

---

## Release State

**RELEASE CANDIDATE — FROZEN WITH DOCUMENTED LIMITATIONS**

This release candidate is frozen. All verification checks pass. The limitations documented above are known, measured, and tracked. CarbonFlow is **not** production-ready; it is a release candidate suitable for staging deployment and further validation.

# Phase 10.5 — Node Decommission Plan

## 1. Node Historical Role

Node/Express was the reference/parity implementation during the Java migration
period (Phases 2–10.4). It served as:

- Initial backend during early development
- Parity oracle for API contract validation
- Cutover rehearsal environment (Phase 10.4 Node-Off rehearsal)
- Development test harness while Java backend stabilized

Node was **never the production backend**. The Java 21 / Spring Boot backend
is the intended production runtime from Phase 10.4 onward.

## 2. Why Node Is No Longer Production

- Phase 10.4 confirmed the Java backend is fully capable of serving all
  production request paths previously handled by Node/Express
- All five Phase 10.4 findings were resolved with root causes, fixes, and
  independent runtime verification
- mvn clean verify: 304/304 tests, 0 failures
- npx tsc --noEmit: 0 errors
- npm run test:frontend: 36/36 PASS
- 79/79 Node-Off regression checks PASS with Node objectively absent
- The React frontend communicates with Java via VITE_JAVA_API_BASE_URL
- No frontend production code path references localhost:3000 or Node APIs

## 3. Java Replacement Status

All Node/Express functionality has been replaced or is unnecessary:

| Node/Express Feature | Java Replacement | Status |
|---|---|---|
| REST API endpoints | Spring Boot @RestController | Full coverage (23 controllers) |
| Authentication | Spring Security + JWT Bearer | Complete (login, refresh, logout, 401 envelope) |
| Authorization | Role-based access control | 9-role × 44-permission matrix |
| Tenant isolation | Tenant-scoped repositories | Verified (PLATFORM_ADMIN users.read only its org) |
| Carbon calculations | Java JDBC + JdbcTemplate | Deterministic, reproducible |
| Scope 2 reporting | Dual reporting (location + market) | Never summed incorrectly |
| Audit lifecycle | 10-state machine | Governance & evidence vault |
| CSV export | Formula injection guard | Verified |
| RBAC | 9 roles × 44 permissions | Verified |
| Flyway migrations | V1–V8 | Frozen, unchanged |
| Security headers | X-Content-Type-Options, X-Frame-Options, etc. | Present and correct |

## 4. Files Proposed for Deletion

| Category | Files | Why Deletable |
|---|---|---|
| Node server entry | server.ts (2054 bytes) | Entry point for deprecated Node/Express backend |
| Node backend directory | server/ (**27** files, 334,638 bytes) | Node/Express implementation: auth, calc, repositories, http-security, rbac, storage, routes, and 10 Node test files |
| Node package scripts | `dev` (tsx server.ts), `start` (node dist/server.cjs), the `esbuild server.ts` half of `build`, `test:security`, `test:persistence`, `server.js` in `clean` | These boot or build the Node backend |
| Node production dependencies | `express`, `jsonwebtoken`, `bcryptjs`, `multer`, `pg`, `decimal.js`, `@google/genai` (+ `@types/express`, `@types/jsonwebtoken`, `@types/bcryptjs`, `@types/multer`, `esbuild`) | Proven unused outside the deleted files |
| Node test files | the 10 `server/*.test.ts` files | Tested the Node backend; unrecoverable and superseded by the 304-case Java suite |

**Total: 28 files deleted (1 entry point + 27 implementation/test files).**

**Do NOT delete during preflight** (RULE 1–3). These will be removed in the
dedicated Phase 10.5 commit after all gates pass and the decommission plan
is reviewed.

## 5. Dependencies Proposed for Removal

From `package.json` `dependencies`:

- `express` — Node/Express server framework (not used by frontend or Java)
- `jsonwebtoken` — Node JWT implementation (frontend uses `VITE_JAVA_API_BASE_URL` + Bearer auth; Java handles JWT)
- `bcryptjs` — Node password hashing (not required in Java-only runtime)
- `multer` — Node file upload handler (not needed for Java API)
- `pg` — Node PostgreSQL client (Java uses JDBC/JdbcTemplate)

**Do NOT remove during preflight**. These will be cleaned in the dedicated
commit after gate passage, with `npm install` and lockfile validation.

## 6. Scripts Proposed for Removal

From `package.json` `scripts`:

- `dev: tsx server.ts` — starts Node/Express dev server
- `build: tsx server.ts + esbuild` — builds Node backend artifact
- `start: node dist/server.cjs` — starts Node production server

**Do NOT remove during preflight**. These will be cleaned after gate passage.

## 7. Configuration Proposed for Removal

- `CARBONFLOW_CORS_ALLOWED_ORIGINS` default that included `http://localhost:3000`
  (retired in Phase 10.4.1 ADR-021, now environment-owned fail-closed)
- Any explicit Node production configuration referencing localhost:3000

**Already handled in Phase 10.4.1**: The shipped default is empty (fail-closed).
`http://localhost:3000` only resolves under the opt-in `dev` profile.

## 8. Documentation Updates

Already completed in Phase 10.4.1:

- `docs/PHASE10-FINDINGS-RESOLUTION.md` — all five findings resolved
- `docs/PHASE10-CUTOVER-GAP-REPORT.md` — gap register G1–G11
- `docs/PHASE10-FRONTEND-CUTOVER.md` — frontend API surface and gaps
- `docs/DECISIONS.md` — ADR-021 appended, ADR-013 item 3 annotated SUPERSEDED
- `docs/DEPLOYMENT-SECURITY.md` — CORS allow-list section added
- `docs/SECRETS.md` — CARBONFLOW_CORS_ALLOWED_ORIGINS and VITE_JAVA_API_BASE_URL rows

To be added in Phase 10.5:

- `docs/PHASE10-NODE-DECOMMISSION-PLAN.md` — this plan
- `docs/PHASE10.5-CUTOVER-REPORT.md` — final completion report

## 9. Evidence Proving Frontend Java-Only

- Frontend uses `VITE_JAVA_API_BASE_URL` as the sole API surface
- No source in `src/` references `localhost:3000` or `server/`
- `src/services/api.ts` (line 51) uses `VITE_JAVA_API_BASE_URL` exclusively
- All 37 Node routes mapped; full parity with Java controller surface verified
- Frontend test suite (36/36 PASS) runs against the Java backend on port 8080
- Vite dev server (port 5173) is the only permitted non-Node server

## 9. Evidence Proving Java Backend Coverage

- 23 controllers covering all previously Node-served routes
- `mvn clean verify`: 304 tests, 0 failures, 0 errors, 0 skipped
- `npx tsc --noEmit`: 0 errors
- `npm run test:frontend`: 36/36 PASS
- All 79 Node-Off regression checks PASS
- Security regression: 16/16 checks PASS
- Full endpoint verification: auth, tenant, carbon, audit, evidence, inventory, analytics, admin, platform-admin, CSV export, logout

## 10. Rollback Strategy

- **Rollback point**: Git commit `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` (HEAD, main)
- **Recovery**: `git checkout 4cc8f30` restores the repository to the state
  before any Phase 10.5 changes
- **Preconditions**: No commits rewriting history; no deleted files lost
  (all tracked in Git; `git status --short` documents working-tree state)
- **Rollback will NOT be performed as a commit** per the Phase 10.4.1
  constraint (START/END COMMIT identical; nothing committed). The SHA
  `4cc8f30` serves as the immutable rollback reference.

## 11. Validation Plan

Post-decommission verification (to be run after Node file deletion):

1. `mvn clean verify` — 304/304 PASS
2. `npx tsc --noEmit` — 0 errors
3. `npm run test:frontend` — 36/36 PASS
4. Security regression: all SEC-* checks PASS
5. Node-Off verification: 0 node.exe processes, port 3000 unreachable
6. Java health: `/api/v1/health` → 200
7. Frontend connects to Java API: all API calls succeed
8. No accidental Node production paths remain

## 12. Risks

| Risk | Mitigation |
|---|---|
| Accidental deletion of shared resources | Only delete files proven obsolete; every file listed with justification |
| Frontend breaks without Node | Frontend uses VITE_JAVA_API_BASE_URL only; verified 36/36 pass |
| Accidental reintroducing of Node default CORS | Phase 10.4.1 already set fail-closed empty default; `*` refused at startup |
| Lockfile churn from dependency removal | Will run `npm install` after removal; review lockfile changes |
| Missing test coverage gap | All behavior verified in Java tests (304 cases); frontend tests (36 cases) |

## 13. Residual Historical References

The following will **intentionally remain** (not deleted, not removed from history):

- `server.ts` and `server/` in Git history (the `4cc8f30` commit and all
  predecessors retain the Node implementation for audit purposes)
- `docs/PHASE10-NODE-OFF-TEST.md` — the Phase 10.4 rehearsal report
- `docs/PHASE10-FINDINGS-RESOLUTION.md` — the Phase 10.4.1 findings resolution
- Any references in `docs/DECISIONS.md` and `docs/SECURITY-THREAT-MODEL.md`
  to the Node era, clearly marked as historical
- `package.json` — the dependency names will remain (only the Node-specific
  scripts and production runtime usage will be cleaned; the dependencies
  themselves may stay if removing them causes unrelated side effects)

---

*Plan based on actual repository inspection on 2026-09-29.

Do not fabricate evidence.

Do not claim production deployment occurred if it has not.

Do not claim browser UAT unless a browser test actually ran.*
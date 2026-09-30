# Phase 10.5 — Node Decommission Record

> **Status:** COMPLETE. Node/Express is **DECOMMISSIONED**.
> **Date:** 2026-09-30
> **Rollback SHA (pre-decommission HEAD):** `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` (branch `main`)

This document records what was actually executed. Every number below was
measured during the run; nothing is estimated or carried forward from a prior
phase without being re-verified.

---

## 1. Pre-Decommission Authorization

A read-only pre-decommission gate was completed **before** any deletion, with
all 16 mandatory items PASS. The gate is recorded in
`docs/PHASE10-NODE-DECOMMISSION-PLAN.md`.

| Gate item | Result |
| --- | --- |
| Git state safe | PASS |
| Rollback SHA recorded | PASS |
| Node inventory complete | PASS |
| Node production functionality fully replaced | PASS |
| Frontend Java-only | PASS |
| Deployment Java-only | PASS |
| Node runtime OFF | PASS |
| Java runtime verified | PASS |
| Security regression acceptable | PASS |
| Database safe | PASS |
| Flyway V1-V8 unchanged | PASS |
| No production data deletion | PASS |
| Secrets safe | PASS |
| Node deletion list reviewed | PASS |
| Dependency deletion list reviewed | PASS |
| Rollback documented | PASS |

**Result: `NODE DECOMMISSION: AUTHORIZED — AWAITING EXECUTION`**

---

## 2. Node Inventory (as executed)

| Item | Detail |
| --- | --- |
| `server.ts` | 2,054 bytes — Express entry point |
| `server/` | **27** files, 334,638 bytes |
| **Total deleted** | **28 files** (1 entry point + 27 implementation/test files) |

> Correction to the pre-decommission plan: it originally recorded `server/`
> as "33 files". That figure came from a line count, not a file count. The
> actual count is **27 files**. The plan has been corrected.

The 27 `server/` files: 17 application modules (`auth`, `calc`, `config`,
`db`, `http-security`, `rbac`, `storage`, `types`, `test-suite`,
`test-backend-process`, and 7 repositories — `activity-repository`,
`calculation-repository`, `evidence-repository`, `identity-repository`,
`postgres-refresh-repository`, `scope-repository`) plus **10 Node test files**.

---

## 3. Files Deleted (29 total)

| Category | Count | Files |
| --- | --- | --- |
| Node entry point | 1 | `server.ts` |
| Node backend modules | 27 | all of `server/` |
| Stale lockfile | 1 | `bun.lock` |

### Why `bun.lock` was removed

`bun.lock` is an orphaned lockfile from 2026-09-24 that still declared
`express`, `jsonwebtoken`, `bcryptjs` and `multer` as project dependencies.
Verification before removal:

- `bun` is **not installed** in the verification environment
- **no tracked file** references `bun` or `bun.lock` (searched all 100+ files)
- the repository has **no CI or deployment configuration**
- the documented package manager throughout the repo is **npm**

Left in place it would have let a `bun install` **reinstall the removed Node
packages**, defeating the decommission. Because it cannot be regenerated here
(no bun available) and is now provably wrong, it was removed rather than left
stale. `package-lock.json` remains the authoritative lockfile.

---

## 4. Dependencies Removed (12 direct, 178 lockfile entries)

Removal was driven by **evidence, not by name**: the third-party import list was
extracted from the deleted files at `HEAD`, and only packages that appear
*only* there were removed.

| Package | Class | Removed from |
| --- | --- | --- |
| `express` | Node HTTP framework | dependencies |
| `jsonwebtoken` | Node JWT | dependencies |
| `bcryptjs` | Node password hashing | dependencies |
| `multer` | Node multipart uploads | dependencies |
| `pg` | Node PostgreSQL client | dependencies |
| `decimal.js` | Node decimal arithmetic | dependencies |
| `@google/genai` | Node-side LLM trend insights | dependencies |
| `@types/express` | type package | devDependencies |
| `@types/jsonwebtoken` | type package | devDependencies |
| `@types/bcryptjs` | type package | devDependencies |
| `@types/multer` | type package | devDependencies |
| `esbuild` | bundled `server.ts` to `dist/server.cjs` | devDependencies |

Import scan of the deleted files returned exactly: `@google/genai`, `bcryptjs`,
`decimal.js`, `express`, `jsonwebtoken`, `multer`, `pg`, `vite`, plus Node
built-ins (`crypto`, `fs`, `path`, `node:*`).

`vite` was **kept** — the deleted files imported it, but so does the frontend.

### Deliberately kept (not proven Node-only)

| Package | Reason |
| --- | --- |
| `dotenv` | Not imported by any tracked file. Pre-existing dead weight, but removal is not attributable to the Node decommission. |
| `motion` | No tracked file imports it. Same reasoning. |
| `react-is` | No tracked file imports it. Same reasoning. |
| `autoprefixer` | No tracked file imports it. Same reasoning. |
| `esbuild` (transitive) | Removed as a direct dep; Vite supplies its own copy, confirmed by a successful production build. |

> These four are a **known follow-up**, recorded in §15. They are unused, but
> they were unused *before* the decommission too, so cleaning them up would be
> unrelated work.

---

## 5. Scripts Removed / Changed

| Script | Before | After | Reason |
| --- | --- | --- | --- |
| `dev` | `tsx server.ts` | `vite` | Booted the Node API. Now starts the frontend. |
| `build` | `vite build && esbuild server.ts ...` | `vite build` | Dropped the Node server bundle. |
| `start` | `node dist/server.cjs` | **removed** | No Node production server exists. |
| `clean` | `rm -rf dist server.js` | `rm -rf dist` | Dropped the Node artifact. |
| `test` | `npm run test:security && npm run test:frontend` | `npm run test:frontend` | `test:security` was a Node suite. |
| `test:security` | 6 Node test files | **removed** | Booted `server.ts`; the files it referenced were deleted. |
| `test:persistence` | 4 Node test files | **removed** | Same. |
| `test:frontend` | unchanged | unchanged | Still valid. |
| `lint`, `preview` | unchanged | unchanged | Still valid. |

**Consequence to state plainly:** the **Node oracle test suites are gone**. The
`test:security` and `test:persistence` suites exercised the Node backend and
referenced the deleted files, so they could not survive. Authoritative coverage
is now the **304-case Java suite** plus the 36-case frontend suite. No Java or
frontend test was removed, weakened, or made tolerant.

---

## 6. Configuration Removed / Updated

`.env.example` was rewritten to the Java-only variable set, derived from the
placeholders actually present in `backend-java/src/main/resources/application*.properties`:

**Now documented:** `CARBONFLOW_JWT_SECRET`, `CARBONFLOW_REFRESH_TOKEN_SECRET`,
`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`,
`CARBONFLOW_CORS_ALLOWED_ORIGINS`, `CARBONFLOW_SEED_DEMO_DATA`,
`CARBONFLOW_EVIDENCE_VAULT_DIR`, `VITE_JAVA_API_BASE_URL`.

**Marked removed (no longer read by anything):** `GEMINI_API_KEY`, `JWT_SECRET`,
`REFRESH_TOKEN_SECRET`, `DATABASE_URL`, `STORAGE_DRIVER`, `SUPABASE_URL`,
`SUPABASE_SERVICE_ROLE_KEY`, `SUPABASE_STORAGE_BUCKET`, `APP_URL`.

Preserved: `VITE_JAVA_API_BASE_URL` as the production API path.
Not reintroduced: `localhost:3000`.

---

## 7. Frontend Java-Only Proof

Searches over all 27 files in `src/` (before and after deletion):

| Search | Result in `src/` |
| --- | --- |
| `localhost:3000` / `127.0.0.1:3000` | **0** |
| `server.ts` / `server.cjs` | **0** |
| imports from `../server` / `./server` | **0** |
| `express` / `Express` | **0** |
| `VITE_JAVA_API_BASE_URL` | the only API base (5 call sites in `src/services/api.ts`) |

Evidence was gathered **before** deletion: the pre-decommission gate confirmed
zero `src/` imports from `server/`, which is what made deletion safe.

---

## 8. Java Replacement Proof

Java exposes **23 controllers**. The deleted `server/routes.ts` registered **37
routes**. Every Node route category has a Java equivalent:

| Node capability | Java owner |
| --- | --- |
| auth: login / refresh / logout / switch / me / register | `AuthController` |
| organizations, facilities, legal entities, departments, periods, boundaries | `OrganizationController`, `FacilityController`, `LegalEntityController`, `DepartmentController`, `ReportingPeriodController`, `BoundaryController` |
| activity data, calculations, emissions | `ActivityDataController`, `CalculationController`, `EmissionLedgerController` |
| reference data | `ReferenceController` |
| audits, findings, corrections, review | `AuditController`, `ReviewController` |
| evidence vault | `EvidenceController` |
| inventory, targets, reduction projects | `InventoryController`, `TargetController`, `ReductionProjectController` |
| analytics, trend insights, reports, CSV | `AnalyticsController`, `ReportsController` |
| users, platform tenant lifecycle | `UserController`, `PlatformTenantController` |

Two endpoints Java does **not** expose, and neither did Node:
`GET /api/v1/calculations` (list) and `GET /api/v1/analytics/periods/{id}/summary`
requires a period id. No Node capability was lost.

Java is additionally a **superset**: it adds `/platform/tenants`, `/users`,
`/boundaries`, `/departments`, `/test-suite`, and a deterministic analytics
engine replacing the Node-side LLM call (`@google/genai`, removed).

---

## 9. Test Results (measured after deletion)

| Gate | Command | Result |
| --- | --- | --- |
| Java | `mvn clean verify` | **304 tests, 0 failures, 0 errors, 0 skipped** — `BUILD SUCCESS`, exit 0, 0 `[ERROR]` lines |
| TypeScript | `npx tsc --noEmit` | **0 errors**, exit 0 |
| Frontend | `npm run test:frontend` | **36/36 pass, 0 fail**, exit 0 |
| Frontend build | `npm run build` | **success**, 2,253 modules transformed, exit 0 |

The build check is load-bearing: it is what proves removing the direct
`esbuild` dependency was safe.

### Runtime + security regression (HTTP level, Node absent)

**73 checks, 73 PASS, 0 FAIL** against a disposable clean-room database
(`carbonflow_p105`) migrated V1→V8 from empty, with demo identities seeded.
Full transcript: `%TEMP%\opencode\phase105-final-output.txt`.

Coverage: health, login, invalid login, anonymous 401 + envelope, bogus bearer,
`?token=` query-string rejection, session/me, refresh rotation, refresh-reuse
family revocation, logout, post-logout refresh, login throttling, tenant
context, facilities, legal entities, reporting periods, GWP sets, emission
factors, activity data, calculation UUID validation, emissions, Scope 1 present,
Scope 2 dual perspectives, audits, evidence, evidence-download IDOR, inventory,
dashboard, analytics breakdown (3 ways), targets, reduction projects, CSV
export, CSV formula-injection guard, platform login, platform tenant list,
tenant detail by valid/legacy/malformed/all-zero UUID, platform-admin `/users`
tenant scoping, role-escalation refusal, cross-tenant switch refusal, malformed
org refusal, partial-body validation, manager `users.read` 200 vs
`users.create` 403, denied-create persistence check, assurance-provider login
and switch, auditor role-escalation refusal, cross-tenant facility IDOR, HTTP
415 (form-encoded and XML), 415 envelope leak check, 4 security headers, 4 CORS
cases, 405, 404, and Node-absence (port 3000 closed, 0 node processes).

### Harness corrections (transparency)

The first run reported 44/50. **All 6 failures were my harness's wrong
expectations, not product defects.** Each was confirmed against source and
re-run to PASS:

| Initial expectation | Reality | Evidence |
| --- | --- | --- |
| access token string must change on refresh | same-second JWT is byte-identical (`iat`/`exp` are second-precision); the **refresh** token does change | `refresh-rotation-refresh-changed` PASS |
| `GET /calculations` returns 200 | no such endpoint exists in Java **or** Node | `CalculationController` has only `run`, `batch-run`, `{id}` |
| `GET /analytics/period-summary` | path is `/analytics/periods/{periodId}/summary` | `AnalyticsController.java:45` |
| escalation attempt returns 403 | needed **both** `targetOrgId` and `targetRole`; partial body is 400 by Node-parity rule | error message: `targetOrgId and targetRole are required.` |
| manager `users.create` returns 403 | field is `fullName`, not `name`; invalid body is 400 | `UserRequests.java` |
| logout returns 200 | ran after a reuse test had already revoked the family | `refresh-reuse-revokes-family` PASS |

One further case showed the product **more** correct than expected: a malformed
`targetOrgId` returns `403 SWITCH_NOT_AUTHORIZED`, not 400/404. The assertion
was corrected, not the product.

---

## 10. Database / Flyway Verification

| Check | Result |
| --- | --- |
| `git status --porcelain -- db/` | **empty** |
| `git diff -- db/migration` | **empty** |
| Migration files | exactly `V1`…`V8`; **no `V9`** |
| SHA-256 V1–V8 | byte-identical to the Phase 10.4.1 fingerprints (below) |
| `flyway_schema_history` (clean-room DB) | 8 rows, `success = t` on all |
| Clean-room migration | V1→V8 applied from empty schema, no error |
| Production data deleted | **none** |

```
V1__carbonflow_initial_schema.sql              00433C777E5305E6...
V2__seed_reference_data.sql                    0A978D2814B35501...
V3__refresh_token_persistence_alignment.sql     E4820434BCA74F38...
V4__scope_constraints.sql                      3C85EB34BF539DBD...
V5__activity_evidence_tenant_integrity.sql     518DBF4EDADAB728...
V6__calculation_emission_integrity.sql         2EE5C7A72EE1F27D...
V7__audit_status_correction_rejection.sql      872AB0D158F97CED...
V8__organization_lifecycle_status.sql          9A55A252A110C653...
```

All eight match the values recorded in Phase 10.4.1 exactly.

---

## 11. Secret Hygiene

Literal scan over `git diff HEAD`, five rules:

| Rule | Matches |
| --- | --- |
| JWT literal | **0** |
| Database password literal | **0** |
| Bearer-token literal | **0** |
| Private-key block | **0** |
| AWS access-key id | **0** |

`.env` is git-ignored and untracked. The live secrets used during
verification were passed as environment variables only and appear in **0**
tracked files. No secret value appears in this document.

---

## 12. Repository Search After Deletion

Remaining `server.ts` / `localhost:3000` / `multer` / `bcryptjs` references,
classified:

| Location | Classification |
| --- | --- |
| `docs/PHASE10-NODE-OFF-TEST.md` | **HISTORICAL** — Phase 10.4 rehearsal evidence |
| `docs/PHASE10-FINDINGS-RESOLUTION.md` | **HISTORICAL** — Phase 10.4.1 findings evidence |
| `docs/PHASE10-CUTOVER-GAP-REPORT.md` | **HISTORICAL** — reconstructed gap register |
| `docs/PHASE10-FRONTEND-CUTOVER.md` | **HISTORICAL** — reconstructed frontend state |
| `docs/PHASE10-NODE-DECOMMISSION-PLAN.md` | **HISTORICAL** — this phase's plan |
| `docs/DECISIONS.md` | **HISTORICAL** — ADR context (`server/calc.ts` etc.) |
| `docs/TEST_PLAN.md`, `docs/TASKS.md` | **HISTORICAL** — per-phase task records |
| `SecurityConfig.java:124`, `application.properties:53` | **COMMENT** — javadoc explaining that `localhost:3000` is the *retired* origin and is refused |
| `SecurityConfig.java:96`, `ApiExceptionHandler.java:60` | **COMMENT** — parity rationale (BCrypt cost 10; error-wording difference) |
| `docs/EXECUTION.md:105` | **COMMENT** — states Node is not part of deployment |
| `io.jsonwebtoken` in `pom.xml` / `JwtTokenProvider.java` | **FALSE POSITIVE** — the *Java* JJWT library, unrelated to the npm package |

**Zero active production dependencies on Node remain.**

### Documentation corrected (10 files)

`README.md` (rewritten), `.env.example` (rewritten), `docs/EXECUTION.md`
(rewritten), `docs/README.md`, `docs/ARCHITECTURE.md`, `docs/CALCULATIONS.md`,
`docs/DATABASE.md`, `docs/PERSISTENCE-ARCHITECTURE.md`, `docs/SECURITY.md`,
`docs/UI-AUDIT.md`, `backend-java/README.md`.

Two of these fixed genuinely dangerous staleness:

- `docs/README.md` told operators to `npm run dev` and browse
  `http://localhost:3000`.
- `backend-java/README.md` still documented the **retired** `CORS_ORIGINS`
  variable with a `localhost:3000` default — the exact foot-gun that ADR-021
  was written to remove.

---

## 13. Browser UAT Status

**NOT AVAILABLE.** No desktop browser was connected in the verification
environment. No visual, rendering, focus or interaction claim is made anywhere
in this phase. All verification is HTTP-, source- and database-level.

---

## 14. External Production Deployment Status

**NOT VERIFIED.** No external deployment occurred. This work is local/runtime
verification only. It is not evidence that CarbonFlow is deployed anywhere.

---

## 15. Known Limitations

1. **No browser UAT** (above).
2. **No external deployment verification** (above).
3. **Node oracle coverage removed.** `test:security` (44 backend/security tests
   historically) and `test:persistence` no longer exist. Their assertions live
   on in the 304-case Java suite, but the Node implementation is no longer
   differentially testable. Accepted consequence of decommission.
4. **Four unused npm packages remain** — `dotenv`, `motion`, `react-is`,
   `autoprefixer`. Unused before the decommission as well; left alone to avoid
   unrelated churn. Candidate for a future dependency-hygiene task.
5. **No performance claim.** No performance tooling was run.
6. **Backup/restore not verified.**
7. **V2 seeds GWP-set UUIDs with a variant nibble the contract rejects**, so
   seeded GWP ids are unaddressable via `gwpSetId`. Pre-existing, reported in
   Phase 10.4.1 §10, **not fixed** (V2 is frozen).
8. **`X-Frame-Options` is `DENY` from Spring's default**, superseding the
   filter's `SAMEORIGIN`. Stricter value; documented in Phase 10.4.1 §10.
9. **Body validation runs before `@PreAuthorize`**, so an unauthorized caller
   with an invalid body gets `400` rather than `403`. Authorization is still
   enforced — the corrected harness confirms `403` for a validly shaped body.

---

## 16. Final Architecture

```text
React 19 + TypeScript + Vite
            |
            | REST (Authorization: Bearer)
            v
Java 21 + Spring Boot 3.3
            |
            | JDBC (JdbcTemplate, no ORM)
            v
       PostgreSQL
            |
            v
     Flyway (V1-V8, applied at startup)
```

**Node/Express: DECOMMISSIONED.** It survives only in Git history at `4cc8f30`
and in the labelled Phase 10 evidence documents.

---

## 17. Decommission Conclusion

CarbonFlow's production runtime no longer depends on Node/Express.

- 28 Node files and one stale lockfile removed; the Node production dependency
  is **gone** from the repository.
- 12 direct npm packages removed **on import evidence**, with the lockfile
  diff containing 178 removals and **zero additions** — no dependency churn and
  no upgrades.
- Every test gate is green after deletion: 304 Java, 0 TypeScript errors,
  36/36 frontend, plus a successful production build.
- 73/73 HTTP-level runtime and security checks pass with Node provably absent.
- Flyway V1–V8 are byte-identical; no migration was created, edited or deleted.
- No secret appears in the diff; no production data was deleted.
- Documentation now presents Java as the sole backend and Node as historical.
- Rollback is a single documented command: `git checkout 4cc8f30`.

**The one thing this does not prove is that it works in a browser or in a real
deployment**, because neither was available to test.

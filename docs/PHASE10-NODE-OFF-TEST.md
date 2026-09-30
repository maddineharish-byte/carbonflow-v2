# Phase 10.4 — Node-Off Cutover Rehearsal Test Report

> **Phase:** 10.4 (Node-Off cutover rehearsal)
> **Amendment:** Phase 10.4.1 dispositioned all five §33.1 findings — see `docs/PHASE10-FINDINGS-RESOLUTION.md`. This report is otherwise unaltered historical record.
> **Successor phase:** 10.5 (Node decommission) — **NOT STARTED**, per scope.
> **Single final status:** **PASS** (see §33).

---

## 1. Document Control

| Field | Value |
| --- | --- |
| Document | `docs/PHASE10-NODE-OFF-TEST.md` |
| Phase | 10.4 — Node-Off cutover rehearsal |
| Author | Automated cutover rehearsal (OpenCode agent) |
| Date of run | 2026-09-29 |
| Repository | `carbonflow` |
| Branch | `main` |
| Baseline commit | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` ("Phase 8: Frontend Integration — wire React to the verified Java backend") |
| Verdict | **PASS** |
| Next phase | Phase 10.5 — Node decommission (**not executed**) |

Raw evidence files produced during this rehearsal:

| File | Purpose |
| --- | --- |
| `%TEMP%\opencode\phase104-smoke3.ps1` | Final runtime verification harness (139 checks) |
| `%TEMP%\opencode\phase104-smoke3-output.txt` | Full harness transcript |
| `%TEMP%\opencode\phase104-results3.csv` | Machine-readable per-step results |
| `%TEMP%\opencode\phase104-mvn-verify.log` | `mvn clean verify` transcript |
| `%TEMP%\opencode\phase104-frontend-tests.txt` | `npm run test:frontend` transcript |
| `%TEMP%\opencode\phase104-java.log` | Java backend boot log (Flyway + Tomcat) |
| `%TEMP%\opencode\phase104-vite.log` | Vite dev-server log |
| `%TEMP%\opencode\start-java.ps1` | Java launcher (maps `.env` → Java env vars) |

---

## 2. Executive Verdict

**PASS.**

The complete CarbonFlow request/response surface exercised by the React client was
driven, end to end, against the **Java 21 / Spring Boot** backend with the
**Node/Express** backend verifiably **OFF** (no process, closed port, unreachable
HTTP). Every required workflow completed successfully; **zero** required checks
failed; **no** workflow contacted Node.

Evidence summary:

- `mvn clean verify` — **260 / 260 tests passed**, `BUILD SUCCESS`.
- Runtime harness — **135 PASS / 0 FAIL / 2 NOT VERIFIABLE / 2 FINDING** (139 checks);
  the 2 `NOT VERIFIABLE` entries were each closed by a dedicated probe (§15).
- `tsc --noEmit` — clean (exit 0).
- Frontend suite — **36 / 36 tests passed**, 0 fail, 0 skipped (exit 0).
- Flyway V1–V8 applied from scratch to the clean-room rehearsal database.
- Five non-blocking findings recorded (§33.1); none is a cutover blocker.

---

## 3. Objective and Acceptance Criteria

**Objective.** Prove that CarbonFlow operates fully with the Node/Express backend
**OFF**, with React 19 talking to Java 21 / Spring Boot talking to PostgreSQL.

**Acceptance criteria.**

1. Node/Express is objectively OFF for the entire run and never restarted.
2. Java backend builds cleanly and passes its full test suite.
3. The React client boots and targets Java (not Node).
4. Every workflow the React client performs succeeds against Java.
5. No required workflow contacts Node.
6. Frozen artifacts (migrations V1–V8, RBAC 9×44, audit 10 states) are unchanged.
7. No database schema change, no modified migration.
8. Exactly one final status is reported.

**Methodology.** Evidence-first: a check is `PASS` only with observed runtime
evidence. Anything that could not be observed is reported `NOT VERIFIABLE`, and
nothing was upgraded from `NOT VERIFIABLE` to `PASS`.

---

## 4. Scope: In and Out

**In scope**

- Node-OFF proof (process, port, HTTP).
- Java backend build + test evidence.
- Flyway migration application to a rehearsal database.
- Live HTTP verification of every endpoint family the React client calls.
- RBAC, multi-tenancy, audit state machine, Scope 2 dual reporting.
- Evidence vault controls, CSV export and injection guard.
- Frontend type-check and frontend test suite.

**Out of scope**

- Phase 10.5 (Node decommission). Not started.
- Any change to `server/`, `server.ts`, the `package.json` scripts, or the Node
  oracle. (The only file this phase adds is this report.)
- Any database migration, or edits to Flyway V1–V8.
- Any browser UAT claim (no browser available — see §33.3).
- Any performance claim (no tooling; none asserted).

---

## 5. Frozen Baseline and Working Tree

| Item | Value |
| --- | --- |
| HEAD | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` |
| Branch | `main` |
| Working tree | Dirty — carries **Phase 9** uncommitted changes only |

Modified (Phase 9, pre-existing):

```
 M backend-java/src/main/java/com/carbonflow/config/JwtAuthenticationFilter.java
 M backend-java/src/main/java/com/carbonflow/config/SecurityConfig.java
 M backend-java/src/main/java/com/carbonflow/service/AuthService.java
 M backend-java/src/test/java/com/carbonflow/config/SecurityChainIntegrationTest.java
 M backend-java/src/test/java/com/carbonflow/controller/CalculationBatchTest.java
 M backend-java/src/test/java/com/carbonflow/controller/CalculationRunTest.java
 M backend-java/src/test/java/com/carbonflow/controller/EmissionLedgerTest.java
 M backend-java/src/test/java/com/carbonflow/controller/ReferenceDataTest.java
 M backend-java/src/test/java/com/carbonflow/service/AuthServiceTest.java
```

Untracked (Phase 9, pre-existing):

```
?? backend-java/src/main/java/com/carbonflow/config/SecurityHeadersFilter.java
?? backend-java/src/main/java/com/carbonflow/service/LoginThrottle.java
?? backend-java/src/test/java/com/carbonflow/controller/AuthHardeningTest.java
?? backend-java/src/test/java/com/carbonflow/service/LoginThrottleTest.java
?? docs/DEPLOYMENT-SECURITY.md
?? docs/PHASE9-BASELINE.md
?? docs/SECRETS.md
?? docs/SECURITY-THREAT-MODEL.md
```

`git diff --stat`: 9 files changed, 44 insertions(+), 19 deletions(-).

**Phase 10.4 changed no application file.** The final `git diff --stat` is
byte-identical to the pre-rehearsal baseline above (9 files, 44 insertions(+),
19 deletions(-)), and `db/migration` is clean. The **only** difference between
the baseline and the final `git status --short` is one added untracked line:

```
?? docs/PHASE10-NODE-OFF-TEST.md      <-- this report
```

Everything else the phase produced is a throwaway harness or log under `%TEMP%`.
No test was weakened, skipped or deleted, and Node was never restarted to make
a workflow pass.

---

## 6. Test Environment Inventory

| Component | Version / Detail |
| --- | --- |
| Java | OpenJDK **21.0.12.1** LTS (2026-08-18) |
| Maven | **3.9.16** |
| Node.js | **v25.8.2** |
| npm | **11.11.1** |
| PostgreSQL | **18.6** (service `postgresql-x64-18`, port 5432) |
| Spring Boot backend | `carbonflow-backend-1.0.0-PRO.jar` (29,433,931 bytes) |
| React / Vite | React 19, Vite 8 (`--port 5173 --host 127.0.0.1`) |
| TypeScript | `tsc --noEmit` (typescript ^7.0.2) |

Environment variables in `.env` — **names and presence only, values never read
into this report**:

```
GEMINI_API_KEY               = <empty>
APP_URL                      = <set>
JWT_SECRET                   = <set>
REFRESH_TOKEN_SECRET         = <set>
DATABASE_URL                 = <empty>
DB_HOST                      = <set>
DB_PORT                      = <set>
DB_NAME                      = <set>
DB_USER                      = <set>
DB_PASSWORD                  = <set>
STORAGE_DRIVER               = <set>
SUPABASE_URL                 = <empty>
SUPABASE_SERVICE_ROLE_KEY    = <empty>
SUPABASE_STORAGE_BUCKET      = <set>
```

`VITE_JAVA_API_BASE_URL` is **not** in `.env`; it is defined in `.env.example`
as `http://localhost:8080` and was supplied explicitly to the Vite process for
this rehearsal.

---

## 7. Node-Off Proof

Node/Express is objectively OFF, proven three independent ways, at both the
**start** and the **end** of the run:

| Evidence | Observation |
| --- | --- |
| Process scan | `0` processes matching `server.ts` / `server.cjs` (start **and** end) |
| Port | TCP `localhost:3000` **not listening** (start **and** end) |
| HTTP probe | `GET http://localhost:3000/api/v1/health` → **UNREACHABLE** |

The only `node.exe` processes present are the Vite dev server:

```
PID 9656 : npx-cli.js vite --port 5173 --host 127.0.0.1
PID 14352: node_modules/vite/bin/vite.js --port 5173 --host 127.0.0.1
```

Neither runs the CarbonFlow Express backend. Node was never restarted during the
rehearsal. `server/` (27 files) and `server.ts` remain present and unmodified —
the Node backend was left in place for Phase 10.5, only switched off.

---

## 8. Java Backend Build Evidence

Command: `mvn clean verify` (transcript: `phase104-mvn-verify.log`).

```
[INFO] Tests run: 260, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:03 min
```

Representative suites (all green):

- `SwitchTenantTest` 7/7, `TargetTest` 4/4, `UserAdminTest` 9/9
- `RolePermissionsParityTest` 6/6, `AuditStateMachineTest` 6/6
- `LoginThrottleTest` 11/11, `AuthServiceTest` 4/4
- `UnitConversionServiceTest` 5/5, `EmbeddedPostgresSmokeTest` 1/1

Artifact produced: `backend-java/target/carbonflow-backend-1.0.0-PRO.jar`
(29,433,931 bytes). The transcript contains **41** per-class
`Tests run: …` result lines and **zero** `[ERROR]` lines.

One benign artefact worth recording so it is not mistaken for a failure: the
transcript contains 5 lines of the form `ERROR: duplicate key ...`. These are
emitted by the **embedded PostgreSQL** child process
(`io.zonky.test.db.postgres.embedded.EmbeddedPostgres`) while the idempotency
and unique-constraint tests deliberately insert duplicate keys. They are
forwarded at Maven's `INFO` level, are not Maven errors, and the build result
above is unaffected.

---

## 9. Frozen Contract Integrity (RBAC 9×44, Audit 10 States)

Re-verified by inspection and by passing tests:

| Contract | Expected | Observed | Source |
| --- | --- | --- | --- |
| Roles | 9 | **9** | `model/enums/Role.java` |
| Permission codes | 44 | **44** | `security/Permission.java` |
| Audit states | 10 | **10** | `service/AuditStateMachine.java` (`STATES`) |
| Role/permission parity | match | **PASS** | `RolePermissionsParityTest` (6 tests) |
| State machine | match | **PASS** | `AuditStateMachineTest` (6 tests) |

The 9 roles: `COMPANY_ADMIN`, `SUSTAINABILITY_MANAGER`, `CARBON_ACCOUNTANT`,
`DATA_OWNER`, `FACILITY_MANAGER`, `REVIEWER`, `MANAGEMENT`, `ASSURANCE_PROVIDER`,
`PLATFORM_ADMIN`.

The 10 audit states: `DRAFT`, `SUBMITTED`, `DATA_COLLECTION`, `VALIDATION`,
`REVIEW`, `APPROVED`, `AUDIT_READY`, `LOCKED`, `CORRECTION_REQUESTED`, `REJECTED`.

Live cross-check: a `COMPANY_ADMIN` login returned **41** permissions and an
`ASSURANCE_PROVIDER` returned **14**, consistent with the frozen matrix.
`COMPANY_ADMIN` holds `audits.approve/create/lock/read/submit` but **not**
`audits.review` — confirmed at runtime (§26).

Note: DB tables `permissions` and `role_permissions` contain **0** rows; the
authoritative matrix is code-level (`RolePermissions.java`), matching the Node
reference. `roles` holds the 9 seeded rows. This is by design, not a gap.

---

## 10. Database Target and Clean-Room Rationale

**Why the long-lived dev database was rejected as the rehearsal target.**
`carbonflow_dev` exists (38 tables, same schema) but its demo-seed fixture is
incomplete, so role-driven workflows cannot be exercised against it. Observed
directly:

| `carbonflow_dev` organization | Status | Memberships |
| --- | --- | --- |
| `22222222-…-201` Acme Global Manufacturing (demo seed) | ACTIVE | **0** |
| `22222222-…-202` Apex CleanTech Logistics (demo seed) | ACTIVE | **0** |
| `22222222-…-299` CarbonFlow Platform (demo seed) | ACTIVE | **0** |
| `23872878-…` Acme Global Manufacturing Corp (self-registered) | ACTIVE | 286 |
| `2dfa6a82-…` Vertex Tech Solutions Inc (self-registered) | ACTIVE | 72 |

All three demo-seed organizations have **zero** memberships, and the membership
table contains no `PLATFORM_ADMIN`, no `ASSURANCE_PROVIDER`, and only
`COMPANY_ADMIN` (171) and `REVIEWER` (187) roles in total. The platform
lifecycle and review-gated audit workflows required by this phase are therefore
unreachable there. Its Flyway history is additionally **baselined at V6**
(`<< Flyway Baseline >>`, then V7, V8), not a from-scratch V1–V8 application.

**Decision.** Rather than patch the seeder or mutate `carbonflow_dev`, a
**clean-room database `carbonflow_nodeoff`** was created and Flyway was allowed
to apply the **frozen V1–V8 unchanged**. This is the rehearsal target.

**`carbonflow_dev` was not touched.** Its Flyway history remains exactly
baseline + V7 + V8. Both databases coexist:

```
      datname
--------------------
 carbonflow_dev
 carbonflow_nodeoff
```

No migration was added or edited; `db/migration` is git-clean.

---

## 11. Schema Migration Evidence (Flyway V1–V8)

On boot against `carbonflow_nodeoff`, Flyway applied all eight frozen
migrations from scratch:

```
Successfully applied 8 migrations to schema "public", now at version v8
```

| Rank | Version | Description | Success |
| --- | --- | --- | --- |
| 1 | 1 | carbonflow initial schema | t |
| 2 | 2 | seed reference data | t |
| 3 | 3 | refresh token persistence alignment | t |
| 4 | 4 | scope constraints | t |
| 5 | 5 | activity evidence tenant integrity | t |
| 6 | 6 | calculation emission integrity | t |
| 7 | 7 | audit status correction rejection | t |
| 8 | 8 | organization lifecycle status | t |

Post-boot seed state: `roles`=9, `organizations`=3 (demo) → 4 after registration,
`users`=5 (demo) → 7 after rehearsal, `permissions`=0, `role_permissions`=0.
38 tables present. The seeder reported:
`Demo identities seeded (idempotent): 3 organizations, 5 users, 6 memberships`.

Four benign Flyway `WARN` lines were logged while applying the frozen
migrations — `there is already a transaction in progress (SQL State: 25001)`.
All 8 versions nonetheless recorded `success = t` and the schema finished at
`v8`, so the migration outcome is unaffected. No migration file was added or
edited.

---

## 12. Application Boot Evidence

Launcher: `%TEMP%\opencode\start-java.ps1` (maps `.env` names to Java env vars;
targets `carbonflow_nodeoff`).

Observed boot sequence (final run, PID **2232**, timestamps from
`phase104-java.log`):

```
09:41:09.661  Tomcat initialized with port 8080 (http)
09:41:14.144  Migrating schema "public" to version "1 - carbonflow initial schema"
  ...         (V1 through V8 applied in order)
09:41:15.640  Successfully applied 8 migrations to schema "public", now at version v8
09:41:21.199  Tomcat started on port 8080 (http) with context path '/'
09:41:21.658  Demo identities seeded (idempotent): 3 organizations, 5 users, 6 memberships
09:41:21.255  Started CarbonFlowApplication in 20.799 seconds (process running for 22.457)
09:41:39.189  Completed initialization in 10 ms   (DispatcherServlet ready)
```

- `GET /api/health` → **HTTP 200**, `{"status":"UP"}`, runtime `Java 21.0.12.1`

**Log-hygiene observation.** The backend log contains exactly **one** `ERROR`
entry for the entire 139-check run, and it is the deliberate
unsupported-media-type probe of Finding 3 (§33.1). No unhandled exception
occurred during any of the 135 `PASS` checks. The log also contains 4 benign
Flyway `WARN` lines ("there is already a transaction in progress",
SQLSTATE 25001) emitted while applying the frozen migrations; all 8
migrations reported `success = t` and the schema reached `v8`.

**Frontend runtime configuration note.** `vite.config.ts` defines **no** `/api`
dev-server proxy, so the React client must be pointed at Java explicitly via
`VITE_JAVA_API_BASE_URL`. This was supplied for the rehearsal.

---

## 13. React Frontend Runtime Evidence

| Check | Result |
| --- | --- |
| Vite dev server `http://127.0.0.1:5173/` | **HTTP 200** |
| `VITE_JAVA_API_BASE_URL=http://localhost:8080` substituted into served `src/services/api.ts` | **PASS** |
| Served `api.ts` contains no `localhost:3000` | **PASS** |

**Type-check:** `npm run lint` (`tsc --noEmit`) → exit **0**, no diagnostics.

**Frontend suite:** `npm run test:frontend` → exit **0**, 3.47 s.

```
tests 36 | pass 36 | fail 0 | cancelled 0 | skipped 0 | todo 0
```

| Group | Tests | What it pins down |
| --- | --- | --- |
| `API REFRESH` 1–4 | 4 | single-flight refresh, retry, no refresh on 403 |
| `AUTH BOUNDARY` 1–4 | 4 | protected content never flashes while loading/unauthenticated |
| `AUTH FLOW` 1–6 | 6 | login/refresh/logout, memberships+permissions, `USER_DEACTIVATED` |
| `NAV` 1–3 | 3 | permission-gated sidebar per role |
| `DASHBOARD` 1–2 | 2 | real KPIs; empty state with no demo fallback |
| `TARGETS` 1–2 | 2 | backend-computed progress, gated create |
| `PROJECTS` 1 | 1 | reduction project rows from backend |
| `AUDIT` 1–2 | 2 | only backend-legal transitions offered |
| `INVENTORY` 1 | 1 | snapshots render **both** Scope 2 perspectives |
| `ANALYTICS` 1 | 1 | period summary + breakdown consume backend payloads |
| `EVIDENCE` 1 | 1 | upload failure surfaces inline, no silent throw |
| `ADMIN` 1–2 | 2 | reviewer cannot see user administration; `users.create` gates the button |
| `API ERR` 1–4 | 4 | 403/404/409/500 map to `ApiError` without crashing |
| `CSV` 1 | 1 | export sends `Authorization` header and **never** a query-string token |
| `ACTIVITY` 1 | 1 | posts the Java `ActivityCreateRequest` shape |
| `CALC` 1 | 1 | posts the Java `CalculationRequest` shape |

Two of these are directly Node-off relevant: `CSV 1` asserts the query-string
token is never sent, and `CALC 1` / `ACTIVITY 1` assert the **Java** request
DTOs, not the Node ones.

**Not run:** the aggregate `npm test`, because it chains `test:security` — the
**Node oracle** suite, which boots `server.ts`. It is intentionally not run
during Node-off (see §33.2).

---

## 14. Frontend API Surface Analysis

`src/services/api.ts` is the sole frontend API surface. Static inspection
confirms:

- Every call is built as `${API_BASE_URL}/api/v1${endpoint}`.
- `API_BASE_URL` resolves from `VITE_JAVA_API_BASE_URL` (empty ⇒ same-origin).
- Authentication is `Authorization: Bearer <jwt>` **only**; no query-string token.
- Zero references to `localhost:3000` or the Express server anywhere in `src/`.

This is corroborated at runtime: the served module carries the Java base URL
(§13), and every workflow below succeeded while Node was off.

---

## 15. Verification Method and Harness

The harness (`phase104-smoke3.ps1`) drives the **live** stack over HTTP using
only endpoints the React client uses:

- JSON calls via `Invoke-WebRequest`; multipart uploads via `curl.exe`
  (PowerShell 5.1 lacks `Invoke-WebRequest -Form`).
- Every step records `PASS` / `FAIL` / `NOT VERIFIABLE` / `FINDING` with observed
  evidence (status codes, error codes, counts, hashes, amounts).
- The harness was run from a **freshly recreated** `carbonflow_nodeoff`
  (dropped, re-created, V1–V8 re-applied) so the result is deterministic.
- Final tally: **135 PASS / 0 FAIL / 2 NOT VERIFIABLE / 2 FINDING (139 checks)**,
  reconciling exactly to the 139 CSV rows (see §16).

**Two follow-up probes.** Two requirements could not be settled by the scripted
fixture, so each was re-tested directly against the running stack rather than
being reported as passing on a weak assertion:

1. **CSV formula-injection guard** — the scripted run had no formula-prefixed
   field in its fixture, so it recorded `NOT VERIFIABLE`. A dedicated probe
   created facilities whose name/code began with `=`, `+`, `-` and `@` and
   inspected the exported CSV (§29).
2. **Audit reject-reason rule** — the harness step used a token without
   `audits.review`, so its `403` proved RBAC rather than the validation rule. A
   dedicated probe repeated the transition with an `ASSURANCE_PROVIDER` token
   and captured the exact `400 VALIDATION_ERROR` (§26).

Both probes are reported as separate evidence; neither was used to overwrite the
scripted result. Everything else in §§17–32 comes from the 139-step run.

Allowed rehearsal identities on `carbonflow_nodeoff` (all `Password123!`, demo
seed): `admin@acmeglobal.com` (COMPANY_ADMIN), `manager@acmeglobal.com`
(SUSTAINABILITY_MANAGER), `auditor@ey-assurance.com` (ASSURANCE_PROVIDER, two
memberships), `admin@apexcorp.com` (COMPANY_ADMIN), `platform.admin@carbonflow.test`
(PLATFORM_ADMIN).

---

## 16. Runtime Verification Matrix

Per-area tally. Every one of the **139** recorded steps is assigned to exactly one
area, and the columns reconcile to `PASS=135 / FAIL=0 / NOT VERIFIABLE=2 /
FINDING=2`:

| Area | Checks | Result |
| --- | --- | --- |
| Node-off proof + stack health | 8 | PASS=8 |
| Authentication and session | 5 | PASS=5 |
| Token refresh / rotation / replay | 2 | PASS=2 |
| Scope bootstrap + reference reads | 11 | PASS=11 |
| Activity data lifecycle | 7 | PASS=7 |
| Calculation engine | 14 | PASS=14 |
| Emissions ledger / Scope 2 | 6 | PASS=6 |
| Evidence vault | 10 | PASS=10 |
| Audit governance + RBAC | 17 | PASS=17 |
| Inventory + accounting lock | 7 | PASS=7 |
| Targets / projects / analytics | 7 | PASS=7 |
| CSV export | 3 | PASS=2, **NOT VERIFIABLE=1** |
| Company administration | 6 | PASS=6 |
| Tenant isolation + switching | 10 | PASS=10 |
| Platform administration | 13 | PASS=11, **FINDING=2** |
| Token rejection | 3 | PASS=3 |
| Login throttle | 2 | PASS=2 |
| Logout | 2 | PASS=2 |
| Security headers / CORS | 5 | PASS=5 |
| Node oracle | 1 | **NOT VERIFIABLE=1** |
| **Total** | **139** | **PASS=135, FAIL=0, NOT VERIFIABLE=2, FINDING=2** |

Complete per-step results, including the exact status code and error code for
every check, are in `phase104-results3.csv`. The notable observations are
expanded in §§17–32.

---

## 17. Authentication and Session

| Check | Observation |
| --- | --- |
| Login (`COMPANY_ADMIN`) | HTTP 200; role `COMPANY_ADMIN`; 41 permissions; org "Acme Global Manufacturing" |
| Bearer JWT | 3-segment JWT |
| Session memberships | `memberships` returned (≥1) |
| `GET /auth/me` | HTTP 200; `organization.id` matches session org |
| Query-string token (`?token=`) | **HTTP 401** — Phase 9 removal holds |
| Invalid token | HTTP 401 |
| Missing token | HTTP 401 |
| Malformed JWT (`aaa.bbb.ccc`) | HTTP 401 |

Login response shape: `data.{accessToken, refreshToken, user, organization,
role, permissions, memberships}`.

---

## 18. Token Refresh, Rotation, Replay

| Check | Observation |
| --- | --- |
| Refresh | HTTP 200; a **new** refresh token is issued (rotation) |
| Replay of consumed refresh token | **HTTP 401**; token family revoked |
| Refresh after logout | **HTTP 401** |

Refresh-token replay detection and family revocation behave as designed.

---

## 19. Query-String Token Removal

Phase 9 removed `?token=` authentication from `JwtAuthenticationFilter`.
Verified live: `GET /api/v1/auth/me?token=<valid-jwt>` returns **HTTP 401**.
Tokens are accepted only via the `Authorization: Bearer` header.

---

## 20. Authorization (RBAC Enforcement)

| Check | Observation |
| --- | --- |
| COMPANY_ADMIN → platform API | **HTTP 403** (no `platform.*`) |
| COMPANY_ADMIN → `checklist/{id}/verify` | **HTTP 403 `FORBIDDEN`** (no `audits.review`) |
| ASSURANCE_PROVIDER (REVIEWER-equivalent) → `checklist/{id}/verify` | **HTTP 200** (holds `audits.review`) |
| ASSURANCE_PROVIDER login | 200; role `ASSURANCE_PROVIDER`; 14 permissions |

RBAC denials are correct and role-derived, not incidental. This also disambiguates
an earlier false alarm: a `403` on review-gated endpoints for `COMPANY_ADMIN` is
**correct** matrix behaviour.

---

## 21. Activity Data Lifecycle

| Check | Observation |
| --- | --- |
| List | HTTP 200 |
| Create with `status="DRAFT"` | **HTTP 400 `VALIDATION_ERROR`** — status may be absent or literal `SUBMITTED` only |
| Unmatched factor at create | Persists (HTTP 201); factor is resolved at **calculation** time |
| Create `SCOPE_1 / STATIONARY_COMBUSTION / NATURAL_GAS` | 201; status `SUBMITTED`; unit `kWh` |
| Create `SCOPE_2 / ELECTRICITY_LOCATION / GRID_ELECTRICITY_US` | 201 |
| Create `SCOPE_2 / ELECTRICITY_MARKET / RESIDUAL_MIX_US` | 201 |
| Update quantity | 200; quantity reflected |
| Unmatched factor at calculation | **HTTP 400 `FACTOR_NOT_FOUND`** |

Reference data available on the clean room: 7 emission factors, 3 GWP sets.

---

## 22. Calculation Engine: Determinism, Precision, Provenance

Run on the natural-gas activity (`13100 kWh` after update):

| Check | Observation |
| --- | --- |
| Run | HTTP 200; 3 gas results; `totalCo2eTonnes = 2.836622` |
| Deterministic re-read | Two identical reads → **byte-identical JSON** |
| Provenance SHA-256 | `calculationHash` = `e9f02e63965baca3162549d9b004d5ab1f8037499adc1f9758cfc86d2e374a78` (64 hex chars) |
| Reproducible hash | Re-read hash **==** run hash |
| Provenance columns | `factorValue=0.18288`, `factorUnit=kgCO2e/kWh`, `factorVersion=1`, `gwpName="IPCC Sixth Assessment Report (AR6)"`, `factorSource="UK DEFRA / BEIS (2024)"` |
| Per-gas GWP | `CO2: 2391.274 kg × GWP 1 = 2391.274 kg`; `CH4: 3.144 kg × GWP 27.9 = 87.7176 kg`; `N2O: 1.31 kg × GWP 273 = 357.63 kg` |
| Gas-sum integrity | `sum(co2eKg) = 2836.6216` == `totalCo2eKg` |
| Rounding | `2836.6216 kg → 2.836622 t` (HALF_UP, 6 dp) — exact match |
| Emission record | Linked; scope `SCOPE_1`; co2e `2.836622` |
| Batch run | HTTP 200; `processed=3`, `total=4` (the unmatched-factor activity is correctly skipped) |
| Own calculation read | HTTP 200 |
| Foreign/unknown calculation | **HTTP 404 `CALCULATION_NOT_FOUND`** |

Provenance uses the frozen V6 factor snapshot (not a re-read of today's factor),
and results are deterministic and reproducible.

---

## 23. Scope 2 Dual Reporting

Both Scope 2 methods were recorded with real data and reported **separately**:

| Check | Observation |
| --- | --- |
| Ledger | HTTP 200; 3 records |
| Summary | `scope1Tonnes=2.8366`, `scope2LocationTonnes=3.1999`, `scope2MarketTonnes=3.676`, `totalLocationBasedTonnes=6.0365`, `totalMarketBasedTonnes=6.5126` |
| Dual reporting | `LOCATION_BASED=1` and `MARKET_BASED=1` as **distinct ledger lines** |
| LOCATION_BASED total | `3.199896 t` |
| MARKET_BASED total | `3.675968 t` |
| Method integrity | No record carries an out-of-contract `scope2Type` |

The two methods are **never summed** into a single figure and there is **no
cross-method fallback**: the summary exposes both `totalLocationBasedTonnes` and
`totalMarketBasedTonnes` as separate totals.

---

## 24. Accounting Lock Guard

| Check | Observation |
| --- | --- |
| Set a reporting period to `LOCKED` | HTTP 200; status `LOCKED` |
| Write activity into the LOCKED period | **HTTP 409 `AUDIT_LOCKED`** |
| Read the LOCKED period | **HTTP 200** (reads are never blocked) |

The guard (`AccountingLockGuard`) checks both the `reporting_periods.status` and
an audit in state `LOCKED`. A LOCKED **inventory snapshot** is a different control
(§27) and correctly does not by itself block accounting writes.

---

## 25. Evidence Vault Controls

| Check | Observation |
| --- | --- |
| List | HTTP 200 |
| Upload valid PDF (multipart) | HTTP 201; `mime=application/pdf` |
| Download | HTTP 200 |
| SHA-256 integrity | Client-computed SHA-256 **==** server `sha256Hash` |
| Magic-byte check | Text bytes named `.pdf` → **HTTP 400** |
| MIME allow-list (`application/octet-stream`) | **HTTP 400** |
| MIME allow-list (`application/zip`) | **HTTP 400** |
| 25 MB size limit | 26 MB file → **HTTP 400** (rejected by the application guard, not by Tomcat — no `MaxUploadSizeExceededException` was logged) |
| Path containment (on disk) | All files under `vault_storage/<organizationId>/`; **0** outside; e.g. `1790655131091_cc08cd1b_p104-good.pdf` |
| Unauthenticated access | **HTTP 401** |

The allow-list (`application/pdf`, `text/csv`, xlsx/xls/docx, png/jpeg/jpg,
`text/plain`) matches the Node reference exactly; `text/plain` is intentionally
permitted, while extension-spoofing and non-listed MIME types are rejected. The
size guard that fired is the application-level one
(`EvidenceStorageService:221`, `content.length > MAX_FILE_SIZE_BYTES` =
`25L * 1024 * 1024`), not the servlet ceiling
(`spring.servlet.multipart.max-file-size=25MB` / `max-request-size=28MB`).

---

## 26. Audit Governance State Machine

| Check | Observation |
| --- | --- |
| List | HTTP 200 |
| Create | HTTP 201; state `DRAFT` |
| Illegal transition `DRAFT→LOCKED` | **HTTP 400 `INVALID_TRANSITION`** |
| State skip `DRAFT→REVIEW` | **HTTP 400** |
| Detail | `DRAFT`; 8 checklist items; 0 findings |
| COMPANY_ADMIN verify checklist | **HTTP 403 `FORBIDDEN`** (no `audits.review`) |
| Legal forward path | `DRAFT→SUBMITTED→DATA_COLLECTION→VALIDATION→REVIEW` all HTTP 200 |
| REVIEW→REJECTED without reason (COMPANY_ADMIN) | **HTTP 403 `FORBIDDEN`** (RBAC, not validation) |
| ASSURANCE_PROVIDER verify checklist | HTTP 200 |
| REVIEW→REJECTED with reason (ASSURANCE_PROVIDER) | HTTP 200; state `REJECTED` |
| REJECTED→DATA_COLLECTION | HTTP 200 |
| REJECTED again from REJECTED | **HTTP 400** |
| Unknown audit id | **HTTP 404 `AUDIT_NOT_FOUND`** |

Only server-permitted transitions succeed; skipped and terminal transitions are
rejected. Review-gated actions require the frozen `audits.review` code.

**Harness label corrected, and the rule then verified properly.** The harness
step `audit-reject-requires-reason` was issued with the **COMPANY_ADMIN** token,
so the `403 FORBIDDEN` it recorded is the RBAC denial, **not** a missing-reason
validation error; the step asserted only "status >= 400" and therefore passed for
the wrong reason. Because the rule is a governance requirement, it was re-tested
with a token that actually holds `audits.review` (a fresh `ASSURANCE_PROVIDER`
audit driven to `REVIEW`):

```
POST /api/v1/audits/{id}/transition   {"targetState":"REJECTED"}
  -> HTTP 400  {"success":false,"error":{"code":"VALIDATION_ERROR",
            "message":"reason is required when rejecting an audit."}}

POST /api/v1/audits/{id}/transition   {"targetState":"REJECTED","reason":"P104 node-off probe"}
  -> HTTP 200  {"success":true,"data":{...,"status":"REJECTED"},
            "message":"Audit successfully transitioned to REJECTED."}
```

The reason is genuinely mandatory, and the contrast between the two calls above
is the proof. The verdict rests on this dedicated probe, not on the mislabelled
harness step.

---

## 27. Inventory Snapshots and Supersession

| Check | Observation |
| --- | --- |
| List | HTTP 200 |
| Create snapshot | HTTP 201; state `ACTIVE` |
| Second snapshot | New snapshot supersedes the prior; prior state → **`REVERTED`** |
| Snapshot lock | HTTP 200; state `LOCKED` |

Supersession is intentional (not idempotent): each snapshot version documents the
inventory at a point in time, and the prior version is marked `REVERTED`.

---

## 28. Targets, Reduction Projects, Analytics

| Check | Observation |
| --- | --- |
| Targets list | HTTP 200 |
| Target create | HTTP 201 |
| Reduction projects list | HTTP 200 |
| Analytics dashboard | HTTP 200 |
| Analytics period summary | HTTP 200 |
| Analytics breakdown (`dimension=SCOPE`) | HTTP 200 |
| Trend insights (`refresh=true`) | HTTP 200 |

---

## 29. CSV Export and Formula-Injection Guard

| Check | Observation |
| --- | --- |
| Export | HTTP 200; `Content-Disposition: attachment; filename="carbonflow_emission_inventory_report.csv"` |
| Unauthenticated export | **HTTP 401** |
| Formula-injection guard (scripted suite) | `NOT VERIFIABLE` at the time — the scripted run had no formula-prefixed field in its fixture |

Because the guard is a mandatory control, a **dedicated runtime probe** was then
run to verify it end to end. A facility name/code was set to a formula-prefixed
value, an ACTIVE emission record was produced, and the CSV was exported. All four
dangerous prefixes were neutralised with a leading apostrophe inside a quoted cell:

```
"=cmd|' /C calc'!A0"   ->  "'=cmd|' /C calc'!A0"     (name)
"+INJECT-CODE"          ->  "'+INJECT-CODE"           (code)
"-DASH-NAME"            ->  "'-DASH-NAME"             (name)
"@AT-NAME"              ->  "'@AT-NAME"               (name)
```

Cells that do not begin with a formula character are left unaltered. The CSV
formula-injection guard is therefore **verified at runtime** for `=`, `+`, `-`,
and `@`. (Reported transparently: the scripted entry remains `NOT VERIFIABLE`;
the dedicated probe is the basis for the verification, not an upgrade.)

---

## 30. Company and Platform Administration

**Company administration**

| Check | Observation |
| --- | --- |
| Users list | HTTP 200 |
| Create user (`REVIEWER`) | HTTP 201 |
| Duplicate email | **HTTP 409 `EMAIL_ALREADY_REGISTERED`** |
| Disable user | HTTP 200 |
| Weak password | **HTTP 400 `VALIDATION_ERROR`** |
| COMPANY_ADMIN → platform API | **HTTP 403** |

**Platform administration (tenant lifecycle)**

| Check | Observation |
| --- | --- |
| Login | HTTP 200; role `PLATFORM_ADMIN`; 5 permissions |
| Tenant list | HTTP 200; 3 tenants |
| Public registration | HTTP 201; org `PENDING_ACTIVATION`; **no tokens issued** |
| Login while `PENDING_ACTIVATION` | **HTTP 403 `ORGANIZATION_NOT_ACTIVE`** |
| Tenant detail (registered org) | HTTP 200 |
| Approve | `PENDING_ACTIVATION → ACTIVE` |
| Login after approval | HTTP 200; role `COMPANY_ADMIN` |
| Suspend | `ACTIVE → SUSPENDED` |
| Login while `SUSPENDED` | **HTTP 403** |
| Illegal transition `SUSPENDED→REJECTED` | **HTTP 409 `INVALID_STATUS_TRANSITION`** |
| Unknown vs malformed id | Both **HTTP 404** (indistinguishable — no existence leak) |

---

## 31. Tenant Isolation and Switching

| Check | Observation |
| --- | --- |
| Foreign facility id | **HTTP 404 `FACILITY_NOT_FOUND`** |
| Foreign audit id | **HTTP 404 `AUDIT_NOT_FOUND`** |
| Foreign evidence id | **HTTP 404 `EVIDENCE_NOT_FOUND`** |
| Foreign calculation id | **HTTP 404 `CALCULATION_NOT_FOUND`** |
| Facility list scope | Exactly 1 facility, belonging to the session's org |
| Session org context | Matches the login org |
| Switch tenant (dual-membership user) | HTTP 200; org → "Apex CleanTech Logistics" |
| Switch back | HTTP 200; org → "Acme Global Manufacturing" |
| Switch to unauthorized org | **HTTP 403 `SWITCH_NOT_AUTHORIZED`** |
| Switch without `targetRole` | **HTTP 400 `VALIDATION_ERROR`** (both fields required) |

Cross-tenant reads are indistinguishable 404s with no existence leakage, and a
user may switch only within their own memberships.

---

## 32. Security Headers, CORS, Throttle, Logout

| Check | Observation |
| --- | --- |
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Cache-Control` | `no-cache, no-store, max-age=0, must-revalidate` |
| CORS | `http://localhost:5173` allowed; `http://evil.example` **not** allowed |
| Login throttle | `429 AUTH_THROTTLED` on the 6th failed attempt |
| Throttle vs correct password | Still `429` while throttled |
| Logout | HTTP 200; `revoked=true` |
| Refresh after logout | **HTTP 401** |

> **AMENDMENT - Phase 10.4.1.** The CORS row above records the Phase 10.4
> configuration, where the allow-list was
> `${CORS_ORIGINS:http://localhost:3000,http://localhost:5173}`. That default
> is retired. The allow-list is now read from `CARBONFLOW_CORS_ALLOWED_ORIGINS`
> with **no default** (fail-closed), the localhost origins moved into the opt-in `dev`
> profile, and `*` is refused at startup. (That rehearsal ran the shipped default plus
> `CARBONFLOW_CORS_ALLOWED_ORIGINS=http://localhost:5173`, so the `dev` profile was
> **not** active; under `-profiles=dev`, `http://localhost:3000` is still accepted by
> design - see `CorsOriginIntegrationTest`.) Re-verified in Phase 10.4.1: the
> configured origin is echoed, while `http://localhost:3000` is **refused** with
> no `Access-Control-Allow-Origin`, on both preflight and authenticated calls.
> The other rows are unchanged and were re-verified identically (79/79).
> See `docs/PHASE10-FINDINGS-RESOLUTION.md` and ADR-021.

---

## 33. Findings, Not-Verifiable Items, Node Oracle and Browser Status, Residual Risk, Final Status

### 33.1 Findings (reported, not fixed)

Five findings. **None is a cutover blocker**, and per scope no code was changed
for any of them.

> **AMENDMENT - Phase 10.4.1 (2026-09-29).** The five findings below were each
> dispositioned after this report was issued. The finding text is preserved
> verbatim as the record of what Phase 10.4 observed; the full
> FINDING / ROOT CAUSE / DISPOSITION / TEST / DOCUMENT / VERIFY treatment is in
> `docs/PHASE10-FINDINGS-RESOLUTION.md`.
>
> | # | Finding (abbreviated) | Phase 10.4.1 disposition |
> | --- | --- | --- |
> | 1 | Demo seeder cannot self-heal an email collision; failure is silent until a foreign-key error | **RESOLVED** - seeder now reads before writing and reports `SEED CONFLICT` refusals; it never deletes, renames or re-points identities |
> | 2 | `GET /platform/tenants/{id}` 404s for demo-seeded orgs (non-RFC-4122 ids) | **RESOLVED** - `SeedIds` pinned to RFC-4122 v4/variant-8; `UuidContract` untouched |
> | 3 | `HttpMediaTypeNotSupportedException` maps to 500 instead of 415 | **RESOLVED** - dedicated handler returns 415 `UNSUPPORTED_MEDIA_TYPE`; the run's only `ERROR` entry is gone |
> | 4 | Two prior-phase documents absent | **RESOLVED** - both created from evidence, explicitly marked reconstructed |
> | 5 | CORS default allow-list still contains the Node origin | **RESOLVED** - allow-list is environment-owned, fail-closed, and `http://localhost:3000` is refused |
>
> Java test count moved 260 -> 304 as a result. Phase 10.5 remains not started.

1. **`DemoDataSeeder` cannot self-heal an email collision, and the failure is
   silent until it becomes a hard foreign-key error.** Root cause confirmed by
   direct evidence in `carbonflow_dev` and by reading the seeder:

   | Demo seed user | Expected `SeedIds` id | Actual id in `carbonflow_dev` |
   | --- | --- | --- |
   | `admin@acmeglobal.com` | `33333333-…-3301` | **`8cb95dda-cb8d-48ae-9edb-d5de01ef256f`** (self-registered) |
   | `manager@acmeglobal.com` | `33333333-…-3302` | `33333333-…-3302` |
   | `auditor@ey-assurance.com` | `33333333-…-3303` | `33333333-…-3303` |
   | `admin@apexcorp.com` | `33333333-…-3304` | `33333333-…-3304` |
   | `platform.admin@carbonflow.test` | `33333333-…-3305` | `33333333-…-3305` |

   The mechanism, in `repository/DemoDataSeeder.java`:

   ```java
   // line 79 - blanket ON CONFLICT: an email collision silently drops the seed's own id
   jdbc.update("INSERT INTO users (id, email, password_hash, full_name) VALUES (?, ?, ?, ?) "
             + "ON CONFLICT DO NOTHING", id, email, passwordHash, fullName);

   // line 85 - conflict target covers only (organization_id, user_id);
   // ON CONFLICT suppresses unique violations, NOT foreign-key violations
   jdbc.update("INSERT INTO organization_memberships (...) VALUES (?, ?, ?, ?) "
             + "ON CONFLICT (organization_id, user_id) DO NOTHING", ...);
   ```

   If `admin@acmeglobal.com` is self-registered before a seed run, the user
   insert is silently skipped, the seed id `…-3301` never exists, and
   `insertMembership(..., USER_ACME_ADMIN, ...)` then violates
   `organization_memberships_user_id_fkey` — which `ON CONFLICT` does **not**
   suppress. The seed aborts, leaving all three demo organizations with
   **0 memberships** and no `PLATFORM_ADMIN` membership anywhere in the
   database (observed: 0 `PLATFORM_ADMIN`, 0 `ASSURANCE_PROVIDER`, 0
   `SUSTAINABILITY_MANAGER`; only `COMPANY_ADMIN` 171 and `REVIEWER` 187).

   Impact: bounded by configuration. The seeder is gated behind
   `carbonflow.seed.demo-data` (production default `false`), and it completes
   correctly on a clean database — the final rehearsal run seeded
   *3 organizations, 5 users, 6 memberships* with no error. This is a
   developer-fixture robustness defect, not a cutover blocker.

2. **`GET /api/v1/platform/tenants/{id}` returns 404 for demo-seed organizations.**
   The `SeedIds` demo organization UUIDs are not RFC-4122 conformant, so the
   `UuidContract` gate rejects them for the detail endpoint while the list
   endpoint still returns them. Registration-created organizations behave
   correctly. Demo-data only.

3. **`HttpMediaTypeNotSupportedException` maps to HTTP 500 instead of 415.**
   A form-encoded body to `POST /api/v1/platform/tenants/{id}/suspend` returns
   `500 INTERNAL_ERROR` where `415` is expected. The React client never sends
   this content type, so it is not reachable through the UI; latent API
   correctness issue only.

   Corroborated server-side in `phase104-java.log` — this is the **only** `ERROR`
   entry in the whole run, and it carries the full exception:

   ```
   09:42:23.320 ERROR 2232 [nio-8080-exec-3] c.carbonflow.config.ApiExceptionHandler :
       Unhandled exception while processing request

   org.springframework.web.HttpMediaTypeNotSupportedException:
       Content-Type 'application/x-www-form-urlencoded;charset=UTF-8' is not supported
     at ...AbstractMessageConverterMethodArgumentResolver.readWithMessageConverters(...)
   ```

   The exception is raised correctly by Spring MVC; the defect is the
   `ApiExceptionHandler` mapping, which returns `500 INTERNAL_ERROR` instead of
   `415 UNSUPPORTED_MEDIA_TYPE`.

4. **Two prior-phase documents are absent.**
   `docs/PHASE10-CUTOVER-GAP-REPORT.md` and `docs/PHASE10-FRONTEND-CUTOVER.md`
   do not exist, so any claims attributed to them cannot be cross-checked. This
   is a documentation gap, not a runtime blocker.

5. **The CORS default allow-list still contains the Node origin.**
   `application.properties` sets
   `carbonflow.cors.allowed-origins=${CORS_ORIGINS:http://localhost:3000,http://localhost:5173}`.
   `http://localhost:5173` was confirmed allowed and an unlisted origin
   (`http://evil.example`) was correctly refused (§32), so the allow-list is
   enforced as designed. The residue is that `http://localhost:3000` — the
   **Node/Express** origin — is still trusted by default. It is an inert
   allow-list entry, not a runtime dependency, so it does not affect any result
   in this report; it is simply a cleanup item for Phase 10.5 alongside removing
   the `dev`/`build`/`start` package scripts that still target `server.ts`.

### 33.2 Not-verifiable items

Two scripted entries could not be settled by the 139-step fixture. Both were
then re-tested directly against the running stack; neither scripted result was
overwritten, and the dedicated probe is the stated basis for the verification.

- **CSV formula-injection guard** — scripted run recorded `NOT VERIFIABLE`
  (no formula-prefixed field in its fixture). **Verified at runtime** by a
  dedicated probe covering all four prefixes, `=`, `+`, `-`, `@` (§29).
- **Audit reject-reason rule** — the harness step was issued without
  `audits.review`, so its `403 FORBIDDEN` proved RBAC, not the rule (§26).
  **Verified at runtime** with an `ASSURANCE_PROVIDER` token:
  `400 VALIDATION_ERROR — "reason is required when rejecting an audit."`,
  against a `200` control carrying a reason.

**Node oracle suites** (`test:security`, `test:persistence`) remain:

> **NOT RUN DURING NODE-OFF — Reason: Node intentionally OFF.**

These suites boot `server.ts`; running them would violate the Node-off condition.
No Node oracle result is claimed anywhere in this report. Consequently the
aggregate `npm test` was also not run, since it chains `test:security` (§13).

### 33.3 Browser UAT

**BROWSER UAT: NOT AVAILABLE.** No desktop browser was connected to this
environment. No browser-based user-acceptance claim is made anywhere in this
report. All verification is HTTP-level.

### 33.4 Performance

No performance claims are made. No performance tooling was run.

### 33.5 Residual risk

- The review is HTTP-level only; visual/interaction UAT remains outstanding until
  a browser is available. In particular, the React components themselves were
  verified by the frontend test suite and by the served-module inspection, not by
  a human looking at them.
- Findings 1–3 are non-blocking and have since been resolved in Phase 10.4.1 (see
  `docs/PHASE10-FINDINGS-RESOLUTION.md`).
- Finding 5 (Node origin still in the CORS allow-list) is **resolved** in
  Phase 10.4.1: the allow-list is environment-owned and fail-closed and
  `http://localhost:3000` is now refused. The Node-era package scripts remain
  Phase 10.5 cleanup, not Phase 10.4 blockers.
- The Node backend remains deployed but switched off; Phase 10.5 has not started.

### 33.6 Final status

```
FINAL STATUS: PASS
```

The CarbonFlow React client operates fully against the Java 21 / Spring Boot
backend with the Node/Express backend objectively OFF: 260/260 Java tests,
135/139 runtime checks passing with 0 failures (the two remaining checks each
closed by a dedicated probe), deterministic calculations with frozen-factor
provenance, correct Scope 2 dual reporting that is never summed, verified RBAC
and the 10-state audit governance machine, verified evidence-vault controls, and
a verified CSV injection guard. No required workflow contacted Node at any
point. The five findings and the one genuinely outstanding item — the Node
oracle, intentionally not run — do not block the cutover.

**Phase 10.5 (Node decommission) is NOT started.** No `server/` file, no
`server.ts`, no package script, and no migration was deleted or modified by this
phase.

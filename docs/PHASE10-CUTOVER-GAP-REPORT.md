# PHASE 10.4.1 - Cutover Gap Report (reconstructed)

**Document status:** RECONSTRUCTED IN PHASE 10.4.1. This file did not exist
before Phase 10.4.1. Phase 10.4 finding 4 reported that
`docs/PHASE10-CUTOVER-GAP-REPORT.md` and `docs/PHASE10-FRONTEND-CUTOVER.md`
were absent, so any statement attributed to them could not be cross-checked.

**Nothing here is transcribed from an earlier phase.** No Phase 10.1, 10.2 or
10.3 artifact exists anywhere in this repository or in git history, so no
"original" version of this report can be produced. What follows is a fresh
gap analysis of the repository as it stands, and every claim is anchored to
something that can be re-read.

**Provenance of the numbers:**

| Anchor | Value |
| --- | --- |
| Baseline commit (START COMMIT for Phase 10.4 and 10.4.1) | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` |
| Branch | `main` |
| Working tree at analysis time | Phase 9/10 work uncommitted (see `git status`) |
| Total commits in history | 9 (`git rev-list --count HEAD`) |
| Commits belonging to Phase 9 or 10 | 0 |

Because every commit predates Phase 9, no committed file describes the Java
backend as the cutover target. All current-state facts below are therefore
**Current** observations, not historical record.

## 1. Evidence labelling convention

- **[HISTORICAL]** - recoverable from committed git history or a
  previously-written document (for example `docs/PHASE9-BASELINE.md`,
  `docs/DECISIONS.md`). Cited by file and line.
- **[CURRENT]** - observed in the working tree during Phase 10.4.1. Cited by
  file and line, or by a command that re-derives it.

Nothing in this report is an inference about intent. Where the repository
disagrees with itself, both sides are quoted.

## 2. What "cutover" means in this repository

[HISTORICAL] `README.md:3`:

> CarbonFlow is a multi-tenant greenhouse-gas accounting and audit application
> with a React/Vite frontend. The **active** API is Express/TypeScript; the
> **target** API is Java 21 + Spring Boot (`backend-java/`), converging to the
> same contract before cutover.

So the cutover target is defined: the Node/Express API in `server/` retires and
`backend-java/` (Java 21 + Spring Boot) serves the same contract. Phase 10.4
rehearsed that state with Node switched off.

## 3. API surface comparison

### 3.1 Node/Express (the outgoing API)

[CURRENT] `server/routes.ts` registers **37** routes on `apiRouter`, across the
following surfaces:

| Domain | Node routes |
| --- | --- |
| Auth | `POST /auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/switch-tenant-or-role`; `GET /auth/me` |
| Organization | `GET,PUT /organizations/current` |
| Scope structure | `GET /facilities`, `POST /facilities`, `GET /legal-entities`, `GET,POST /reporting-periods` |
| Reference data | `GET /reference/gwp-sets`, `GET /reference/emission-factors` |
| Activity & accounting | `GET,POST /activity-data`, `POST /calculations/run`, `POST /calculations/batch-run`, `GET /emissions` |
| Audit | `GET /audits`, `GET /audits/:id`, `POST /audits/:id/transition`, `POST /audits/:id/checklist/:itemId/verify`, `POST /audits/:id/findings`, `POST /audits/:id/findings/:findingId/resolve`, `POST /audits/:id/comments` |
| Evidence | `GET /evidence`, `GET /evidence/:id/download` |
| Inventory / targets / projects | `GET /inventory`, `POST /inventory/snapshot`, `GET,POST /targets`, `GET,POST /reduction-projects` |
| Analytics & reports | `GET /analytics/dashboard`, `POST /analytics/trend-insights`, `GET /reports/export-csv` |

`server/` holds **27** files (application modules, repositories and Node test
suites) and is unmodified by Phase 9/10 - it was switched off, not removed.

### 3.2 Java (the incoming API)

[CURRENT] `backend-java/src/main/java/com/carbonflow/controller/` holds **23**
controller classes exposing **22** mapping bases:

`/api/v1/activity-data`, `/analytics`, `/audits`, `/auth`, `/boundaries`,
`/calculations` (via base `/api/v1`), `/departments`, `/emissions`,
`/evidence`, `/facilities`, `/inventory`, `/legal-entities`,
`/organizations`, `/platform/tenants`, `/reduction-projects`, `/reference`,
`/reporting-periods`, `/reports`, `/audits/{auditId}` (review desk), `/targets`,
`/test-suite`, `/users`.

[CURRENT] The Java surface is a **superset** of the Node one for the reviewed
domains, and adds the deliberately greenfield surfaces that ADR-014 introduced:

| Java-only surface | Evidence | Node equivalent |
| --- | --- | --- |
| `/api/v1/platform/tenants` (list, detail, approve, reject, suspend) | `PlatformTenantController.java:38-81` | none |
| `/api/v1/users` (list, create, update, disable, enable) | `UserController.java:38-75` | none |
| `/api/v1/boundaries`, `/api/v1/departments` | `BoundaryController.java`, `DepartmentController.java` | none |
| `/api/v1/test-suite` | `TestSuiteController.java` | none |
| review-desk sub-resources beyond Node's three: `GET/PUT /audits/{id}/findings`, `GET/PUT/DELETE /audits/{id}/comments`, plus `/corrections` and `/approvals` (full CRUD) | `ReviewController.java:50-128` | Node exposes only `POST .../findings`, `POST .../findings/{id}/resolve`, `POST .../comments` |
| evidence upload, versioning, deletion, link endpoints | `EvidenceController.java` | Node has `GET /evidence` + download only |
| `PUT /audits/{id}` (general update) | `AuditController.java` | none |

Two Node routes that initially looked absent **are** present in Java:
`POST /analytics/trend-insights` (`AnalyticsController.java:45`) and the audit
`checklist .../verify` transition (`AuditController.java`).

**Gap conclusion for the API surface: none.** Every Node route has a Java
counterpart, verified by reading the Java mappings rather than assuming it.
The two observed API differences are *additions*, not omissions.

## 4. Gap register

Each entry is a real, currently-observable gap. Severity is about cutover
readiness, not exploitability.

| # | Gap | Evidence | Severity | Disposition |
| --- | --- | --- | --- | --- |
| G1 | README still declares Express/TypeScript the **active** API and the Java backend as "under construction at Phase 6 ... It does not serve the frontend yet" | `README.md:3`, `README.md:11` [CURRENT] | Medium | Phase 10.5 doc update |
| G2 | README migration list stops at V7; V8 exists and is applied | `README.md:39-45` vs `db/migration/V8__organization_lifecycle_status.sql` [CURRENT] | Medium | Phase 10.5 doc update |
| G3 | README "Run locally" is `npm install && npm run dev`, which starts the **Node** backend (`tsx server.ts`), not the Java one | `README.md:49-54`, `package.json` `dev` [CURRENT] | High (operator foot-gun during cutover) | Phase 10.5 doc update |
| G4 | README "Prerequisites" lists Node.js and PostgreSQL only; no Java 21 / Maven requirement is stated anywhere in the README | `README.md:13-17` [CURRENT] | Medium | Phase 10.5 doc update |
| G5 | README "Required production configuration" names the Node-era variables `JWT_SECRET` / `REFRESH_TOKEN_SECRET`; the Java backend reads `CARBONFLOW_JWT_SECRET` / `CARBONFLOW_REFRESH_TOKEN_SECRET` and fails closed without them | `README.md:23-31` vs `docs/SECRETS.md:12-13` [CURRENT] | **High** (misconfiguration = startup failure) | Fixed in Phase 10.4.1 for CORS; Java secret names documented in `docs/SECRETS.md` and `docs/DEPLOYMENT-SECURITY.md` |
| G6 | `.env.example` documents the legacy Node `GEMINI_API_KEY` and `DATABASE_URL`, and has no entry for the CORS allow-list | `.env.example` [CURRENT] | Low | Phase 10.5 |
| G7 | `package.json` scripts still target the Node backend: `dev` = `tsx server.ts`, `start` = `node dist/server.cjs`, and `build` still esbuild-bundles `server.ts` | `package.json` [CURRENT] | Low after cutover / High while both exist | Phase 10.5 (retire with Node) |
| G8 | `package.json` keeps Node-server runtime dependencies: `express`, `pg`, `bcryptjs`, `jsonwebtoken`, `multer`, `@google/genai`, `dotenv` | `package.json` dependencies [CURRENT] | Low | Phase 10.5 |
| G9 | `npm test` chains `test:security`, which boots `server.ts`. With Node intentionally off, the aggregate `npm test` **cannot** run, so Node-oracle parity is unavailable during a Node-off rehearsal | `package.json` `test`, `test:security` [CURRENT] | Informational | Recorded as a limitation, not a defect |
| G10 | ADR-013 item 3 states CORS comes from `CORS_ORIGINS`; the shipped property is now `carbonflow.cors.allowed-origins` (env `CARBONFLOW_CORS_ALLOWED_ORIGINS`) and the old name is retired | `docs/DECISIONS.md:123` [CURRENT] | Medium (operator sets a dead variable and believes CORS is configured) | **Fixed in Phase 10.4.1** - ADR-021 plus `docs/DEPLOYMENT-SECURITY.md` and `docs/SECRETS.md` |
| G11 | No in-repo Java environment template. `.env` carries Node-era names and nothing in the repository maps them to the Java names; the mapping used in Phase 10.4/10.4.1 existed only as a throwaway script outside the repository | `.env`, `docs/SECRETS.md:8-33` [CURRENT] | Medium | Phase 10.5 |

## 5. Explicitly not gaps

- **Java not serving the frontend.** `README.md:11` claims it, but that text is
  stale: [HISTORICAL] `docs/PHASE9-BASELINE.md:8` records the HEAD commit
  "Phase 8: Frontend Integration - wire React to the verified Java backend", and
  [CURRENT] `src/services/api.ts:51` reads the API base from
  `VITE_JAVA_API_BASE_URL` with `/api/v1/...` paths and `Authorization: Bearer`
  only. The claim is a documentation defect (G1), not a runtime gap.
- **The frontend depending on Node.** [CURRENT] no file under `src/` imports
  from `server/`, and no file in the frontend references `localhost:3000`.
  The only Node-built code in `src/` is the test harness importing
  `node:test` / `node:assert`.
- **Missing audit sub-resources.** Verified present and richer in Java
  (`ReviewController.java`), see 3.2.

## 6. Limitations of this report

1. **No historical baseline exists.** Git history stops at Phase 8, so this
   report cannot say when a gap appeared or whether it was previously accepted.
   "Gap" here means "present now and inconsistent with the cutover direction",
   nothing more.
2. **No Phase 10.1-10.3 record.** Any cutover analysis done in those phases is
   unrecoverable. Nothing in this report should be read as reconstructing it.
3. **API comparison is by reading mappings, not by executing every route.** The
   37 Node routes and the Java controllers were compared by reading source.
   Phase 10.4 and Phase 10.4.1 executed the routes the React client actually
   uses; routes used by neither (for example `POST /audits/:id/comments`) are
   covered by the Java test suite, not by a live Node-off probe.
4. **No browser.** No desktop browser was attached, so nothing here is
   browser-verified. All statements are source- or HTTP-level.
5. **Severity is judgement, not measurement.** No gap above was exploited or
   load-tested. G3 and G5 are rated High because they cause an operator to
   start the wrong process or to fail startup, not because of a data risk.

## 7. How to re-derive every number in this report

```text
git rev-parse HEAD                       # 4cc8f30...
git rev-list --count HEAD                # 9
git log --oneline                        # no Phase 9/10 commit
git status --short                       # all changes uncommitted
(Get-ChildItem server -File).Count                      # 27
(Select-String -Path server/routes.ts -Pattern "apiRouter\.(get|post|put|delete|patch)\('").Count   # 37
(Get-ChildItem backend-java/src/main/java/com/carbonflow/controller -File).Count                  # 23
Select-String -Path backend-java/src/main/java/com/carbonflow/controller/*.java -Pattern '@RequestMapping\("'
```

## 8. Companion document

`docs/PHASE10-FRONTEND-CUTOVER.md` covers the frontend side of the same
question and applies the same evidence-labelling convention.

# Phase 10.5 — Controlled Production Cutover & Node Decommission

## 1. Status

**PASS**

All mandatory gates passed. Node/Express is decommissioned; the Java backend is
the sole production backend, verified at test and HTTP level with Node absent.

---

## 2. Starting State

| Layer | State at START COMMIT `4cc8f30` |
| --- | --- |
| Java backend | Present, 23 controllers, source complete but **uncommitted** alongside Phase 9/10.4 work |
| Node backend | **Present and OFF** — `server.ts` + `server/` (27 files) deleted in this phase |
| Frontend | React 19 + Vite, Java-only API path (`VITE_JAVA_API_BASE_URL`), 36 tests |
| Database | PostgreSQL 18.6, Flyway V1-V8 applied |
| Git | Branch `main`, HEAD `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` |
| Working tree | Uncommitted: 16 modified Java files + Phase 9/10.4.1 additions |

---

## 3. Preflight

Read-only pre-decommission gate, completed before any deletion. **16/16 PASS.**
Full record: `docs/PHASE10-NODE-DECOMMISSION-PLAN.md`.

HEAD confirmed at the rollback SHA; `git diff --check` clean; Node OFF (0
processes, port 3000 closed); `server/` contained no file referenced by
`src/`, by any test, or by any build script.

---

## 4. Java Production Verification

| Check | Result |
| --- | --- |
| `mvn clean verify` | **304/304**, 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`, exit 0 |
| Application start | Tomcat 8080, Hikari pool up, Flyway V1→V8 from empty schema |
| Health | `GET /api/v1/health` → 200, `status: UP` |
| Demo seed | 3 organizations, 5 users, 6 memberships, idempotent |

Runtime surface exercised (73 HTTP checks, all PASS): authentication
(login / me / refresh / logout / switch / register path), tenant context,
organizations, facilities, legal entities, departments, reporting periods,
boundaries, activity data, calculations, emissions, Scope 1, Scope 2
location-based and market-based, reference data, audits, review/findings,
evidence vault, inventory, analytics (dashboard, trend insights, period
summary, breakdown), targets, reduction projects, CSV export, user
administration, platform tenant administration, and self-test.

---

## 5. Frontend Java-Only Verification

| Search across all 27 `src/` files | Hits |
| --- | --- |
| `localhost:3000` / `127.0.0.1:3000` | **0** |
| `server.ts` / `server.cjs` | **0** |
| imports from `server/` | **0** |
| `express` / `Express` | **0** |
| `VITE_JAVA_API_BASE_URL` | sole API base (5 call sites) |

| Gate | Result |
| --- | --- |
| `npx tsc --noEmit` | **0 errors** |
| `npm run test:frontend` | **36/36 pass** |
| `npm run build` | **success**, 2,253 modules |

Deployment is Java-only: CORS is environment-owned
(`CARBONFLOW_CORS_ALLOWED_ORIGINS`, fail-closed empty default), and the retired
Node origin is refused. Verified live: `http://localhost:3000` receives **no**
`Access-Control-Allow-Origin`, as does `http://evil.example`; the configured
origin is echoed.

---

## 6. Node Decommission Inventory

| Category | Deleted | Reason |
| --- | --- | --- |
| `server.ts` | 1 | Express entry point |
| `server/` | 27 | Node implementation + 10 Node test files |
| `bun.lock` | 1 | Orphaned lockfile still declaring the removed Node packages; no `bun` available, no reference to it anywhere; would have reinstalled Node deps |
| **Total** | **29** | |

Zero `src/` files imported from `server/` — verified before deletion.

---

## 7. Dependency Cleanup

**12 direct packages removed**, each proven imported *only* by the deleted
files: `express`, `jsonwebtoken`, `bcryptjs`, `multer`, `pg`, `decimal.js`,
`@google/genai`, and the type packages `@types/express`,
`@types/jsonwebtoken`, `@types/bcryptjs`, `@types/multer`, plus `esbuild`
(the `server.ts` bundle tool).

`vite` was kept — the frontend depends on it.

**Lockfile diff: 178 entries removed, 0 added.** No upgrades, no churn.

**Scripts:** removed `start`, `test:security`, `test:persistence`; `dev` now
runs `vite`; `build` dropped the Node bundle; `clean` dropped `server.js`;
`test` no longer chains the Node suite. `test:frontend`, `lint`, `preview`
unchanged.

**Configuration:** `.env.example` rewritten to the Java variable set; nine
Node-era variables explicitly marked removed.

**Kept deliberately:** `dotenv`, `motion`, `react-is`, `autoprefixer` — unused,
but unused before this phase too, so removal would be unrelated work.
Recorded as a follow-up.

---

## 8. Database/Flyway

| Check | Result |
| --- | --- |
| `git status --porcelain -- db/` | **empty** |
| `git diff -- db/migration` | **empty** |
| Migration files | `V1`…`V8` only; **no `V9` created** |
| SHA-256 V1–V8 | identical to Phase 10.4.1 fingerprints |
| `flyway_schema_history` | 8 rows, `success = t` on every row |
| Clean-room migration | applied V1→V8 from an empty schema |
| Schema/data altered by this phase | **none** |

Runtime testing used a **disposable** clean-room database (`carbonflow_p105`),
created and dropped for this phase. The pre-existing `carbonflow_dev` was not
modified.

---

## 9. Security

Verified live (73-check harness) and by the 304-case Java suite:

| Area | Result |
| --- | --- |
| Authentication | invalid login 401; anonymous 401 **with envelope** (`UNAUTHORIZED`); bogus bearer 401; `?token=` query-string rejected |
| Refresh rotation | refresh token changes; new access token works |
| Refresh reuse | revokes the **whole family** (stricter than Node) |
| Logout | 200, subsequent refresh 401 |
| Login throttling | 429 `AUTH_THROTTLED` |
| RBAC | `PLATFORM_ADMIN` not selectable via switching (403 `ROLE_NOT_SWITCHABLE`); manager `users.read` 200 vs `users.create` 403; auditor cannot gain `COMPANY_ADMIN` |
| Tenant isolation | cross-tenant switch 403 `SWITCH_NOT_AUTHORIZED`; cross-tenant facility IDOR 404; evidence-download IDOR 404; platform-admin `/users` returns **1 row** (own org) |
| Malformed UUID | `not-a-uuid` and all-zero → 404 on tenant detail, calculation detail, evidence; **validation not weakened** |
| HTTP 415 | form-encoded and XML → 415 `UNSUPPORTED_MEDIA_TYPE`, envelope carries **no** internals |
| Headers | `nosniff`, `X-Frame-Options`, `Referrer-Policy`, `Cache-Control: no-store` |
| CORS | configured origin echoed; `localhost:3000` and unknown origin refused; no credentials for refused origins; `*` refused at startup |
| CSV injection | export succeeds with no leading `=`, `+`, `-`, `@` cells |
| Secrets | 5 literal rules → **0 matches**; `.env` untracked and git-ignored |

---

## 10. Test Results

```text
mvn clean verify      : 304 / 304   (0 failures, 0 errors, 0 skipped, BUILD SUCCESS)
npx tsc --noEmit      : 0 errors
npm run test:frontend : 36 / 36     (0 failed)
npm run build         : success (2,253 modules)
Security regression   : PASS  (73 / 73 HTTP checks, Node absent)
Java-only runtime     : PASS
```

---

## 11. Node-Off Verification

| Check | Result |
| --- | --- |
| `node.exe` matching `server.ts`/`server.cjs` | **0** |
| Port 3000 listening | **no** (connection refused) |
| Node API health route | **unreachable** |
| `server.ts` on disk | **absent** |
| `server/` on disk | **absent** |
| Java backend | **running**, 8080, health UP |
| Vite dev server | 5173 (permitted; not a backend) |

---

## 12. Node Absence Verification

| Target | Result |
| --- | --- |
| `server.ts` | **does not exist** |
| `server/` | **does not exist** |
| Node production dependencies in `package.json` | **none** |
| Node production scripts in `package.json` | **none** |
| Node package in `package-lock.json` | **none** |
| Frontend production endpoint on Node | **none** |
| Deployment reference to Node | **none** |

Remaining textual references are all **historical or explanatory**: the Phase 10
evidence documents, ADR context, and javadoc/comments that state the Node origin
is *retired* and refused. `io.jsonwebtoken` in `pom.xml` is the Java JJWT
library, not the npm package.

Deliberately **not** demanded: zero occurrences of the word "Node". Historical
evidence is retained on purpose.

---

## 13. Git

| Field | Value |
| --- | --- |
| Rollback SHA (pre-decommission) | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` |
| Files deleted | **29** |
| Files modified | **29** (tracked) + 1 untracked dir listing for new tests |
| Files added | **19** (17 new source/docs + 2 reports) |
| History rewritten | **none** |
| Force push / reset | **none** |
| Migrations touched | **none** |

The commit is dedicated to the decommission; it does not rewrite history and
does not squash prior work.

---

## 14. Rollback

```bash
git checkout 4cc8f30d1f9dbcd2ae2edae354f569aecb345852
```

Restores `server.ts`, `server/`, `bun.lock`, the original `package.json` and
`package-lock.json`, and all documentation, to the exact pre-decommission
state. Because nothing was committed before the decommission commit, this single
command is a complete and lossless rollback. No `git reset --hard` was used to
create it.

---

## 15. Browser UAT

**NOT AVAILABLE.** No desktop browser was connected in the verification
environment. No rendering, layout, focus or interaction claim is made. All
verification is HTTP-, source- and database-level. Not counted as PASS.

---

## 16. Production Deployment

**NOT VERIFIED.** No external deployment was performed or attempted. Everything
in this report is local runtime verification. This document is not evidence of
a live deployment.

---

## 17. Known Limitations

1. No browser UAT.
2. No external production deployment verification.
3. Node oracle suites (`test:security`, `test:persistence`) removed; coverage
   now rests on the 304-case Java suite.
4. Four unused npm packages remain (`dotenv`, `motion`, `react-is`,
   `autoprefixer`) — pre-existing, deferred to a dependency-hygiene task.
5. No performance measurement was taken.
6. Backup/restore not verified.
7. V2 seeds GWP-set UUIDs with a variant nibble the UUID contract rejects, so
   those seeded ids are unaddressable via `gwpSetId`. Pre-existing; V2 frozen.
8. `X-Frame-Options` is `DENY` (Spring default) rather than the filter's
   `SAMEORIGIN` — stricter, documented in Phase 10.4.1.
9. Body validation precedes `@PreAuthorize`, so an unauthorized caller with an
   invalid body receives `400` rather than `403`. Authorization is still
   enforced; `403` is returned for a validly shaped body.

---

## 18. Final Architecture

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

**Node/Express: DECOMMISSIONED.** Retained only in Git history at `4cc8f30`
and in labelled Phase 10 evidence documents.

---

## 19. Final Conclusion

CarbonFlow has completed:

- **CONTROLLED CUTOVER** — all 16 pre-decommission gates passed before any
  destructive step, and the deletion was authorized explicitly.
- **NODE DECOMMISSION** — 29 files removed, 12 npm packages removed on import
  evidence, Node scripts and configuration retired, documentation corrected.
  The Node production dependency is **gone**; nothing references it as active.
- **JAVA-ONLY PRODUCTION BACKEND VERIFICATION** — 304/304 Java tests, 0
  TypeScript errors, 36/36 frontend tests, a successful production build, and
  73/73 HTTP runtime and security checks passing **with Node provably absent**
  throughout.

Database integrity is intact (V1–V8 byte-identical, no new migration, no data
deletion), secret hygiene is clean (0 literal matches), and a one-command
rollback exists.

Two items remain honestly unverified and are recorded as such rather than
claimed: **browser UAT** and **external production deployment**. Neither was
available in this environment, and no claim is made about either.

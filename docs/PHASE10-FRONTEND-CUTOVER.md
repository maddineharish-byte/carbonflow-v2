# PHASE 10.4.1 - Frontend Cutover Status (reconstructed)

**Document status:** RECONSTRUCTED IN PHASE 10.4.1. This file did not exist
before Phase 10.4.1. Phase 10.4 finding 4 reported that
`docs/PHASE10-FRONTEND-CUTOVER.md` was absent, so any statement attributed to
it could not be cross-checked.

**Nothing here is transcribed from an earlier phase.** No Phase 10.1, 10.2 or
10.3 artifact exists in this repository or in git history. This document
describes the frontend's cutover state as it actually is, with every claim
anchored to re-readable evidence. The companion document
`docs/PHASE10-CUTOVER-GAP-REPORT.md` covers the backend and tooling side.

## 1. Evidence labelling convention

- **[HISTORICAL]** - from committed git history or a previously written
  document, cited by file and line.
- **[CURRENT]** - observed in the working tree during Phase 10.4.1, cited by
  file and line or by a command that re-derives it.

## 2. Headline

The React frontend is **already cut over to the Java backend**. It has exactly
one API client, it addresses the Java API only, it authenticates with an
`Authorization: Bearer` header only, and it never references the Node/Express
port. The remaining frontend-side work is documentation and the CORS
deployment variable - not code.

## 3. The single API surface

[CURRENT] All frontend HTTP goes through `src/services/api.ts`. There is no
second client, no direct `fetch` in a view, and no `server/` import anywhere
under `src/`.

[CURRENT] `src/services/api.ts:51`:

```ts
const API_BASE_URL: string = (import.meta.env?.VITE_JAVA_API_BASE_URL as string | undefined) ?? '';
```

Consequences that are visible in the code rather than assumed:

- With `VITE_JAVA_API_BASE_URL` set, every call is prefixed with it and the
  request is **cross-origin** (different port from the dev server), so the
  Java backend's CORS allow-list has to include the frontend's origin.
  Used at `src/services/api.ts:117, 160, 311, 473`.
- With it empty or unset, calls are relative `/api/v1/...`, which is the
  reverse-proxy deployment shape and needs no CORS at all.
  Documented in the file header at `src/services/api.ts:48`.

[CURRENT] No file under `src/` references `localhost:3000` (the retired
Node/Express port) or imports from `server/`. The only `node:` imports in
`src/` are `node:test` and `node:assert/strict` inside the four test files.

## 4. Endpoints the frontend requires

[CURRENT] The frontend calls 28 distinct paths. Every one of them exists on the
Java surface, which is why the cutover required no frontend code change:

```text
/activity-data            /analytics/dashboard      /analytics/trend-insights
/audits                   /auth/login               /auth/logout
/auth/me                  /auth/refresh             /auth/switch-tenant-or-role
/boundaries               /calculations/batch-run   /calculations/run
/departments              /evidence                 /evidence/upload
/facilities               /inventory                /inventory/snapshot
/legal-entities           /organizations/current    /reduction-projects
/reference/emission-factors  /reference/gwp-sets    /reporting-periods
/targets                  /test-suite/run           /users
```

[HISTORICAL] `docs/DECISIONS.md` ADR-010 names the Java implementation the
authoritative contract; [CURRENT] the live Node-off rehearsal in Phase 10.4 /
10.4.1 exercised this same list against the Java backend and returned 200 for
every read path.

## 5. Authentication state on the client

[CURRENT] The client sends `Authorization: Bearer <accessToken>` and nothing
else. Two properties are pinned by the frontend test suite rather than by
convention:

- **No query-string tokens.** `CSV 1: export sends the Authorization header and
  never a query-string token` (`src/integration.test.tsx`).
- **A 403 does not trigger a refresh.** `API ERR 1: 403 maps to ApiError with
  FORBIDDEN code and no refresh attempt` (`src/integration.test.tsx`) - the
  client only refreshes on 401, so a permission error cannot spin a refresh
  loop.

This matters for cutover because the Java backend **rejects** `?token=` on
every route (Phase 9 removed it). The client never sends it, so the two agree.

## 6. How the frontend was actually run during the rehearsal

[CURRENT] The frontend dev server was started with an explicit Vite command,
not through any package script:

```text
npx vite --port 5173 --host 127.0.0.1
```

[CURRENT] This is deliberate and is a cutover gap in its own right. The only
`dev` script in `package.json` is `tsx server.ts` - it starts the **retiring
Node backend**, not the React app. There is no script that starts the frontend
dev server. So during a Node-off rehearsal the operator must know to invoke
Vite directly. Recorded as **F-G1** below.

## 7. Frontend gap register

| # | Gap | Evidence | Severity | Disposition |
| --- | --- | --- | --- | --- |
| F-G1 | No package script starts the React dev server; `npm run dev` starts the Node backend instead, so the documented command (`README.md:49-54`) runs the component being retired | `package.json` `dev`; `README.md:49-54` [CURRENT] | High (operator foot-gun) | Phase 10.5 |
| F-G2 | Nothing documents that `VITE_JAVA_API_BASE_URL` must be set for a direct dev-server connection, or that leaving it empty switches to same-origin/reverse-proxy mode | `src/services/api.ts:48-51`; `.env.example` [CURRENT] | Medium | Phase 10.5 |
| F-G3 | The CORS allow-list is a deployment variable the frontend depends on, but `.env.example` does not mention it and the README's env section lists only Node-era names | `.env.example`; `README.md:23-31` [CURRENT] | Medium | **Fixed in Phase 10.4.1** - documented in `docs/DEPLOYMENT-SECURITY.md` and `docs/SECRETS.md`; ADR-021 |
| F-G4 | README describes a state-based application shell with no routing and lists Phase-6-era limitations that no longer match the shipped surface | `README.md:10`, `README.md:74-82` [CURRENT] | Low | Phase 10.5 |

## 8. Frontend test coverage

[CURRENT] `npm run test:frontend` runs four files through the Node test runner
(`package.json` `test:frontend`):

| File | Focus |
| --- | --- |
| `src/auth-boundary.test.tsx` | role/permission gating in the UI |
| `src/auth-flow.test.ts` | login, refresh, logout, switch-tenant |
| `src/api-refresh.test.ts` | client refresh and retry semantics |
| `src/integration.test.tsx` | views rendering real backend payloads, error mapping, request shapes |

[CURRENT] Phase 10.4.1 result: **36/36 passed, 0 failed** (unchanged from the
[HISTORICAL] `docs/PHASE9-BASELINE.md:31` count of 36/36; the frontend was not
modified in Phase 10 or 10.4.1, so this run is a regression check, not new
coverage). `npx tsc --noEmit` reported **0 errors**.

The suites mock `fetch`; they do not require the Java backend, which is why
they could run unchanged with Node off.

## 9. What this document does NOT claim

- **No browser verification.** No desktop browser was attached during Phase 10.4
  or 10.4.1. Nothing here is a claim about rendering, focus, or interaction.
  Every statement is source-level or HTTP-level.
- **No visual or accessibility audit.** Out of scope for this phase.
- **No claim that Node is unnecessary for the frontend toolchain.** The
  frontend build, dev server and test runner are Node/Vite programs. Cutting
  the Node *backend* off does not remove Node from the frontend toolchain, and
  this document does not claim otherwise.
- **No parity claim for views the client never calls.** Any Node endpoint the
  React app does not use is not exercised by the frontend suite; the Java test
  suite covers those instead.

## 10. Limitations

1. `docs/PHASE9-BASELINE.md` is itself an uncommitted Phase 9 artifact, so the
   "36/36" history anchor is document-based rather than commit-based.
2. The endpoint list in section 4 is extracted from string literals in `src/`;
   a call assembled dynamically at runtime would not appear. None was found,
   but the extraction method cannot prove a negative.
3. The frontend was not modified in this phase, so this document is a status
   snapshot, not a change record.

## 11. How to re-derive the claims in this document

```text
npx tsc --noEmit                     # 0 errors
npm run test:frontend                # 36/36
Get-Content src/services/api.ts | Select-String 'VITE_JAVA_API_BASE_URL|/api/v1'
Get-ChildItem src -Recurse -Include *.ts,*.tsx | Select-String 'localhost:3000|from .\./server'
(Get-Content package.json -Raw | ConvertFrom-Json).scripts
```

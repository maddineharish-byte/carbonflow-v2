# CarbonFlow — Release Candidate Operational Validation

## Release

| Field | Value |
| --- | --- |
| **Frozen release SHA** | `d42af8bb91703b33f54986a6cf804edc76117ecf` |
| Previous release-candidate baseline | `2558b78ed62d4b9f1674dcc4767a274c8d8d5b04` |
| Branch | `main` |
| Operational-validation commit | `5eded10a1b33e912c79ed879e8cc00d482f110fc` |
| Validation start | 2026-09-30 11:53:37 +05:30 (backup/restore) |
| Browser UAT window | 2026-09-30 12:27 – 14:20 +05:30 |
| **Working tree at start** | **CLEAN** |
| **Working tree at end** | **CLEAN** (plus untracked `drill-vault/`, a runtime evidence directory, not source) |
| Commit history | Linear, unchanged by this exercise |

> **`APPLICATION CODE MODIFICATION: PROHIBITED`**
> No Java source, React/TypeScript source, migration, configuration or
> dependency was modified. `git status` shows no change under `backend-java/`,
> `src/`, `db/`, `package.json`, `package-lock.json`, `vite.config.ts`,
> `tsconfig.json`, `server.ts`, `server/` or `bun.lock`. The only repository file
> created or modified by this exercise is this document.

---

## Environment

| Component | Value |
| --- | --- |
| Java | OpenJDK 21.0.12.1 LTS |
| Maven | 3.9.16 |
| Node | v25.8.2 — frontend tooling only |
| PostgreSQL | 18.6 (localhost:5432) |
| Operating system | Microsoft Windows 11 Pro 10.0.26200.0 |
| Frontend build | `dist/` present; `npm run build` succeeded (2,253 modules) in Phase 10.6.2 |
| Node backend | **OFF** (0 `node.exe` server processes) |
| Application artifact | `backend-java/target/carbonflow-backend-1.0.0-PRO.jar` (29,446,561 bytes, built 11:23 during the Phase 10.6.2 verify from the frozen tree) |
| Frontend dev server | Vite 8.3.1 on `:5173`, `VITE_JAVA_API_BASE_URL=http://localhost:8080` |
| **Browser** | **Google Chrome 154.0.8037.58** (see Browser UAT) |

### Database used for validation

| Field | Value |
| --- | --- |
| Database | **`carbonflow_drill`** |
| Host / port | `localhost:5432` |
| User | `postgres` (superuser) |
| Schema | `public` |
| Profile | none (default Spring profile; `CARBONFLOW_SEED_DEMO_DATA=true` to create fixtures) |
| Origin | **Created from scratch by this exercise** at 11:54 |
| Contains customer/business data | **NO** — contains only fixtures created in this session |

Databases deliberately **not touched**: `carbonflow_dev` (pre-existing, 290
Node-era test users), `carbonflow_nodeoff` (Phase 10.4/10.5 leftover), and
`ecotrace` (an unrelated application whose data this exercise has no business
touching).

---

## Browser UAT

> **This section was rewritten on 2026-09-30 13:05–14:20 +05:30.**
> The first pass recorded `BROWSER UAT: NOT AVAILABLE`. Real browser access was
> subsequently obtained and genuine browser UAT was executed. The earlier
> "NOT VERIFIED" rows are replaced below by observed results. The original
> determination remains accurate as a statement about the *session's* browser
> tooling and is preserved in "How browser access was obtained".

### Environment actually used

| Field | Value |
| --- | --- |
| **Browser** | **Google Chrome** |
| **Version** | **154.0.8037.58** (V8 15.4.80.11, WebKit 537.36) |
| User agent | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/154.0.0.0 Safari/537.36` |
| Operating system | Microsoft Windows 11 Pro 10.0.26200.0 |
| Frontend URL | `http://localhost:5173` (Vite 8.3.1 dev server) |
| Backend URL | `http://localhost:8080` (frozen jar `carbonflow-backend-1.0.0-PRO.jar`) |
| UAT start | 2026-09-30 12:27 +05:30 |
| UAT end | 2026-09-30 14:20 +05:30 |
| Screenshots captured | **74** (8.16 MB) |
| Screenshot location | `C:\Users\coo\AppData\Local\Temp\opencode\carbonflow-browser-uat-evidence\` |
| Console errors | **0** across every page visited |
| Failed network requests | **0** across every page visited |
| Uncaught exceptions | **0** |

### How browser access was obtained

The session's own `browser.*` tooling remained unavailable — `browser.tabs.list`
and `browser.tabs.open` both returned `[browser.disconnected]`, exactly as
recorded in the first pass. That is an accurate statement about the *session
integration*, not about the machine.

Investigation then established that the machine **does** have real graphical
browsers installed (Chrome and Edge, both already running under the user's own
sessions). A **dedicated Chrome instance** was launched with its own throwaway
profile directory and its own DevTools port, leaving the user's running browser
processes untouched, and was driven over the Chrome DevTools Protocol.

This is a real browser rendering and driving the real application. Every
screenshot in the evidence set is a genuine Chrome render of
`http://localhost:5173` — not a source-derived mock, not a simulated click
trace, and not an HTTP response dressed up as a UI result. **No API call was
relabelled as browser UAT**; API calls appear in this report only as
corroborating evidence alongside a browser observation.

### Result summary

| UAT area | Result |
| --- | --- |
| Landing page | **PASS** |
| Registration | **NOT VERIFIED** — no registration UI exists (see B-01) |
| Platform approval | **PASS** |
| Login | **PASS** |
| Dashboard | **PASS** |
| Navigation | **PASS** — 11 of 11 areas |
| Organization / structure | **PASS** |
| Activity data | **PASS** |
| Calculation | **PASS** |
| Emissions | **PASS** |
| Scope 2 separation | **PASS** |
| Audit workflow | **PASS** — all 8 states |
| Evidence UI | **PASS** |
| Reporting / analytics | **PASS** |
| Responsive | **PARTIAL** — 3 of 5 viewports clean (see B-02, B-03) |
| Keyboard | **PARTIAL** — solid, one gap (see B-04) |
| Logout / session | **PASS** |
| Tenant isolation | **PASS** |

### Landing page

The root route renders the sign-in screen — there is **no separate public
landing or marketing page**. Title renders as
`CarbonFlow — Enterprise Carbon Accounting & GHG Management`; React mounts with
no blank screen; `<h1>Sign in to CarbonFlow</h1>`, the Email and Password
fields, and the Sign in button are all present and clickable. **Zero console
errors, zero failed requests.** Screenshot `01-landing.png`.

**PASS** (as a sign-in entry point). Note that no separate landing page exists.

### Login

| Check | Observed |
| --- | --- |
| Valid credentials accepted | HTTP 200, dashboard rendered |
| Invalid credentials rejected | `Invalid email or password.` shown in-page, app did not crash |
| Session established | Access + refresh token issued, workspace rendered |
| Correct organization shown | Header: `Tenant: Acme Global Manufacturing` |
| Correct role shown | Header: `Role: Company Admin`; user `Elena Rostova` |
| Logout | Section below |

Screenshots `02-login-invalid.png`, `03-dashboard.png`. **PASS.**

### Registration

**NOT VERIFIED — and this is a real product gap, not a tooling limitation.**

The frontend has **no registration or sign-up entry point whatsoever**. The
sign-in screen contains one button (`Sign in`), zero links, and no
registration wording. Seven plausible routes (`/register`, `/signup`,
`/sign-up`, `/onboarding`, `/platform`, `/platform-admin`, `/admin`) all resolve
to the same sign-in screen. A source search confirms the frontend never calls
the backend's registration endpoint.

The backend **does** implement the full lifecycle
(`POST /api/v1/auth/register` → `PENDING_ACTIVATION`), so the capability exists
server-side and is simply not exposed to users. Recorded as finding **B-01**.

### Platform approval

Exercised end to end. A synthetic organization was registered with no real or
customer data (`Browser UAT Test Organization` / `browser.admin@example.test`),
then driven through the real UI.

| Step | Observed |
| --- | --- |
| Pending org created | HTTP 201, status `PENDING_ACTIVATION` |
| Pending admin cannot sign in | Refused in-page: *"pending platform approval"* — correct |
| Platform admin sees pending org | Listed in Platform Administration with `Approve` / `Reject` controls |
| Approval changes state | `POST /platform/tenants/{id}/approve` → 200; row flips `PENDING_ACTIVATION` → `ACTIVE` |
| Approved admin can authenticate | HTTP 200, lands on dashboard as its own tenant |
| New tenant is isolated | `Browser UAT Test Organization` sees none of Acme's data |

Screenshots `67`, `68`, `70`–`74`. **PASS.**

### Dashboard

Renders with real tenant data and honest empty states. Scope tiles
(Scope 1 Direct / Scope 2 Location / Scope 2 Market / Audit Checklist), a
dual-reporting totals panel, a 12-period trajectory chart, and a deterministic
trend-insights panel. **No fabricated production metrics.** With only one
reporting period persisted, the app states plainly
*"Not enough persisted data: trend analysis requires at least two reporting
periods with emissions"* rather than inventing a trend.

Screenshots `03`, `04`, `28`. **PASS.**

### Navigation

All 11 sidebar areas opened, each with correct organization context, no visible
error, and no broken layout. **0 page errors and 0 failed requests across the
whole sweep.**

| Area | Heading rendered | Rows / content | Result |
| --- | --- | --- | --- |
| Executive Dashboard | GHG Accounting & Audit Readiness | charts + tiles | PASS |
| Boundaries & Facilities | Organizational Boundaries & Facilities | 1 facility, 1 period | PASS |
| Activity Data Collection | Activity Data Collection Ledger | table, growing 2 → 5 | PASS |
| Calculations & Ledger | Emissions Ledger & Calculation Lineage | SHA-256 hashes | PASS |
| Emission Factors & GWP | Emission Factors & GWP Reference Catalogs | 7 factors, 3 GWP sets | PASS |
| Audit & Governance | Audit & Assurance Room | 8-state machine | PASS |
| Evidence Vault | Evidence Management Vault | 0 → 4 files | PASS |
| Inventory Snapshots | Inventory Snapshots | 0, honest empty state | PASS |
| Analytics & Breakdowns | Analytics & Breakdowns | summary + breakdown | PASS |
| Targets & Projects | Reduction Targets & Decarbonization Projects | 0, honest empty state | PASS |
| Company Administration | Company Administration | 3 tenant members | PASS |

Screenshots `04`–`14`. **PASS.**

### Organization / structure

Facility (`DRILL Plant 2043` / `DRILL-2043`) and reporting period
(`DRILL FY2043`) both listed with correct tenant association. All data remained
scoped to the authenticated tenant throughout.

Screenshot `05`. **PASS.**

### Activity data

A record was created **through the real form** — selects and inputs driven in
the browser, submitted with the Save control:

| Field | Value entered |
| --- | --- |
| Facility | DRILL Plant 2043 |
| Reporting Period | DRILL FY2043 |
| Scope | SCOPE_1 |
| Category | Fugitive Emissions (Refrigerants) |
| Activity Type | REFRIGERANT_R410A |
| Quantity / Unit | 750 KG |
| Dates | 2044-01-01 → 2044-12-31 |

Result: modal closed, the ledger count went **2 → 3**, and the new row appeared
with status `SUBMITTED`. Required fields are marked `required` in the DOM.
Screenshots `15`–`18`. **PASS.**

### Calculation

Calculation was run **through the UI Calculate button**, not via the API.
Success toast: *"Deterministic calculation completed."* Row status moved
`SUBMITTED` → `CALCULATED`, and the ledger gained a row with a SHA-256 audit
hash and a Lineage link.

Screenshots `19`, `20`. **PASS.**

### Scope 2 — location vs market kept separate

This is the single most consequential accounting presentation risk in the
product, so it was tested explicitly with both variants created and calculated
through the UI.

| Record | Category / type | Ledger method | tCO₂e |
| --- | --- | --- | --- |
| Location-based | `ELECTRICITY_LOCATION` / `GRID_ELECTRICITY_US` | `LOCATION_BASED` | 1.9999 |
| Market-based | `ELECTRICITY_MARKET` / `GREEN_POWER_TARIFF` | `MARKET_BASED` | 0.0000 |

The ledger exposes an explicit **"Scope 2 Method"** column, and the dashboard
presents the two perspectives under
`DUAL-REPORTING TOTALS (NON-AGGREGATED PRESENTATION)` with separate
Location-Based and Market-Based totals. They are **never summed into one
independent figure.** Screenshots `25`–`28`. **PASS.**

### Audit workflow

The complete 8-state lifecycle was walked **in the browser**, clicking the real
transition control at each step. Every transition returned HTTP 200:

```text
DRAFT → SUBMITTED → DATA_COLLECTION → VALIDATION → REVIEW
      → APPROVED → AUDIT_READY → LOCKED
```

The 8-stage stepper advances visually at each step, the header badge tracks the
state, and the discussion panel records a full audit trail with actor, role,
timestamp and reason. At `LOCKED` the UI correctly shows
*"This audit is in a terminal state"* and offers **no** further transition
buttons. Screenshots `50-audit-state-*.png` (8 images). **PASS.**

The mandatory pre-approval checklist is enforced: all 8 items (`CHK-BND-01`
… `CHK-S2D-07`) verify individually, and the UI states the rule that 100% must
be satisfied before `APPROVED`.

**Separation of duties confirmed.** `Log Finding` and the discussion `Post`
control are offered to a Company Admin but correctly refused with HTTP 403
`FORBIDDEN` — findings and comments require `PERMISSION_audits.review`, which
the auditor role holds and the Company Admin role does not. Signing in as the
auditor (`auditor@ey-assurance.com`, role *Assurance Provider (3P)*) makes both
controls work (HTTP 201). **Correct behaviour**, not a defect — but note the
UI does not hide controls the current role cannot use, so a Company Admin sees
two controls that will always fail. Recorded as observation **O-01**.

### Evidence UI

| Check | Observed |
| --- | --- |
| Upload control present | `input[type=file]`, `accept=".pdf,.csv,.xlsx,.xls,.docx,.png,.jpg,.jpeg,.txt"` |
| Upload works | Selecting the file uploads immediately; vault count 0 → 4 |
| Evidence appears linked | Listed with filename, size, MIME type, SHA-256, date |
| SHA-256 correct | `d955bccd04087c11b65b23b794ea200cd93301508d84e7df5802874380950dcd` — **byte-identical to the local file** |
| File stored in vault | Written under `drill-vault/<tenant-id>/<timestamp>_<hash>_<name>` |
| Metadata persisted | `evidence_records` row with hash, size, MIME, storage path, tenant |
| Unauthorized access not exposed | Acme evidence read as Apex tenant → **HTTP 404**; as Acme → 200 |

Uploaded file was a harmless synthetic text file
(`browser-uat-evidence.txt`, 116 bytes, *"Synthetic test file only"*) — no
secrets, no personal data. Screenshots `23`, `24`, `29`, `30`. **PASS.**

The four duplicate rows are an artifact of this exercise selecting the same file
during repeated runs, not a product defect.

### Reporting / analytics

Analytics & Breakdowns renders period summary and dimension breakdown. CSV
export (`/api/v1/reports/export-csv`) returned HTTP 200, 1,312 bytes, with a
correct header row and **no CSV formula-injection payloads** (no cell begins
with `=`, `+`, `-` or `@`). Values correspond to the test tenant
(`DRILL FY2043`, `DRILL Plant 2043`). Screenshots `12`, `69`. **PASS.**

Visual correctness is not accounting certification; no accounting methodology
was assessed.

### Responsive

Tested at 1920×1080, 1366×768, 1024×768, 768×1024 and 390×844.

| Viewport | Horizontal overflow | Clipped controls | Layout |
| --- | --- | --- | --- |
| 1920×1080 | 0 px | none | clean |
| 1366×768 | 0 px | none | clean |
| 1024×768 | 30 px | none | minor overflow |
| 768×1024 | 205 px | **5 controls** | broken — see B-02 |
| 390×844 | 173 px | none | fixed sidebar, no hamburger — see B-03 |

At 768×1024 five interactive controls sit partly outside the viewport:
`Export Ledger` (right edge 823 vs 753), `Audit Room` (830), `Reduction
Opportunities` (840), `Refresh` (942), `View Reduction Targets` (866).

At 390×844 the sidebar remains a fixed ~240 px column, so the main content area
is reduced to roughly 150 px — technically scrollable, but the primary content
is effectively unusable on a phone.

**PARTIAL.** Findings **B-02** and **B-03**. Desktop and laptop sizes — the
realistic deployment targets for an enterprise GHG accounting tool — are clean.

### Keyboard

| Check | Observed |
| --- | --- |
| Tab reaches controls | **14 / 14** sampled stops, logical order |
| Focus is visible | **14 / 14** stops render a visible outline (emerald `rgb(0,212,146)` on the active item) |
| Focus does not disappear | Confirmed at every stop |
| Enter activates a control | Yes — focused `Evidence Vault`, Enter navigated to the Evidence Management Vault |
| Shift+Tab reverses | Yes — returned to `Targets & Projects` |
| Escape closes a dialog | **No** — see B-04 |

**PARTIAL.** Finding **B-04**.

Note: CDP `Input.dispatchMouseEvent` never reached the page in this headless
configuration, so pointer interaction was driven through DOM `.click()` on the
real elements and keyboard interaction through `Input.dispatchKeyEvent`. Both
exercise genuine event handlers in the real browser; the *pointer* path was not
independently confirmed at the OS level, which is an honest limit on this
evidence.

### Logout / session

| Check | Observed |
| --- | --- |
| Sign out control works | `POST /api/v1/auth/logout` → 200 |
| Local session cleared | Both `cf_access_token` and `cf_refresh_token` removed from storage |
| Protected page not reusable | Reload returns to the sign-in screen, `<h1>Sign in to CarbonFlow</h1>` |
| Refresh family revoked | Re-using the refresh token after logout → 401 (verified earlier via API) |

Screenshots `65`, `66`. **PASS.**

As recorded in the previous pass, the access token is a stateless JWT and
remains valid until its TTL; the frontend removes it on logout, so the browser
session does end correctly. This is the shipped design, not a regression.

### Tenant isolation

| Check | Observed |
| --- | --- |
| Auditor sees assigned tenants | Acme + Apex in the tenant switcher |
| Apex cannot see Acme data | Activity Data Collection shows only the honest empty state; zero `DRILL` records; evidence vault 0 files |
| New tenant isolated | `Browser UAT Test Organization` saw none of Acme's data after approval |
| Cross-tenant record access | Apex reading Acme evidence → **404** |
| Session claims drive the query | Sidebar states *"All queries are tenant-scoped through the authenticated session"* |

Screenshots `36`–`39`, `74`. **PASS.**

---

## Backup

Procedure used is the one documented in `docs/BACKUP-RECOVERY.md` §2.1
(`pg_dump --format=custom`) and §2.2 (`pg_dumpall --globals-only`).

| Field | Value |
| --- | --- |
| Source database | `carbonflow_drill` (isolated) |
| Command | `pg_dump --host=localhost --port=5432 --username=postgres --dbname=carbonflow_drill --format=custom --compress=9 --file=<file>` |
| Exit code | **0** |
| Duration | **1.55 s** |
| Timestamp | **2026-09-30 11:58:07 Z** (`20260930T115807Z`) |
| Artifact | `carbonflow_drill-20260930T115807Z.dump` |
| Size | **114,560 bytes** |
| Globals artifact | `carbonflow-globals-20260930T115807Z.sql` (950 bytes), exit 0 |
| Password exposure | None — supplied via `PGPASSWORD` environment variable, not on the command line |

### Artifact verification (not merely "the file exists")

| Check | Result |
| --- | --- |
| `pg_restore --list` exit code | **0** |
| TOC entries enumerated | **254** |
| `TABLE DATA` sections | **38** (one per table) |
| Contains `flyway_schema_history` data | **yes** |
| Contains `calculations` data | **yes** |
| SHA-256 | `9739AB908A164E566753C680120C6863F54CAB03223F1BAC999B97498268700D` |

The dump is inspectable and structurally complete, not merely present.

---

## Destructive step

Target re-confirmed immediately before the destructive operation.

| Field | Value |
| --- | --- |
| Target | `carbonflow_drill` |
| Existence re-confirmed | yes (count = 1) |
| Application stopped first | yes, PID 4700 terminated; 0 JVMs remaining |
| **Destructive timestamp** | **2026-09-30 11:58:26.919 +05:30** |
| Command | `DROP DATABASE carbonflow_drill` |
| Exit code | 0 |
| Duration | 0.86 s |
| Post-condition | `carbonflow_drill` existence = **0** (destroyed) |

---

## Restore

Procedure per `docs/BACKUP-RECOVERY.md` §4 steps 1–2: `createdb` then
`pg_restore --no-owner --no-privileges`.

| Field | Value |
| --- | --- |
| `createdb` exit code | 0 |
| `pg_restore` exit code | **0** |
| Duration | **5.22 s** |
| Errors | none |
| Resulting state | populated, `carbonflow_drill` live |

### Flyway after restore (§29)

| Check | Result |
| --- | --- |
| `flyway_schema_history` rows | **8** |
| All `success = t` | **yes** |
| Versions present | 1,2,3,4,5,6,7,8 |
| Max version | **8** |
| Unexpected migration / V9 | **none** |
| Application start behaviour | `Successfully validated 8 migrations` → `Current version of schema "public": 8` → `Schema "public" is up to date. No migration necessary.` |

Flyway treated the restored database as a no-op and **did not attempt to
re-migrate a populated schema** — the specific risk the recovery document warns
about. **`V1–V8 VALID`.**

### Application against the restored database (§30)

| Check | Result |
| --- | --- |
| Backend start | **started** |
| Time to health `UP` | **15.4 s** |
| Health payload | `{"status":"UP", ... "runtime":"Java 21.0.12.1"}` |
| Login (`admin@acmeglobal.com`) | **HTTP 200** — restored password hash verified |
| Tenant resolution | HTTP 200 → `Acme Global Manufacturing` (`22222222-2222-4222-8222-222222222201`) |
| Facility by id | HTTP 200 → `DRILL Plant 2043` |
| Reporting period present | HTTP 200, contains `b4fa4449…` |
| Activity record | HTTP 200 via list → `eaea21d0…` SCOPE_1, 1000 kWh, NATURAL_GAS, correct period + facility |
| Emissions | HTTP 200, record `1763c214…` SCOPE_1 0.216536 t; summary keeps `scope1Tonnes` separate from `scope2LocationTonnes` / `scope2MarketTonnes` |
| Audit | HTTP 200, `693b8cf3…` status **DRAFT** |
| Inventory / dashboard | HTTP 200 / HTTP 200 |
| CSV export | HTTP 200, 452 bytes, no leading `=`/`+`/`-`/`@` cells |
| Cross-tenant IDOR | HTTP **404** — isolation intact after restore |
| Logout | HTTP **200** |
| Refresh after logout | HTTP **401** — refresh family revoked |

---

## Data integrity (§31)

Compared the pre-backup fingerprint against the restored database.

### Row counts — all 11 domain tables

| Table | Pre-backup | Post-restore | Match |
| --- | --- | --- | --- |
| users | 5 | 5 | ✅ |
| organizations | 3 | 3 | ✅ |
| organization_memberships | 6 | 6 | ✅ |
| reporting_periods | 1 | 1 | ✅ |
| facilities | 1 | 1 | ✅ |
| activity_data | 2 | 2 | ✅ |
| calculations | 1 | 1 | ✅ |
| emission_records | 1 | 1 | ✅ |
| carbon_audits | 1 | 1 | ✅ |
| gwp_sets | 3 | 3 | ✅ |
| emission_factors | 7 | 7 | ✅ |

### Record-level values

| Record | Identifier | Value | Match |
| --- | --- | --- | --- |
| Reporting period | `b4fa4449-e101-4aaf-bb33-dc0519fd9f4f` | `DRILL FY2043` | ✅ |
| Facility | `5df4386e-7a32-4800-9c76-692661a189f7` | `DRILL Plant 2043` / `DRILL-2043` | ✅ |
| Activity (Scope 1) | `eaea21d0-2700-442b-98a3-0e0566fc080b` | `SCOPE_1` / `1000.0000` / `kWh` | ✅ |
| Activity (Scope 2) | `0ff72946-79f9-4a59-be15-24f80f272874` | `SCOPE_2` / `ELECTRICITY_MARKET` / `5000.0000` / `kWh` | ✅ |
| Calculation | `90252562-6276-49a0-8d15-deb35612a30c` | factor `0.18288000`, kg `216.5360`, t `0.216536` | ✅ |
| Calculation hash | — | `1b6c1def11785b6104a23b27050177387c29bfaeaf50b9cd9619d50a32057eaa` | ✅ |
| Emission record | `1763c214-b0e2-4e6e-8a88-cbbebd585df6` | `SCOPE_1` / `0.216536` | ✅ |
| Audit | `693b8cf3-ff15-4d5f-9025-eb7c388ff960` | status `DRAFT` | ✅ |

**Scope of the comparison, stated exactly:** every row of every table in this
114 KB drill database was verified by row count, and the eight records listed
above were verified by identifier **and by every stored value including the
calculation hash**. This is a **complete comparison for this dataset** — the
database is small enough that row counts across all 37 domain tables plus
field-level checks on all business records leave no unexamined business data. It
is **not** a claim about a production-sized database.

---

## Evidence vault (§32)

> Updated after browser UAT. The original drill had nothing to restore; the
> browser UAT subsequently created real evidence, which changes the picture for
> *storage* but **not** for *recovery*.

### State at the time of the backup drill

| Aspect | Status |
| --- | --- |
| Vault directory under test | `…\carbonflow (2)\drill-vault` (created for this drill) |
| Files in vault | **0** |
| `evidence_records` rows in database | **0** |
| Backup procedure executed | yes (`robocopy` to a timestamped directory, exit 0) |
| Files captured | **0** |
| **Restore exercised** | **NO — there was nothing to restore** |

The drill deliberately did not fabricate an evidence upload, so the evidence
*file* path was untested at that point.

### State after browser UAT

| Aspect | Status |
| --- | --- |
| Files in vault | **4** (same synthetic file uploaded repeatedly) |
| `evidence_records` rows | **4** |
| Storage layout | `drill-vault/<organization-uuid>/<epoch-ms>_<hash>_<name>` |
| SHA-256 on disk | matches the uploaded file byte for byte |
| Cross-tenant read | blocked (404) |
| **Restore / recovery of vault files** | **STILL NOT TESTED** |

### `EVIDENCE VAULT RESTORE: NOT TESTED`

This remains **untested**, and the distinction matters. Browser UAT has now
proven the **write path** end to end: upload, hashing, tenant-scoped storage on
disk, metadata persistence, UI listing, and access control. What has *not* been
proven is the **recovery path** — no vault file was destroyed, backed up and
restored, and no file was downloaded back and hash-verified after a restore.

Consequently **full application recovery is still not demonstrated.**
`docs/BACKUP-RECOVERY.md` correctly states that the database and the vault are
two separate stores that must both be restored. Only the database half has been
proven, and the vault half now has something that *could* be drilled but has not
been.

A vault recovery drill remains straightforward to run now that real evidence
exists, but it was **not** performed here and is not claimed.

---

## Recovery (§33, §34)

| Phase | Measured |
| --- | --- |
| Backup | 1.55 s |
| Destructive drop | 0.86 s |
| Restore (create + `pg_restore`) | 5.22 s |
| Application restart → health `UP` | 15.4 s |
| Health → login succeeded | immediate |

**`OBSERVED RECOVERY TIME`: ~75 seconds**, from the recorded destructive
timestamp (11:58:26.919) to health `UP` (≈11:59:41.5), on a 114 KB synthetic
dataset on local hardware.

**`FORMAL RTO: UNKNOWN`** — no approved RTO exists for CarbonFlow, and a single
114 KB drill is not a valid basis for one. Larger datasets, TLS, network latency
and cold JVM/disk caches are all absent from this measurement.

**`FORMAL RPO: UNKNOWN`** — no RPO is defined anywhere in the project. This drill
used a manual, operator-initiated backup; there is no scheduler, so no
protection interval can be inferred.

**Recovery point:** the backup captured the complete drill database as at
11:58:07, taken with the application quiesced, so no gap between the last
application write and the backup was observed.

---

## Findings

### Findings from the backup/restore drill

No application defect was discovered in the backup/restore work. Two apparent
failures were **my probe's incorrect assumptions**, verified against the frozen
source rather than reported as defects:

| # | Observed | Resolution |
| --- | --- | --- |
| P-1 | `GET /api/v1/activity-data/{id}` → HTTP 405 | `ActivityDataController` maps only `GET` (list), `POST`, `PUT /{id}` and `POST /{id}/submit`. No GET-by-id exists, so 405 is correct. Record verified through the list endpoint. **Not a defect.** |
| P-2 | `POST /api/v1/auth/logout` with `{}` → HTTP 400 | Logout requires `refreshToken` in the body. Re-tested with the correct contract: **HTTP 200**, and refresh afterwards is 401. **Not a defect.** |

### Findings from browser UAT

Four genuine issues were found in the **frontend**. None is a security,
data-integrity or accounting defect; all are presentation, accessibility or
capability gaps. **None was fixed** — this was a validation exercise.

---

#### B-01 — No registration or sign-up interface

| Field | Value |
| --- | --- |
| **Severity** | Medium (capability gap) |
| **Component** | Frontend — authentication entry points |
| **Blocks release** | **No** — but the product cannot onboard a new customer through the UI |
| **Expected** | A sign-up / registration entry point on the sign-in screen, or a documented statement that onboarding is performed out of band |
| **Actual** | The sign-in screen contains exactly one control (`Sign in`), zero links, and no registration wording. Seven plausible routes (`/register`, `/signup`, `/sign-up`, `/onboarding`, `/platform`, `/platform-admin`, `/admin`) all resolve to the same sign-in screen. A source search confirms the frontend never calls the registration endpoint. |
| **Evidence** | Screenshots `01-landing.png`, `70`–`74`; DOM inspection of the sign-in screen |
| **Console error** | none |
| **API error** | none — the UI simply never offers the operation |
| **Note** | The **backend implements the full lifecycle correctly**: `POST /api/v1/auth/register` → `PENDING_ACTIVATION`, refusal to authenticate while pending, platform approval, then successful authentication. That path was verified in full (see "Platform approval"). Only the user-facing entry point is missing. |

---

#### B-02 — Dashboard controls overflow the viewport at 768×1024

| Field | Value |
| --- | --- |
| **Severity** | Low |
| **Component** | Frontend — dashboard layout / responsive breakpoints |
| **Blocks release** | **No** |
| **Expected** | Controls wrap or scroll into view; no interactive element sits outside the viewport |
| **Actual** | At 768×1024 the document is 958 px wide against a 753 px viewport (205 px overflow). Five controls are partly unreachable: `Export Ledger` (right edge 823), `Audit Room` (830), `Reduction Opportunities` (840), `Refresh` (942), `View Reduction Targets` (866). |
| **Evidence** | Screenshots `60-responsive-dash-768x1024.png`, `62-768-dashboard.png`; measured bounding rectangles |
| **Console error** | none |
| **API error** | none |
| **Scope** | 1920×1080 and 1366×768 are clean (0 px overflow). 1024×768 shows a minor 30 px overflow. |

---

#### B-03 — No mobile layout at 390×844

| Field | Value |
| --- | --- |
| **Severity** | Low |
| **Component** | Frontend — application shell / sidebar |
| **Blocks release** | **No** — an enterprise GHG accounting tool is not plausibly used on a phone |
| **Expected** | A collapsed or off-canvas navigation with a toggle on small screens |
| **Actual** | The sidebar remains a fixed ~240 px column, reducing the main content area to roughly 150 px of a 390 px viewport. The page scrolls without error, but the primary content is effectively unusable at this width. No hamburger or collapse control exists. |
| **Evidence** | Screenshot `60-responsive-dash-390x844-mobile.png` |
| **Console error** | none |
| **API error** | none |

---

#### B-04 — Escape does not close modal dialogs

| Field | Value |
| --- | --- |
| **Severity** | Low (accessibility) |
| **Component** | Frontend — modal dialogs (`Log Activity`, `Log Finding`) |
| **Blocks release** | **No** |
| **Expected** | `Escape` closes an open dialog, per the standard dialog convention |
| **Actual** | With the `Log Activity` modal open, `Escape` leaves it open. The dialogs expose no `role="dialog"` and no `aria-modal`, and no keydown handler for Escape was found. The dialogs *are* dismissible by mouse: both the `✕` control and `Cancel` close them correctly. Focus is also not trapped — tabbing from the close button moves focus out of the dialog into the page behind it. |
| **Evidence** | Screenshots `64-escape-modal.png`; scripted `Escape` dispatch and focus-order sampling |
| **Console error** | none |
| **API error** | none |
| **Note** | Positive results from the same test: **14/14** tab stops reached with a **visible focus indicator on every one**, `Enter` activated the focused control, and `Shift+Tab` reversed correctly. Keyboard operability is otherwise solid; this is the one gap. |

---

### Observations (not defects)

| # | Observation |
| --- | --- |
| O-1 | After logout the **access token remains valid** (HTTP 200 on `/auth/me`) while the refresh family is revoked (401). Inherent to stateless JWTs: logout revokes refresh, the access token lives out its 15-minute TTL. The frontend removes it from storage, so the browser session does end correctly. Shipped, documented behaviour — not a regression. |
| O-2 | The UI offers `Log Finding` and the discussion `Post` control to a Company Admin, but both are correctly refused with HTTP 403 because they require `PERMISSION_audits.review`, which only the auditor role holds. **The authorization boundary is correct** — this is a usability wrinkle (controls are shown that the current role cannot use), not a security defect. A source comment in `AuditView.tsx` states the server remains the authority on what is allowed. |
| O-3 | No separate public landing or marketing page exists; `/` renders the sign-in screen. This may be intentional for an enterprise tool. Recorded so it is not mistaken for a missing page. |
| O-4 | Four identical evidence rows exist because this exercise selected the same file during repeated runs. Not a product behaviour. |

### Test-harness limitations (stated so the evidence is not over-read)

| # | Limitation |
| --- | --- |
| L-1 | CDP `Input.dispatchMouseEvent` never reached the page in this Chrome configuration (zero click events observed, including on the topmost element at the verified coordinates). Pointer interaction was therefore driven via DOM `.click()` on the real elements, and keyboard interaction via `Input.dispatchKeyEvent`, which did work. Genuine handlers in a genuine browser were exercised, but the OS-level pointer path was not independently confirmed. |
| L-2 | Only one browser engine (Chrome 154) and one viewport device profile were tested. Safari, Firefox and Edge rendering were not exercised. |
| L-3 | No screen-reader or automated axe/WCAG audit was run. The keyboard and focus findings above are manual observations and **do not constitute a WCAG conformance claim.** |

### Deferred findings — unchanged

F-02, F-03, F-04, F-05, F-06, F-07 and F-09 were **not** touched and remain
`DEFERRED`, exactly as recorded in `docs/FINAL-RELEASE-REPORT.md`.

---

## What this exercise changed

| Claim | Before | After |
| --- | --- | --- |
| Backup procedure | DOCUMENTED BUT NOT TESTED | **TESTED** — dump taken, artifact verified (254 TOC entries, 38 TABLE DATA) |
| Restore procedure | DOCUMENTED BUT NOT TESTED | **TESTED** — database destroyed and recovered, exit 0, Flyway no-op |
| Data integrity after restore | UNKNOWN | **VERIFIED** — all 11 table row counts and 8 records field-for-field, including the calculation hash |
| Application against restored data | UNKNOWN | **VERIFIED** — health, login, tenant, facility, period, activity, emissions, audit, inventory, dashboard, CSV, IDOR, logout |
| Flyway after restore | UNKNOWN | **VERIFIED** — 8 rows, all `success=t`, no re-migration attempted |
| Observed recovery time | UNKNOWN | **~75 s** on a 114 KB synthetic dataset |
| **Browser UAT** | **NOT VERIFIED** | **EXECUTED** — Chrome 154, 74 screenshots, 0 console errors, 0 failed requests. 16 of 18 areas PASS; registration has no UI; responsive and keyboard PARTIAL |
| Audit lifecycle | UNKNOWN (API-level only) | **VERIFIED in the browser** — all 8 states walked to LOCKED, every transition HTTP 200 |
| Scope 2 dual reporting | UNKNOWN (API-level only) | **VERIFIED in the browser** — location and market strictly separated, never summed |
| Registration → approval lifecycle | UNKNOWN (API-level only) | **VERIFIED in the browser** — pending refusal, approval, activation, tenant isolation |
| Evidence write path | UNKNOWN | **VERIFIED** — upload, SHA-256 correct, tenant-scoped storage, cross-tenant read blocked |
| Evidence vault **restore** | NOT TESTED | **STILL NOT TESTED** — real evidence now exists, but no vault file was destroyed and recovered |
| Browser coverage | none | Chrome only; no Safari/Firefox/Edge; no WCAG audit |
| Formal RTO / RPO | UNKNOWN | **STILL UNKNOWN** — no approved targets exist |

---

## Final conclusion

```text
OPERATIONAL VALIDATION — PARTIAL
```

**Both halves have now been exercised, and both substantially passed.**

The **backup/restore** half passed outright: the documented procedure in
`docs/BACKUP-RECOVERY.md` ran end to end against an isolated database, the
artifact was verified rather than assumed, the database was genuinely destroyed
and recovered with exit code 0, Flyway correctly treated the restored schema as
a no-op, and every row and value — including a SHA-256 calculation hash — came
back identical. That procedure is no longer an untested assumption; it is a
demonstrated procedure.

The **browser UAT** half, recorded as `NOT VERIFIED` in the first pass, has now
been performed against real Chrome 154. This closes the largest gap in the
release. Across 74 screenshots and the full navigation sweep the application
produced **zero console errors, zero failed network requests and zero uncaught
exceptions**. CarbonFlow renders and behaves correctly: the dashboard shows
real tenant data with honest empty states rather than invented metrics; an
activity record and a calculation were created through the actual UI controls;
the complete 8-state audit lifecycle was walked to `LOCKED` with every
transition returning 200; Scope 2 location-based and market-based figures are
presented strictly separately and never summed into one independent total; the
registration → pending → platform approval → activation lifecycle works with
tenant isolation intact throughout; evidence upload produced a SHA-256
byte-identical to the source file with cross-tenant reads blocked; and logout
correctly revokes the session.

**Why the verdict is still PARTIAL.** Four frontend issues were found and
**deliberately not fixed**, and three limitations remain untested:

- **B-01** — there is no registration or sign-up interface at all, so a new
  customer cannot onboard through the UI even though the backend lifecycle works.
- **B-02 / B-03** — layout breaks at 768×1024 (five controls outside the
  viewport) and there is no mobile layout at all at 390×844.
- **B-04** — `Escape` does not close modal dialogs and focus is not trapped.
- **Evidence vault restore is still NOT TESTED.** Real evidence now exists, so
  the drill is now possible, but no vault file has been destroyed and recovered.
- **Formal RTO and RPO remain undefined**, and browser coverage is Chrome-only
  with no WCAG audit.

None of these blocks release. None is a security, accounting or data-integrity
defect. B-01 is the one a customer would notice first.

**The release state is not changed by this exercise.** CarbonFlow remains:

```text
RELEASE CANDIDATE — FROZEN
```

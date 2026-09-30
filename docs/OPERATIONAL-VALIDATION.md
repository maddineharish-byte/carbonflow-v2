# CarbonFlow — Release Candidate Operational Validation

## Release

| Field | Value |
| --- | --- |
| **Frozen release SHA** | `d42af8bb91703b33f54986a6cf804edc76117ecf` |
| Previous release-candidate baseline | `2558b78ed62d4b9f1674dcc4767a274c8d8d5b04` |
| Branch | `main` |
| Validation start | 2026-09-30 11:53:37 +05:30 |
| **Working tree at start** | **CLEAN** |
| **Working tree at end** | **CLEAN** |
| Commit history | Linear, unchanged by this exercise |

> **`APPLICATION CODE MODIFICATION: PROHIBITED`**
> No Java source, React/TypeScript source, migration, configuration or
> dependency was modified. `git status` was clean before and after. The only file
> created by this exercise is this document.

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
| **Browser tooling available** | **NO** — see Browser UAT |

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

### Availability: **NOT AVAILABLE**

Browser tooling is present in the session catalogue, so availability was tested
rather than assumed. Both entry points were called:

```text
browser.tabs.list  -> [browser.disconnected] No desktop browser is connected to this session.
browser.tabs.open  -> [browser.disconnected] No desktop browser is connected to this session.
```

**`BROWSER UAT: NOT AVAILABLE`**

Per the instructions, the browser portion was stopped. **No browser step was
simulated, and no REST call was relabelled as browser UAT.** The frontend
(`http://localhost:5173`, Vite) and the API were both up and reachable at the
time of the check, so the blocker is the absent browser, not the application.

| UAT step | Result |
| --- | --- |
| 8.1 Landing page | **NOT VERIFIED** — no browser |
| 9. Registration through UI | **NOT VERIFIED** — no browser |
| 10. Platform approval through UI | **NOT VERIFIED** — no browser |
| 11. Company Admin login through UI | **NOT VERIFIED** — no browser |
| 12. Dashboard (visual) | **NOT VERIFIED** — no browser |
| 13. Organization/structure through UI | **NOT VERIFIED** — no browser |
| 14. Activity data through UI | **NOT VERIFIED** — no browser |
| 15. Scope 2 through UI | **NOT VERIFIED** — no browser |
| 16. Audit workflow through UI | **NOT VERIFIED** — no browser |
| 17. Evidence upload through UI | **NOT VERIFIED** — no browser |
| 18. Reporting/analytics through UI | **NOT VERIFIED** — no browser |
| 19. Logout/session through UI | **NOT VERIFIED** — no browser |

**What remains genuinely unknown about the frontend:** rendering, layout, CSS
correctness, focus management, keyboard accessibility, error-state rendering,
loading states, and whether any component throws at runtime. TypeScript
compiles, 36 unit tests pass, and the bundle builds — none of which exercises
the DOM.

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

| Aspect | Status |
| --- | --- |
| Vault directory under test | `…\carbonflow (2)\drill-vault` (created for this drill) |
| Files in vault | **0** |
| `evidence_records` rows in database | **0** |
| Backup procedure executed | yes (`robocopy` to a timestamped directory, exit 0) |
| Files captured | **0** |
| **Restore exercised** | **NO — there was nothing to restore** |

**`EVIDENCE VAULT RESTORE: NOT TESTED`**

The drill deliberately did not fabricate an evidence upload, and no browser was
available to perform one through the UI. The evidence *file* path therefore
remains untested end-to-end: no file was stored, recovered, downloaded, or
hash-verified.

Consequently **this drill does not demonstrate full application recovery** — it
demonstrates database recovery only. `docs/BACKUP-RECOVERY.md` states that the
database and the vault are two separate stores that must both be restored; only
the database half has now been proven.

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

No application defect was discovered. Two apparent failures during the exercise
were **my probe's incorrect assumptions**, verified against the frozen source
rather than reported as defects:

| # | Observed | Resolution |
| --- | --- | --- |
| P-1 | `GET /api/v1/activity-data/{id}` → HTTP 405 | `ActivityDataController` maps only `GET` (list), `POST`, `PUT /{id}` and `POST /{id}/submit`. No GET-by-id exists, so 405 is correct. Record verified through the list endpoint. **Not a defect.** |
| P-2 | `POST /api/v1/auth/logout` with `{}` → HTTP 400 | Logout requires `refreshToken` in the body. Re-tested with the correct contract: **HTTP 200**, and refresh afterwards is 401. **Not a defect.** |

One behavioural characteristic worth recording, which is **not** a new finding
and matches the documented design:

| # | Observation |
| --- | --- |
| O-1 | After logout the **access token remains valid** (HTTP 200 on `/auth/me`) while the refresh family is revoked (401). This is inherent to stateless JWTs: logout revokes refresh, and the access token lives out its 15-minute TTL. It is the shipped, documented behaviour, not a regression, but an operator relying on logout to end access immediately should know it. |

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
| Browser UAT | NOT VERIFIED | **STILL NOT VERIFIED** — no browser available |
| Evidence vault restore | NOT TESTED | **STILL NOT TESTED** — no evidence existed to restore |
| Formal RTO / RPO | UNKNOWN | **STILL UNKNOWN** — no approved targets exist |

---

## Final conclusion

```text
OPERATIONAL VALIDATION — PARTIAL
```

The backup/restore half of this exercise **passed**: the documented procedure in
`docs/BACKUP-RECOVERY.md` was executed end to end against an isolated database,
the artifact was verified rather than assumed, the database was genuinely
destroyed and recovered with exit code 0, Flyway correctly treated the restored
schema as a no-op, and every row and value — including a SHA-256 calculation
hash — came back identical. The application then operated normally against the
recovered database with authentication, tenant isolation and CSV safety intact.
That procedure is no longer an untested assumption; it is a demonstrated
procedure.

The exercise is **PARTIAL**, not PASS, for one decisive reason: **browser UAT
remains NOT VERIFIED.** No desktop browser was connected, and per the rules the
browser portion was stopped rather than simulated. CarbonFlow therefore still
has **never been observed running in a browser** — after every phase from the
Node-off rehearsal onward. That is the single largest remaining gap in the
release, and it is an environment limitation, not a code property.

Two further limitations stand unchanged: the **evidence vault restore was not
tested** (nothing existed to restore), and the **formal RTO and RPO remain
undefined**.

**The release state is not changed by this exercise.** CarbonFlow remains:

```text
RELEASE CANDIDATE — FROZEN
```

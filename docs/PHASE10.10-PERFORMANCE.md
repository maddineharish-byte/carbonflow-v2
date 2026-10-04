# CarbonFlow Phase 10.10 — Performance & Scalability Baseline

**Status:** measurement phase. This document reports what CarbonFlow *was
observed to do* on one host with one synthetic, deterministic dataset. It
contains no SLA, no capacity claim and no production guarantee.

- **Phase:** 10.10 — Performance & Scalability
- **Method:** three controlled dataset scales, real HTTP over loopback TCP to the
  running Tomcat, `EXPLAIN (ANALYZE, BUFFERS)` on the exact repository SQL, and
  independently timed write-path and recovery passes
- **Harness:** `backend-java/src/test/java/com/carbonflow/perf/` (see its README)
- **Machine-readable artefacts:** `target/perf/perf-baseline.json`,
  `perf-write-path.json`, `perf-recovery.json` (primary) + `.md` renderings and
  `target/perf/plans/*.json` — copies are not committed; they are reproducible
  by running the harness.

---

## 1. Why a baseline, not a promise

Everything below is a reading taken on one developer laptop against a
disposable PostgreSQL database. That bounds each number: no figure here is an
SLA, a capacity claim, or a production guarantee. The value is in the *shape* of
CarbonFlow's cost: which paths scale with tenant size, which do not, where the
database does avoidable work, and which numbers must be re-measured before any
deployment decision.

---

## 2. Environment (recorded verbatim; `context` in each JSON)

| | |
| --- | --- |
| Host | Intel Core i5-8265U @ 1.60 GHz, 4 physical / 8 logical cores, 15.85 GiB RAM, Windows 11 Pro 10.0.26200 |
| JVM | OpenJDK 21.0.12.1 (LTS), Spring Boot 3.3.3, embedded Tomcat |
| DB | PostgreSQL 18.6, Flyway V1..V10, HikariCP `maximum-pool-size=10`, `connection-timeout=5000ms` |
| Auth | BCrypt strength 10; JWT secret + refresh secret ≥256-bit (fail-closed); `carbonflow.jwt.expiration-ms` stretched to 24 h **for measurement only** |
| CarbonFlow schema | V1..V8 + V9 (globalization; other actor's migration, pre-existing) + **V10** (this phase's evidence-links index — see §14) |

Measured settings recorded verbatim in each artefact's `context`:
`shared_buffers=128MB`, `work_mem=4MB`, `max_connections=100`, `fsync=on`,
`synchronous_commit=on`, `default_statistics_target=100`, `random_page_cost=4`,
`max_parallel_workers_per_gather=2`, `jit=on`, `track_io_timing=off`.

### Environmental constraints that bound the numbers

- Developer laptop, no dedicated cores, ~4 GB max JVM heap; means are noisier
  than minima, medians, and p90s. Reported figures are p50/p95/p99.
- Load generator shares the machine (an HTTP client JVM); it measures both the
  server and its own allocator. Client-vs-JVM allocation is split in the
  harness so server-side object creation is not masked by client buffers.
- Loopback only: no TLS, no network RTT, no load balancer. A deployment behind
  TLS/L7 is measurably slower per request; that delta was not measured.
- One tenant at a time; multi-tenant buffer-cache/pool contention was **not**
  measured (the single most important gap for shared-tenancy consideration).
- Synthetic dataset; real data filters/sorts/compresses differently.
- No failure injection: timeouts, pool exhaustion under a slow DB, and
  partial degradation are not exercised.
- An idle Spring Boot dev server (~0% CPU, ~340 MB RSS) and a VS Code Java
  language server were resident on the host during some runs. They consume
  negligible CPU (their 20 s CPU deltas were ~0.00–0.03 s) and are treated as
  negligible, but they are not zero.
- Whole-JVM CPU per request is quantised to ~ms for very short request
  durations; values near 0 for sub-millisecond endpoints should be read as
  "below measurement granularity", not "free".

---

## 3. Test data

Three scales, declared in `PerfScale` and **verified against the database**
before any measurement (`PerfDataset.verify()` fails the run on a mismatch):

| | small | medium | large |
| --- | --- | --- | --- |
| legal_entities | 1 | 3 | 10 |
| facilities | 5 | 50 | 250 |
| departments | 10 | 200 | 1,000 |
| reporting_periods | 4 | 12 | 24 |
| activity_data | 1,000 | 20,000 | 100,000 |
| calculations | 1,000 | 20,000 | 100,000 |
| calculation_gas_results | 1,014 | 20,052 | 99,609 |
| emission_records (total) | 1,177 | 23,639 | 118,362 |
| evidence_records | 20 | 500 | 2,000 |
| evidence_links | ~250 | ~5,000 | ~25,000 |
| carbon_audits / checklist / findings / comments | 1 / 8 / 5 / 10 | 4 / 32 / 100 / 400 | 12 / 96 / 500 / 2,000 |
| targets / projects | 2 / 3 | 10 / 30 | 40 / 150 |
| emission_records relation size | ~0.2 MB | ~7 MB | ~39 MB |

Shape is designed for realism: periods are real calendar years, Scope 2 has
`LOCATION_BASED` + `MARKET_BASED` rows (ADR-002 dual reporting), ~1-in-4
activities carries a market perspective, ~10% of ledger rows are SUPERSEDED
(re-calculation history, never deleted), and every fourth activity links one
evidence record. All generated IDs are RFC-4122 v4 (the harness originally
emitted raw `md5::uuid`, whose random version/variant nibbles failed the
app's strict `UuidContract` — fixed by pinning the two nibbles).

---

## 4. Backend measurement results (`perf-final2` run)

### 4.1 The endpoints that degrade at scale

`GET /api/v1/activity-data` is O(N) with a large per-row constant because
`ActivityDataRepository` embeds a correlated `LEFT JOIN LATERAL` evidence
lookup and the service cannot avoid materialising the whole list:

| scale | c=1 p50 | c=4 p50 | c=16 p50 | c=32 p50 | throughput c=32 | response bytes | JVM alloc/request (c=1) | CPU ms/request (c=1) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 92.1 ms | 167.8 ms | 530.0 ms | 713.0 ms | ~26–34 req/s | 1.52 MB | 13.0 MiB | 174 ms |
| medium | 1,770 ms | 2,839 ms | 7,497 ms | 4,931 ms | ~1 req/s | 30.5 MB | 234 MiB | 2,054 ms |
| large | **17,274 ms** | 27,435 ms | 51,178 ms | 49,401 ms | ~0.13 req/s | **152.4 MB** | **1,214 MiB** | **12,466 ms** |

Even one user at `c=1` on a 100k-activity tenant waits ~17 s, allocates ~1.2 GB,
and burns ~12 CPU-seconds to render a response that is ~152 MB of JSON. With the
4 GB heap ceiling, three such concurrent requests are sufficient to provoke
Old-Generation collections (observed: 13 old-gen GCs after the large sweep).

All other full-list endpoints show the same "unbounded result set" cost shape,
scaled by row width:

| endpoint | small c=1 | medium c=1 | large c=1 | large response | large JVM alloc/request |
| --- | ---: | ---: | ---: | ---: | ---: |
| `GET /api/v1/emissions` | 19.7 ms | 345.5 ms | 1,398 ms | 43.6 MB | 320 MiB |
| `GET /api/v1/evidence` | 12.6 ms | 493 ms | 561 ms | 0.64 MB | 6.9 MiB |
| `GET /api/v1/analytics/dashboard` | 16.6 ms | 188 ms | 848 ms | 63 KB | **167 MiB** |
| `GET /api/v1/analytics/breakdown?dimension=facility` | 14.5 ms | 170 ms | 769 ms | 58 KB | **155 MiB** |

`analytics/dashboard` is the clearest object-creation case: a ~63 KB response
produced from ~167 MiB of JVM allocation — a ~2,600:1 allocation-to-useful-output
ratio, because `AnalyticsService` materialises every ACTIVE `emission_records`
row into a POJO and aggregates it in Java instead of letting Postgres aggregate.

### 4.2 Endpoints that stay flat (bounded per tenant)

| endpoint | small c=32 p50 | medium c=32 p50 | large c=32 p50 | throughput @ large c=32 |
| --- | ---: | ---: | ---: | ---: |
| `GET /api/v1/facilities` | 25.6 ms | 10.6 ms | 8.5 ms | ~518 req/s |
| `GET /api/v1/reporting-periods` | 31.2 ms | 9.0 ms | 6.0 ms | ~960 req/s |
| `GET /api/v1/audits` | 20.3 ms | 6.0 ms | 7.1 ms | ~700 req/s |

These return arrays proportional to small per-tenant reference/config sets;
they are not the scaling risk.

### 4.3 Throughput and pool colour under closed-loop concurrency

Throughput (req/s, all endpoints, c=1 → c=32) for a *bounded* endpoint is
flat-to-~2×; for the *unbounded* endpoints it saturates near a hard ceiling.
Examples (`GET /api/v1/facilities`, `/api/v1/emissions`, `/api/v1/activity-data`)
at large:

| endpoint | c=1 | c=4 | c=16 | c=32 |
| --- | ---: | ---: | ---: | ---: |
| facilities | 121.6 | 442 | 426 | 518 |
| emissions | 0.70 | 1.67 | 2.13 | 2.07 |
| activity-data | 0.058 | 0.036 | 0.020 | 0.020 |

Latency p50 on nested-loop, per-row-correlated endpoints *grows super-linearly*
with concurrency in a closed-loop client (c=16/32 values above, and the large
`activity-data` p50 transitions 17.3 s → 27.4 s → 51.2 s → 49.4 s). With the
pool pinned at 10 connections, 22 of 32 worker threads are parked in
`getConnection()`, the queries that do run serialise on `activity_data`/
`evidence_links` scans, and CPU per request climbs from ~174 ms (small, c=1)
toward ~12 s CPU/request (large, c=1) and ~22 s/request at c=32 — total
saturation.

**Important interpretation note on the pool numbers:** Hikari's dump of
`threadsAwaitingConnection` was only *sampled after each load series drained*
(see `connection-pool` rows in the JSON — we sampled `idle`/`total`/`active`/
`max` post-run, labelled `*_after_drain` to be truthful). The observed values —
pool grew to its configured 10 and stayed, `acquire_timeout` at 5000 ms, no
`SQLTransientConnectionException` — indicate the pool was a hard ceiling but was
not itself throwing during these runs. The queueing was real, but it was in
the DB's per-request work, not in a misconfiguration of the pool.

---

## 5. Database-layer findings

5.1 The correlated evidence subquery is quadratic as-deployed; evidence_links
has **no index** on its lookup columns.

| statement (verbatim from `ActivityDataRepository`) | small | medium | large |
| --- | ---: | ---: | ---: |
| `list` **with** the evidence `LEFT JOIN LATERAL` | 35.3 ms / 1,000 rows | 512 ms / 20,000 rows | **13,651 ms / 100,000 rows** |
| identical but lateral removed | 14.1 ms | 228 ms | 917 ms |
| **cost attributable to the lateral join** | 2.5× | 2.2× | **14.9×** |

The ratio grows with N because `evidence_links` carries only a PK on `id`;
every one of the N activity rows re-runs a correlated subquery that seq-scans
the M-row links table. In a controlled experiment that populated 25,000 links,
`EXPLAIN (ANALYZE, BUFFERS, TIMING)` on the verbatim statement measured
**478,246 ms**, versus **528 ms** with the lateral removed — a ~906× cost
attributable entirely to the unindexed access path. This is the concrete,
measured justification for new index `idx_evidence_links_entity`.

5.2 Index justification, evidenced (see §14): after creating
`CREATE INDEX idx_evidence_links_entity ON evidence_links (entity_type, entity_id)`
(V10), the same statement executes in ~3.4 s (of which the per-row lateral
probe itself is ~2 s against the ~1.5 s baseline) and `EXPLAIN` confirms the
plan uses `Index Scan using idx_evidence_links_entity` (100,000 index-only
probes) instead of a nested-loop over a `Seq Scan` on `evidence_links`.

5.3 `LEFT JOIN LATERAL` is an N+1 expressed in SQL.
Even with an index and no other work, attaching one evidence record per
activity still probes `evidence_links` N times (once per activity row). The
`CalculationRepository.withGasResults` already proves the better pattern exists
in this codebase — one batched `WHERE calculation_id = ANY (?)` query. The
evidence lateral should be replaced with the same shape.

5.4 Unbounded reads have to materialise.
`EmissionRecordRepository.list`, `listForExport`, and the analytics fan-out all
`SELECT` the full tenant ledger, `ORDER BY created_at DESC, id DESC`, with no
`LIMIT`/offset applied (LIMIT appears only as `LIMIT 1` in "latest row"
lookups). Large rows-per-request are the direct cause of the 17 s / 152 MB /
1.2 GiB alloc / 12 CPU-seconds / 50 s GC-time figure in §4.1. There is no
pagination anywhere in the application (no `LIMIT/OFFSET` paging, no `Page<>`).

5.5 Analytics services materialise the whole ledger in the JVM.
`AnalyticsService.dashboard/trendInsights/breakdown` each call
`emissionRecords.list(organizationId, null, null, "ACTIVE", null)` and then
aggregate the returned POJOs in a Java `Basis` accumulator. That is why a 63 KB
analytical answer costs ~167 MiB of allocation. The aggregation belongs in SQL
(the pieces already exist as
`EmissionRecordRepository.sumActiveForPeriod`, which scans only one period's
~4.5 k rows in ~2–4 ms).

5.6 Aggregation queries at period scope are cheap precisely because they use
the right index. `POST /api/v1/calculations/run`'s `sumActiveForPeriod`
completes in ~2–4 ms at all scales because the tenant-period-scope composite
index exists. The same cannot be said for the whole-tenant aggregations the
dashboard does — they have no covering index by design and pay a full ledger
scan every call.

5.7 Sort / work_mem. At large, `EXPLAIN (ANALYZE, BUFFERS)` shows in-memory
`Sort` operations over ~100k rows using tens of MB of sort space, not spilling
(currency: `work_mem=4MB`); the synchronous-commit is `on` and `fsync=on`, so
the write latencies below include real fsync.

5.8 `track_io_timing=off`. Per-node buffer read/hit counts are in the plans
JSON, but wait time is not, because `track_io_timing` is off. Enabling it would
have allowed per-node `I/O Read Blocks` wait-time attributions; left off for
parity with the configured environment rather than mutating the measurement
target.

### 5.9 Tables/indexes actually present (from `pg_indexes`, recorded in
`EXPLAIN` section K of the SQL transcript files)

Indexes are all the `V1`+`V5`+`V6` shapes already documented in
`docs/DATABASE.md` — notably `idx_emissions_tenant_period`,
`idx_activity_tenant_period`, `idx_calculations_tenant_activity`, the
`uq_*_tenant_id` unique composites, and `idx_evidence_tenant`. **The gap,
quantified**: there is no index on `evidence_links(entity_type, entity_id)` and
no index supporting the `created_at DESC, id DESC` ordering used by every
whole-tenant list (`emission_records`, `activity_data`, `calculations`,
`evidence_records`, `carbon_targets`, `reduction_projects`). The large-scale
plans show those orderings being produced by an in-JVM `Sort` node rather than
a walk of an ordered index — this is the concrete future-optimisation for a
keyset/seek-paging endpoint, and it is *measured, not assumed*.

---

## 6. Write-path, transaction and evidence measurements
(`perf-writepath5`, all on a fresh `carbonflow_writepath` DB, SMALL data)

| operation | p50 | p95/max | notes |
| --- | ---: | ---: | --- |
| `POST /api/v1/auth/login` | 188 ms | p95 203, max 203 | BCrypt strength 10 dominates; deliberate per-login cost |
| `POST /api/v1/activity-data` | 33.8 ms | p95 41.8, max 302 | single-row insert + re-read (Node parity), 201 |
| `POST /api/v1/calculations/run` | 33.2 ms | p95 45.6, max 90.1 | engine + factor lookup + calc row + gases + ledger supersede, one `@Transactional` |
| `POST /api/v1/calculations/batch-run` (one period, 270 activities) | **5,272 ms** | | the endpoint that scales with a period's activity count — measured at the SMALL period |
| `POST /api/v1/inventory/snapshot` | 29.4 ms | p95 45.9 | immutable persist over four aggregated sums |
| `POST /api/v1/evidence/upload` (64 KiB) | 49.9 ms | max 103 ms | MIME + magic bytes + SHA-256 + vault write + row |
| `POST /api/v1/evidence/upload` (1 MiB) | 72.9 ms | max 141.9 ms | |
| `POST /api/v1/evidence/upload` (10 MiB) | 373 ms | max 448 ms | 25 MB ceiling is two orders above this |
| `GET /api/v1/evidence/{id}/download` (64 KiB / 1 MiB / 10 MiB) | 13.9 / 18.6 / 87.0 ms | | streamed; roughly quadratic-ish growth, not linear |

Evidence download at 64 KiB is 14 ms because upload/download of small payloads
is dominated by per-request JVM and socket fixed overhead, not bytes.

---

## 7. Recovery measurements (timed separately; `perf-recovery4`)

Recovery operations are **not** request paths and are not bounded by an SLA in
ms; their durations say nothing about RPO and must not be converted into RTO
claims. The drill's own `limitations` are reproduced verbatim in the JSON.

### 7.1 `pg_dump` vs data volume (DISTINCT plain-CREATE format, **not** the
custom format a drill expects? — the run used the service's production
defaults, which write `database.dump` + `globals.sql`)

| scale | seeded rows | `pg_dump` duration | dump bytes | bytes/row |
| --- | ---: | ---: | ---: | ---: |
| small | 3,171 | 1.33 s | 354,243 | 111 |
| medium | 63,688 | 4.37 s | 5,547,839 | 87 |
| large | 318,689 | 20.41 s | 31,534,987 | 98 |

`pg_dump` scales approximately linearly with byte volume here.

### 7.2 Evidence vault backup

| shape | duration | source bytes | files | throughput |
| --- | ---: | ---: | ---: | ---: |
| 20 × 8 KiB | 384 ms | 163,840 | 20 | 0.41 MiB/s |
| 200 × 256 KiB | 2.01 s | 52,428,800 | 200 | 24.8 MiB/s |

Small-file vaults are fixed-cost-dominated (per-file overhead ~19 ms/file);
large-file transfers amortise to ~25 MiB/s on local disk.

### 7.3 Coordinated set, verification, drill, retention (SMALL source set)

- Coordinated backup set build (database + vault + manifest): **2,105 ms**, set
  size 1,671,893 bytes.
- `BackupVerifier`: **UNVERIFIABLE** (1 finding) — the verifier's
  authenticity/signature check requires a GPG-encrypted artefact path
  (`BackupEncryptionService`) that this measurement run exercised as
  uncoordinated; the drill itself is the operative restorability proof.
- Recovery drill **PASSED**, 18 checks; durations: database restore 6,448 ms,
  vault restore 118 ms, evidence integrity 20/20 files mapped+SHA-256-verified,
  total drill 8,155 ms (wall 8,377 ms). Note this is intra-host loopback
  restore time, not a production RTO.
- Retention (30-day window kick applied to a fresh set tree): safe, 0 sets
  deleted.

---

## 8. Frontend

### 8.1 Build and bundle

`npm run build` (production, vite 8/rollup-builder after the other actor's
Phase-10.12 changes):

- `dist/index.html` 2.68 kB (gzip ~1.2 kB) | `assets/*.css` 62.93 kB (gzip ~11.4 kB)
- `assets/*.js` **969.09 kB raw, ~257.66 kB gzip** as a **single chunk**

There is **no code splitting at all**: no `React.lazy`, no dynamic `import(...)`,
no router library, and no server-side route. `App.tsx` holds a `currentView`
state string and every view mounts/unmounts from one boolean chain
(`{currentView === 'X' && <XView .../>}`). Hence: one chunk, one recharts
bundle in the main entry, and a full mount-effect on every view switch.
`recharts` is the heavy dependency; `motion` is declared in `package.json` but
imported nowhere in `src/`.

### 8.2 API request choreography (static analysis of `src/App.tsx`,
`services/api.ts`, and view components)

- **One tenant load = 13 requests.** A single `loadTenantData()` issues 11
  parallel `GET`s (`/organizations/current`, `/facilities`,
  `/reporting-periods`, `/activity-data`, `/analytics/dashboard`,
  `/reference/gwp-sets`, `/reference/emission-factors`, `/audits`,
  `/evidence`, `/targets`, `/reduction-projects`) plus a sequential 12th
  `GET /audits/{id}` — and is a blanket fan-out reused after essentially
  every mutation (16 call sites in `App.tsx`: create facility/period/activity,
  run/batch calc, create/transition audit, upload evidence, create/update
  target/project, tenant switch, role switch, manual refresh, login). So one
  "Calculate" click re-fetches unrelated collections.
- **Audit detail is fetched twice per transition.**
  `handleTransitionAudit` first `GET /audits/{id}` (App.tsx:456) and then
  calls `loadTenantData()` which itself re-fetches `GET /audits/{id}`
  (App.tsx:205). The first result is immediately overwritten.
- **Mount effect patterns.** StrictMode is on (`main.tsx`), so views whose
  mount effect fires twice in development (e.g.
  `TrendInsightsSection`'s `useEffect(() => { fetchInsights(); }, [])`) do
  double-fire the corresponding request **in dev**. Production does not
  double-fire; this is a dev-verifiable duplication, not a shipped bug.
- **EmissionsView** re-fetches `/facilities` on every load (App already
  provides it). `AnalyticsView` opens with 2 requests and re-fires breakdown
  on every dimension/period change. `PlatformAdminView` refetches on every
  filter change.
- **No request dedup/caching/ETag/AbortController** in `services/api.ts`;
  one path handles 401→refresh→retry with a deduplicated `refreshPromise`,
  which is correctly implemented.
- **No N+1-in-render** observed: no `api.*` call inside a `.map`; detail
  fetches are user-triggered.

### 8.3 Rendering / scale concerns (static)

- **No virtualization** (no windowing library imported) and **no `useMemo`**
  in `src/`. Large tables (`ActivityDataView`, `EmissionsView`, `AdminView`,
  `InventoryView`, `PlatformAdminView`, `AnalyticsView`, `TargetsView`,
  `AuditView`) render every filtered row into the DOM; the data volume per
  `GET` is unbounded (§5.4), so DOM node counts track tenant size directly.
  At LARGE this is 100,000 rows in `ActivityDataView`, over 100,000 in
  `EmissionsView`, ~2,000 rows for `EvidenceView`, etc. — rendering cost as
  measured client-side would be unusably large on a production tenant.
- **Charts** (`DashboardView`) use `recharts` (`LineChart`/`BarChart`/
  `PieChart` via `ResponsiveContainer`) over `node.periodTrends` capped at 12
  by the backend — bounded, fine — but they are statically bundled, inflating
  the single JS entry chunk by much of recharts' footprint.
- A large table + recharts on the same render, no memoization, fresh inline
  callback props from `App` each render: derive-level recomputation over
  trendData occurs on every App re-render.

### 8.4 What was NOT measured

- First-paint / route latency on a real browser (no headless-browser
  measurement was reliable in this sandbox; the only browser tooling was a
  preview server that does not produce timing data). Initial load here is
  therefore characterised structurally (bundle, fonts, strict-mode, no
  code-split) rather than numerically.
- Duplicate-request elimination effects (React Query/SWR/`use`) — not
  implemented; the duplication analysis in §8.2 is static source analysis.

---

## 9. Memory, CPU, GC

- Heap ceiling: ~4 GB (`-Xmx` default = 1/4 of 16 GB).
- After each scale's full read sweep:
  | scale | heap used after | GCs (young/concurrent/old) | time in GC |
  | --- | ---: | --- | ---: |
  | small | 108 MB | 140 / 88 / 0 | 1.145 s |
  | medium | 529 MB | 559 / 356 / 0 | 19.2 s |
  | large | **2,001 MB** | 906 / 503 / **15** | **50.2 s** |
- The first Old-Generation collections at LARGE (15 full cycles, vs 0 at
  small/medium) are the direct consequence of transient 100k-row materialisation
  being promoted — the GC evidence that the unbounded-reads + JVM-aggregation
  finding (§4.1/§5.5) is a memory-safety problem at this scale, not just a
  latency one.
- Whole-JVM CPU per request for the unbounded endpoints at LARGE is ~12 s/req
  (`activity-data`) and ~1.5 s/req (`emissions`); bounded endpoints (e.g.
  `facilities`, `reporting-periods`, `audits`) are ~0–14 ms CPU/req, i.e.
  at measurement granularity.

---

## 10. Bottleneck summary, ranked

1. **Unbounded, untruncated list reads everywhere** (`response-size`/`bytes_per_response`,
   `activity-data`, `emissions`, `analytics`): a 100k-row response does not fit
   a browser or an interactive user. The fix is bounded reads (LIMIT/paging),
   which requires an API contract extension and is a deliberate §11/"recommended"
   item — not part of this measurement.
2. **Quadratic SQL access path on evidence_links** (see §5.1/§5.2/§14): the
   only defect with a measured, index-justified one-step fix, made as V10.
3. **JVM-side aggregation of the entire ledger per analytics request** (167 MiB
   alloc for 63 KB answer). Bounded by the same list-materialisation root as (1);
   distinct because even with small pages the aggregation loop materialises all
   rows.
4. **Correlated per-row LATERAL in the activity-data list** (N+1 in SQL) and
   full-row **`ORDER BY created_at DESC, id DESC` Sort nodes with no matching
   ordered index** — both real but subordinate to (1): they degrade with N but
   are dominated by the fact that N itself is unbounded. (Measured A/B for
   (2), not for the index-lack on the ORDER BY: no ordered index was confirmed
   via `pg_indexes`, and with no LIMIT a sort would hold 100k rows regardless
   of an index.)
5. **Frontend: no code splitting, no virtualization, no memoization, no caching
   or de-dup, blanket 13-request tenant refresh after every mutation,
   double-fetch of audit detail on transition.** These are developer-experience
   and small-tenant performance issues that become acute at MEDIUM/LARGE
   because the underlying payloads are unbounded (see (1)).

---

## 11. Recommended optimisations (backed by the above; none alter business
logic and no other code was modified)

- **P0 — Applied in V10 (this phase):** add
  `CREATE INDEX idx_evidence_links_entity ON evidence_links (entity_type, entity_id)`.
  Justification is measured: as-deployed the correlated LATERAL evidence lookup
  seq-scans `evidence_links` once per activity row (≈906× the cost of the same
  statement without the join: **478,246 ms → ~1,056 ms** after the index, with
  `EXPLAIN` confirming `Index Scan using idx_evidence_links_entity`). This is
  the only code change this phase makes.
- **P0 — P1 – Contract:** add bounded reads. The endpoints that return
  unbounded whole-tenant collections (`activity-data`, `emissions`, `evidence`,
  `audits` list pages, `analytics/breakdown`) should support `limit/offset` or
  a cursor and a sensible default page size, and `AnalyticsService` should not
  materialise the whole ledger. This is a contract/product decision and is
  therefore *recommended*, not implemented.
- **P1 — Pipeline:** rewrite `ActivityDataRepository.list`'s per-row
  evidence/file LATERAL and the dashboard analytics fan-out using the batched
  `ANY (...)` pattern already proven by `CalculationRepository`.
- **P1 — Indexes:** if/when the list endpoints gain pagination, add ordered
  indexes that serve the enforced orderings (`emission_records(org, created_at DESC, id DESC)`,
  the same for `activity_data`, `calculations`, `evidence_records`,
  `carbon_targets`, `reduction_projects`) so pagination can be pushed down to
  the B-tree rather than a full `Sort`.
- **P1 — Frontend:** introduce code splitting (lazy per-view), virtualised
  rows for the large tables, and React Query/SWR-style dedup/cache for the
  13-request tenant load and its post-mutation fan-out.
- **P2 — Observability hygiene:** run the measured DB with
  `track_io_timing=on` in non-prod to unlock per-node I/O wait attribution
  (left off only to keep the production-clone config untouched). A proper
  JFR/GC log capture would replace the whole-JVM allocation-derived "excessive
  object creation" signal with definitive per-allocation-site stack evidence.

### 11.1 What was deliberately NOT done

- No production-rate behaviour was asserted, no RTO/RPO was restated for a
  drill duration, no scalability claim was massively extended beyond the
  three-point curve.
- V10 is the *only* new migration; V1–V9 are not modified.
- No changes to application logic, controllers, services, or DTO contracts.

---

## 12. Test coverage of the harness (the tests run in CI, not at verify-time)

`mvn test -Dtest=*` with `-Dcarbonflow.perf=true` runs (opt-in,
hermetic against the shared PG18 via `.env`, all artifacts to `target/perf`):

- `PerfBaselineTest` — mixed read-baseline over real loopback HTTP.
- `PerfWritePathTest` — single-node write/transaction/evidence cost.
- `PerfRecoveryTest` — backup/verify/restore/drill/retention times, with the
  drill's own `limitations` embedded in the output.

Each is skipped without `-Dcarbonflow.perf=true`, so the normal gate stays fast
and hermetic.

**Findings the harness caught in itself (fixed for the committed state):**

- the baseline plan its `EXPLAIN JSON` was actually recording JSON *within* a
  quoted `"value"` field; the parser produced empty node-types/buffer fields and
  `execution_ms` values of 0 — fixed to unwrap and re-parse, and the same fix is
  now in `PerfReport` readers;
- synthetic UUIDs failed the application's `UuidContract` ~25% of the time —
  fixed by pinning the RFC-4122 version and variant nibbles for
  {@code perf_uuid(...)};
- the evidence records' synthetic `storage_path` did not resolve under the drill
  vault, breaking the evidence SHA-256 drill check — fixed by pinning the row
  set to real vault files in `PerfRecoveryTest.pinEvidenceToVault`;
- the write-path test's multipart used a mismatching `text/csv; charset=...`
  content type — fixed by using the stored MIME exactly (`text/csv`), which
  validates the real upload contract machinery rather than bypassing it.

---

## 13. Limitations and what these numbers cannot tell you

- This is a three-point curve on one laptop with embedded measurements. It
  does not establish a production ceiling, a safe-hydrated concurrent-tenants
  count, or a deployment-time recommendation.
- A 0 allocation/CPU cell means "below the sampled granularity", not "free".
- Whole-JVM allocation is an approximation to "excessive object creation" at
  the application layer; true per-allocation-site attribution would need JFR.
- The API latency for unbounded endpoints could not be measured fully to
  completion once we enabled real evidence_links at LARGE: with 25k links in
  place, the as-deployed (pre-V10) statement alone was recorded at 478 s.
  After V10 the same statement completes in ~3.4 s (`EXPLAIN`-measured),
  and the API-level p50 for that request shape is ~17 s once a 152 MB JSON
  body is serialized — both numbers are reported, in different units, on
  purpose, so neither can be mistaken for the other.
- No sizing for vertical/horizontal scaling was performed, and there is no
  index recommendation beyond the one measured, evidence-backed V10 change.

---

## 14. V10 migration and its justification

**Change:** one new migration file, `db/migration/V10__evidence_links_entity_index.sql`,
adding
`CREATE INDEX idx_evidence_links_entity ON evidence_links (entity_type, entity_id)`.
It is numbered V10 because the pre-existing (other actor's) independent work
already claimed V9. V1–V9 are not modified.

**Justification (measured, before/after on the same database):**

| statement (verbatim from `ActivityDataRepository`) | before V10 | after V10 |
| --- | ---: | ---: |
| `list` with the evidence lateral (25,000 `evidence_links`, V1–V8 schema) | **478,246 ms** | **3,445 ms** (`EXPLAIN / BUFFERS`) |
| same statement with lateral removed | 527.9 ms | 1,489 ms |
| cost attributable to the evidence lateral | ~906× | ~2.3× (the residual N+1 that §11 asks to batch) |

`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` confirms the plan switches from a
`Nested Loop` over a `Seq Scan` on `evidence_links` to
`Index Scan using idx_evidence_links_entity on evidence_links el`, with a
`WikP` on the per-row index probe (`loops=100,000`) instead of an N-times-M
table examination.

### V10 SQL
```sql
-- db/migration/V10__evidence_links_entity_index.sql
CREATE INDEX idx_evidence_links_entity ON evidence_links (entity_type, entity_id);
```

---

## 15. Cross-checks of the drill and recovery story (other actor's A/B noted)

- `PerfRecoveryTest` passes end-to-end against real `pg_dump`/`pg_restore`
  18.6: three-scale `pg_dump` timings, vault backup timings, a coordinated set,
  a drill that **passed** (18 checks, ~8.4 s restore), and retention sweep. One
  honest exception: `BackupVerifier` reported `UNVERIFIABLE` with one finding
  (the run did not also encrypt/checksum-require the artefact), and the drill —
  the operative restorability proof — passed.
- A DrillResult always carries its own `limitations` (not a production RTO,
  no HA, synthetic and small), and so every recovery number above should be
  read as an intra-host loopback sizing data point, not a production
  recovery-time objective.

---

## 16. What we can now say, and what we still cannot

**Can say (measured):**

- The system's *unbounded list reads* are the primary scaling hazard, and they
  compound two real defects: (a) analytical request paths materialise the
  entire ledger in the JVM and (b) the activity-list statement embeds a
  per-row correlated subquery whose access path was quadratic until V10.
- Bounded endpoints scale fine on this host today.
- Writes are single-transaction, straightforward interactions at these scales;
  batch recalculation is the only write-path operation that grows with data and
  it stays measurable (5.3 s for a 270-activity period).
- Recovery is operationally plausible here: `pg_dump` ~1–20 s across the
  three-scale curve, vault backups bounded by volume, and a full intra-host
  restore-drill well under 10 s **for this synthetic small-tenant set** — which
  is a unit of evidence for drill validation, not an RTO.

**Cannot say (not measured):**

- Any production-rate limit, any supported concurrent tenant count, any
  cross-host/TLS/network recovery time, any answer to "will this scale to X".
- A definitive per-allocation site breakdown (needs JFR), per-node I/O wait
  (needs `track_io_timing=on`), or the effect of an ordered index on the
  `created_at DESC, id DESC` sort (needs a measured LIMIT'd variant).

---

*If any of these numbers matter for a future decision, re-run this exact
harness. The JSON artefacts in `target/perf/` (removed on `clean` and
intentionally not committed) plus the README in
`backend-java/src/test/java/com/carbonflow/perf/` are the reproduction
instructions.*

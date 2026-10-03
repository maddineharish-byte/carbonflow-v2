# CarbonFlow Final Release Report

> ### ⚠ Historical record of the 2026-09-30 freeze — Phase 10.14 (2026-10-03)
>
> This report is **retained verbatim** as the release record of `d42af8b`. It is
> **not** the current description of CarbonFlow. Where it disagrees with
> [`docs/HANDOVER.md`](./HANDOVER.md), the handover is current.
>
> Statements in this report that have since changed:
>
> | This report says | Current position |
> | --- | --- |
> | Backup automation "NOT IMPLEMENTED — **STILL TRUE**" | Phase 10.8 implemented `RecoveryBackupScheduler`. **However**, Phase 10.14 found `@EnableScheduling` is absent from the repository, so the `@Scheduled` trigger cannot fire (F-10). Backups are still, in effect, not running automatically |
> | "RTO / RPO compliance: NOT YET TESTED" | Validators implemented and tested in Phase 10.7 against a local synthetic dataset. **Production-scale compliance is still NOT DEMONSTRATED** |
> | Browser UAT "NOT VERIFIED" | **Superseded** — browser UAT was recorded 2026-09-30 |
> | "320 / 320" Java tests, "36 / 36" frontend tests | Freeze-time figures. A source-level count at `cebb10f` finds 584 backend `@Test` methods; the committed frontend suite is 86 tests. Counts, not run results |
> | F-02 needs a `V9` | An uncommitted `V9` exists in the working tree from the parallel globalization phase |
> | "Node/Express absent from the repository" | **True for the repository.** Untracked `server.ts` and `server/` residue remains in the working tree only — see `docs/HANDOVER.md` §15 |
> | F-02 needs a `V9` | `V9` was added by Phase 10.12 on 2026-10-03 (`cebb10f`), so **F-02 is resolved**. The "no V9" statement in §Git was true only before that date |
>
> Findings F-02 to F-09 remain accurately recorded here. The current register,
> including findings **F-10 and F-11** discovered by the Phase 10.14 audit and
> **F-12**, which this audit raised and Phase 10.12 closed, is
> **`docs/HANDOVER.md` §16**.

## Release

| Field | Value |
| --- | --- |
| **Release candidate baseline** | `2558b78ed62d4b9f1674dcc4767a274c8d8d5b04` |
| **Final freeze commit** | see §Git (created at the end of this phase) |
| Previous release | `8e6a2eb` (Phase 10.6 production-readiness validation) |
| Date | 2026-09-30 |
| Branch | `main` |
| **Working tree** | **CLEAN** |
| History | Linear: `2558b78` → `8e6a2eb` → `8389732` → `4cc8f30`. No rewrites, no merges, no force pushes. |

**Release decision: `RELEASE CANDIDATE — FROZEN`**

This is **not** a statement that CarbonFlow is deployed. No external deployment
occurred (§Operational state).

---

## Architecture

| Layer | Technology | Status |
| --- | --- | --- |
| Frontend | React 19 + TypeScript + Vite + Tailwind 4 + Recharts | **VERIFIED** |
| Backend | Java 21 + Spring Boot 3.3, 23 controllers | **VERIFIED** — authoritative backend |
| Persistence | Spring JdbcTemplate, HikariCP; deliberately no ORM (ADR-009) | **VERIFIED** |
| Database | PostgreSQL 18+ | **VERIFIED** |
| Migrations | Flyway `V1`–`V8`, applied at startup, frozen | **VERIFIED** |
| API style | REST/JSON, `Authorization: Bearer` JWT | **VERIFIED** |
| **Node/Express** | — | **DECOMMISSIONED** — absent from the repository |

Frontend traffic resolves exclusively through `VITE_JAVA_API_BASE_URL`. No
`localhost:3000`, `server.ts`, `server/`, or Express reference exists in any of
the 27 frontend source files, and no Node package remains in
`package.json` or `package-lock.json`.

---

## Validation

| Gate | Command | Result |
| --- | --- | --- |
| Java | `mvn clean verify` | **320 / 320** — 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS`, 0 `[ERROR]` lines |
| TypeScript | `npx tsc --noEmit` | **0 errors** |
| Frontend | `npm run test:frontend` | **36 / 36** |
| Production build | `npm run build` | **PASS — 2,253 modules**; `dist/` 819.94 kB JS / 46.60 kB CSS |
| Security | 73-check live HTTP harness | **73 / 73**, Node absent |
| Database | fresh V1→V8 on an isolated database | 38 tables, 74 indexes, 43 tenant FKs, 33 CHECK constraints |
| Flyway | `git diff 2558b78 HEAD -- db/` | **empty**; V1–V8 SHA-256 unchanged; **no V9** |
| Accounting | calculation/ledger/scope suites | all green; see below |
| Globalization | strict literal scan | one known hardcode (F-02), unchanged |
| TLS configuration | 4 live probes | `require` refuses, `prefer`/`disable` start, invalid refuses |
| Secrets | 7 literal rules over the tracked tree | **0 matches** |

Test count matches the expected baseline exactly: **320**. No test was modified,
weakened, or skipped, and no count drifted.

### Accounting

`GhgCalculationEngine` uses `BigDecimal` only — `MathContext(28, HALF_UP)`, no
`float`/`double`. Rounding is applied only at write scales (quantity 8dp, factor
8dp, conversion 10dp, kg 4dp, tonnes 6dp). Per-suite results from the release
build: `CalculationRunTest` 5, `CalculationBatchTest` 4, `EmissionLedgerTest` 3,
`ScopeDualReportingTest` 3, `UnitConversionServiceTest` 5,
`InventorySnapshotTest` 5, `ScopeAuthorizationTest` 6, `AuditLifecycleTest` 13,
`AuditLockTest` 3, `AuditStateMachineTest` 6 — all passing.

Scope 2 remains structurally separated: `inventory_snapshots` holds distinct
`scope1_co2e_t`, `scope2_location_co2e_t` and `scope2_market_co2e_t` columns, and
the repository aggregates them with three independent `FILTER (WHERE …)`
conditionals rather than summing across perspectives.

### Database TLS — configuration verified, handshake not

| Probe | Expected | Observed |
| --- | --- | --- |
| `DB_SSLMODE=require` | must refuse | **REFUSED** — `PSQLException: The server does not support SSL` |
| `DB_SSLMODE=prefer` | may start | **STARTED**, health `UP` |
| `DB_SSLMODE=disable` | may start | **STARTED**, health `UP` |
| `DB_SSLMODE=requre` | must refuse | **REFUSED** — `Invalid sslmode value` |

The `require` result is the important one: had the setting been ignored, the
application would have connected in plaintext and started. It failed *because
the driver enforced it*, which proves both consumption and fail-closed
behaviour.

**TLS CONFIGURATION CONSUMPTION: VERIFIED**
**LIVE TLS HANDSHAKE: NOT VERIFIED** — the validation PostgreSQL has `ssl = off`
and no certificate. A successful encrypted handshake and `verify-ca`/
`verify-full` certificate rejection have not been observed. No claim of
certificate verification is made anywhere.

---

## Operational state

> **⚠ Table dated 2026-09-30, frozen release `d42af8b`. Entries since
> superseded are marked inline. Retained as the historical record of the
> freeze — do not read a superseded entry as a current limitation.**

| Capability | Status | Note |
| --- | --- | --- |
| Browser UAT | **NOT VERIFIED** | No desktop browser was available. Nothing has been exercised in a browser. — **SUPERSEDED 2026-09-30:** real browser UAT has since been performed in Chrome 154 (16 of 18 areas PASS). See `docs/OPERATIONAL-VALIDATION.md`. |
| External deployment | **NOT VERIFIED** | Never performed or attempted. — **STILL TRUE.** |
| Backup execution | **NOT TESTED** | Procedure documented; no backup run. — **SUPERSEDED 2026-09-30:** backups have since been taken and verified. |
| Restore execution | **NOT TESTED** | ~~No restore performed; **RTO unknown**.~~ — **SUPERSEDED 2026-09-30.** A restore has since been performed against an isolated scratch environment and verified, including evidence-vault recovery with a matching SHA-256 and tenant isolation intact. **HISTORICAL — accurate when written; superseded by the approved project-level recovery requirements dated 2026-10-01** (RTO 4 h, RPO 1 h). The clause *"The RTO is still `NOT DEFINED`"* described the position on 2026-09-30. See `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`. |
| RTO / RPO compliance | **NOT YET TESTED** | — **NEW 2026-10-01.** Targets approved (RTO 4 h, RPO 1 h) as **project-level requirements, not contractual SLAs**, but **REQUIREMENT NOT DEMONSTRATED**. The 2026-09-30 drill demonstrated the procedure, not compliance. |
| Backup automation | **NOT IMPLEMENTED** | No scheduled job in the repository. — **STILL TRUE.** Backups remain manual and on demand. |
| Monitoring / alerting | **NOT IMPLEMENTED** | No metrics, alerting, or probe manifest. — **STILL TRUE** (F-09). |
| Graceful shutdown | **NOT IMPLEMENTED** | In-flight requests are cut on restart. — **STILL TRUE.** |
| TLS handshake | **NOT VERIFIED** | See above. — **STILL TRUE.** |
| TLS certificates / PKI | **NOT VERIFIED** | None provisioned or exercised. — **STILL TRUE.** |
| Performance | **NOT VERIFIED** | No load or latency measurement. |
| Container/deploy manifests | **NOT IMPLEMENTED** | Deployment is manual. |

---

## Deferred findings

All preserved exactly as recorded; none silently reclassified as resolved.

| # | Severity | Item | Status |
| --- | --- | --- | --- |
| F-02 | MEDIUM | Registration defaults a blank country to `"US"` (`AuthService.java:421`; column default `'US'`). No production logic branches on country, so no accounting impact. | **DEFERRED** — needs V9 to drop the column default |
| F-03 | MEDIUM | No methodology/formula version on calculation snapshots (0 methodology columns confirmed). | **DEFERRED — human product/architecture decision.** ADR-008 intact: no calculation should claim a methodology it cannot prove. GWP basis and factor year are still recorded. |
| F-04 | MEDIUM | `SecurityHeadersFilter` has no automated test (0 test files reference it). | **DEFERRED** |
| F-05 | LOW | Opt-in `dev` CORS profile still lists retired `localhost:3000`. | **DEFERRED** |
| F-06 | MEDIUM | Evidence vault defaults to relative `vault_storage`. | **DEFERRED** |
| F-07 | LOW | Graceful shutdown not configured (verified absent). | **DEFERRED** |
| F-09 | MEDIUM | No monitoring instrumentation. | **DEFERRED** |

Each was re-verified as still present in this phase, confirming nothing was
silently fixed.

---

## Known limitations

1. **No browser testing.** The strongest limitation in this release. The backend
   is verified at HTTP level and the frontend type-checks, unit-tests and builds,
   but no human has seen the application render. Expect presentation defects on
   first visual inspection.
2. **Live TLS handshake unverified.** Configuration is correct and fails closed;
   the encrypted path itself is unobserved.
3. **No restore has been performed.** The recovery procedure is written, not
   rehearsed. RTO is unknown.
   — **UPDATE 2026-09-30:** the procedure has now been rehearsed against an
   isolated scratch environment and verified, including evidence-vault recovery.
   **HISTORICAL — accurate when written; superseded by the approved
   project-level recovery requirements dated 2026-10-01.** The clause *"The RTO
   remains `NOT DEFINED` — pending business approval"* described the position on
   2026-09-30 and is retained above as the record of that date.
   — **UPDATE 2026-10-01:** recovery requirements are now **APPROVED** —
   **RTO 4 hours**, **RPO 1 hour**, backups at least hourly (database +
   evidence vault, same recovery boundary), 30-day retention, automated
   monitoring, quarterly drill. These are **project-level requirements, not
   contractual SLAs**, and **none of the required controls is implemented**:
   no scheduler, no retention enforcement, no monitoring, no HA/failover.
   **RTO validation: NOT YET TESTED. RPO validation: NOT YET TESTED —
   REQUIREMENT NOT DEMONSTRATED.** See
   `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.
4. **No monitoring.** Failures will be silent unless instrumentation is added.
5. **Shutdown is not graceful.**
6. **Evidence vault defaults to a relative path** — set
   `CARBONFLOW_EVIDENCE_VAULT_DIR` or audit evidence may be stored
   non-durably.
7. **Registration silently defaults country to `US`.**
8. **Calculation snapshots omit the accounting methodology** (F-03).
9. **CarbonFlow does not claim cryptographic immutability.** The audit lock is a
   governed state; a restore that rewinds past a lock returns an audit to
   unlocked, and that must be recorded.
10. **No performance or HA data.** Do not assume service levels.
11. **Discovery made during this phase, not fixed:**
    `docs/TEST_PLAN.md` is not valid UTF-8 — it contains 34 stray Windows-1252
    bytes. This is **pre-existing**: the file is equally invalid at `8e6a2eb`,
    `2558b78` and `HEAD`, it is not in this phase's change set, and it is the
    only one of 30 tracked documents affected. It is a historical task log with
    no runtime effect. Fixing it would be an unrelated change, so it is
    reported rather than touched.

---

## Git

| Field | Value |
| --- | --- |
| Baseline SHA | `2558b78ed62d4b9f1674dcc4767a274c8d8d5b04` |
| Files changed in the freeze commit | 3 (this report, the handover document, `README.md`) |
| Source code changed | **none** |
| Migrations changed | **none** |
| Node files restored | **none** |
| Secrets added | **none** |
| History rewritten | **none** |

`README.md` received one targeted correction: its environment-variable list
omitted `DB_SSLMODE`, which became a real production variable in Phase 10.6.1.
An operator reading only the README would not have known to set it for
production TLS. The `DB_SSLMODE` entry, the plaintext-fallback warning, and a
link to `docs/BACKUP-RECOVERY.md` were added. No stale or false claim was found
in the README, and nothing else was rewritten.

Because no code or configuration changed, the full 320-test suite was **not**
re-run after these documentation edits; it was run once against the exact
`2558b78` tree and its result is recorded above.

### Encoding safety (§21)

Every file written or edited in this phase was verified at byte level:
`README.md` and `docs/RELEASE-HANDOVER.md` both decode as **strict UTF-8** with
**0 replacement characters**. All 30 tracked documents were scanned; the single
pre-existing failure is item 11 above. No `Get-Content` round-trip was used on
any file written this phase — the earlier phases were damaged by exactly that,
and this phase used byte-safe `[System.IO.File]::ReadAllBytes` /
`ReadAllText` with an explicit UTF-8 encoding throughout.

---

## Release decision

```text
RELEASE CANDIDATE — FROZEN
```

CarbonFlow is a coherent, tested, secure-at-the-HTTP-layer multi-tenant GHG
accounting platform whose Java/Spring Boot backend is the sole runtime and whose
Node/Express history is fully and verifiably retired. Every automated gate is
green, the database and migrations are intact, and no secret is committed.

This is a **frozen release candidate**, not a production deployment. Ten
operational capabilities are explicitly unverified or unimplemented, and they
are enumerated above rather than smoothed over — most importantly that **no
browser testing has ever been performed** and that **no backup has been restored**.

The repository should remain in this state until a deliberate human decision
starts the next phase.

# CarbonFlow — Project & Phase History

**Prepared:** Phase 10.14, 2026-10-03
**Purpose:** preserve the record of how CarbonFlow reached its current state.
**Read this for:** why the code is shaped the way it is, and which decisions were
deliberate rather than accidental.

This document **adds** to the historical record. It does not replace, rewrite or
soften any earlier document. Where an earlier document is now superseded, that
supersession is stated explicitly in both places — the old text is preserved.

---

## 1. How to read this history

The status words are the same six used in `docs/HANDOVER.md` §0.

One additional caution applies to the whole file: **earlier phase documents were
written when the code was in the state it was in then.** Statements about
Node/Express, about recovery controls being absent, or about test counts are
records of a past state, not current claims.

---

## 2. Phase map

| Phase | Scope | Outcome |
| --- | --- | --- |
| 1 | Discovery / baseline | Repository-wide audit of the pre-Java state |
| 2 | Java foundation & decisions | JDK 21, Spring Boot, Flyway adoption, RBAC, API envelope, BCrypt; ADRs 009–013 |
| 3 | Identity & tenant core | JDBC identity (organizations, users, memberships, refresh tokens), rotation, logout, tenant/role switching, registration → approval lifecycle, tenant user admin; ADR-014, migration `V8` |
| 4 | Scope & structure | Legal entities, facilities, departments, reporting periods, boundaries + membership; the `ScopeService` tenant choke point; ADR-015, migration `V4`/`V5` |
| 5 | Governance & audit | Ten-state audit machine, checklist, review desk, corrections, approvals, evidence vault with versions and links, governed lock; ADR-016, migrations `V5`/`V6` |
| 6 | Carbon accounting core | Deterministic `BigDecimal` engine, gas-level results, emission ledger with supersession, Scope 2 dual reporting, unit conversion, reference data, audit-lock integration; ADR-017 |
| 7 | Reporting, portfolio, targets, platform admin | Dashboard, trend insights, period summary, breakdown, hardened CSV export, inventory snapshots, carbon targets, reduction projects, platform tenant administration; ADR-018/019/020 |
| 8 | Frontend integration | React wired to the verified Java backend. No mock data, no demo fallbacks |
| 9 | Hardening & QA | Security-chain, RBAC parity, IDOR, tenant-isolation work |
| 10.4 | Node-off rehearsal | Rehearsed the Java-only stack before cutover |
| 10.5 | **Cutover** | **Node/Express decommissioned** (`8389732`). Java became the sole runtime |
| 10.6 / 10.6.1 | Production readiness, TLS | Real PostgreSQL TLS (`DB_SSLMODE`, `DataSourceTls`) — finding F-01; readiness validation |
| 10.6.2 | Release freeze | `RELEASE CANDIDATE — FROZEN` (`d42af8b`) |
| — | Operational validation | Recovery drill rehearsed on an isolated scratch environment; browser UAT recorded |
| — | Recovery requirements | RTO 4 h / RPO 1 h approved 2026-10-01 as **project-level, not SLA** |
| 10.7 | Recovery controls REC-02..REC-12 | Manifest, PostgreSQL backup, evidence-vault backup, coordinated recovery boundary, verification, retention, encryption, monitoring, drill, RTO/RPO validators |
| 10.8 | Operational automation REC-13..REC-17 | Backup scheduling, operational notifications, drill scheduling, runbook, end-to-end operational validation |
| 10.9 | REC-13 hardening, RPO analysis, REC-05 | PostgreSQL advisory-lock cross-host exclusion; zero-margin RPO finding; quiescence limitation confirmed rather than closed |
| 10.11 | Accessibility & responsive UX | Skip link, focus visibility and trapping, reduced motion, dialog semantics, live regions, 30-test accessibility suite |
| 10.12 | Globalization & locale handling | Migration `V9` removing the frozen `'US'`/`'USD'` defaults; UTC-based `src/services/format.ts`; `Intl.NumberFormat` via `formatQuantity`; a 20-test `globalization.test.ts` guard suite. `docs/GLOBALIZATION.md` |
| 10.13 | Deployment readiness | `docs/DEPLOYMENT-READINESS.md` — provider-neutral assessment of what a safe deployment requires and what the operator must supply |
| 10.10 *(in progress, uncommitted)* | Performance & scalability baseline | `docs/PHASE10.10-PERFORMANCE.md` + the `perf/` harness. Single-host synthetic measurements that **explicitly disclaim** SLA, capacity and production claims. Ordering within the 10.x series is the phase author's, not this phase's |
| **10.14** | **Documentation & client handover** | **This documentation pass**, reconciled against `cebb10f`. `docs/HANDOVER.md`, `docs/TROUBLESHOOTING.md`, this file; corrections to stale status claims in `README.md`, `docs/README.md`, `backend-java/README.md`, `FRONTEND.md` |

> Phases 10.10 and the "final hardening audit" are not separately labelled in
> the commit history; the commits in that range are the accessibility work
> recorded as 10.11 above. No phase number has been invented to fill a gap.
>
> **This documentation pass ran concurrently with at least three parallel
> phases** (10.10, 10.12, 10.13), which committed while it was in progress. The
> handover is reconciled against `cebb10f`. Later commits are not reflected; see
> `docs/HANDOVER.md` §15.

---

## 3. Decisions that shaped the system

Full records with rationale live in **`docs/DECISIONS.md`** (ADR-001–021). The
ones an incoming maintainer must understand before changing anything:

| ADR | Decision | Why it matters |
| --- | --- | --- |
| ADR-009 | **No ORM. Plain JDBC via `JdbcTemplate`.** | Every query is visible and tenant-predicate is the reviewer's job. This is also why tenant isolation is a *code* discipline, not a framework feature — nothing enforces it for you. |
| ADR-010 | Java 21 + Spring Boot as the target backend; the Node backend was the interim implementation | It is why a full Java port existed at all, and why parity testing against the Node contract mattered |
| ADR-011 | 9 roles × 44 permissions, parity-tested against the frozen reference matrix | The matrix is **code**, not database rows. Changing it changes the frozen contract and requires updating `expected-role-permissions.json` |
| ADR-012 | Flyway baseline strategy at version 6 | The schema predates Flyway; a fresh history could not be invented honestly. Migrations are frozen because checksum validation is the only thing preventing silent drift |
| ADR-013 | Security posture baseline (constant-time comparison, no committed secrets, no CORS `*` with credentials, envelope-complete errors) | Explains several deliberate strictnesses that look unusual |
| ADR-014 | DB-backed identity; refresh rotation with **family revocation on replay** | Deliberately *stronger* than the Node implementation it replaced. Reuse detection revoking the whole family is intentional, and surprises people |
| ADR-015 | Tenant scoping with a single ID-resolution choke point (`ScopeService`) | This is why malformed, unknown and cross-tenant ids are indistinguishable. Changing it to be more informative would create an enumeration oracle |
| ADR-016 | Governed lock = SHA-256 integrity checksum, **not** cryptographic immutability | CarbonFlow never claims immutability. The wording throughout the documentation is load-bearing |
| ADR-017 | Accounting statements tenant-predicated; organization taken only from `TenantContext`, never a request body | A client cannot select its own tenant by sending a different id |
| ADR-008 | **No methodology field on calculation snapshots** | Deliberate. No calculation should claim a methodology it cannot prove. This is deferred finding **F-03**, preserved as a documented tension rather than silently resolved |
| ADR-020 | Platform tenant transitions enforce the documented from-state, SQL-guarded against concurrent writes | Produces `409 INVALID_STATUS_TRANSITION` rather than a lost update |
| ADR-021 | CORS allow-list is fail-closed; `*` refused with credentials enabled | Browsers reject `*` with credentials anyway; failing fast is better than a confusing runtime failure |

---

## 4. Decisions deliberately *not* taken

Recording these matters as much as recording the decisions that were made,
because the absence is easy to mistake for an oversight.

| Not done | Why |
| --- | --- |
| A `SUPER_ADMIN` role | The 9-role set is canonical. A superuser would defeat the permission model |
| A second permission matrix in the frontend | Hiding is UX; the backend is the boundary. A duplicate matrix is a drift source |
| A `SUPER_ADMIN`-style platform tenant read | `PLATFORM_ADMIN` is deliberately denied every tenant reporting surface |
| Quiescing writes during backup | Requires intercepting the live request path — a business-behaviour change. Recorded as a scope constraint, not an engineering estimate (`RECOVERY-CONTROLS-DESIGN.md` §31) |
| Shortening the backup interval to create RPO margin | The interval is an **approved requirement**. Changing it to manufacture headroom would be silently altering an approved requirement |
| Adding a methodology/formula version field | ADR-008. See F-03 |
| Naming a Tier-3 escalation owner | The approval records a *role*, not a person. Naming an individual is an organisational decision and **has not been made** |
| Deleting historical phase documents | They are evidence. Superseding them is recorded, not erasing them |
| Rewriting `docs/TEST_PLAN.md` despite invalid UTF-8 | Pre-existing, no runtime effect, and out of scope for the phase that found it. Reported rather than silently touched |

---

## 5. How earlier documents were superseded

The rule applied throughout: **add a supersession note, never rewrite the
original claim.** A reader who finds the old text must be able to see both what
was believed then and what is known now.

| Superseded statement | Where it survives | Current position |
| --- | --- | --- |
| "No backup scheduler, no retention enforcement, no backup monitoring" | `README.md` (pre-10.14), `docs/RELEASE-HANDOVER.md`, `docs/FINAL-RELEASE-REPORT.md` | Phases 10.7–10.8 implemented all three. **However** finding **F-10** now shows the scheduler trigger cannot fire |
| "RTO/RPO NOT YET TESTED" | `docs/RELEASE-HANDOVER.md`, `docs/FINAL-RELEASE-REPORT.md` | Validators were implemented and tested in Phase 10.7, but **only against a local synthetic dataset**. Compliance at production scale is still **NOT DEMONSTRATED** |
| "The RTO is still NOT DEFINED" | `docs/RELEASE-HANDOVER.md`, `docs/FINAL-RELEASE-REPORT.md` | Superseded 2026-10-01: RTO 4 h / RPO 1 h **approved** as project-level requirements |
| "No cross-host scheduling lock" | `docs/BACKUP-RECOVERY.md` §7, §8.8 | Phase 10.9 implemented a PostgreSQL advisory-lock lease. Two-**host** behaviour remains unverified |
| "Node/Express is the live implementation" | `backend-java/README.md` | Phase 10.5 decommissioned it. Untracked `server.ts`/`server/` residue remains in the working tree only |
| "243 tests" | `backend-java/README.md` | A source-level count at `cebb10f` found **584 `@Test` methods**. The old figure predates the recovery, accessibility and globalization suites |
| "Browser UAT has not been performed" | `README.md` (pre-10.14), `docs/FINAL-RELEASE-REPORT.md` | Superseded: real browser UAT was recorded on 2026-09-30. See `docs/OPERATIONAL-VALIDATION.md` |
| "V1–V8 frozen, no V9" | `README.md`, `docs/DECISIONS.md`, `docs/FINAL-RELEASE-REPORT.md` | **Superseded 2026-10-03.** `V9` was added by Phase 10.12 (`cebb10f`). The freeze principle is unchanged: migrations are added forward, never edited |
| "F-02 deferred — needs a V9" | `README.md`, `backend-java/README.md`, `docs/FINAL-RELEASE-REPORT.md` | **Resolved** by `V9` in Phase 10.12. Residual: no API path can yet set a reporting currency |
| "36 tests" | `FRONTEND.md` | **Superseded.** 86 tests at `cebb10f`, all passing (verified 2026-10-03) |

---

## 6. Evidence retained

These are the records a reviewer can use to check claims rather than take them on
trust. They are retained even where they are partly superseded.

| Record | What it evidences |
| --- | --- |
| `docs/OPERATIONAL-VALIDATION.md` | Recovery drill against an isolated scratch environment (2026-09-30); evidence-vault restore with SHA-256 match at four independent points; browser UAT |
| `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` | The approved RTO/RPO, backup frequency, retention, ownership, escalation tiers — and their implementation status over time |
| `docs/RECOVERY-CONTROLS-DESIGN.md` | REC-02..REC-17 design and rationale, the measured development-host figures, and the §30 zero-margin RPO analysis |
| `docs/BACKUP-RECOVERY.md` | The operational runbook, with each step labelled `TESTED` / `DOCUMENTED BUT NOT TESTED` / `NOT IMPLEMENTED` |
| `docs/DECISIONS.md` | ADR-001–021 |
| `docs/PHASE10*.md`, `docs/UI-AUDIT.md`, `docs/PHASE9-BASELINE.md` | Phase-by-phase record, including the Node era |

Deliberately **retained as historical even though superseded**:

- `docs/PHASE10-NODE-OFF-TEST.md`
- `docs/PHASE10-FINDINGS-RESOLUTION.md`
- `docs/PHASE10-CUTOVER-GAP-REPORT.md`
- `docs/PHASE10-FRONTEND-CUTOVER.md`
- `docs/PHASE10-NODE-DECOMMISSION-PLAN.md`
- `docs/PHASE10.5-NODE-DECOMMISSION.md`
- `docs/PHASE10.5-CUTOVER-REPORT.md`
- `docs/PHASE10.6-PRODUCTION-READINESS.md`
- `docs/PHASE10.6.1-BLOCKER-RESOLUTION.md`
- `docs/FINAL-RELEASE-REPORT.md`
- `docs/RELEASE-HANDOVER.md`

They describe Node/Express, or a pre-recovery-control codebase, because that is
what existed when they were written. **They are not rewritten.** Where they
disagree with `docs/HANDOVER.md`, the handover is current.

---

## 7. Verification timeline

| Date | Event | Status words |
| --- | --- | --- |
| 2026-09-26 | `V7`–`V8` baselined and applied to live PostgreSQL 18.6 | VERIFIED |
| 2026-09-27 | Phases 5 and 6 complete | TESTED |
| 2026-09-28 | Phase 7 complete; Phase 8 frontend integration | TESTED |
| 2026-09-28 | Node/Express decommissioned (`8389732`) | IMPLEMENTED |
| 2026-09-30 | TLS configuration implemented and probed (F-01) | VERIFIED (configuration) — **handshake NOT VERIFIED** |
| 2026-09-30 | Release frozen (`d42af8b`) | `RELEASE CANDIDATE — FROZEN` |
| 2026-09-30 | Recovery drill on an isolated scratch environment | VERIFIED (procedure) — **RTO/RPO compliance NOT DEMONSTRATED** |
| 2026-09-30 | Browser UAT recorded in Chrome 154 | VERIFIED (16 of 18 areas PASS) |
| 2026-10-01 | Recovery requirements approved (RTO 4 h, RPO 1 h) | APPROVED — project-level, **not** an SLA |
| 2026-10-01→02 | Recovery controls REC-02..REC-12 implemented and tested | IMPLEMENTED / TESTED |
| 2026-10-02→03 | REC-13..REC-17 implemented; runbook written | IMPLEMENTED / TESTED |
| 2026-10-03 | Cross-host exclusion, RPO margin analysis, quiescence limitation | IMPLEMENTED / TESTED — two-host **NOT VERIFIED** |
| 2026-10-03 | Accessibility hardening (`eae70c6`) | TESTED |
| 2026-10-03 | Globalization hardening, migration `V9` (`cebb10f`) | IMPLEMENTED / TESTED — frontend suite **86/86** |
| 2026-10-03 | Deployment readiness assessment (10.13) | Documented — **not deployed** |
| 2026-10-03 | **Phase 10.14 documentation pass** | Documentation only — **no code changed** |
| — | External production deployment | **NEVER PERFORMED** |
| — | Production-scale load or performance measurement | **NOT DONE** |

---

## 8. What the next maintainer should do first

1. Read `docs/HANDOVER.md` end to end, especially §14 (limitations) and §16
   (open findings).
2. **Resolve finding F-10.** Automated backup cannot fire. Until it is resolved,
   CarbonFlow has no working scheduled backup and the documentation cannot claim
   otherwise.
3. Decide the **RPO margin** question (`RECOVERY-CONTROLS-DESIGN.md` §30). The
   approved interval equals the approved RPO. Shortening it is a business
   decision.
4. Decide the **7-year retention** question. 30 days is implemented; 7 years is
   unresolved and no regulatory input has been given.
5. Name a **Tier-3 escalation owner**, or accept that escalation is blocked.
6. Reconcile the **working tree** before committing anything: untracked
   `server.ts` / `server/` residue, an untracked `perf/` test package, and a
   second `bun.lock` (`docs/HANDOVER.md` §15). Decide each deliberately.
7. Get a decision on the remaining deferred findings F-04 through F-09, and on
   the open Phase 10.12 follow-up (no API path for reporting currency).

Do not do any of these silently. Each one changes an approved requirement, a
frozen contract, or a documented limitation, and each therefore needs a human
decision recorded the way the earlier ones were.
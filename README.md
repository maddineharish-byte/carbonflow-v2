# CarbonFlow

CarbonFlow is a multi-tenant greenhouse-gas accounting and audit-preparation
application for enterprise organisations. It covers the full workflow: define
the reporting boundary, collect activity data with evidence, calculate emissions
deterministically, govern the figures through a governed audit workflow, report
them, and track reduction targets.

> **Start here:** [`docs/HANDOVER.md`](docs/HANDOVER.md) — the full technical and
> client handover, including what has *not* been verified.
> Something broken? [`docs/TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md).

> **CarbonFlow is not** an assurance provider, a certification authority or a
> regulator, and it makes no legal-compliance guarantee. Audit locking is a
> **governed state seal**, not a cryptographic immutability guarantee. It claims
> **no production SLA**, and it delivers **no human notification** — alerts are
> written to the application log. See [Known limitations](#known-limitations).

## Architecture status

The Node/Express backend was **DECOMMISSIONED in Phase 10.5**. It survives only
in Git history (`8389732`) and in labelled historical documents. The Java backend
is the sole runtime.

```text
React 19 + TypeScript + Vite
            |
            | REST (Authorization: Bearer)
            v
Java 21 + Spring Boot
            |
            | JDBC (JdbcTemplate, no ORM)
            v
        PostgreSQL
            |
            v
        Flyway  (V1–V8, applied at startup)

Evidence file bytes → filesystem vault (CARBONFLOW_EVIDENCE_VAULT_DIR)
```

| Layer | Technology | Location |
| --- | --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind 4, Recharts | `src/` |
| API | Java 21, Spring Boot 3.3.3 | `backend-java/` |
| Persistence | Spring JDBC (`JdbcTemplate`), HikariCP — deliberately no ORM (ADR-009) | `backend-java/…/repository/` |
| Database | PostgreSQL | external |
| Migrations | Flyway (applied at startup) | `db/migration/V1..V9` |
| Evidence bytes | Local filesystem vault, **unencrypted at rest** | external mount |

## Prerequisites

- **Java 21** — the backend runtime
- **Maven 3.9+** — build/test driver (no Maven wrapper is committed; `mvn` must
  be on `PATH`)
- **PostgreSQL 15+** — an existing database, or an empty one Flyway will build
- **Node.js 20.19+ or 22.12+** — build/dev/test tooling for the **frontend only**
  (required by Vite 8). The frontend is a static bundle; Node is not a runtime
  server

## Environment

Copy `.env.example` to `.env` and supply values through your environment or
secret manager. `.env` is git-ignored. Never commit credentials.

**Required — the application refuses to start without them:**

- `CARBONFLOW_JWT_SECRET` (≥ 32 random bytes)
- `CARBONFLOW_REFRESH_TOKEN_SECRET` (≥ 32 random bytes)
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`

**Operational / optional:**

- `DB_SSLMODE` — PostgreSQL transport security. Defaults to `prefer`, which
  **silently falls back to plaintext**; set `require` (encryption only) or
  `verify-ca` / `verify-full` (certificate verification via the JVM trust store)
  in production. An unrecognised value fails startup. See
  `docs/DEPLOYMENT-SECURITY.md`.
- `CARBONFLOW_CORS_ALLOWED_ORIGINS` — fail-closed; empty trusts **no** browser
  origin. `*` is refused at startup.
- `CARBONFLOW_EVIDENCE_VAULT_DIR` — private evidence file storage. **Set an
  absolute, durable, backed-up path in production** (F-06).
- `CARBONFLOW_SEED_DEMO_DATA` — development fixture seeding, defaults `false`.
- `VITE_JAVA_API_BASE_URL` — frontend build-time API base URL. Empty means
  same-origin relative `/api/v1/...` paths.
- `carbonflow.recovery.backup.*` — recovery automation. **Off by default.**

See `docs/SECRETS.md`, `docs/DEPLOYMENT-SECURITY.md` and
`docs/BACKUP-RECOVERY.md`.

## Database migrations

Flyway migrations live in `db/migration/` and are **applied automatically by the
Java backend at startup**. `V1`–`V9` are frozen: never edited, and no migration
is created for convenience. `V9` was added by Phase 10.12 to remove the
hardcoded `'US'` country default and the frozen `'USD'` currency default.

`V3` fails closed if `refresh_tokens` is non-empty — a reviewed backfill plan is
required first. A schema applied manually through `V6` with no Flyway history is
**baselined at 6** (ADR-012) and only newer migrations are applied.

## Run locally

Start the Java backend first (it applies migrations and serves the API on 8080):

```bash
cd backend-java
mvn spring-boot:run
```

Then the frontend dev server, in a second terminal:

```bash
npm install
VITE_JAVA_API_BASE_URL=http://localhost:8080 npm run dev
```

- API: `http://localhost:8080` (health: `http://localhost:8080/api/v1/health`)
- Frontend: `http://localhost:5173`

Full runbook: `docs/EXECUTION.md`.

## Build and test

```bash
# Backend (runs the suite against an embedded PostgreSQL — no Docker needed)
cd backend-java && mvn clean verify

# Frontend
npx tsc --noEmit
npm run test:frontend     # 86 tests
npm run build
```

The backend suite was not re-run during the Phase 10.14 documentation pass; a
source-level count at commit `cebb10f` found **584 `@Test` methods**. That count
is not a test-run result. The frontend suite was run and **86/86 pass**.

## Roles

Nine roles × 44 permissions, enforced server-side with `@PreAuthorize`. The
matrix lives in code, not in the database, and is parity-tested. There is **no
`SUPER_ADMIN`**. `PLATFORM_ADMIN` is deliberately denied every tenant reporting
surface. Full matrix: `docs/RBAC.md`.

Tenant isolation is enforced at three layers: repository tenant predicates,
endpoint permission checks, and database composite foreign keys. Client-supplied
ids resolve through a single choke point, so malformed, unknown and cross-tenant
ids return byte-identical 404s.

## Known limitations

Read these before treating the repository as production-ready.

1. **No external production deployment has ever been performed.** Everything
   recorded here is local or runtime verification.
2. **Recovery scheduling does not currently fire (F-10).** `@Scheduled` is used,
   but `@EnableScheduling` is absent from the repository, so the hourly backup
   will not run even with `carbonflow.recovery.backup.enabled=true`. The
   scheduler tests call the scheduler directly, which is why they pass. Found by
   the Phase 10.14 audit; **not fixed** — it is a code change.
3. **No human is ever notified.** Backup failure, staleness and RPO risk appear
   in the application log only. There is no mail, SMS, pager or webhook code.
4. **No monitoring, metrics or health-probe manifest.** No Spring Actuator
   dependency. Failures are silent unless you build detection yourself (F-09).
5. **No HA or failover.** Single instance; recovery assumes the same host
   returns.
6. **The approved RPO has zero worst-case margin** against the hourly interval,
   and is conditional on no consecutive backup failures.
7. **Backups are crash-consistent, not point-in-time consistent.** No write
   quiescence, no PITR, no offsite replication.
8. **Retention is 30 days.** The possible 7-year GHG regulatory baseline is
   **unresolved and not implemented**; it needs legal input.
9. **Evidence is stored unencrypted at rest**, and the vault path defaults to a
   relative directory (F-06).
10. **Shutdown is not graceful** — in-flight requests are cut on restart (F-07).
11. **The live TLS handshake has never been observed.** The *configuration* is
    verified and fails closed correctly, but no encrypted handshake and no
    certificate rejection have been seen.
12. **No performance figure here is a service level.** A measurement phase is in
    progress in the working tree (`docs/PHASE10.10-PERFORMANCE.md`); its
    single-host synthetic readings explicitly disclaim SLA, capacity and
    production guarantees. Do not assume service levels.
13. **Frontend navigation is state-based, not URL-routed** — no deep links or
    browser history (F-08).
14. **Some backend capabilities have no UI**: legal entities, departments,
    evidence detail/versions/link/delete, activity update/submit, facility
    update/delete.
15. **Calculation snapshots record no methodology or formula version** (F-03).
16. **No tenant can set a reporting currency.** `V9` removed the frozen `'USD'`
    default, but no request DTO carries the field, so it is `NULL` ("not
    captured"). See `docs/GLOBALIZATION.md`.
17. **Tier-3 escalation has no named owner**, so that escalation path is blocked.
18. **Deferred findings F-04 and F-05** remain open. (F-02 was resolved by `V9`
    in Phase 10.12.)

Full register with owners and evidence: `docs/HANDOVER.md` §16.

## Documentation

| Document | Purpose |
| --- | --- |
| **`docs/HANDOVER.md`** | **Start here.** Current state, how to run it, limitations, open findings |
| **`docs/TROUBLESHOOTING.md`** | **Start here when something breaks.** Symptoms, causes, actions |
| **`docs/PHASE-HISTORY.md`** | **How the project got here**, and which decisions were deliberate |
| `docs/README.md` | Full documentation index |
| `docs/EXECUTION.md` | Running and operating the stack |
| `docs/DEPLOYMENT-READINESS.md` | Deployment prerequisites, rollback, and what an operator must supply |
| `docs/ARCHITECTURE.md` | System architecture |
| `docs/PERSISTENCE-ARCHITECTURE.md` | Persistence design |
| `docs/API.md` | REST API contracts |
| `docs/DATABASE.md` | Schema, indexes, tenant integrity constraints |
| `docs/RBAC.md` | Role and permission matrix |
| `docs/CALCULATIONS.md` | Calculation specification and precision |
| `docs/AUDIT_WORKFLOW.md` | Audit state machine |
| `docs/SECURITY.md` | Security model |
| `docs/SECURITY-THREAT-MODEL.md` | Threat model |
| `docs/DEPLOYMENT-SECURITY.md` | TLS, CORS, deployment configuration |
| `docs/SECRETS.md` | Secret variable inventory |
| `docs/DECISIONS.md` | Architecture decision records (ADR-001–021) |
| `docs/BACKUP-RECOVERY.md` | Backup/recovery procedures and operational runbook |
| `docs/RECOVERY-CONTROLS-DESIGN.md` | Recovery control design and rationale |
| `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` | Approved RTO/RPO, ownership, escalation |
| `docs/OPERATIONAL-VALIDATION.md` | Operational validation and drill evidence |
| `docs/TEST_PLAN.md` | Quality assurance plan (historical task log) |
| `docs/UI_UX.md`, `docs/UI-AUDIT.md`, `docs/PHASE10.11-ACCESSIBILITY.md` | UI, UX and accessibility |
| `docs/GLOBALIZATION.md` | Locale-independent formatting and the removal of hardcoded geography/currency defaults |
| `docs/PHASE10.10-PERFORMANCE.md` | Performance & scalability baseline (measurement only — **not** an SLA) |
| `docs/PRD.md` | Product requirements |
| `FRONTEND.md`, `backend-java/README.md` | Component references |

### Recovery objectives — read carefully

CarbonFlow's project-level recovery requirements were **approved 2026-10-01**:
**RTO 4 hours**, **RPO 1 hour**, backups at least hourly (database **and**
evidence vault, same recovery boundary), 30-day retention, automated backup
monitoring, quarterly restore drill.

These are **project-level requirements, not contractual SLAs**. The controls
were implemented and tested in Phases 10.7–10.9, but:

- they were measured against a **local synthetic dataset over loopback**, not
  at production scale;
- the scheduled trigger cannot currently fire (**F-10**);
- notification is **log-only** — nobody is emailed or paged.

**Approved target ≠ implemented control ≠ validated compliance.** Compliance
with the 4-hour RTO and 1-hour RPO is **NOT DEMONSTRATED**. Do not quote any
measured drill figure as a service commitment. See
`docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` and `docs/HANDOVER.md` §9.

### Phase 10 evidence (historical — read as a record, not as current state)

| Document | Purpose |
| --- | --- |
| `docs/PHASE10-NODE-OFF-TEST.md` | Phase 10.4 Node-off rehearsal report |
| `docs/PHASE10-FINDINGS-RESOLUTION.md` | Phase 10.4.1 findings resolution |
| `docs/PHASE10-CUTOVER-GAP-REPORT.md` | Reconstructed pre-cutover gap register |
| `docs/PHASE10-FRONTEND-CUTOVER.md` | Reconstructed frontend cutover state |
| `docs/PHASE10-NODE-DECOMMISSION-PLAN.md` | Phase 10.5 decommission plan and gate |
| `docs/PHASE10.5-NODE-DECOMMISSION.md` | Phase 10.5 decommission record |
| `docs/PHASE10.5-CUTOVER-REPORT.md` | Phase 10.5 final cutover report |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | Phase 10.6 readiness validation |
| `docs/PHASE10.6.1-BLOCKER-RESOLUTION.md` | Phase 10.6.1 TLS blocker resolution |
| `docs/FINAL-RELEASE-REPORT.md` | Frozen release record (`d42af8b`) |
| `docs/RELEASE-HANDOVER.md` | Frozen handover — **superseded by `docs/HANDOVER.md`** |

These documents describe Node/Express, or a codebase from before the recovery
controls, because that is what existed when they were written. They are retained
as evidence and are not rewritten. Where they disagree with `docs/HANDOVER.md`,
the handover is current.

### Working tree notice

The working tree still contains **untracked items that should not be committed**:
decommissioned `server.ts` / `server/` Node remnants, an untracked `perf/` test
package, and a second `bun.lock`. `docs/HANDOVER.md` §15 lists them with a
recommended action for each. Note also that several documents state Node is
"absent from the repository" — true of the repository, **false of the working
tree**.

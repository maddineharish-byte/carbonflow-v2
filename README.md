# CarbonFlow

CarbonFlow is a multi-tenant greenhouse-gas accounting and audit-preparation
application.

> **Architecture status (Phase 10.5):** the Node/Express backend is
> **DECOMMISSIONED**. It is retained only in Git history and in the Phase 10
> evidence documents. The Java backend is the sole production backend.

## Final architecture

```text
React 19 + TypeScript + Vite
            |
            | REST (Authorization: Bearer)
            v
Java 21 + Spring Boot
            |
            | JDBC (JdbcTemplate)
            v
       PostgreSQL
            |
            v
         Flyway  (V1-V8)
```

| Layer | Technology | Location |
| --- | --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind 4, Recharts | `src/` |
| API | Java 21, Spring Boot 3.3 | `backend-java/` |
| Database | PostgreSQL | `db/migration/` |
| Migrations | Flyway (applied at startup) | `db/migration/V1..V8` |

## Prerequisites

- **Java 21** (the backend runtime)
- **Maven 3.9+**
- **PostgreSQL 18+** (an existing CarbonFlow database, or let Flyway create
  the schema on an empty database)
- **Node.js 20+** — build/dev/test tooling for the **frontend only**. The
  frontend is a static bundle; Node is not a server at runtime.

## Environment

Copy `.env.example` to `.env` and provide values through your environment or
secret manager. `.env` is git-ignored. Never commit credentials.

Required by the Java backend:

- `CARBONFLOW_JWT_SECRET` (>= 32 random bytes; startup fails if absent)
- `CARBONFLOW_REFRESH_TOKEN_SECRET` (>= 32 random bytes; startup fails if absent)
- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`

Optional / operational:

- `CARBONFLOW_CORS_ALLOWED_ORIGINS` — fail-closed; empty trusts no browser
  origin. `*` is refused at startup.
- `CARBONFLOW_EVIDENCE_VAULT_DIR` — private evidence file storage
- `CARBONFLOW_SEED_DEMO_DATA` — development fixture seeding, defaults `false`
- `DB_SSLMODE` — PostgreSQL transport security. Defaults to `prefer`, which
  **silently falls back to plaintext**; set `require` (encryption only) or
  `verify-ca` / `verify-full` (certificate verification via the JVM trust
  store) in production. An unrecognised value fails startup. See
  `docs/DEPLOYMENT-SECURITY.md`.
- `VITE_JAVA_API_BASE_URL` — frontend build-time API base URL

See `docs/SECRETS.md`, `docs/DEPLOYMENT-SECURITY.md` and
`docs/BACKUP-RECOVERY.md`.

## Database migrations

Flyway migrations live in `db/migration/` and are **applied automatically by
the Java backend at startup**. `V1`–`V8` are frozen: they are never edited, and
no migration is created for convenience.

## Run locally

Start the Java backend (it applies migrations and serves the API on 8080):

```text
cd backend-java
mvn spring-boot:run
```

Start the frontend dev server (Vite on 5173), in a second terminal:

```text
npm install
npm run dev
```

The frontend calls the Java API through `VITE_JAVA_API_BASE_URL`.

## Build and test

Backend (Java):

```text
cd backend-java
mvn clean verify
```

Frontend:

```text
npx tsc --noEmit
npm run lint
npm run test:frontend
npm run build
```

## Product notes

CarbonFlow is **carbon accounting and audit-preparation software**. It is not
an assurance provider, a certification authority, or a regulator, and it makes
no legal-compliance guarantee. Audit locking is a **governed state seal**; it
is not a cryptographic immutability guarantee.

The platform is globally configurable: country, currency, timezone, language,
regulatory regime, geography and reporting framework are deployment
configuration, not hardcoded values. Facilities may span multiple countries.

## Known limitations

- **Browser UAT has not been performed.** All verification to date is
  HTTP-, source- and database-level. No visual or interaction testing has been
  done, because no browser was available in the verification environment.
- **No external production deployment has been performed.** Everything
  verified here is local/runtime verification. This repository documents the
  cutover; it is not evidence of a live deployment.
- **Recovery requirements are approved but not implemented.** Targets are RTO 4
  hours and RPO 1 hour (project-level, not SLA). The backup/restore *procedure*
  was rehearsed successfully on 2026-09-30, but there is no backup scheduler, no
  retention enforcement, no backup monitoring and no HA/failover, so compliance
  with those targets is **NOT YET DEMONSTRATED**.
- The frontend uses state-based navigation rather than URL routing.

## Documentation

| Document | Purpose |
| --- | --- |
| `docs/ARCHITECTURE.md` | System architecture |
| `docs/EXECUTION.md` | How to run and operate the stack |
| `docs/SECURITY.md` | Security model |
| `docs/SECURITY-THREAT-MODEL.md` | Threat model |
| `docs/DEPLOYMENT-SECURITY.md` | Deployment configuration and CORS |
| `docs/SECRETS.md` | Secret variable inventory |
| `docs/DECISIONS.md` | Architecture decision records (ADRs) |
| `docs/CALCULATIONS.md` | Calculation specification |
| `docs/RBAC.md` | Role and permission matrix |
| `docs/AUDIT_WORKFLOW.md` | Audit state machine |
| `docs/BACKUP-RECOVERY.md` | Backup and recovery procedures |
| `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` | **Approved 2026-10-01.** RTO 4 hours, RPO 1 hour, hourly backups, 30-day retention, ownership, monitoring, quarterly drill — **project-level requirements, not implemented** |
| `docs/OPERATIONAL-VALIDATION.md` | Operational validation and recovery-drill evidence |

> **Recovery objectives:** CarbonFlow's project-level recovery requirements were
> **approved on 2026-10-01** — **RTO 4 hours**, **RPO 1 hour**, backups at least
> hourly (database **and** evidence vault, same recovery boundary), 30-day
> retention, automated backup monitoring, quarterly restore drill.
>
> These are **project-level requirements, not contractual SLAs**, and they are
> **not yet implemented or validated**. There is no backup scheduler, no
> retention enforcement, no backup monitoring and no HA/failover in this
> repository. The 2026-09-30 recovery drill demonstrated the *procedure*; it did
> **not** demonstrate compliance with the 4-hour RTO or 1-hour RPO.
>
> **Approved target ≠ implemented control ≠ validated compliance.** Do not quote
> any measured drill figure as a service commitment. See
> `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.

### Phase 10 evidence (historical — read as a record, not as current state)

| Document | Purpose |
| --- | --- |
| `docs/PHASE10-NODE-OFF-TEST.md` | Phase 10.4 Node-Off rehearsal report |
| `docs/PHASE10-FINDINGS-RESOLUTION.md` | Phase 10.4.1 findings resolution |
| `docs/PHASE10-CUTOVER-GAP-REPORT.md` | Reconstructed pre-cutover gap register |
| `docs/PHASE10-FRONTEND-CUTOVER.md` | Reconstructed frontend cutover state |
| `docs/PHASE10-NODE-DECOMMISSION-PLAN.md` | Phase 10.5 decommission plan and gate |
| `docs/PHASE10.5-NODE-DECOMMISSION.md` | Phase 10.5 decommission record |
| `docs/PHASE10.5-CUTOVER-REPORT.md` | Phase 10.5 final cutover report |

These documents describe Node/Express because it existed when they were
written. They are retained as evidence and are labelled as historical.

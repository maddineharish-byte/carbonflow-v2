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
- `VITE_JAVA_API_BASE_URL` — frontend build-time API base URL

See `docs/SECRETS.md` and `docs/DEPLOYMENT-SECURITY.md`.

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
- Backup/restore procedures have not been verified.
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

# CarbonFlow

CarbonFlow is a multi-tenant greenhouse-gas accounting and audit application with a React/Vite frontend. The **active** API is Express/TypeScript; the **target** API is Java 21 + Spring Boot (`backend-java/`), converging to the same contract before cutover.

## Current architecture

- PostgreSQL 18.6 is authoritative in production for refresh tokens, identity/scope, activity data, evidence metadata/links, calculations, calculation gas results, and emission records.
- Evidence file bytes use the private local storage adapter; PostgreSQL stores metadata and tenant-owned associations, not file content.
- Audits, inventory, targets, projects, and organization settings remain in their existing process-local paths pending later tasks.
- The frontend is a single authenticated application shell with state-based view navigation.
- The target backend (`backend-java/`, Java 21 + Spring Boot) is under construction at Phase 5 (governance & audit complete: ADRs through 016, 176 tests). It does not serve the frontend yet. See `backend-java/README.md` and `docs/DECISIONS.md`.

## Prerequisites

- Node.js
- PostgreSQL 18.6
- A CarbonFlow PostgreSQL database with V1 through V6 applied

## Environment

Copy `.env.example` to a local environment file and provide the required values through your environment or deployment secret manager.

Required production configuration:

- `DB_HOST`
- `DB_PORT`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`
- `JWT_SECRET`
- `REFRESH_TOKEN_SECRET`

Never commit `.env` files or database credentials.

## Database migrations

Existing migration files are in `db/migration/`:

- `V1__carbonflow_initial_schema.sql`
- `V2__seed_reference_data.sql`
- `V3__refresh_token_persistence_alignment.sql`
- `V4__scope_constraints.sql`
- `V5__activity_evidence_tenant_integrity.sql`
- `V6__calculation_emission_integrity.sql`
- `V7__audit_status_correction_rejection.sql` — widens `carbon_audits.status` from 8 to the canonical 10 audit states so `CORRECTION_REQUESTED`/`REJECTED` can be persisted (ADR-012)

The Node backend has no migration runner: apply and verify migrations through the deployment tooling before starting the application. The Java backend runs these same files with Flyway at startup (for a database already migrated manually through V6 it baselines at 6 and applies only newer migrations — ADR-012).

## Run locally

```text
npm install
npm run dev
```

The frontend build and server bundle can be verified with:

```text
npm run build
```

## Tests

```text
npm test
npm run lint
npx tsc --noEmit
npm run test:persistence
node --import tsx --test server/activity-evidence-persistence.test.ts
```

The persistence tests require PostgreSQL and the database environment variables. They cover identity/scope, refresh-token restart and cross-process concurrency, TASK 2.4 activity/evidence metadata, links, tenant isolation, storage failure cleanup, and restart retrieval, plus TASK 2.5 calculation/emission snapshots, accounting semantics, tenant isolation, supersession, and restart retrieval.

## Current limitations

- Audits, inventory, targets, projects, and organization settings remain in the in-memory server store; calculations and emissions are PostgreSQL-backed in production.
- Calculation methodology/version fields are not present in the established calculation model, so no methodology field was added during TASK 2.5.
- Data requests, activity general update/submit endpoints, evidence versioning/deletion, and independent evidence-link endpoints are not implemented by the current application and were not added in TASK 2.4.
- Repository pools are environment-configured but not yet a single shared injected pool with server-owned graceful shutdown.
- The rate limiter is process-local and should be replaced with a shared store for multi-process production deployment.
- The frontend currently uses state-based navigation rather than URL routing.
- Production deployment, backup/restore, and browser E2E procedures are not yet complete.

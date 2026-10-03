# CarbonFlow — Technical & Client Handover

**Document status:** current authoritative handover
**Prepared:** Phase 10.14 (documentation & client handover), 2026-10-03
**Written against commit:** `cebb10f` (`fix: audit and harden globalization behaviour (Phase 10.12)`)
**Release state of record:** `RELEASE CANDIDATE — FROZEN` (`d42af8b`, 2026-09-30)
**Working tree at time of writing:** **not clean** — see
[§15 Working-tree state](#15-working-tree-state-not-clean).

This document tells an incoming developer or operator what CarbonFlow is, how to
run it, and — stated just as plainly — **what has not been verified**.

Nothing in this document is a service commitment. See
[§14 Known limitations](#14-known-limitations).

---

## 0. How to read the status words

Every capability claim in this handover uses one of six words. They are not
interchangeable and are not used loosely.

| Word | Meaning |
| --- | --- |
| **IMPLEMENTED** | The code exists and is wired into the running application. |
| **TESTED** | An automated test exercises it and passes. Says nothing about production. |
| **VERIFIED** | A human-recorded procedure observed it in a running (or rendered) environment. Evidence is cited. |
| **HISTORICAL** | A record of a past phase. May have been superseded; retained deliberately as evidence. |
| **DEFERRED** | Known, tracked, deliberately not fixed. Carries an ID and an owner decision. |
| **NOT IMPLEMENTED** | It does not exist in this repository. |

Three distinctions the rest of this document depends on:

- **Approved target ≠ implemented control ≠ validated compliance.** A project may
  approve an RTO and still not meet it. See [§9](#9-recovery).
- **Implemented ≠ verified.** Code that compiles and passes tests has still not
  been observed in production.
- **A test is not a guarantee.** The suite proves assertions about code, not
  about behaviour under production load, failure, or adversarial use.

---

## 1. What CarbonFlow is

CarbonFlow is a **multi-tenant greenhouse-gas (GHG) accounting and
audit-preparation application** for enterprise organisations.

It covers the carbon accounting workflow end to end:

1. Define the reporting boundary — legal entities, facilities, departments,
   reporting periods, consolidation boundaries.
2. Collect activity data (fuel, electricity, refrigerant, fleet, process) with
   supporting evidence.
3. Calculate emissions with a deterministic decimal engine, resolving emission
   factor versions and GWP sets to produce an immutable calculation snapshot.
4. Record emissions in a ledger with Scope 1 and both Scope 2 perspectives
   (location-based and market-based) held separately.
5. Govern the figures through a ten-state audit machine with a checklist, review
   findings, corrections, approvals and a governed lock that freezes the
   reporting period.
6. Report — dashboard, analytics, breakdowns, inventory snapshots, CSV export.
7. Track decarbonisation — carbon targets and reduction projects.

### What CarbonFlow is **not**

- It is **not** an assurance provider, a certification authority, or a regulator.
- It makes **no regulatory or legal-compliance guarantee**.
- It does **not** claim cryptographic immutability. The audit lock is a
  **governed state seal**: a restore that rewinds past a lock returns an audit to
  an unlocked state, and that must be recorded as an operational event.
- It claims **no production SLA**. The RTO/RPO figures in
  `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` are **project-level requirements,
  approved 2026-10-01** — not contractual service levels.
- It has **not** completed a 7-year retention obligation. See F-11 in §16.
- It delivers **no human notification**. Alerts are written to the application
  log; nobody is emailed or paged. See §9 and §14 item 3.

---

## 2. Architecture

```text
React 19 + TypeScript + Vite + Tailwind 4 + Recharts
        (static bundle; src/)
             |
             | REST / JSON over HTTPS
             | Authorization: Bearer <JWT access token>
             v
Java 21 + Spring Boot 3.3.3          (backend-java/)
  23 controllers · ~99 endpoint mappings
             |
             | Spring JdbcTemplate (plain JDBC, deliberately no ORM — ADR-009)
             | HikariCP pool, max 10 connections
             v
PostgreSQL 15+                        (single source of truth)
             |
             v
Flyway V1..V8, applied at startup     (db/migration/)

Filesystem evidence vault             (CARBONFLOW_EVIDENCE_VAULT_DIR)
             |
             v
Recovery backup set                   (carbonflow.recovery.backup.root, opt-in)
```

### Component inventory

| Layer | Technology | Location | Notes |
| --- | --- | --- | --- |
| Frontend | React 19, TypeScript, Vite 8, Tailwind 4, Recharts, `motion`, `lucide-react` | `src/` | 24 files: 13 views, 1 API client, 5 test suites |
| API | Java 21, Spring Boot 3.3.3, Spring Security 6 | `backend-java/` | **Authoritative backend** |
| Persistence | Spring JDBC (`JdbcTemplate`), HikariCP | `backend-java/src/main/java/com/carbonflow/repository/` | 28 repositories; **no JPA/Hibernate** |
| Auth | JJWT 0.12.6, HS256 access tokens; BCrypt cost 10 | `config/JwtTokenProvider`, `config/SecurityConfig` | Stateless; no HTTP session |
| Database | PostgreSQL | external | Flyway owns the schema |
| Migrations | Flyway (`flyway-core`, `flyway-database-postgresql`) | `db/migration/` | Packaged onto the classpath by `pom.xml` |
| Evidence bytes | Local filesystem vault | external mount | **Not** PostgreSQL, **not** object storage |
| Recovery | Framework-free classes wired by `@ConditionalOnProperty` | `backend-java/src/main/java/com/carbonflow/recovery/` | 43 classes; **disabled by default** |

### Backend structure

`com.carbonflow` contains:

- `config/` (7) — security filter chain, JWT provider, tenant `ThreadLocal`,
  TLS validation, global exception handler, security headers.
- `security/` (3) — the `Permission` enum (44 codes), the `RolePermissions`
  matrix, authority-name constants.
- `controller/` (23) — HTTP surface only; no business logic.
- `service/` (32) — business rules, including the calculation engine and the
  tenant choke point (`ScopeService`).
- `repository/` (28) — tenant-predicated SQL. One residual in-memory `DataStore`
  survives, used only by the platform self-test endpoint.
- `model/` + `model/enums/` (35 + 4) — POJOs and enums.
- `dto/` (17) — request/response contracts and the API envelope.
- `recovery/` (43) — backup, manifest, verification, encryption, retention,
  monitoring, notification, drill and scheduling. See §9–§10.

### Frontend structure

- `src/App.tsx` — the application shell. **There is no router**; navigation is
  state-based (`useState<NavView>`), and `document.title` is updated per view
  for screen-reader orientation. See §14 item 14.
- `src/services/api.ts` — the single API client. Handles the envelope, bearer
  injection, single-flight refresh, and 401 retry. Blob paths (evidence
  download, CSV export) implement their own fetch so an `Authorization` header is
  always sent.
- `src/services/permissions.ts` — `hasPermission()` only. **The frontend holds
  no second permission matrix.** Hiding a control is UX; the backend
  re-authorizes every request.
- `src/components/` — 13 permission-gated views plus `AuthBoundary`,
  `LoginView`, `Modal`, `ConfirmDialog`, `Navbar`, `Sidebar`,
  `TrendInsightsSection`.

### Node/Express history

The Node/Express backend was **decommissioned in Phase 10.5** and survives only
in Git history (`8389732`) and the labelled historical documents listed in §17.
There is no Node server in `package.json`, no Express dependency, and no runtime
code path that references one.

> **Caveat — see §15.** Untracked `server.ts` and `server/` from the Node era
> are still present in the working tree. They are **not** in the repository and
> **not** part of the release, but their presence in a working tree is a trap
> for anyone reading the tree rather than the history.

---

## 3. How to start the backend

### Prerequisites

| Requirement | Version | Notes |
| --- | --- | --- |
| JDK | **21** | The backend runtime. Verified on OpenJDK 21.0.12.1 LTS. |
| Maven | **3.9+** | Verified on 3.9.16. **No Maven wrapper is committed** — `mvn` must be on `PATH`. |
| PostgreSQL | **15+** | Verified against 18.6. See §5. |
| Node.js | 20+ | **Frontend build/dev/test only.** Not a runtime server. |

### Required environment

The application **refuses to start** if either secret is missing, blank, or
shorter than 32 bytes. This is deliberate and fail-closed.

```bash
export CARBONFLOW_JWT_SECRET="$(openssl rand -base64 48)"
export CARBONFLOW_REFRESH_TOKEN_SECRET="$(openssl rand -base64 48)"
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=carbonflow_dev
export DB_USER=carbonflow
export DB_PASSWORD='...'
```

### Run

```bash
cd backend-java
mvn spring-boot:run
```

Listens on `http://localhost:8080`, applies Flyway migrations at startup, and
seeds nothing unless `CARBONFLOW_SEED_DEMO_DATA=true`.

Health check:

```bash
curl http://localhost:8080/api/v1/health
# {"status":"UP","service":"carbonflow-backend","version":"...","runtime":"...","timestamp":"..."}
```

`GET /api/health` is an alias of the same handler. **Both are public** and are
the only unauthenticated data endpoints besides the auth entry points.

### Production-style run

```bash
cd backend-java
mvn clean package                        # -> target/carbonflow-backend-1.0.0-PRO.jar
java -jar target/carbonflow-backend-1.0.0-PRO.jar
```

There is **no Dockerfile, compose file, Helm chart, Kubernetes manifest, CI
pipeline, or reverse-proxy sample configuration** anywhere in this repository.
Deployment is manual. See F-09.

---

## 4. How to start the frontend

```bash
npm install
npm run dev                              # Vite dev server on :5173
```

The frontend reads one variable:

| Variable | Default | Purpose |
| --- | --- | --- |
| `VITE_JAVA_API_BASE_URL` | `''` (empty) | API origin. **Empty means same-origin relative `/api/v1/...` paths** — the correct setting behind a reverse proxy. Set `http://localhost:8080` for direct dev-server access. |

For local development against a separately-run backend:

```bash
VITE_JAVA_API_BASE_URL=http://localhost:8080 npm run dev
```

Two processes, two terminals. Start the backend first: the frontend calls
`GET /api/v1/auth/me` on mount to restore a session and will show an error if the
API is unreachable.

Production build:

```bash
npm run build                            # -> dist/, static assets
```

Serve `dist/` as static files. Either serve it from the **same origin** as the
API (so the empty base URL works) or set `VITE_JAVA_API_BASE_URL` at build time.

---

## 5. Database setup

### Who owns the schema

**The Java backend, via Flyway, at startup.** There is no separate DBA-owned
bootstrap script, because the migrations *are* the schema.

### Creating a database

```bash
createuser carbonflow --pwprompt
createdb  carbonflow_dev --owner=carbonflow
psql -d carbonflow_dev -c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp"; CREATE EXTENSION IF NOT EXISTS "pgcrypto";'
```

The two extensions are also requested by `V1` itself (`CREATE EXTENSION IF NOT
EXISTS`), so a database role with the appropriate privilege is required.

### Schema size

| Measure | Value | Provenance |
| --- | --- | --- |
| Domain tables | **37** | Static count of `CREATE TABLE` across `db/migration/`, re-counted in this phase |
| Plus `flyway_schema_history` | 38 | — |
| Indexes | **74** | Measured on a live database at the 2026-09-30 freeze. **Not re-measured in this phase.** |
| Tenant-scoped composite FKs | **43** | Same provenance and caveat. |
| Migration files | `V1`–`V9` | Verified — contiguous, no gaps |

### Fresh vs. pre-existing databases

| Starting state | What Flyway does |
| --- | --- |
| Empty database | Applies `V1` → `V9` in order |
| Schema applied manually through `V6`, no `flyway_schema_history` | **Baselines at version 6**, then applies `V7`→`V9` only (`baseline-on-migrate=true`, `baseline-version=6`) |
| Partially migrated | **Must be baselined manually.** Flyway will not guess. |

The baseline-at-6 strategy is deliberate and recorded as ADR-012: the schema
predates the Flyway dependency, so a fresh history cannot be invented for it
without claiming migrations that never ran through Flyway.

---

## 6. Migrations

Flyway migrations live in **`db/migration/`** at the repository root — a single
source of truth shared by the application (`pom.xml` copies the directory onto
the classpath as `db/migration`) and by any deployment tooling.

| File | Contents |
| --- | --- |
| `V1__carbonflow_initial_schema.sql` | 37 UUID-keyed tables, 9 indexes, check constraints; requests `uuid-ossp` and `pgcrypto` |
| `V2__seed_reference_data.sql` | 9 canonical roles, 3 GWP sets (AR6/AR5/AR4) with values, calculation methodologies, emission factors |
| `V3__refresh_token_persistence_alignment.sql` | Refresh-token family/role/organization columns. **Fails closed if `refresh_tokens` is non-empty** — a reviewed backfill plan is required first |
| `V4__scope_constraints.sql` | Facility code uniqueness, non-negative floor area, legal-entity ownership range |
| `V5__activity_evidence_tenant_integrity.sql` | Composite tenant foreign keys on activity data; evidence-link entity integrity |
| `V6__calculation_emission_integrity.sql` | Factor/GWP/conversion provenance columns on `calculations`; emission-ledger indexes |
| `V7__audit_status_correction_rejection.sql` | Widens `carbon_audits.status` from 8 states to the canonical 10-state machine |
| `V8__organization_lifecycle_status.sql` | Organization lifecycle: `status`, `status_changed_at`, `status_changed_by`, `status_note` |
| `V9__remove_hardcoded_country_currency_defaults.sql` | **Phase 10.12.** Drops the `'US'` default from `organizations.country` (keeping `NOT NULL`, so a missing value fails loudly) and drops the `'USD'` default and `NOT NULL` from `organization_settings.currency` (so "not captured" is expressible) |

### Rules

- **`V1`–`V9` are frozen.** Never edited. Checksum validation will refuse to
  start against a modified migration.
- **No migration is created for convenience.** If a change needs a schema
  change, it needs a new `V<n>` and a reason.
- **Never** place a password on a command line or in source. To migrate an
  externally managed database ahead of time:

```bash
flyway -url="jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}" \
       -user="${DB_USER}" -password="${DB_PASSWORD}" migrate
```

- Schema and reference data only. `db/migration` must stay free of demo or
  tenant data — development fixtures are seeded by `DemoDataSeeder` behind the
  `CARBONFLOW_SEED_DEMO_DATA` flag, not by migration.

> **A note on `V9`.** The earlier statement "V1–V8 are frozen, no V9" was true
> for every migration committed before 2026-10-03. `V9` was added by Phase 10.12
> and **is committed** (`cebb10f`). The freeze principle is unchanged: a new
> migration is added forward, existing ones are never edited.
>
> `V9` resolves deferred finding **F-02**. Its own documented follow-up remains
> open: **no API path can yet set a tenant's reporting currency**, because no
> request DTO carries the field. See `docs/GLOBALIZATION.md`.

---

## 7. Environment variables

### Required — startup fails without them

| Variable | Purpose |
| --- | --- |
| `CARBONFLOW_JWT_SECRET` | HS256 access-token signing key. **≥ 32 random bytes.** |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | HMAC-SHA256 key for refresh-token hashes. **≥ 32 random bytes.** |
| `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection (`DB_PORT` defaults to 5432). |

### Operational / optional

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_SSLMODE` | `prefer` | PostgreSQL transport security. **Set this explicitly in production** — see below. |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | *(empty)* | Fail-closed allow-list of exact browser origins. Empty trusts **no** origin. `*` is refused at startup. |
| `CARBONFLOW_EVIDENCE_VAULT_DIR` | `vault_storage` (relative!) | Evidence file bytes. **Set an absolute, durable, backed-up path in production** — F-06. |
| `CARBONFLOW_SEED_DEMO_DATA` | `false` | Development fixture seeding. **Leave `false` in production.** |
| `VITE_JAVA_API_BASE_URL` | *(empty)* | Frontend build-time API base URL. |

### Recovery — read from `@Value` defaults, not declared in `application.properties`

| Property | Default | Meaning |
| --- | --- | --- |
| `carbonflow.recovery.backup.enabled` | `false` | **Master switch.** No scheduler bean exists while false. |
| `carbonflow.recovery.backup.root` | *(required when enabled)* | Absolute directory for backup sets. |
| `carbonflow.recovery.backup.cron` | `0 0 * * * *` | Top of every hour. |
| `carbonflow.recovery.backup.zone` | `UTC` | Zone the cron is evaluated in. |
| `carbonflow.recovery.backup.timeout` | `PT30M` | Bound on one backup attempt. |
| `carbonflow.recovery.backup.copied-with` | `robocopy` | Recorded in the manifest for auditability. |
| `carbonflow.recovery.drill.*` | see `RecoverySchedulerConfiguration` | Quarterly drill schedule, zone, grace. |

### Throttling defaults (also `@Value`-only)

`carbonflow.auth.throttle.max-failures` = 5, `.window-ms` = 900000,
`.lockout-ms` = 900000 → `429 AUTH_THROTTLED`. Counters are **per JVM** and are
lost on restart.

### Secrets

- Environment or secret manager only. `.env` is git-ignored; `.env.example` is
  the committed template.
- **Rotating either secret invalidates all outstanding sessions** and forces
  every user to sign in again. No data is lost — the signing keys are not in the
  database.
- The historical Node-era variables (`GEMINI_API_KEY`, `JWT_SECRET`,
  `REFRESH_TOKEN_SECRET`, `DATABASE_URL`, `STORAGE_DRIVER`, `SUPABASE_*`,
  `APP_URL`) are **not read by anything**. `.env.example` lists them as removed.

---

## 8. Test commands

| Suite | Command | Result in this phase |
| --- | --- | --- |
| Backend (unit + integration, embedded PostgreSQL) | `cd backend-java && mvn clean verify` | **NOT RE-RUN** — see the caveat below |
| Frontend type check | `npx tsc --noEmit` | Not run this phase |
| Frontend tests | `npm run test:frontend` | **86 / 86 pass** at `cebb10f` (run 2026-10-03) |
| Production build | `npm run build` | Not run this phase |

### Backend tests

`mvn clean verify` compiles and runs the suite against a **zonky embedded
PostgreSQL** (`test` scope only — no Docker, no external database required).
Integration tests use the `dbtest` profile and boot the full application context.

The suite was **not re-executed during Phase 10.14**; this is a documentation
phase and a full run is expensive. The last recorded full-suite result is in
`docs/FINAL-RELEASE-REPORT.md`. A static count performed in this phase found
**584 `@Test` methods** across the source tree (30 controller test classes,
19 recovery test classes, and the remainder in config/service/security/repository/perf).
That count is a **source-level count, not a test-run result** — it is not
evidence that 584 tests pass.

### Frontend tests

`npm run test:frontend` runs `node --import tsx --test` over the suites in
`src/`. There is no Jest, no Vitest, no testing-library; tests render with
`react-dom/server` and stub `fetch`.

| Suite | Tests | Covers |
| --- | --- | --- |
| `src/auth-boundary.test.tsx` | 4 | Protected content hidden until authenticated; no credential text in markup |
| `src/api-refresh.test.ts` | 4 | 401 → single-flight refresh → one retry; concurrent 401s share one refresh; failed refresh clears the session; 403 does **not** refresh |
| `src/auth-flow.test.ts` | 6 | Login/logout/`me`; token storage; `USER_DEACTIVATED` handling |
| `src/integration.test.tsx` | 22 | Permission-gated navigation, real backend payloads, no demo fallbacks, audit transitions, CSV auth header, 403/404/409/500 mapping |
| `src/accessibility.test.tsx` | 30 | See §11 |
| `src/globalization.test.ts` | 20 | Locale-independent formatting; no system-default-zone clock in the backend; no hardcoded geography/currency |
| **Total** | **86** | **86 pass, 0 fail** — verified 2026-10-03 at `cebb10f` |

---

## 9. Recovery

> **Approved 2026-10-01, project-level, not an SLA:** RTO **4 hours**, RPO
> **1 hour**, backups at least hourly (database **and** evidence vault, same
> recovery boundary), 30-day retention, automated monitoring, quarterly drill.
> Source of record: `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.

### What exists

| Control | State | Verified how |
| --- | --- | --- |
| PostgreSQL logical backup (`pg_dump`) | IMPLEMENTED, TESTED | 32 tests, incl. generated stub executables |
| Evidence-vault backup + per-file SHA-256 index | IMPLEMENTED, TESTED | 19 tests |
| Recovery manifest (boundary, digests, schema versions, verification) | IMPLEMENTED, TESTED | 33 tests |
| Coordinated recovery set (db → vault → manifest) | IMPLEMENTED, TESTED | 12 tests |
| Backup verification | IMPLEMENTED, TESTED | 19 tests |
| Backup retention, 30 days | IMPLEMENTED, TESTED | 16 tests |
| Backup encryption (asymmetric: `age`, falling back to `gpg`) | IMPLEMENTED, TESTED | 11 tests |
| Backup monitoring / health classification | IMPLEMENTED, TESTED | 17 tests |
| Operational notifications | IMPLEMENTED, TESTED — **logs only** | 23 tests |
| Automated backup scheduling (REC-13) | IMPLEMENTED, TESTED — **see F-10** | 20 tests |
| Cross-host exclusion (PostgreSQL advisory lock) | IMPLEMENTED, TESTED — **single-JVM two-instance test only** | 4 tests |
| Restore drill (REC-10) | IMPLEMENTED, TESTED | 4 + 1 end-to-end tests |
| RTO validator / RPO validator | IMPLEMENTED, TESTED | 5 + 8 tests |
| Drill scheduling (REC-15) | IMPLEMENTED, TESTED — **no trigger fires** | 19 tests |

### What does not exist

| Gap | State |
| --- | --- |
| **Human notification delivery** | **NOT IMPLEMENTED.** The only provider is `LoggingNotificationProvider`, which writes `WARN`/`ERROR` log lines and reports `deliversOutOfBand() == false`. No mail, SMS, pager or webhook code exists. Notifications are in-memory only (200 entries) and are **not persisted**. |
| **Production HA / failover** | NOT IMPLEMENTED. Recovery assumes the same host returns. |
| **Write quiescence during backup** | NOT IMPLEMENTED. `QuiesceGuard.NoOp` — nothing is blocked or paused. The manifest records sets as *not quiesced*. |
| **Point-in-time recovery** | NOT IMPLEMENTED. Requires PostgreSQL WAL archiving configured outside this repository. |
| **Offsite / cross-region replication** | NOT IMPLEMENTED. |
| **7-year retention** | **NOT IMPLEMENTED and unresolved.** Retention is 30 days. A possible 7-year GHG regulatory baseline requires legal/regulatory input that has not been given. |
| **Production-scale validation** | NOT DONE. Every measured figure came from a synthetic dataset over loopback. |

### The zero-margin finding (open)

The approved backup interval (1 hour) **equals** the approved RPO (1 hour), so
there is **zero worst-case margin**. Worst case is not "up to 1 hour": a crash
mid-backup yields the previous completed set (≈ 2 intervals), and two
consecutive failures put worst-case loss at roughly double the RPO. The RPO is
therefore **conditional on no consecutive failures**, and that condition is not
monitored as an RPO statement.

Phase 10.9 **deliberately changed no configuration**, because altering an
approved interval is a business decision. The finding is recorded and escalated
for a project decision. Full arithmetic: `docs/RECOVERY-CONTROLS-DESIGN.md` §30.

### Recovery procedure

The authoritative 11-step recovery procedure and the quarterly drill procedure
are in **`docs/BACKUP-RECOVERY.md` §8.5–§8.6**. Key rules:

- **Always restore into an isolated database whose name differs from `DB_NAME`.**
- A **database-only restore is not a complete recovery** — the vault carries the
  same RTO/RPO, because the database references bytes on disk.
- The JWT and refresh secrets must be the **same values** as before the incident.
- Flyway must report *"No migration necessary"*. If it applies migrations, stop
  and investigate.
- **Stop and escalate** if any tenant can see another tenant's data.

---

## 10. Backup

### Enabling it

Automation is **off by default**. Nothing is backed up until it is deliberately
turned on.

```bash
export carbonflow_recovery_enabled=true   # or -Dcarbonflow.recovery.backup.enabled=true
export carbonflow_recovery_backup_root=/var/backups/carbonflow   # absolute, durable, backed up
```

Then confirm the startup log lines:

```text
Recovery backup schedule configured: enabled cron="0 0 * * * *" zone=UTC
Automated recovery backup is ENABLED: ...
Recovery notifications enabled with provider(s) [structured-log] (out-of-band delivery to a human: false)
```

If the notification line is absent, a failed backup is visible **only in the log**.

### The set

One set = database dump + vault copy + `manifest.json` +
`vault-integrity.json`. The manifest records the application version and git
commit, the schema versions and table count, the **recovery boundary**
(`min(databaseSnapshotAt, vaultSnapshotAt)`), whether the set was quiesced, and
the verification status.

### Guarantees and non-guarantees

- A **failed attempt leaves no manifest**, so a partial set cannot be mistaken
  for a recovery point.
- An existing set directory is **never overwritten**.
- Verification **precedes** recording success — a backup that cannot be verified
  is not a recovery point.
- A retention failure **never fails** the backup cycle.
- The snapshot is **crash-consistent, not point-in-time consistent.** `pg_dump`
  takes its snapshot at dump start; rows modified after that instant may reflect
  post-snapshot values. This is a documented limitation, not a defect claim in
  either direction.

### Daily operator check

The newest set must be **under one hour old** and its manifest
`verification.status` must be `VERIFIED`. `UNVERIFIABLE` is **not** a pass.

```bash
ls -1t <backupRoot> | head -1
cat <backupRoot>/<newestSet>/manifest.json | grep -E 'createdAt|"status"'
```

Full runbook — daily check, failure triage, staleness diagnosis, escalation —
is `docs/BACKUP-RECOVERY.md` §8.

---

## 11. Evidence handling

Evidence (utility bills, meter data, invoices) is **split by design**:

| What | Where | Why |
| --- | --- | --- |
| Metadata, SHA-256, tenant links, version history | PostgreSQL (`evidence_records`, `evidence_versions`, `evidence_links`) | Transactional, tenant-predictable, queryable |
| File bytes | Filesystem vault under `CARBONFLOW_EVIDENCE_VAULT_DIR/<organizationId>/` | Large binaries do not belong in the relational store |

### Upload validation chain

Applied in this order; any failure returns `400 UPLOAD_FAILED` and nothing is
persisted:

1. **Size** — `25 MB` hard limit (multipart max-file `25MB`, max-request `28MB`).
2. **MIME allow-list** — pdf, csv, xlsx, xls, docx, png, jpeg, jpg, text/plain.
3. **Magic bytes** — the content must match the declared MIME type.
4. **SHA-256** — computed on the accepted bytes and stored.

Then: sanitised filename (path separators stripped, every character outside
`[a-zA-Z0-9._-]` replaced), a **containment check** that the resolved path stays
inside the tenant directory, and the write. A failed write cleans up.

### Versioning and linking

- Every upload appends an `evidence_versions` row (number, sha256, path).
  Versions are **append-only**.
- Links are tenant-validated and target `ACTIVITY_DATA`, `AUDIT` or `FACILITY`.
- Delete is **governed**: evidence linked to an audit returns
  `409 EVIDENCE_IN_USE`.

### Security properties

- `storagePath` is **never serialised** into an API response.
- Download goes through the tenant choke point first, so a cross-tenant id is an
  indistinguishable 404.
- The internal path is never leaked: a missing file and a traversal attempt both
  return the same message, `503 EVIDENCE_STORAGE_ERROR` / `404
  EVIDENCE_FILE_NOT_FOUND` as appropriate, with identical text.

### Honest limitations

- **Evidence bytes are stored unencrypted at rest.** The class documents this
  explicitly. Protect the volume, not just the database.
- The default vault path is **relative** (`vault_storage`). Unset in production,
  audit evidence lands under the process working directory — typically neither
  durable nor backed up. **F-06.**

---

## 12. Roles and permissions

Nine roles, 44 permission codes. The matrix lives in **code**
(`security/RolePermissions`, an `EnumMap`) — **not** in the database. The
`permissions` and `role_permissions` tables are seeded empty and never queried.
Parity against the frozen reference matrix is asserted by
`RolePermissionsParityTest`.

| Role | Focus |
| --- | --- |
| `COMPANY_ADMIN` | Full tenant administration |
| `SUSTAINABILITY_MANAGER` | Accounting and target management |
| `CARBON_ACCOUNTANT` | Activity data and calculation |
| `DATA_OWNER` | Owns activity data for their scope |
| `FACILITY_MANAGER` | Facility-scoped operations |
| `REVIEWER` | Read + review; cannot self-approve |
| `MANAGEMENT` | Read and reporting |
| `ASSURANCE_PROVIDER` | Independent read access, possibly multi-tenant |
| `PLATFORM_ADMIN` | Cross-tenant lifecycle. **Deliberately holds only** `users.read`, `emission_factors.read`, `emission_factors.manage`, `platform.tenants.read`, `platform.tenants.manage` — it is refused every tenant reporting surface. |

There is **no `SUPER_ADMIN`.**

### Enforcement

Authorization is **server-side and authoritative**, at four points:

1. `@PreAuthorize("hasAuthority('PERMISSION_…')")` on controller methods.
2. `AuditService.assertTransitionPermission` — a per-edge permission from the
   state machine, failing **closed** when no authentication is bound.
3. Tenant scoping taken from `TenantContext` (a server-side `ThreadLocal`),
   **never from a request body**.
4. `ScopeService` — the single choke point through which every client-supplied
   id resolves. Malformed, unknown and cross-tenant ids all collapse to
   byte-identical 404s, so the API does not confirm that an id exists elsewhere.

Granted authorities are `ROLE_<Role>` plus one `PERMISSION_<code>` per role
permission.

Frontend hiding is **UX only**. `src/services/permissions.ts` holds a single
`hasPermission()` helper and no matrix, by design.

### Tenant model

- Tenant = `organizations.status = ACTIVE`. Registration creates
  `PENDING_ACTIVATION`; a `PLATFORM_ADMIN` approves, rejects or suspends. **A
  company admin cannot self-approve.**
- A user may hold memberships in several organizations; the client switches with
  `POST /api/v1/auth/switch-tenant-or-role`, and the server refuses any
  organization/role pair the user does not already hold.
- Three enforcement layers: repository tenant predicates, endpoint permission
  checks, and database composite foreign keys that prevent a row being attached
  to another organisation's facility, department or period.

Full matrix: `docs/RBAC.md`.

---

## 13. Security

| Area | Control |
| --- | --- |
| Password storage | BCrypt, cost 10. Comparison is timing-equalised. |
| Access token | JWT HS256, 15-minute TTL, `Authorization: Bearer` only. **Query-string `?token=` is refused.** |
| Refresh token | 32 random bytes → 64 hex chars. **Only the HMAC-SHA256 hash is stored.** 7-day TTL. Single-use rotation; **replaying a rotated token revokes the whole family.** |
| Session | Stateless. `csrf` disabled because there is no cookie session. |
| Per-request validation | The JWT filter re-reads the user and membership from PostgreSQL on **every** request, so a deactivation takes effect immediately. |
| Login throttling | 5 failures / 15 min → `429 AUTH_THROTTLED`. **Per JVM; lost on restart.** |
| Authorization | 9 roles × 44 permissions, `@PreAuthorize`, fail-closed. |
| Tenant isolation | Tenant predicate + permission check + `ScopeService` choke point + composite tenant FKs. |
| Deactivated organisations | Sign-in and refresh refused unless `ACTIVE`. |
| Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-XSS-Protection: 1; mode=block`, `Cache-Control: no-store`. **No HSTS.** |
| CORS | Fail-closed; explicit allow-list; `*` refused at startup (`IllegalStateException`) because credentials are always enabled. |
| DB transport | `DB_SSLMODE`, validated at startup; an unrecognised value **fails the application** rather than degrading. |
| Error envelopes | Typed `{success:false, error:{code,message}}`. No stack traces, SQL, or filesystem paths reach clients. |
| Media types | `415 UNSUPPORTED_MEDIA_TYPE`, not `500`. |
| CSV export | Formula-injection guard (no leading `=`, `+`, `-`, `@`), deterministic ordering, ISO-8601 UTC. |
| Frontend tokens | `localStorage` only. Never in a URL — including the blob download and CSV export paths. |
| Secrets | Environment/secret manager only. `.env` git-ignored, `.env.example` committed and blank. |

### Database TLS

`DB_SSLMODE` is passed to PgJDBC and validated at startup.

| Value | Encrypted | Server identity verified | Forbids plaintext |
| --- | --- | --- | --- |
| `disable` | No | No | No |
| `allow` | Only if offered | No | No |
| `prefer` *(default)* | Only if offered | No | **No — silently downgrades** |
| `require` | Yes | **No** | Yes |
| `verify-ca` | Yes | Yes (chain) | Yes |
| `verify-full` | Yes | Yes (chain + hostname) | Yes |

**Set it explicitly in production.** `prefer` will quietly fall back to
plaintext if the server offers no TLS. `require` encrypts but does not prove who
the server is. Certificate verification uses the JVM trust store:

```bash
java -Djavax.net.ssl.trustStore=/opt/certs/pg-ca.jks \
     -Djavax.net.ssl.trustStorePassword="$PG_TRUSTSTORE_PASSWORD" \
     -jar carbonflow-backend-1.0.0-PRO.jar
```

Confirm the connection is actually encrypted:

```sql
SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();   -- must return t
```

Threat model: `docs/SECURITY-THREAT-MODEL.md`. Deployment detail:
`docs/DEPLOYMENT-SECURITY.md`. Secrets inventory: `docs/SECRETS.md`.

---

## 14. Known limitations

Ordered by how badly they will hurt if you do not know about them.

1. **No external production deployment has ever been performed.** Everything
   recorded here is local or runtime verification. This repository documents a
   cutover; it is not evidence of a live deployment.
2. **Recovery scheduling does not currently fire — F-10.** See §16. Do not
   assume `carbonflow.recovery.backup.enabled=true` produces hourly backups.
3. **No human is ever notified.** Backup failure, staleness and RPO risk appear
   in the application log only. Without log-based alerting you will not learn
   about them.
4. **No monitoring, metrics, or health-probe manifest.** There is no Spring
   Actuator dependency and no `management.*` configuration. Lockouts, pool
   saturation, evidence-write failures and error-rate regressions are silent
   unless you build detection yourself. F-09.
5. **No high availability or failover.** Single instance. Recovery assumes the
   same host returns.
6. **The approved RPO has zero worst-case margin** against the hourly interval,
   and is conditional on no consecutive backup failures. See §9.
7. **Backups are crash-consistent, not point-in-time consistent.** No quiescence,
   no PITR.
8. **Retention is 30 days.** The possible 7-year GHG regulatory baseline is
   **unresolved and not implemented**; it needs legal input that has not been
   given.
9. **Evidence is stored unencrypted at rest**, and the default vault path is
   relative. F-06.
10. **Graceful shutdown is not configured.** In-flight requests are cut on
    restart. F-07.
11. **The live TLS handshake has never been observed.** The *configuration* is
    verified and fails closed correctly (an invalid value refuses to start), but
    no encrypted handshake and no certificate rejection have been seen, because
    no TLS-enabled PostgreSQL was available.
12. **No TLS certificates or PKI are provisioned.** `verify-ca` / `verify-full`
    have not been exercised against a real CA.
13. **No performance figure in this repository is a service level.** A Phase
    10.10 measurement phase is in progress in the working tree
    (`docs/PHASE10.10-PERFORMANCE.md`, `perf/` harness), and it reports
    single-host synthetic readings over loopback that **explicitly disclaim**
    SLA, capacity and production guarantees. Nothing committed here establishes
    where the system stops working. Do not assume service levels.
14. **Frontend navigation is state-based, not URL-routed.** No deep links, no
    browser back/forward, no per-view URL.
15. **Some backend capabilities have no UI.** Legal entities, departments,
    evidence detail/versions/link/delete, activity update/submit, and facility
    update/delete are API-only.
16. **Calculation snapshots carry no methodology or formula version.** F-03. An
    assurance reviewer cannot determine from a snapshot alone which corporate
    standard produced a figure. GWP basis and factor version *are* recorded.
17. **Deferred findings F-04 and F-05 remain open** — see §16. F-02 was resolved
    by migration `V9` in Phase 10.12.
18. **No tenant can set a reporting currency.** `V9` removed the frozen `'USD'`
    default and made the column nullable, but **no request DTO carries the
    field**, so there is still no API path to set it. `NULL` ("not captured") is
    the honest state, but a tenant reporting in EUR cannot yet say so. Tracked
    as a Phase 10.12 follow-up in `docs/GLOBALIZATION.md`.
19. **Tier-3 escalation has no named owner.** The approved escalation path
    requires a designated technical/hosting administrator. The approval records a
    *role*, not a person. Until one is named, that escalation path is
    **blocked**. No name has been invented here.
20. **The cross-host backup lock has not been tested across two machines.** The
    mechanism is enforced by PostgreSQL and was demonstrated with two scheduler
    instances in one JVM; two-host behaviour is unverified and unclaimed.
21. **`docs/TEST_PLAN.md` is not valid UTF-8** — 34 stray Windows-1252 bytes,
    pre-existing at every baseline. It is a historical task log with no runtime
    effect; it is reported rather than silently rewritten.

---

## 15. Working-tree state (not clean)

Phase 10.14 committed **documentation only**. It did not touch, stage or delete
anything belonging to another phase.

At the time of writing, `HEAD` is `cebb10f` — the Phase 10.12 globalization
phase, which committed while this documentation pass was in progress. This
document has been reconciled against it: `V9` is now part of the schema, the
globalization test suite is part of the runner, and the frontend suite is **86
tests, all passing**.

Remaining untracked items in the working tree:

| Path | Nature | Action |
| --- | --- | --- |
| `server.ts`, `server/` | **Decommissioned** Node/Express remnants from before Phase 10.5 | **Do not commit.** Delete them; the authoritative history is `8389732` and `docs/PHASE10.5-NODE-DECOMMISSION.md` |
| `docs/PHASE10.10-PERFORMANCE.md`, `backend-java/src/test/java/com/carbonflow/perf/` | A **Phase 10.10 performance measurement phase**, in progress and uncommitted | Belongs to that phase. Its own document disclaims SLA, capacity and production claims, and this handover inherits that disclaimer — see §14 item 13 |
| `bun.lock` | A second, unused JavaScript lockfile alongside `package-lock.json` | Remove, or commit deliberately if `bun` is a supported package manager. Two lockfiles silently diverge |
| `drill-vault/` | Runtime evidence from a recovery drill | Now git-ignored by this phase. Do not commit |

### One trap for the next reader

**`server.ts` and `server/` are not part of CarbonFlow.** They are
decommissioned Node/Express files present only as untracked working-tree
residue. Several documents state that Node is "absent from the repository" —
which is **true of the repository** and **false of the working tree**. Anyone
reading the tree rather than the history will draw the wrong conclusion.

---

## 16. Open findings register

Deferred findings carried from earlier phases, plus findings discovered during
this documentation audit. **None was silently reclassified.**

| ID | Severity | Finding | Status |
| --- | --- | --- | --- |
| F-01 | — | Real PostgreSQL TLS configuration | **RESOLVED** (Phase 10.6.1) — `DB_SSLMODE`, validated at startup |
| F-02 | MEDIUM | Registration defaulted a blank country to `"US"`; column default `'US'` in `V1` | **RESOLVED** (Phase 10.12, migration `V9`, `cebb10f`) — the default is dropped; `country` stays `NOT NULL` so a missing value now fails loudly. `V9` also drops the unchangeable `'USD'` currency default. **Residual:** no API path can yet set a tenant's reporting currency — see `docs/GLOBALIZATION.md` |
| F-03 | MEDIUM | Calculation snapshots carry no methodology or formula version | **DEFERRED — human product/architecture decision.** ADR-008 deliberately chose not to invent a field: no calculation should claim a methodology it cannot prove. Preserved as a documented tension. |
| F-04 | MEDIUM | `SecurityHeadersFilter` has no automated regression test. Headers are verified at runtime, but a future removal would not fail a test. | **DEFERRED** |
| F-05 | LOW | The opt-in `dev` CORS profile still lists the retired `http://localhost:3000` Node origin. Nothing listens there. | **DEFERRED** |
| F-06 | MEDIUM | The evidence vault defaults to a relative path (`vault_storage`). If unset in production, evidence lands under the process working directory — typically not durable, not backed up. | **DEFERRED** — set `CARBONFLOW_EVIDENCE_VAULT_DIR` explicitly |
| F-07 | LOW | Graceful shutdown is not configured; in-flight requests are cut on restart. | **DEFERRED** |
| F-09 | MEDIUM | No monitoring instrumentation, no metrics/health-probe manifest, no container or deployment manifests. | **DEFERRED** |
| **F-10** | **HIGH** | **The scheduled backup trigger cannot fire.** `@Scheduled` is used by `RecoveryBackupScheduleConfiguration.scheduledBackup()`, but **`@EnableScheduling` does not appear anywhere in the repository** — not on `CarbonFlowApplication`, not on any configuration class. Spring Boot does not enable `@Scheduled` processing automatically, so the hourly backup will not run even with `carbonflow.recovery.backup.enabled=true`. Separately, `RecoveryDrillScheduler.runScheduled(...)` has **no trigger at all**. | **DISCOVERED in Phase 10.14. Not fixed — this is a code change and Phase 10.14 is documentation-only.** The unit and integration tests call the schedulers **directly**, which is why they pass and why this was not caught. Documentation previously stated backup scheduling was operational; that statement is superseded by this finding. |
| **F-11** | MEDIUM | **7-year retention is not implemented and the requirement is unresolved.** Retention is 30 days. A possible 7-year GHG regulatory baseline requires legal/regulatory input. | **OPEN — escalated, not decided.** No claim of completed retention is made anywhere in this document. |
| **F-12** | MEDIUM | **The working-tree frontend suite failed.** The `src/globalization.test.ts` guard reported `AuditService.java -> OffsetDateTime.now()` — a system-default-zone-dependent clock, and locale-unaware number/timestamp formatting in the UI. | **RESOLVED** by Phase 10.12 (`cebb10f`) — UTC-based `src/services/format.ts`, `Intl.NumberFormat` via `formatQuantity`, and a passing 20-test guard suite. Raised by this audit and closed by the parallel phase |

---

## 17. Where to look next

### Current state

| Need | Document |
| --- | --- |
| **Start here — full handover** | `docs/HANDOVER.md` (this file) |
| Running and operating the stack | `docs/EXECUTION.md` |
| Deploying safely — what exists, what you must supply | `docs/DEPLOYMENT-READINESS.md` (Phase 10.13) |
| Something is broken | **`docs/TROUBLESHOOTING.md`** |
| Schema, migrations, tenant integrity | `docs/DATABASE.md`, `docs/PERSISTENCE-ARCHITECTURE.md` |
| API contract | `docs/API.md` |
| Frontend integration | `FRONTEND.md` |
| Roles and permissions | `docs/RBAC.md` |
| Accounting rules and precision | `docs/CALCULATIONS.md` |
| Audit state machine | `docs/AUDIT_WORKFLOW.md` |
| Security model / threat model | `docs/SECURITY.md`, `docs/SECURITY-THREAT-MODEL.md` |
| TLS, CORS, deployment configuration | `docs/DEPLOYMENT-SECURITY.md` |
| Secrets inventory | `docs/SECRETS.md` |
| Backup, recovery, runbook | `docs/BACKUP-RECOVERY.md` |
| Recovery control design and rationale | `docs/RECOVERY-CONTROLS-DESIGN.md` |
| Approved recovery requirements (RTO/RPO) | `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` |
| Operational validation evidence | `docs/OPERATIONAL-VALIDATION.md` |
| Architecture decisions (ADRs) | `docs/DECISIONS.md` |
| Product requirements | `docs/PRD.md` |
| Globalization & locale handling | `docs/GLOBALIZATION.md` |
| Performance baseline (measurement only) | `docs/PHASE10.10-PERFORMANCE.md` |
| Test plan | `docs/TEST_PLAN.md` |
| UI/UX and accessibility | `docs/UI_UX.md`, `docs/PHASE10.11-ACCESSIBILITY.md` |
| Backend module reference | `backend-java/README.md` |
| **Phase history** | **`docs/PHASE-HISTORY.md`** |

### Historical — read as a record, not as current state

| Document | Phase | Note |
| --- | --- | --- |
| `docs/PHASE9-BASELINE.md` | 9 | Baseline before Java hardening |
| `docs/PHASE10-NODE-OFF-TEST.md` | 10.4 | Node-off rehearsal |
| `docs/PHASE10-FINDINGS-RESOLUTION.md` | 10.4.1 | Findings resolution |
| `docs/PHASE10-CUTOVER-GAP-REPORT.md` | 10 | Reconstructed pre-cutover gaps |
| `docs/PHASE10-FRONTEND-CUTOVER.md` | 10 | Reconstructed frontend cutover |
| `docs/PHASE10-NODE-DECOMMISSION-PLAN.md` | 10.5 | Decommission plan and gate |
| `docs/PHASE10.5-NODE-DECOMMISSION.md` | 10.5 | Decommission record |
| `docs/PHASE10.5-CUTOVER-REPORT.md` | 10.5 | Final cutover report |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | 10.6 | Readiness validation |
| `docs/PHASE10.6.1-BLOCKER-RESOLUTION.md` | 10.6.1 | TLS blocker |

| `docs/PHASE10.11-ACCESSIBILITY.md` | 10.11 | Accessibility hardening |
| `docs/UI-AUDIT.md` | — | UI audit |
| `docs/FINAL-RELEASE-REPORT.md` | 10.6.2 | Frozen release record |
| `docs/RELEASE-HANDOVER.md` | 10.6.2 | Frozen handover (superseded by this file) |

> These documents describe Node/Express, or the state of the code before the
> recovery controls of Phases 10.7–10.9, because that is what existed when they
> were written. They are **retained as evidence** and are not rewritten.
> Where they disagree with this document, this document is current.

---

## 18. Final statement

CarbonFlow is a coherent, tested, security-conscious multi-tenant GHG accounting
platform whose Java/Spring Boot backend is the sole runtime. Every automated
gate recorded at the freeze was green, the database and migrations are intact,
and no secret is committed.

It is a **release candidate**, not a production deployment. This handover makes
no production SLA claim, no certification claim, no regulatory guarantee, no HA
claim, no completed 7-year retention claim, no human-notification claim, and no
production-scale RTO/RPO claim — because none of those is true.

Two findings in this document (**F-10**, **F-11**) were discovered by this audit
and are **not** fixed. A third, **F-12**, was discovered by this audit and closed
by Phase 10.12. The repository should not be treated as operationally complete
until a human decides what to do about **F-10**.
# CarbonFlow — Release Handover

**Release candidate:** `2558b78` (baseline) / freeze commit at end of Phase 10.6.2
**Date:** 2026-09-30
**State:** `RELEASE CANDIDATE — FROZEN`

This is the operator- and client-facing handover. It states what CarbonFlow is,
how to run it, and — just as importantly — **what has not been verified**.

---

## 1. Product

CarbonFlow is a **multi-tenant greenhouse-gas accounting and audit-preparation
application** for enterprise organisations. It covers the carbon accounting
workflow: collect activity data, validate it, calculate emissions, review and
lock the figures, report them, and track reduction targets and projects.

CarbonFlow is **not** an assurance provider, a certification authority, or a
regulator, and it makes no legal-compliance guarantee. Audit locking is a
**governed state seal** maintained by operators — it is not a cryptographic
immutability guarantee, and no such claim is made anywhere in the codebase.

### Functional modules

| Module | Purpose |
| --- | --- |
| Identity & tenancy | Users, organisations, memberships, tenant switching, role switching |
| Registration & activation | Public signup creates a `PENDING_ACTIVATION` organisation; a platform administrator approves, rejects, or requests information. A company admin cannot self-approve. |
| Structure & scope | Legal entities, facilities, departments, reporting periods, organisational boundaries |
| Activity data | Emission-source activity records, with validation and status workflow |
| Calculations | Deterministic emissions engine with factor and GWP provenance |
| Emissions ledger | Scope 1, Scope 2 (location-based and market-based), supersession history |
| Governance & audit | Ten-state audit machine, checklist, findings, comments, corrections, approvals, locking, period freeze |
| Evidence vault | Versioned evidence records with size/MIME/magic-byte validation, SHA-256, and tenant-owned links |
| Inventory | Immutable inventory snapshots |
| Planning | Carbon targets and reduction projects |
| Reporting | Dashboard, analytics, dimension breakdown, trend insights, CSV export |
| Platform administration | Tenant lifecycle, cross-tenant user administration |
| User administration | Tenant-scoped user management with permission gating |

---

## 2. Architecture

```text
React 19 + TypeScript + Vite + Tailwind 4 + Recharts
            |
            | REST / JSON, Authorization: Bearer <JWT>
            v
Java 21 + Spring Boot 3.3 (23 controllers)
            |
            | Spring JdbcTemplate (no ORM), HikariCP
            v
       PostgreSQL 18+
            |
            v
   Flyway V1..V8 (applied at startup)
```

| Layer | Technology | Notes |
| --- | --- | --- |
| Frontend | React 19, TypeScript, Vite, Tailwind 4, Recharts | Static bundle; Node is build tooling only |
| API | Java 21, Spring Boot 3.3 | **Authoritative backend** |
| Persistence | Spring JDBC (`JdbcTemplate`) | Deliberately **no JPA/Hibernate** (ADR-009) |
| Database | PostgreSQL | Single source of truth |
| Migrations | Flyway | `V1`–`V8`, frozen |

**Node/Express is DECOMMISSIONED.** It survives only in Git history at
`8389732` and in labelled historical documents. There is no Node server, no
`server.ts`, no `server/`, no Express dependency, and no production code path
that references one.

### Authentication and RBAC

- JWT access tokens (15-minute TTL) with `Authorization: Bearer`. Tokens are
  **never** accepted from a query string.
- Opaque refresh tokens, stored only as HMAC hashes, with rotation and
  **family revocation on reuse** — replaying a rotated token revokes the whole
  family.
- Logout revokes the refresh family.
- Failed-login throttling: 5 failures / 15 minutes → `429 AUTH_THROTTLED`.
- Authorization is **server-side and authoritative**: 9 roles × 44 permission
  codes, enforced with `@PreAuthorize`. The 9×44 matrix is frozen.
- Registration → `PENDING_ACTIVATION` → platform review → `ACTIVE`.

### Tenant architecture

Multi-tenancy is enforced at three layers:

1. **Repository/SQL** — every statement carries a tenant predicate.
2. **Framework** — permission checks per endpoint.
3. **Database** — 43 tenant-scoped composite foreign keys prevent a row being
   attached to another organisation's facility, department or period.

---

## 3. Deployment prerequisites

### Requirements

| Component | Requirement |
| --- | --- |
| Java | **21** (tested on 21.0.12.1 LTS) |
| Maven | 3.9+ (tested on 3.9.16) |
| PostgreSQL | 15+ (tested on 18.6) |
| Node.js | 20+ — **build/dev/test tooling for the frontend only.** Node is not a runtime server. |

### Build

```bash
# Backend
cd backend-java
mvn clean package
# -> backend-java/target/carbonflow-backend-1.0.0-PRO.jar

# Frontend
npm install
npm run build
# -> dist/  (static assets)
```

### Start

```bash
# API
java -jar carbonflow-backend-1.0.0-PRO.jar
# listens on :8080

# Frontend (dev only; production serves the static build)
npm run dev
# Vite dev server on :5173
```

In production, serve `dist/` as static assets and run the jar as the API. Point
the browser at the API with `VITE_JAVA_API_BASE_URL` at build time, or serve
both behind one origin so the frontend can use relative `/api/v1/...` paths.

### Environment variables

**Required — the application refuses to start without them:**

| Variable | Purpose |
| --- | --- |
| `CARBONFLOW_JWT_SECRET` | Access-token signing key, **≥ 32 random bytes** |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | Refresh-token HMAC key, **≥ 32 random bytes** |
| `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | PostgreSQL connection (`DB_PORT` defaults to 5432) |

**Optional / operational:**

| Variable | Default | Purpose |
| --- | --- | --- |
| `DB_SSLMODE` | `prefer` | PostgreSQL transport security — see below |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | *(empty)* | Fail-closed. Empty trusts **no** browser origin. `*` is refused at startup. |
| `CARBONFLOW_EVIDENCE_VAULT_DIR` | `vault_storage` | Evidence file storage. **Set this explicitly in production** — the default is a relative path. |
| `CARBONFLOW_SEED_DEMO_DATA` | `false` | Development fixture seeding. Leave `false` in production. |
| `VITE_JAVA_API_BASE_URL` | *(empty)* | Frontend build-time API base URL |

Copy `.env.example` as a starting point. It is a template only — never commit a
filled copy. `.env` is git-ignored.

### JWT configuration

Both secrets must be at least 32 random bytes. Startup **fails closed** if
either is missing, blank, or too short. Generate with, for example,
`openssl rand -base64 48`.

**Rotating either secret invalidates all outstanding sessions** (access and
refresh tokens become unverifiable) and forces every user to sign in again. No
data is lost.

### Database SSL configuration

`DB_SSLMODE` is passed to PgJDBC as a connection property and validated at
startup; an unrecognised value **fails the application** rather than degrading
silently.

| Value | Encrypted | Server identity verified | Forbids plaintext |
| --- | --- | --- | --- |
| `disable` | No | No | No |
| `allow` | Only if offered | No | No |
| `prefer` *(default)* | Only if offered | No | No — **silently downgrades** |
| `require` | Yes | **No** | Yes |
| `verify-ca` | Yes | Yes (chain) | Yes |
| `verify-full` | Yes | Yes (chain + hostname) | Yes |

**Set this explicitly in production.** `prefer` will quietly fall back to
plaintext if the server offers no TLS. `require` encrypts the transport but does
**not** prove server identity — use `verify-ca` or `verify-full` where a party
outside your trust boundary is in the path. Certificate verification uses the
JVM trust store:

```bash
java -Djavax.net.ssl.trustStore=/opt/certs/pg-ca.jks \
     -Djavax.net.ssl.trustStorePassword="$PG_TRUSTSTORE_PASSWORD" \
     -jar carbonflow-backend-1.0.0-PRO.jar
```

Full detail: `docs/DEPLOYMENT-SECURITY.md`.

### CORS configuration

The shipped default is **empty**, which trusts no browser origin at all — this is
fail-closed and correct for production. Set an explicit comma-separated list of
exact origins. `*` is refused at startup with an `IllegalStateException`,
because credentials are always enabled and browsers reject that pairing. The
localhost origins local development needs live in the opt-in `dev` profile only.

---

## 4. Database

| Item | Value |
| --- | --- |
| Schema owner | The Java backend, via Flyway, at startup |
| Migrations | `db/migration/V1` … `V8` — **frozen** |
| Applied automatically | Yes, on startup |
| Tables | 37 domain + `flyway_schema_history` = 38 |
| Indexes | 74 |
| Tenant-scoped composite FKs | 43 |
| Verification command | `mvn clean verify` (applies V1→V8 to an embedded database on every run) |

`V1`–`V8` are immutable. They are never edited, and no migration is created for
convenience. **Do not hand-edit them**; checksum validation will refuse to start
against a modified migration.

**Backup and recovery: `docs/BACKUP-RECOVERY.md`** — covers logical and physical
backup, the evidence vault, consistency ordering, retention, encryption, access
control, and a 12-step recovery procedure. Every procedure there is labelled
`TESTED` / `DOCUMENTED BUT NOT TESTED` / `NOT IMPLEMENTED`.

> ### ⚠ Update — 2026-09-30: recovery has since been rehearsed
>
> **The statement below was accurate when this document was frozen and is now
> superseded. It is retained as the historical record of the freeze.**
>
> ~~No restore has been performed, so the RTO is unknown.~~
>
> **A restore has since been performed against an isolated scratch
> environment.** Database and evidence-vault backup, destruction and restore
> were all executed and verified; the application was restarted against the
> restored database; a restored evidence file matched its original SHA-256 at
> four independent points. Evidence: `docs/OPERATIONAL-VALIDATION.md`.
>
> **Historical statement — accurate when written; superseded by the approved
> project-level recovery requirements dated 2026-10-01.** The clause *"The RTO
> is still NOT DEFINED … recovery requirements remain pending business approval"*
> described the position on 2026-09-30 and is retained unchanged above.

> ### ⚠ Update — 2026-10-01: recovery requirements approved
>
> ```text
> APPROVED RTO:        4 hours
> APPROVED RPO:        1 hour
> BACKUP FREQUENCY:    at least once every hour (database + evidence vault)
> RETENTION:           30 days (both stores)
> RESTORE DRILL:       quarterly
> CLASSIFICATION:      PROJECT-LEVEL REQUIREMENT — NOT A CONTRACTUAL SLA
> APPROVAL STATUS:     APPROVED — COMPLETE (CarbonFlow Project Owner)
> ```
>
> **The evidence vault carries the same RTO/RPO as the database**, and a
> database-only restore is not a complete recovery.
>
> **Approved target ≠ implemented control ≠ validated compliance.** As of
> 2026-10-01 the required controls are **NOT IMPLEMENTED**: no backup scheduler,
> no retention enforcement, no backup monitoring, no encryption at rest, no
> production HA/failover. The 2026-09-30 rehearsal demonstrated the **procedure**;
> it did **not** demonstrate the 4-hour RTO or 1-hour RPO. **RTO validation:
> NOT YET TESTED. RPO validation: NOT YET TESTED.**
>
> The release remains **`RELEASE CANDIDATE — FROZEN`** and is **not production
> ready**. Full record: `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.

---

## 5. Security

| Area | Control |
| --- | --- |
| Password storage | BCrypt, cost 10 |
| Session | JWT bearer, `?token=` query-string tokens rejected |
| Refresh | Rotation with family revocation on reuse |
| Throttling | 5 failed logins / 15 min → `429 AUTH_THROTTLED` |
| Authorization | 9 roles × 44 permissions, server-side `@PreAuthorize` |
| Tenant isolation | Tenant predicate + permission check + 43 composite FKs |
| Deactivated users | Sign-in refused for non-`ACTIVE` organisations |
| DB transport | `DB_SSLMODE` (see above) |
| CORS | Fail-closed, explicit allow-list, `*` refused |
| Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-XSS-Protection: 1; mode=block`, `Cache-Control: no-store` |
| Error envelopes | Typed `{success, error:{code,message}}`; no stack traces, SQL, or filesystem paths leak to clients |
| Unsupported media type | `415 UNSUPPORTED_MEDIA_TYPE`, not `500` |
| CSV export | Formula-injection guard (no leading `=`, `+`, `-`, `@`) |
| Secret management | Environment/secret-manager only; nothing committed; `.env` git-ignored |

**A note on secret rotation:** the signing secrets are not stored in the
database, so losing them invalidates sessions but not data, and forces
re-authentication. This is fail-safe, not corruption.

---

## 6. Known deferred items

These are **open, tracked, and intentionally not fixed** in this release. None is
a release blocker; each is a product, architecture, or operations decision.

| # | Severity | Item | Status |
| --- | --- | --- | --- |
| F-02 | MEDIUM | Registration defaults a blank country to `"US"`. Not accounting-affecting (no production logic branches on country), but a globalization defect in a platform whose principle is configurable geography. | **DEFERRED** — a fix needs a V9 to drop the column default |
| F-03 | MEDIUM | Calculation snapshots carry no methodology or formula version. An assurance reviewer cannot determine from a snapshot which corporate standard produced a figure. | **DEFERRED — human product/architecture decision.** ADR-008 deliberately chose not to invent a field, on the principle that no calculation should claim a methodology it cannot prove. GWP basis *is* recorded (`gwp_set_id`), and factor year is derivable. **Preserved as a documented tension, not silently resolved.** |
| F-04 | MEDIUM | `SecurityHeadersFilter` has no automated regression test. The headers are verified at runtime but a future removal would not fail a test. | **DEFERRED** |
| F-05 | LOW | The opt-in `dev` CORS profile still lists the retired `http://localhost:3000` Node origin. No service listens there; a test asserts the value is permitted in `dev`. | **DEFERRED** |
| F-06 | MEDIUM | The evidence vault defaults to a relative path (`vault_storage`). If unset in production, evidence lands under the process working directory — typically not durable or backed up. | **DEFERRED** — set `CARBONFLOW_EVIDENCE_VAULT_DIR` explicitly |
| F-07 | LOW | Graceful shutdown is not configured; in-flight requests are cut on restart. Already acknowledged in `docs/PERSISTENCE-ARCHITECTURE.md`. | **DEFERRED** |
| F-09 | MEDIUM | No monitoring instrumentation, no metrics/health-probe manifest, no container or deployment manifests. | **DEFERRED** |

---

## 7. Verification status

### VERIFIED

| Capability | Evidence |
| --- | --- |
| Java build and tests | `mvn clean verify` — **320 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS** |
| TypeScript | `npx tsc --noEmit` — **0 errors** |
| Frontend tests | `npm run test:frontend` — **36/36** |
| Production build | `npm run build` — success, 2,253 modules |
| Authentication | 73-check live HTTP harness — login, invalid login, anonymous 401 + envelope, bogus bearer, `?token=` rejection, refresh rotation, family revocation, logout, throttling |
| Authorization | Role escalation refused, cross-tenant switch refused, least-privilege matrix |
| Tenant isolation | Cross-tenant IDOR refused; platform `/users` scoped to one tenant |
| UUID validation | Malformed and all-zero ids collapse to 404; not weakened |
| HTTP 415 | Form-encoded and XML bodies rejected with a safe envelope |
| CORS | Fail-closed; configured origin echoed; unknown and retired origins refused; `*` refused at startup |
| Database | Fresh V1→V8 on an isolated database: 38 tables, 74 indexes, 43 tenant FKs, 8 migrations `success=t` |
| Flyway | V1–V8 byte-identical to the release baseline; **no V9** |
| Accounting | Deterministic `BigDecimal` engine (28-digit, HALF_UP), unit conversion, factor/GWP provenance, Scope 1, Scope 2 location and market kept separate and never summed, audit locking, period freeze |
| DB TLS **configuration** | `require` refuses to start against a non-TLS server; `prefer` and `disable` start; an invalid value refuses to start |
| Secrets | 7 literal rules over the tracked tree — **0 matches**; `.env` untracked and git-ignored |
| Node decommission | No `server.ts`, no `server/`, no Express dependency, frontend Java-only |

### NOT VERIFIED

> **⚠ Table dated 2026-09-30, frozen release `d42af8b`. Entries that have since
> been superseded are marked inline below. The table is retained as the
> historical record of the freeze — do not read a superseded entry as a current
> limitation.**

| Capability | Why |
| --- | --- |
| **Browser UAT** | No desktop browser was available. Nothing in this release has been exercised in a browser. **Rendering, layout, focus, accessibility and interaction behaviour are entirely unverified.** |
| **Live TLS handshake** | No TLS-enabled PostgreSQL was available. The TLS *configuration* is verified and fails closed correctly, but a successful encrypted handshake and certificate rejection have not been observed. |
| **External production deployment** | No deployment was performed or attempted. This is local/runtime verification only. |
| **TLS certificates / PKI** | No certificates were provisioned; `verify-ca`/`verify-full` have not been exercised against a real CA. |
| **Backup execution** | No backup has been taken by a defined schedule; no automation exists. — **STILL TRUE** (2026-09-30 and 2026-10-01: backups remain manual and on demand; no scheduler exists, so the approved "at least once every hour" frequency is **NOT IMPLEMENTED**) |
| **Restore execution** | ~~No restore has been performed. The RTO is unknown.~~ — **SUPERSEDED 2026-09-30.** A restore has since been performed against an isolated scratch environment and verified, including evidence-vault recovery with a matching SHA-256. **HISTORICAL — accurate when written; superseded by the approved project-level recovery requirements dated 2026-10-01** (RTO 4 h, RPO 1 h, **NOT YET TESTED**). The 2026-09-30 clause *"The RTO is still `NOT DEFINED`"* described the position on that date. See `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`. |
| **RTO / RPO compliance** | — **NEW 2026-10-01.** Targets approved (4 h / 1 h) but **NOT YET TESTED — REQUIREMENT NOT DEMONSTRATED**. The 2026-09-30 drill demonstrated the procedure, not compliance. |
| **Performance** | No load, latency, or throughput measurement was taken. |
| **Multi-instance / HA** | The login throttle and pool configuration were not exercised under multiple instances. |

### NOT IMPLEMENTED

| Capability |
| --- |
| Monitoring / metrics / alerting |
| Graceful shutdown |
| Automated backup, retention enforcement, offsite replication, PITR |
| Container / deployment manifests (no Dockerfile, compose, Helm, or Kubernetes) |
| Reverse-proxy sample configuration (TLS termination, routing) |

### DEFERRED

F-02, F-03, F-04, F-05, F-06, F-07, F-09 — see §6.

---

## 8. Operational warnings

Read these before assuming the system is production-ready in every respect.

1. **No browser testing has been performed.** The backend is thoroughly verified
   at HTTP level and the frontend is type-checked, unit-tested and builds
   cleanly, but **no human has looked at this application running**. Expect to
   find presentation defects on first visual inspection.

2. **The live TLS handshake is unverified.** `DB_SSLMODE` is real, validated and
   fails closed — but confirm on your own target that the connection is
   encrypted, e.g. `SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid();`
   should return `t`.

3. **No backup or restore has been executed.** `docs/BACKUP-RECOVERY.md` is a
   written procedure, not a rehearsed one. **Rehearse the restore before you
   trust it.** A backup that has never been restored is an assumption.
   — **UPDATE 2026-09-30:** the procedure has now been rehearsed against an
   isolated scratch environment and verified (including evidence-vault recovery).
   **Still true and still important:** backups are **manual and on demand**,
   there is **no scheduler**, **no retention enforcement**, **no backup
   monitoring**, and **no HA/failover**.
   — **UPDATE 2026-10-01:** requirements were approved on that date — **RTO 4
   hours, RPO 1 hour**, hourly backups, 30-day retention, quarterly drill —
   as **project-level requirements, not contractual SLAs**. **None of the
   required controls is implemented, and compliance is NOT YET TESTED.**
   Rehearse again against *your own* environment before trusting it in
   production, and note that `rsync` itself was never exercised (the
   drill host is Windows; `robocopy` was used equivalently).

4. **There is no monitoring.** You will not be told about lockouts, pool
   saturation, evidence-write failures, or error-rate regressions unless you
   build that yourself.

5. **Shutdown is not graceful.** Deploys will cut in-flight requests.

6. **The evidence vault defaults to a relative path.** Set
   `CARBONFLOW_EVIDENCE_VAULT_DIR` to a dedicated, backed-up mount, or you will
   store audit evidence somewhere that is not durable.

7. **Registration silently defaults a blank country to `US`.** If your
   organisation operates outside the United States, set the country explicitly
   at registration.

8. **Calculation snapshots do not record the accounting methodology** (F-03).
   If your assurance provider asks which corporate standard produced a figure,
   the answer must currently come from outside the snapshot.

9. **CarbonFlow does not claim cryptographic immutability.** The audit lock is a
   governed state. A restore that rewinds past a lock returns an audit to an
   unlocked state, and that must be recorded.

10. **No performance data exists.** Do not assume service levels.

---

## 9. Where to look next

| Need | Document |
| --- | --- |
| Run and operate the stack | `docs/EXECUTION.md` |
| Configure TLS, CORS, secrets | `docs/DEPLOYMENT-SECURITY.md`, `docs/SECRETS.md` |
| Back up and recover | `docs/BACKUP-RECOVERY.md` |
| Schema and data model | `docs/DATABASE.md` |
| Security model and threat model | `docs/SECURITY.md`, `docs/SECURITY-THREAT-MODEL.md` |
| API contract | `docs/API.md` |
| Roles and permissions | `docs/RBAC.md` |
| Accounting rules | `docs/CALCULATIONS.md` |
| Audit state machine | `docs/AUDIT_WORKFLOW.md` |
| Architecture decisions | `docs/DECISIONS.md` |
| This release's evidence | `docs/FINAL-RELEASE-REPORT.md` |
| Phase history | `docs/PHASE10*.md` (historical; describes Node where it existed) |

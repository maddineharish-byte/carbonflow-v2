# CarbonFlow - Deployment Readiness

**Phase:** 10.13
**Date:** 2026-10-03
**Purpose:** Determine what is required for a safe deployment of CarbonFlow.

> **This document does not deploy CarbonFlow.** It records what a deployment
> requires, what the repository currently provides, what is missing, and what an
> operator must supply. It is provider-neutral on purpose: no cloud provider,
> orchestrator, container platform, CDN, managed database, or external monitoring
> product is assumed, because the existing project architecture assumes none.

---

## 1. Scope and non-goals

### In scope

Readiness assessment of the components that actually exist in this repository,
plus the checklist and rollback procedure required to deploy them safely.

| Component | Reality in this repository |
|---|---|
| Backend | Java 21 / Spring Boot 3.3.3, plain JDBC, packaged as an executable jar |
| Frontend | React 19 + Vite 8, packaged as a static directory |
| Database | PostgreSQL, single instance, Flyway-versioned schema |
| Durable state | PostgreSQL database + local filesystem evidence vault |

### Out of scope

The following are **NOT APPLICABLE** to this readiness assessment. They are
listed here so their absence is recorded as a deliberate scope decision rather
than an oversight. None is required by the existing architecture.

- Cloud provider selection or account structure
- Kubernetes, ECS, or any container orchestrator
- Docker or any container image build
- High availability, multi-AZ, or active/active
- Managed PostgreSQL (RDS / Cloud SQL / Azure Database)
- CDN, WAF, or DDoS service
- External monitoring / APM / tracing SaaS
- Infrastructure-as-code

Any of the above may become a future phase. Selecting one would change the
deployment topology but would not change any finding in this document, because
every finding is a property of the application or its configuration, not of the
platform beneath it.

---

## 2. Evidence basis

Every statement in this document is derived from the repository at the commit
recorded below. Nothing is inferred from a cloud console, a runtime
configuration, or an external system, because no such system exists.

| Field | Value |
|---|---|
| Branch | `main` |
| HEAD at time of writing | `eae70c6` ("fix: harden CarbonFlow accessibility and responsive UX") |
| Working tree at time of writing | **DIRTY** - see section 2.1 |
| Backend build config | `backend-java/pom.xml` |
| Backend config | `backend-java/src/main/resources/application.properties`, `application-dev.properties` |
| Migrations | `db/migration/V1..V8` committed; `V9` untracked |
| Frontend build config | `vite.config.ts`, `package.json` |

### 2.1 The working tree is not deployable as-is

At the time of writing, `git status` reports modified tracked files across
`src/`, `docs/`, `package.json`, `.gitignore`, and `backend-java/`, plus
untracked files including `server.ts`, `server/`, `bun.lock`, `db/migration/V9__*`,
`docs/GLOBALIZATION.md`, and `src/services/format.ts`.

This is normal mid-development state and is **not** a defect in CarbonFlow. It
is recorded here because a deployment must be cut from a **identified,
committed** revision. Deploying a dirty working tree makes the deployed artifact
unreproducible and unrollback-able by commit, which defeats the rollback
procedure in section 8.

Two entries warrant an operator's attention because they are runtime data or
residual decommissioned code rather than intended source:

| Path | Status | Assessment |
|---|---|---|
| `drill-vault/` | **NOW gitignored** - the `.gitignore` change is present in the working tree but **not yet committed** | Resolved in working tree. Must be committed, or tenant evidence held in this directory becomes committable. |
| `server.ts`, `server/`, `bun.lock` | Untracked. Documented as decommissioned in `README.md` and `docs/PHASE10-NODE-DECOMMISSION-PLAN.md` | Must **not** be deployed and must **not** be committed. `node_modules` still contains `express`, `pg`, `jsonwebtoken`, `bcryptjs`, `multer`, `cors` which are absent from `package.json`. Their presence on a build host is a supply-chain risk even though no code imports them. |

---

## 3. Architecture facts that constrain any deployment

These are properties of CarbonFlow itself. They are stated first because every
later finding depends on them.

1. **The frontend is a static directory, not a server.** There is no `npm
   start`, no `dist/server.cjs`, no Node process in the deployment. `server.ts`
   is not the entry point.
2. **The frontend has no URL router.** Navigation is React state
   (`App.tsx:63`); `react-router` is not a dependency. The only reachable URL is
   `/`. A reverse-proxy SPA fallback is therefore harmless but unnecessary.
3. **The backend binds port 8080 and speaks plain HTTP.** There is no
   `server.ssl.*` anywhere. TLS must be terminated upstream.
4. **Durable state is exactly two things**: the PostgreSQL database
   (authoritative) and the local filesystem evidence vault. Backing up one
   without the other yields a system whose metadata references files that do not
   exist.
5. **The backend is stateless at runtime.** Sessions are
   `SessionCreationPolicy.STATELESS`; auth is a signed JWT. Nothing is held
   in-process that must survive a restart, with one exception: the login
   throttle, which is in-memory and is lost on restart (section 5.9).

---

## 4. Readiness matrix

Statuses are `READY`, `READY WITH CONDITIONS`, `NOT READY`, `NOT APPLICABLE`.
No overall score is given, because a number would conceal which specific items
block and which do not.

### 4.1 Runtime and build

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 1 | Java runtime (21, LTS) | **READY** | `pom.xml:21` pins `java.version=21`. Spring Boot 3.3.3 parent. |
| 2 | Backend packaging | **READY** | `spring-boot-maven-plugin` (`pom.xml:127-130`) produces executable jar `target/carbonflow-backend-1.0.0-PRO.jar`. Verified present in `target/`. |
| 3 | Backend dependency set | **READY** | `pom.xml:25-108`. Plain JDBC per ADR-009; no ORM. `jjwt 0.12.6`, `flyway-core`, `flyway-database-postgresql`, PgJDBC. No `spring-boot-starter-actuator` - see item 26. |
| 4 | Frontend toolchain | **READY** | `npm run build` -> `dist/`. Verified output present: `index.html` + `assets/index-*.css` + `assets/index-*.js`. |
| 5 | Node version floor | **READY WITH CONDITIONS** | Vite 8 requires `^20.19.0 \|\| >=22.12.0`. `README.md:40` states only "Node.js 20+", which is **wrong** - Node 20.0-20.18 will fail. Pin the real floor on build hosts. |
| 6 | Continuous integration | **NOT READY** | No `.github/`, `.gitlab-ci.yml`, `Jenkinsfile`, `azure-pipelines.yml`, `.circleci/`, or Makefile anywhere in the repository. Every build and test run is manual and unrecorded. See section 7.1. |
| 7 | Deployment artefacts | **NOT APPLICABLE** | No Dockerfile, systemd unit, or service manifest exists. Per section 1, container/orchestrator packaging is out of scope; the operator supplies the process supervision. |
| 8 | Package metadata accuracy | **READY WITH CONDITIONS** | `package.json` is still `"name": "react-example"`, `"version": "0.0.0"` - template defaults. Harmless functionally, misleading in any artefact log. `clean` uses `rm -rf` (POSIX-only). `lint` is `tsc --noEmit`, a typecheck, not a linter; there is no ESLint in the project. |

### 4.2 Database and migrations

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 9 | PostgreSQL connectivity | **READY** | `application.properties:84-88`. `DB_HOST`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` required; `DB_PORT` defaults 5432. `stringtype=unspecified` is **mandatory** - the repositories bind `String` ids into `uuid` columns and fail without it. |
| 10 | Connection pool | **READY WITH CONDITIONS** | Hikari `maximum-pool-size=10`, `connection-timeout=5000` (`application.properties:87-88`). No `minimum-idle`, `max-lifetime`, `idle-timeout`, or `leak-detection-threshold`. At 10 connections against a default `max_connections=100`, one instance is fine; do not scale out horizontally without revisiting this. |
| 11 | Flyway versioning | **READY WITH CONDITIONS** | `locations=classpath:db/migration`, `validate-on-migrate=true` (`application.properties:126-129`). Migrations are packaged onto the classpath by `pom.xml:120-124`, so the application and the CLI apply the same files. Condition: see item 12. |
| 12 | Flyway baseline behaviour | **NOT READY** | `baseline-on-migrate=true` with `baseline-version=6` (`application.properties:127-128`). On any non-empty schema with **no** `flyway_schema_history`, Flyway baselines at V6 and applies only V7 onward. It does not verify that V1-V6 are actually present. A database that was manually migrated to, say, V4 will be baselined at V6 and V5 will **never run**. The file itself warns: "a partially migrated database must be baselined manually instead" (line 124). This must be checked explicitly per environment before first deploy - see section 7.2. |
| 13 | Migration V9 not committed | **NOT READY** | `db/migration/V9__remove_hardcoded_country_currency_defaults.sql` exists in the working tree but is **untracked**. It drops the hardcoded `country DEFAULT 'US'` and makes `organization_settings.currency` nullable, dropping `DEFAULT 'USD'`. The recovery manifest's schema-version default is hardcoded to `V1,...,V8` (`RecoveryBackupScheduleConfiguration.java:68`) and must be extended when V9 ships. Deploying without committing V9, or deploying V9 without updating the manifest default, leaves backups asserting a schema that no longer matches. |
| 14 | Database migration rollback | **NOT READY** | Flyway Community edition provides **no** `undo` capability, and CarbonFlow contains **no** down-migration scripts. There is no reversible migration path. See section 8.3. |
| 15 | Schema-per-tenant model | **NOT APPLICABLE** | Single `public` schema with tenant discriminator columns and enforced constraints (`V4`, `V5`). Single database is the design. |

### 4.3 Configuration, environment, and secrets

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 16 | Required env vars fail closed | **READY** | `CARBONFLOW_JWT_SECRET` and `CARBONFLOW_REFRESH_TOKEN_SECRET` are required and must be >= 32 bytes. `JwtTokenProvider.java:36-46` and `AuthService.java:108-118` throw `IllegalStateException` at startup otherwise. **This is the correct behaviour** - the application cannot start with a missing or weak signing key. |
| 17 | Configuration validation | **READY WITH CONDITIONS** | There is **no** `@ConfigurationProperties` and no `@Validated` anywhere in the backend - all binding is `@Value`. A mistyped key (e.g. `CARBONFLOW_DB_SSLMODE`) silently falls back to its default rather than failing. Because the two secret keys fail closed, the blast radius is limited to tuning parameters, but `DB_SSLMODE` is in that set - see item 22. Condition: verify env var names against section 6 character by character. |
| 18 | Environment-specific config files | **NOT READY** | Only `application.properties` and `application-dev.properties` exist. There is **no** `application-staging.properties` and **no** `application-production.properties`. Every environment difference is currently expressed purely as environment variables. This is workable but means there is no reviewed, version-controlled record of what production is supposed to be set to. Recommended before first production deploy. |
| 19 | Production credentials in repository | **READY** | Verified: `git log --all -- .env` returns nothing - `.env` has never been committed. `git ls-files` confirms only `.env.example` is tracked. `.gitignore:7-8` ignores `.env*` with `!.env.example`. No JWT secret, DB password, API key, or keystore password is present in any committed config or source file. |
| 20 | Local `.env` contains real-looking secrets | **READY WITH CONDITIONS** | The untracked `.env` on the working host holds a populated `DB_PASSWORD` and two weak, human-readable secret strings (`JWT_SECRET`, `REFRESH_TOKEN_SECRET`, both English phrases with an embedded year). These are **stale** - they are the retired Node-era contract and are superseded by `CARBONFLOW_JWT_SECRET` / `CARBONFLOW_REFRESH_TOKEN_SECRET`. Condition: they are valid credentials for a real database and should be rotated and the file removed from the build host. Confirm with `git log --all -- .env` before any push, and again before any public release. |
| 21 | Committed demo credential | **READY WITH CONDITIONS** | `repository/SeedIds.java:69` hardcodes `DEMO_PASSWORD = "Password123!"`, also published in `backend-java/README.md`. It is only used when `CARBONFLOW_SEED_DEMO_DATA=true`, which defaults to `false` (`application.properties:39`). Condition: that flag must be absent or `false` in every non-development environment. It cannot be relied upon as a control - it is a literal in the source tree. |

### 4.4 Network security

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 22 | Database TLS | **READY WITH CONDITIONS** | `DataSourceTls.java` validates the mode at startup against the six PgJDBC values (`SUPPORTED_MODES:39-40`, `resolveSslMode:84-97`); an unrecognised value throws rather than degrading silently. The value is passed as a Hikari data-source property, not concatenated into the URL (`application.properties:113-114`), so it cannot be half-applied. **Condition: the default is `prefer`, which silently downgrades to plaintext.** Production must set `DB_SSLMODE` explicitly to `verify-ca` or `verify-full`. `verify-ca`/`verify-full` verify against the JVM trust store via `-Djavax.net.ssl.trustStore`, not a CarbonFlow variable. |
| 23 | HTTP TLS / HSTS | **NOT READY** | The application provides **no** transport security of its own: no `server.ssl.*`, no `SSLContext`, no keystore, and `SecurityHeadersFilter.java:21-24` explicitly declines to set HSTS, stating it is left to "reverse proxy or external TLS termination". This is a defensible architecture, but it means **the application alone is not production-safe** - an operator who exposes port 8080 directly ships a plaintext API carrying bearer tokens and `DB_PASSWORD`-equivalent material. Condition: TLS termination is mandatory and must exist before go-live. |
| 24 | Forwarded headers | **READY WITH CONDITIONS** | `server.forward-headers-strategy` is **unset**. Any `X-Forwarded-*` the proxy sends is ignored. Today this is harmless because nothing reads the client IP or the scheme. It becomes a correctness problem the moment HSTS or secure-cookie logic depends on the scheme. Condition: set `server.forward-headers-strategy=framework` once a proxy is in front, and do not trust client-supplied forwarded headers from an untrusted network. |
| 25 | CORS | **READY** | `SecurityConfig.java:108-167`. The allow-list comes from `CARBONFLOW_CORS_ALLOWED_ORIGINS`, defaults to **empty**, and an empty list refuses all cross-origin browser requests (fail-closed). A `*` is **rejected at startup** (lines 140-146), so the credentials-plus-wildcard misconfiguration cannot occur. `allowCredentials(true)` is set, methods and headers are fixed and minimal, `maxAge=3600`. Behaviour is correct; the only operator action is naming the real frontend origin. |
| 26 | Attack surface outside `/api/v1` | **READY WITH CONDITIONS** | `SecurityConfig.java:83` is `.anyRequest().permitAll()`. Everything outside `/api/v1` is unauthenticated by design. Combined with item 23 this means **the backend must not be exposed directly to the public internet** - only the frontend origin and `/api/v1` should be reachable through the proxy. Condition: restrict at the network layer. |

### 4.5 Health, logging, monitoring, lifecycle

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 27 | Health endpoint exists | **READY WITH CONDITIONS** | `HealthController.java:16-21` serves `GET /api/health` and `GET /api/v1/health`, unauthenticated (`SecurityConfig.java:79`). Returns service name, version, Java runtime, timestamp. |
| 28 | Health check reflects real state | **NOT READY** | The endpoint returns a hardcoded `"status": "UP"` (`HealthController.java:18`). It does **not** probe PostgreSQL, does **not** check Flyway state, and does **not** check vault directory writability. It will report `UP` with the database down, which is worse than no health endpoint because it will keep a supervisor or load balancer reporting a healthy instance. |
| 29 | Readiness / liveness probes | **NOT READY** | `spring-boot-starter-actuator` is **not a dependency** (`pom.xml:25-108`). There is no `/actuator/health`, no `/actuator/info`, no liveness/readiness group, no `HealthIndicator` implementation, and no `ProbesAutoConfiguration`. Any orchestrator or external prober expecting standard Spring endpoints will find nothing. |
| 30 | Metrics | **NOT READY** | No micrometer dependency, no `MeterRegistry`, no counters, timers, or gauges, no Prometheus config, no tracing, no OpenTelemetry, no MDC/request correlation ID, and no access-log filter. Post-deployment verification is therefore entirely manual. |
| 31 | Logging | **READY WITH CONDITIONS** | There is **no** `logback.xml` and **no** `logging.*` property anywhere, so logging uses the Spring Boot default console appender: **stdout only**, no file, no rotation policy. This is a normal and acceptable container-friendly default, provided the operator captures stdout and rotates it externally. Condition: retention, disk-capacity alerting, and log shipping are the operator's responsibility and are not provided by CarbonFlow. |
| 32 | Secret and PII leakage into logs | **READY** | Verified by grep: no token, password, `Authorization` header, or request body is logged. `JwtAuthenticationFilter.java:84,108` log at `debug` and log only a JJWT exception message and a UUID - never token material. `PostgreSqlBackupTarget.java:125` redacts the password in `toString()`. `RecoverySetGuard.java:105-127` actively rejects secret-named fields from recovery manifests. |
| 33 | Error responses do not leak internals | **READY** | `ApiExceptionHandler.java` (157 lines, `@RestControllerAdvice`) maps 12 exception types to fixed-text envelopes. The catch-all (lines 147-152) returns a static message; full stack traces go to the server log only (line 149). No SQL, class name, or stack frame is serialised to clients. |
| 34 | Graceful shutdown | **NOT READY** | `server.shutdown` is unset, `spring.lifecycle.timeout-per-shutdown-phase` is unset, and there is no `@PreDestroy`, no `DisposableBean`, no `ContextClosedEvent` listener, and no `Runtime.addShutdownHook` anywhere in `backend-java/src`. A `SIGTERM` therefore triggers Spring's immediate shutdown: **in-flight requests are dropped and responses are truncated.** For a service that accepts 25 MB evidence uploads with a 28 MB request ceiling (`application.properties:12-13`), a restart during an upload loses that upload. Condition: set `server.shutdown=graceful` and a shutdown phase timeout, and make sure the supervisor's stop timeout exceeds it. |
| 35 | Startup behaviour | **READY WITH CONDITIONS** | Startup is deterministic and fail-fast on misconfiguration: Flyway migrates on boot, the two signing secrets fail closed, and an invalid `DB_SSLMODE` aborts the context. Two conditions: (a) Flyway runs **inside** the application, so **two instances starting concurrently can race on migration** - start one, wait for a healthy response, then start the next; (b) `DemoDataSeeder` is an `ApplicationRunner` and will seed identities on boot if `CARBONFLOW_SEED_DEMO_DATA=true` - see item 21. |
| 36 | Login throttle is per-instance and in-memory | **READY WITH CONDITIONS** | `LoginThrottle.java:52` keeps counters in a `ConcurrentHashMap`. This is correct for a single instance. It is **not** a global quota behind more than one instance, and it resets on restart. Since high availability is out of scope (item 41), single-instance is acceptable - but do not describe it as brute-force protection in a client-facing security claim. |

### 4.6 Backup and recovery

Statuses here reflect the **code that exists**, judged against the requirements
approved in `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` (RTO 4h, RPO 1h,
hourly backup, 30-day retention, quarterly drill). Those are project-level
targets, **not** contractual SLAs, and `docs/OPERATIONAL-VALIDATION.md` records
that **compliance has never been tested**.

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 37 | Database backup | **READY WITH CONDITIONS** | `PostgreSqlBackupService` performs a real `pg_dump`, with `PostgreSqlBackupLease` preventing concurrent runs, `RecoveryManifest` + `RecoveryManifestWriter` recording a signed set descriptor, `BackupEncryptionService` for encryption at rest, `RetentionService` for the 30-day policy, `BackupVerifier` for integrity, and `BackupMonitor` for health. Condition: requires `pg_dump` on `PATH` (`PostgreSqlToolLocator.java:34`) - see item 39. |
| 38 | Automated backup scheduling | **NOT READY** | Two independent defects. **(a) Disabled by default**: `RecoveryBackupScheduleConfiguration.java:41` is `@ConditionalOnProperty(name = "carbonflow.recovery.backup.enabled", havingValue = "true")`, and `carbonflow.recovery.backup.enabled` is **not defined in any config file**, so the component is never created. **(b) The trigger cannot fire even when enabled**: `@Scheduled` at `RecoveryBackupScheduleConfiguration.java:92` requires `@EnableScheduling`, and **`@EnableScheduling` is absent from the entire `backend-java/src` tree** (grep for `EnableScheduling`, `SchedulingConfigurer`, `ScheduledAnnotationBeanPostProcessor`, `TaskScheduler` returns zero matches). Spring only registers the scheduled-method post-processor via `@EnableScheduling`. **The hourly backup will not run.** This contradicts the "automated recovery backup scheduling" recorded in `docs/PHASE10.8` status notes and must be treated as a defect, not as configuration. |
| 39 | External tool dependencies | **READY WITH CONDITIONS** | `PostgreSqlToolLocator` requires `pg_dump`, `pg_dumpall`, and `pg_restore` on `PATH` (overridable by `CARBONFLOW_PG_DUMP`, `CARBONFLOW_PG_DUMPALL`, `CARBONFLOW_PG_RESTORE`); encryption requires `gpg`. Several recovery tests are `@EnabledIf`-skipped when these are absent, so a green test run does **not** prove the backup path works. Condition: verify tool availability on the target host as part of pre-deployment. |
| 40 | Database restore | **NOT READY** | There is a `RecoveryDrill` and `RtoValidator`/`RpoValidator` in code, and `docs/BACKUP-RECOVERY.md` documents a `pg_restore` procedure that was rehearsed against a scratch database. But **no restore has ever been performed against production data**, and the drill is disabled by default (`carbonflow.recovery.drill.enabled` defaults `false`, `RecoverySchedulerConfiguration.java:157`). Condition: a restore rehearsal into a scratch environment is mandatory before go-live. |
| 41 | Evidence vault restore | **NOT READY** | `EvidenceVaultBackupService` provides `backup(...)` (line 101) and `readIndex(...)` (line 314) - and **no restore method** (grep for `restore` in that file returns nothing). Backing up the vault is implemented; restoring it is a **manual file-copy procedure documented in `docs/BACKUP-RECOVERY.md`**, not code. Because the vault is only half of CarbonFlow's durable state, this is a genuine gap, not a documentation nicety. |
| 42 | Backup consistency | **READY WITH CONDITIONS** | Writes are **not** blocked during backup - `RecoverySchedulerConfiguration.java:287` passes `QuiesceGuard.NoOp()`. Backups are therefore consistent per-object (`pg_dump` is transactionally consistent; the vault is hashed via `EvidenceVaultIntegrityIndex`) but the **database and vault are not a single atomic snapshot**. A file written between the dump and the vault copy can be referenced by a row in the dump with no matching file, or vice versa. `RecoverySetCoordinator` defines the boundary and `BackupVerifier` will detect a mismatch, but a detected mismatch means the set is unusable. Condition: treat a database+vault set as point-in-time-approximate and rehearse accordingly. |
| 43 | Restore destination isolation | **READY WITH CONDITIONS** | `RecoverySchedulerConfiguration.java:184-185` refuses the drill with `DRILL_NOT_EXECUTABLE` rather than guessing a target host, and the drill target database defaults to `postgres`, not the live database. The drill safety gate refuses a live target. Condition: point drill targets at a scratch database only, and never let drill credentials equal production credentials. |
| 44 | Backup alerting | **NOT READY** | `LoggingNotificationProvider.deliversOutOfBand()` returns `false` (lines 60-62) and all notifications are written to the application log. **A failed or missed backup produces a log line and nobody is paged.** Two secondary defects in the same class: the notification list is an unbounded in-memory `ArrayList` (lines 34-35) that grows for the life of the process, and there is no HTTP surface for `BackupMonitor`/`BackupHealth`, so backup health cannot be scraped. |
| 45 | Recovery drill cadence | **NOT READY** | The approved requirement is a quarterly drill. `carbonflow.recovery.drill.enabled` defaults `false`, and no drill has been recorded. Condition: schedule and record the first drill. |

### 4.7 Explicitly not applicable

| # | Area | Status | Reason |
|---|---|---|---|
| 46 | High availability / failover | **NOT APPLICABLE** | Not required by the architecture. CarbonFlow is stateless apart from the in-memory login throttle (item 36). Single instance is a supported topology; the trade-offs are recorded in items 10 and 36 rather than engineered away. |
| 47 | Container / Kubernetes packaging | **NOT APPLICABLE** | Out of scope per section 1. The application is a jar and a static directory, both of which run unmodified under any supervisor. |
| 48 | CDN | **NOT APPLICABLE** | Out of scope per section 1. One caveat is recorded in item 53: the frontend does make a third-party network request at page load. |
| 49 | Managed PostgreSQL / connection pooling proxy | **NOT APPLICABLE** | Out of scope per section 1. Hikari in-process pooling is sufficient at the intended scale (item 10). |
| 50 | External monitoring / APM | **NOT APPLICABLE** | Out of scope per section 1. **This does not make monitoring adequate** - see item 30. CarbonFlow emits no metrics, so the operator has no machine-readable signal beyond stdout and the static health endpoint. |
| 51 | Infrastructure as code | **NOT APPLICABLE** | Out of scope per section 1. Environment configuration is therefore an operator responsibility and must be recorded outside this repository. |

### 4.8 Frontend deployment specifics

| # | Area | Status | Basis / Condition |
|---|---|---|---|
| 52 | Build output | **READY** | `npm run build` emits `dist/` with `index.html` and hashed `assets/`. Verified in the working tree. Sourcemaps are **off** (Vite default) - good for production, but a production stack trace cannot be symbolicated without a rebuild. |
| 53 | Third-party runtime dependency | **READY WITH CONDITIONS** | `index.html:12-14` loads Google Fonts (`fonts.googleapis.com`, `fonts.gstatic.com`) from a CDN at page load. No other external CDN is referenced (no jsdelivr, unpkg, or esm.sh). This leaks visitor IPs to Google and **will fail silently in an egress-restricted or air-gapped deployment**. Condition: either allow egress to those two hosts, or self-host the fonts. Note the CSP consequence: any future `style-src`/`font-src` policy must permit them, or the fonts must be local. |
| 54 | Asset path base | **READY WITH CONDITIONS** | No `build.base` is set, so Vite defaults to `/` and emits absolute `/assets/...` references (confirmed in `dist/index.html`). `dist/` is therefore **only servable from a domain root**. Hosting under a sub-path (e.g. `https://host/carbonflow/`) will 404 every asset. Condition: serve from the root, or set `base` and rebuild. |
| 55 | API base URL configuration | **READY WITH CONDITIONS** | `src/services/api.ts:51` reads `VITE_JAVA_API_BASE_URL` and falls back to `''`, which makes every call same-origin relative (`/api/v1/...`). That is the correct reverse-proxy mode. **Hazard: `.env.example:57` ships `VITE_JAVA_API_BASE_URL=http://localhost:8080`, and `README.md:45` instructs operators to copy `.env.example` to `.env` before building.** Following the documented path bakes `http://localhost:8080` into the production bundle, where it points at the end user's own machine. Condition: build production with `VITE_JAVA_API_BASE_URL` **unset or empty**. Verified: the committed `dist/` was built in correct same-origin mode. |
| 56 | Reverse proxy routing | **READY WITH CONDITIONS** | Required shape: `/api/v1/*` -> backend:8080, everything else -> static `dist/`. Because there is no URL router (item 2 of section 3), no SPA history fallback is required. There is no dev-server proxy in `vite.config.ts`, so cross-origin API access in development depends entirely on CORS and the `dev` profile - production should use same-origin mode. |
| 57 | Proxy body size limit | **READY WITH CONDITIONS** | The backend accepts files up to 25 MB and requests up to 28 MB (`application.properties:12-13`). A reverse proxy with a default 1 MB body limit will reject every evidence upload with a `413` before CarbonFlow sees it. Condition: set the proxy body limit to at least 28 MB. |
| 58 | Token storage on the client | **READY WITH CONDITIONS** | Access and refresh tokens are held in `localStorage` (`api.ts:38-42`) and in module-level variables. This is the accepted design recorded in `docs/SECURITY.md`, and it is why item 53's third-party font dependency matters more than it looks: a compromised or substituted script origin can read tokens. Refresh is single-flight with staleness guards (`api.ts:110-146`) and tokens are never logged or placed in a URL. Condition: this makes CSP and the egress restriction in item 53 load-bearing rather than cosmetic. |
| 59 | Frontend test execution | **READY WITH CONDITIONS** | 66 tests across 6 files, run by Node's built-in runner via `tsx` (`package.json` `test:frontend`). The file list is **explicitly enumerated**, not globbed, so a newly added test file does not run until the script is edited - this already happened once with `globalization.test.ts`. There is no DOM environment: components are asserted via `renderToStaticMarkup`, and network tests stub `globalThis.fetch`. No test mounts a component, dispatches an event, or exercises real interaction. Condition: treat the frontend suite as a static and contract check, not as UI verification. |
| 60 | Browser verification beyond the dev loop | **NOT READY** | `README.md:125-127` and `FRONTEND.md:70` both record that browser UAT was never performed across a browser matrix. Phase 10.13 does not close this. Condition: smoke-test in the target browsers before go-live - see section 7.6. |

---

## 5. Blocking items before production

These are the items that must be resolved, or explicitly accepted in writing, before
CarbonFlow is exposed to production traffic. They are listed by severity.

### 5.1 Must be fixed in code or configuration

| ID | Item | Ref |
|---|---|---|
| **B-1** | **`@EnableScheduling` is missing, so the hourly backup never runs** even when `carbonflow.recovery.backup.enabled=true`. The component is also disabled by default because the property is undefined. Approved recovery targets depend on an hourly backup that does not currently exist. | Item 38 |
| **B-2** | **The health endpoint always returns `UP`.** It cannot detect an unavailable database. A supervisor or proxy health-checking this endpoint will consider a broken instance healthy. | Item 28 |
| **B-3** | **No graceful shutdown.** In-flight requests are dropped on `SIGTERM`; a 28 MB evidence upload in progress is lost on restart or deploy. | Item 34 |
| **B-4** | **`DB_SSLMODE` defaults to `prefer`**, which silently falls back to plaintext. Production must set it explicitly to `verify-ca` or `verify-full`. | Item 22 |
| **B-5** | **Migration `V9` is untracked** while the recovery manifest still asserts `V1..V8`. Deploying either way produces a schema/backup-manifest mismatch. | Item 13 |
| **B-6** | **`drill-vault/` is gitignored only in the working tree, not in a commit.** Until committed, tenant evidence in that directory is committable. | Section 2.1 |

### 5.2 Must be supplied by the deployment environment

No code change is required for these; they simply do not exist in the repository,
so CarbonFlow is not safe until the operator provides them.

| ID | Item | Ref |
|---|---|---|
| **E-1** | **TLS termination in front of port 8080.** The application provides none, and port 8080 must never be directly internet-reachable. | Items 23, 26 |
| **E-2** | **A supervisor** providing restart-on-failure, start-on-boot, and a stop timeout greater than the configured shutdown grace period. | Item 34 |
| **E-3** | **stdout capture with rotation and retention.** CarbonFlow writes only to stdout and configures no rotation. | Item 31 |
| **E-4** | **Log-based alerting on `WARN`/`ERROR` lines** - specifically on `carbonflow.db.ssl-mode is 'prefer'`, the empty-CORS warning, and every recovery notification. There is no out-of-band alerting (item 44) and no metrics (item 30). | Items 30, 31, 44 |
| **E-5** | **`pg_dump`, `pg_dumpall`, `pg_restore`, and `gpg` on `PATH`** if any in-application recovery feature is enabled. | Item 39 |
| **E-6** | **A restore rehearsal into a scratch environment**, performed and recorded before go-live. No production-shaped restore has ever been run. | Items 40, 41 |
| **E-7** | **Environment variable injection from outside the repository**, per section 6. No production secret may live in this repository. | Items 19, 20 |

### 5.3 Should be resolved, or accepted as a documented risk

| ID | Item | Ref |
|---|---|---|
| **R-1** | No readiness/liveness endpoints and no metrics at all. | Items 29, 30 |
| **R-2** | Flyway `baseline-version=6` will silently skip migrations on a partially-migrated database. Requires a manual per-environment check. | Item 12 |
| **R-3** | No CI. Every build is manual and unrecorded, so "the artifact that was tested" and "the artifact that was deployed" cannot be proven identical. | Item 6 |
| **R-4** | Evidence vault restore is a manual file-copy procedure, not code. | Item 41 |
| **R-5** | Database and vault backups are not a single atomic snapshot. | Item 42 |
| **R-6** | Login throttle is per-instance and lost on restart. | Item 36 |
| **R-7** | `.env.example` ships a localhost `VITE_JAVA_API_BASE_URL` that a documented step copies into production builds. | Item 55 |
| **R-8** | Unbounded in-memory recovery notification list. | Item 44 |
| **R-9** | No environment-specific config files; production settings exist only as environment variables. | Item 18 |

---

## 6. Environment separation

Four environments. Each is separated by **configuration only** - CarbonFlow has
one codebase and one artifact shape. Nothing in this section requires a code
change.

| | Development | Test | Staging | Production |
|---|---|---|---|---|
| **Purpose** | Local development | Automated verification | Release rehearsal | Live tenant data |
| **Backend run** | `mvn spring-boot:run` | `mvn verify` (embedded PostgreSQL) | `java -jar` | `java -jar` |
| **Spring profile** | `dev` | `dbtest` | none | none |
| **Frontend** | `npm run dev` (port 5173) | `npm run test:frontend` | built `dist/` | built `dist/` |
| **Database** | Local PostgreSQL, separate DB name | `embedded-postgres` 2.0.7, in-memory | Separate server, disposable data | Separate server, real data |
| **Data** | Synthetic | Synthetic | Synthetic only | **Real tenant data** |
| **TLS to DB** | `prefer` / `disable` acceptable | n/a | `verify-ca` minimum | `verify-ca` or `verify-full` |
| **CORS origins** | `http://localhost:5173,http://localhost:3000` | none (tests stub `fetch`) | Staging origin | Production origin(s), exact |
| **Demo seed** | `CARBONFLOW_SEED_DEMO_DATA=true` optional | `true` (test profile) | `false` | **`false` - must be absent or false** |
| **Recovery backup** | Disabled | Disabled | Enabled, disposable | Enabled |
| **Recovery drill** | Disabled | Test-scoped | Enabled, scratch target | Enabled, **scratch target only** |
| **Evidence vault** | Local `vault_storage/` | `@TempDir` | Disposable directory | Persistent, backed up |
| **Secrets source** | Local shell / untracked `.env` | `application-dbtest.properties` literals | External secret store | **External secret store** |
| **Exposure** | Loopback only | None | Internal network | Behind TLS reverse proxy |

### 6.1 Rules

1. **No production credential may exist in this repository at any commit.**
   Verified currently true: `git log --all -- .env` is empty and only
   `.env.example` is tracked. Re-verify before any push to a shared remote, and
   again before any public release.
2. **Production secrets are injected from outside the repository.** Section 7.1
   lists the exact variables. The two signing keys must be generated per
   environment and must never be reused across environments - reusing
   `CARBONFLOW_JWT_SECRET` between staging and production means a staging token
   is a valid production token.
3. **No real tenant data in staging or test.** Staging must be rehearseable at
   production fidelity using synthetic data only. `docs/OPERATIONAL-VALIDATION.md`
   followed exactly this rule using a dedicated `carbonflow_drill` database, and
   that is the precedent to keep.
4. **`staging` and `production` are distinct databases.** The drill safety gate
   (`RecoverySchedulerConfiguration.java:184-185`) refuses to guess a target, and
   the recovery drill must never be pointed at the production database.
5. **The decommissioned Node backend (`server.ts`, `server/`) belongs to no
   environment** and must not be deployed anywhere.

### 6.2 Exact environment variables

Required - the application **refuses to start** without these:

| Variable | Used at | Constraint |
|---|---|---|
| `CARBONFLOW_JWT_SECRET` | `application.properties:24` | >= 32 bytes, else `IllegalStateException` |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | `application.properties:30` | >= 32 bytes, else `IllegalStateException` |
| `DB_HOST` | `application.properties:84` | Must resolve; used in the JDBC URL |
| `DB_NAME` | `application.properties:84` | Must resolve |
| `DB_USER` | `application.properties:85` | Must resolve |
| `DB_PASSWORD` | `application.properties:86` | Must resolve |

Required only when the corresponding feature is enabled:

| Variable | Used at | Note |
|---|---|---|
| `carbonflow.recovery.backup.root` | `RecoverySchedulerConfiguration.java:108,168`; `RecoveryBackupScheduleConfiguration.java:61` | **No default.** Enabling backups without it is a startup failure. |

Optional, with defaults:

| Variable | Default | Production guidance |
|---|---|---|
| `DB_PORT` | `5432` | Set explicitly; do not rely on the default. |
| `DB_SSLMODE` | `prefer` | **Set explicitly.** `verify-ca` or `verify-full`. See item 22. |
| `CARBONFLOW_EVIDENCE_VAULT_DIR` | `vault_storage` | **Set explicitly to an absolute path.** The default is relative to the process working directory, so an unattended service started from an unexpected directory writes vault files somewhere unbacked. |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | *(empty)* | Empty is correct if the frontend is same-origin. Otherwise list exact origins. Never `*` - it is refused at startup. |
| `CARBONFLOW_SEED_DEMO_DATA` | `false` | Must stay `false`. |
| `VITE_JAVA_API_BASE_URL` | *(unset -> same-origin)* | **Leave unset for reverse-proxy deployment.** See item 55 - this is a build-time value baked into the bundle. |

Build-host tooling (not consumed by the running application):
`CARBONFLOW_PG_DUMP`, `CARBONFLOW_PG_DUMPALL`, `CARBONFLOW_PG_RESTORE` override
`PATH` lookup in `PostgreSqlToolLocator`.

> Because all binding is `@Value` with no `@ConfigurationProperties` validation
> (item 17), a misspelled variable name **fails silently into its default**. For
> `DB_SSLMODE` that means a silent downgrade to plaintext. Verify these names
> character by character, and confirm the effective values from the startup log
> rather than from the deployment script.

---

## 7. Deployment checklist

Run in order. Every item is either satisfied by the repository or must be
satisfied by the operator; nothing here presumes a particular platform.

### 7.1 Pre-deployment

**Source and artefact identity**

- [ ] Working tree is **clean**: `git status --porcelain` returns nothing. A
      deployment from a dirty tree cannot be rolled back by commit. (Section 2.1)
- [ ] Deployment SHA is recorded in the release record.
- [ ] `db/migration/V9__remove_hardcoded_country_currency_defaults.sql` is
      **committed**, and `RecoveryBackupScheduleConfiguration.java:68`
      (`carbonflow.recovery.schema.versions`) is updated to `V1,...,V9`. (Item 13)
- [ ] `.gitignore` change adding `drill-vault/` is **committed**. (B-6)
- [ ] `server.ts`, `server/`, and `bun.lock` are **not** part of the deployment.
      (Section 2.1)

**Build verification** - no CI exists, so this is manual and its output must be
recorded:

- [ ] `cd backend-java && mvn clean verify` - passes
- [ ] `npx tsc --noEmit` - passes
- [ ] `npm run test:frontend` - passes
- [ ] Node on the build host satisfies `^20.19.0 || >=22.12.0`. `README.md:40`'s
      "Node.js 20+" is **insufficient**. (Item 5)
- [ ] `mvn clean package` produces `backend-java/target/carbonflow-backend-1.0.0-PRO.jar`
- [ ] `npm run build` produces `dist/` with `index.html` and hashed `assets/`
- [ ] Artefact checksums recorded

**Frontend build correctness** - verify *before* shipping, because the failure is
invisible until a user's browser hits it:

- [ ] `VITE_JAVA_API_BASE_URL` was **unset or empty** at build time. Grep the
      built bundle for `localhost:8080`; it must not match. (Item 55)
- [ ] `dist/index.html` references `/assets/...` (root-hosted). (Item 54)
- [ ] No `sourceMappingURL` in the bundle, confirming production sourcemap
      settings. (Item 52)

**Secrets and configuration**

- [ ] `CARBONFLOW_JWT_SECRET` set, >= 32 bytes, generated for **this** environment
- [ ] `CARBONFLOW_REFRESH_TOKEN_SECRET` set, >= 32 bytes, distinct from the JWT secret
- [ ] Neither secret is reused from staging or development
- [ ] `DB_USER` / `DB_PASSWORD` injected from the external secret store; not in the
      repository, not on a command line that is logged
- [ ] The untracked `.env` on the build host has been **rotated and removed**
      (item 20), and `git log --all -- .env` still returns nothing
- [ ] `CARBONFLOW_SEED_DEMO_DATA` is `false` or absent (item 21)
- [ ] `CARBONFLOW_EVIDENCE_VAULT_DIR` is an **absolute** path to a persistent,
      backed-up directory
- [ ] Every variable name in section 6.2 checked character by character (item 17)

**Database**

- [ ] `DB_SSLMODE` set explicitly to `verify-ca` or `verify-full` (B-4)
- [ ] If `verify-ca`/`verify-full`: the CA is in the JVM trust store via
      `-Djavax.net.ssl.trustStore`, and the trust store password is injected
      securely, not on the command line
- [ ] If `verify-full`: `DB_HOST` is the **DNS name** the certificate was issued
      for, not an IP address
- [ ] Target database is empty **or** already carries a `flyway_schema_history`
      consistent with its actual schema - see section 7.2
- [ ] Database is **not** directly reachable from the public internet (item 26)
- [ ] `max_connections` is sufficient for `maximum-pool-size=10` (item 10)

**Infrastructure and network**

- [ ] TLS terminates in front of port 8080 (E-1)
- [ ] Port 8080 is **not** directly exposed to the internet (E-1)
- [ ] Proxy routes `/api/v1/*` -> backend:8080 and everything else -> `dist/`,
      served from the domain root (items 54, 56)
- [ ] Proxy body size limit is **>= 28 MB** (item 57)
- [ ] Supervisor configured: restart on failure, start on boot, and stop timeout
      **greater than** the graceful shutdown period once B-3 is fixed (E-2)
- [ ] stdout is captured with rotation and retention configured (E-3)
- [ ] Egress policy reviewed against the Google Fonts dependency (item 53)

**Recovery tooling**

- [ ] `pg_dump`, `pg_restore`, and `gpg` resolvable on the target host, if
      in-application recovery is enabled (E-5)
- [ ] `carbonflow.recovery.backup.root` set to an absolute path (section 6.2)
- [ ] Backup destination is **on separate storage** from the live database and
      the live vault
- [ ] Recovery drill target is a **scratch** database, never production (item 43)

### 7.2 Database migration

Flyway runs **inside the application at startup** (`application.properties:126-129`).
There is no separate migration step and no separate migration tool invocation is
required, though `docs/EXECUTION.md` documents the CLI equivalent.

> **The `baseline-version=6` setting is the single most dangerous thing in this
> section.** Read section 7.2.1 before touching a non-empty database.

- [ ] Establish the current state of the target schema **before** starting the
      application: does `flyway_schema_history` exist, and what is its content?
- [ ] If the database is **empty**: start one instance and let Flyway apply
      V1..Vn in order. Verify.
- [ ] If the database is **already fully migrated** through V6 with no
      `flyway_schema_history` (the situation the baseline setting exists for):
      Flyway will baseline at V6 and apply V7 onward. Verify the applied list.
- [ ] If the database is **partially migrated** (for example, manually applied
      through V4): **Flyway must not be started against it.** It would baseline at
      V6 and permanently skip V5. Reconcile by hand - either create a correct
      `flyway_schema_history` or complete the migration manually. This is exactly
      what `application.properties:124` warns about. (Item 12)
- [ ] **Only one instance is started during migration.** Two instances booting
      simultaneously can race on the migration lock and against `flyway_schema_history`.
      (Item 35)
- [ ] Migration outcome recorded: which versions applied, and any warnings.
- [ ] After migration, confirm the schema matches expectations - in particular
      that `organizations.country` has no default and `organization_settings.currency`
      is nullable if V9 applied (item 13).

### 7.3 Deployment

- [ ] Artefacts in place: the jar, and `dist/` served as static files
- [ ] Environment variables injected from the external secret store (never from a
      file committed to the repository)
- [ ] Working directory of the service process is known and stable, because
      `CARBONFLOW_EVIDENCE_VAULT_DIR` defaults to a path relative to it
- [ ] Start **one** instance
- [ ] Watch the startup log for the two fail-closed checks that confirm secrets
      were accepted, and the `DataSourceTls` line that confirms the **effective**
      `sslmode`
- [ ] Confirm no `WARN` from `DataSourceTls` (a `prefer` warning means B-4 was
      not addressed)
- [ ] Confirm the CORS allow-list line reports the expected origin count, or the
      expected empty-list warning if the frontend is same-origin
- [ ] If recovery backup is intended to be **enabled**, verify the scheduling
      defect in item 38 is fixed first - otherwise the hourly backup silently does
      nothing.
- [ ] Wait for a successful health response **before** starting further instances
- [ ] Point the frontend at the deployed API (same-origin, or the built
      `VITE_JAVA_API_BASE_URL`)

### 7.4 Post-deployment smoke tests

Ordered. Each has an observable pass condition. All are manual - CarbonFlow
exposes no metrics (item 30) and no probes (item 29).

| # | Check | Pass condition |
|---|---|---|
| 1 | `GET /api/v1/health` | HTTP 200 with `status`, `version`, `runtime` fields |
| 2 | Startup log review | No `ERROR`; no `DataSourceTls` insecure-mode warning |
| 3 | Frontend loads over HTTPS | Page renders; no mixed-content warning in the browser console |
| 4 | Login with a real production user | Succeeds; access token issued |
| 5 | Unauthenticated `GET /api/v1/...` | HTTP 401 with the standard envelope |
| 6 | Cross-origin request from an unlisted origin | **Refused** - no `Access-Control-Allow-Origin` header |
| 7 | Tenant isolation | A user in tenant A cannot read tenant B's data (any single record) |
| 8 | Evidence upload | A file below 25 MB uploads and its SHA-256 verifies |
| 9 | Oversized upload | A file above 25 MB is refused with `UPLOAD_FAILED` |
| 10 | CSV export | Exports, and the `Authorization` header is used rather than a query-string token |
| 11 | Audit lock and correction | The 10-state machine behaves as documented |
| 12 | Vault files on disk | Present under the configured absolute `CARBONFLOW_EVIDENCE_VAULT_DIR` |
| 13 | Browser matrix | Manual walkthrough in the target browsers (item 60 - never performed) |
| 14 | `SIGTERM` behaviour | Observed and recorded **before** relying on it; currently requests are dropped (B-3) |

### 7.5 Security verification

- [ ] TLS certificate valid, chain complete, hostname matches
- [ ] HSTS enabled **at the proxy** - the application does not set it (item 23)
- [ ] HTTP redirects to HTTPS at the proxy
- [ ] Port 8080 unreachable from the public internet
- [ ] Database port unreachable except from the application host
- [ ] `DB_SSLMODE` confirmed at its intended value from the **startup log**, not
      from the deployment script (item 22)
- [ ] Effective `sslmode` verified from the driver's perspective, not assumed
- [ ] `CARBONFLOW_JWT_SECRET` and `CARBONFLOW_REFRESH_TOKEN_SECRET` are distinct
      from each other and from every other environment
- [ ] `CARBONFLOW_SEED_DEMO_DATA` is `false`; no demo identity exists in the
      production database
- [ ] No endpoint outside `/api/v1` is unintentionally public (item 26)
- [ ] No secret appears in the application log; confirm by inspecting a sample
      (item 32)
- [ ] `RecoverySetGuard` rejects secret-named fields from any recovery manifest
- [ ] Frontend bundle contains no secret and no localhost API URL (item 55)
- [ ] Third-party font egress either allowed deliberately or fonts self-hosted
      (item 53)
- [ ] `git log --all` searched for secret patterns; `git log --all -- .env`
      returns nothing
- [ ] Stale `.env` credentials rotated (item 20)

### 7.6 Backup verification

Verification that a backup **exists** is not verification that a restore
**works**. Both are required, and the second has never been done against
production-shaped data.

**Existence and scheduling**

- [ ] A backup set exists in the configured destination
- [ ] Its manifest is present and internally consistent
- [ ] The set contains **both** the database dump and the evidence vault -
      a database-only backup is not a recoverable system (section 3, item 4)
- [ ] Retention is actually pruning per the 30-day policy
- [ ] **The scheduled job is genuinely firing.** This cannot be assumed. See
      item 38: the trigger is disabled by default and, absent `@EnableScheduling`,
      does not fire even when enabled. Confirm by observing two consecutive
      scheduled runs, not by reading configuration.
- [ ] Encryption at rest is confirmed - an unencrypted backup set left on disk is
      a finding in itself
- [ ] Backup failure produces a **log line** and nothing more (item 44). Confirm
      the log-based alerting from E-4 would actually catch it, by inspecting the
      alert rule rather than assuming.

**Restorability** - mandatory before go-live (E-6):

- [ ] Database restored into a **scratch** database, never production
- [ ] Restore completes and row counts reconcile against the source
- [ ] Evidence vault restored into a scratch directory and file hashes reconcile
      against the integrity index
- [ ] The restored application starts, authenticates, and serves tenant data
- [ ] Mismatches between the database and vault are understood - they are expected
      because the pair is not an atomic snapshot (item 42)
- [ ] Actual elapsed restore time is **recorded**
- [ ] That measured time is compared against the approved 4-hour RTO, and the
      result is recorded as a **measurement** - not as a compliance claim.
      `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` prohibits stating
      "RTO MET" or "RPO MET" until a real compliance test is performed.

**Cadence**

- [ ] A quarterly restore drill is scheduled and owned (item 45)

### 7.7 Rollback

Full procedure in section 8. Checklist summary:

- [ ] Previous known-good artefact identified **before** deployment
- [ ] Previous artefact still available and startable
- [ ] Rollback decision made and recorded by a named owner
- [ ] Database rollback path chosen deliberately, understanding that destructive
      rollback is **not** automatically safe (section 8.3)
- [ ] A fresh backup taken **before** any destructive database action
- [ ] Post-rollback smoke tests (section 7.4) re-run

---

## 8. Rollback

Rollback in CarbonFlow has three independent parts. They are **not**
interchangeable, and only the first is genuinely reversible.

### 8.1 Application rollback

**This is the safe, reversible part.**

1. Stop the running instance.
2. Redeploy the previous artefact:
   - Backend: the previous `carbonflow-backend-1.0.0-PRO.jar`
   - Frontend: the previous `dist/`
3. Restore the previous environment variable set for that release.
4. Start one instance. Verify health.
5. Re-run the smoke tests in section 7.4.

**Constraints:**

- Rollback by **artefact version** is only meaningful if the previous artefact is
  still available. Retain at least the current and previous release.
- The artefact must be identified by commit. This is only possible from a clean
  tree (section 2.1) - a dirty-tree build cannot be mapped back to a commit.
- Rolling back the **frontend alone** is usually safe: it is a static bundle and
  the API surface is versioned. Rolling back the **backend alone** may not be, if
  the frontend expects a newer API. Roll back both unless the change is provably
  backend-only.
- A frontend rollback does not clear a cached bundle in users' browsers. If
  `Cache-Control` is aggressive, users may keep the newer bundle after the server
  has been rolled back. Bounded asset caching by hashed filename is correct;
  `index.html` must not be cached.

### 8.2 Schema compatibility

The safest and most common rollback is **application-only**: deploy the previous
jar against the **current, migrated** schema, provided the previous version still
works against it.

This is possible only if the migration was **backwards compatible** - additive
only, with no dropped or renamed columns, no changed types, and no new NOT NULL
constraint without a default. CarbonFlow's migrations are largely additive, so
this is often viable. **It must be verified per migration by reading the SQL**;
it is not a property the tooling guarantees.

If the previous version is **not** backwards compatible with the migrated schema,
the options are, in order of preference:

1. **Deploy a forward fix.** Usually the correct answer.
2. **Restore the database from backup.** Destructive - see 8.3.
3. **Hand-write a compensating migration.** Introduces schema state that no
   migration produced; every future `flyway validate` and every future baseline
   assumption must account for it. Use only with a documented decision.

### 8.3 Database migration rollback limitations

**Destructive database rollback is not automatically safe. This section must be
read in full before any database restore is attempted.**

**Flyway Community provides no `undo` capability**, and CarbonFlow contains no
down-migration scripts. There is no supported reverse migration. This is a
structural limitation, not a gap that a future phase will quietly close.

Consequences:

- Every applied migration is **permanent** in the normal sense. Rolling the
  application back does not roll the schema back.
- A `DROP COLUMN` or destructive `UPDATE` in an applied migration **cannot be
  reversed by the application**.
- The only reversal mechanism is a **restore from backup**, which is a
  point-in-time reset, not a migration reversal.
- Restoring from backup **discards every write made since the backup was taken.**
  This includes real tenant data. It is a data-loss event, not a technical
  rollback, and it must be treated as an incident.
- `pg_restore` will **fail** on an existing schema unless the target is clean. In
  practice that means dropping and recreating the target database, which is
  exactly the destructive step that must be justified explicitly.
- Restoring also discards the `flyway_schema_history` state as it was at backup
  time. The restored database will therefore be at the **old** schema version,
  and the **new** application will re-apply any newer migrations on next start.
  If that is not intended, the previous application must be deployed alongside
  the restore.

Procedure, if a restore is genuinely required:

1. **Declare an incident.** This is a data-loss event with tenant impact.
2. Record the exact backup set identifier and its manifest, and verify that set
   with `BackupVerifier` **before** touching anything.
3. Take a fresh backup of the **current** database, so that the pre-restore state
   is itself recoverable. This is the step most often skipped, and skipping it
   removes any way back.
4. Confirm the data loss window explicitly and record its start and end times.
5. Restore into a **clean** target database. Not over the live one.
6. Verify the restored schema version against the intended application version.
7. Deploy the **matching** application version - not the newest one.
8. Re-run section 7.4 smoke tests.
9. Reconcile the data loss window with the business owner. Tenant writes in that
   window are gone and must be communicated.

**Never** restore over production without steps 2, 3, and 4 complete.

### 8.4 Evidence vault restore

Independent of the database restore, and governed by the same incident rules.

- The vault has **no restore code** (item 41). Restore is the manual procedure in
  `docs/BACKUP-RECOVERY.md`: copy the vault tree from the recovery set into the
  configured `CARBONFLOW_EVIDENCE_VAULT_DIR`.
- `EvidenceVaultIntegrityIndex` (`vault-integrity.json`, written by
  `EvidenceVaultBackupService`) records the expected SHA-256 and byte length per
  file. It is the verification mechanism - **verify every file against it.**
- Restoring the vault **without** restoring the database, or vice versa, produces
  a system whose metadata references files that do not exist, or files no record
  references. Both directions are wrong; neither is recoverable automatically.
- **Order matters.** Because the pair is not an atomic snapshot (item 42), restore
  the **database first**, then reconcile the vault against it. Files in the vault
  with no referencing row are orphaned but harmless; rows referencing absent files
  are broken evidence in an audit system, which is materially worse.
- Verify tenant ownership and path safety on restore - `EvidencePathGuard` exists
  to reject unsafe paths on write and the same discipline applies to a manual
  copy.

### 8.5 Post-rollback verification

- [ ] Health endpoint responds
- [ ] Startup log shows no `ERROR` and no insecure-`sslmode` warning
- [ ] Frontend loads over HTTPS
- [ ] Login succeeds
- [ ] Tenant isolation verified
- [ ] Evidence download works and SHA-256 matches
- [ ] Audit workflow behaves as expected for the rolled-back version
- [ ] Database schema version matches the deployed application version
- [ ] Vault integrity verified against the index
- [ ] Data loss window reconciled and communicated
- [ ] Incident record written, including what was rolled back, why, and what was
      lost
- [ ] If a migration was reverted, `flyway validate` passes on the restored
      database

---

## 9. Statements this document does not make

For consistency with `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`, the following
remain false and are not asserted anywhere above:

- That CarbonFlow has met, missed, or complies with its RTO or RPO
- That CarbonFlow has any contractual SLA
- That any restore has been performed against production data
- That a browser matrix beyond the development loop has been verified
- That the automated hourly backup runs
- That the deployment has been rehearsed end to end in any environment

---

## 10. Summary

CarbonFlow is a conventional two-artefact Java + static-frontend application with
a single-instance architecture, and it is **closer to deployable than the
long list of findings above suggests**. Its foundations are genuinely sound: the
application fails closed on missing signing secrets, validates the database TLS
mode rather than silently degrading it, refuses a wildcard CORS origin at startup,
emits no stack traces or secrets to clients or logs, and defaults every
security-relevant toggle to the safe value.

What is missing is not primarily code quality. It is the operational layer
between a working application and a running service:

- **B-1** means the approved hourly backup does not exist. The recovery
  machinery is substantial and well built; the trigger that would run it is
  missing one annotation. This is the highest-value single fix in this document,
  because approved recovery targets depend on it.
- **B-2** and **B-3** mean the service cannot be supervised safely: it reports
  healthy while broken, and drops in-flight requests when restarted.
- **E-1** through **E-7** are not defects in CarbonFlow. They are simply things
  the repository does not contain and an operator must provide - TLS
  termination, process supervision, log capture, alerting, backup tooling, a
  restore rehearsal, and external secret injection.

A deployment is defensible once B-1 through B-6 are resolved or explicitly
accepted in writing, the E-items are in place, the section 7 checklist is
completed with its results recorded, and the deployment is cut from a clean,
committed tree.

No deployment was performed in this phase. No production data was touched. No
restore was attempted. No cloud provider, orchestrator, container platform, CDN,
managed database, or external monitoring service was introduced, recommended, or
assumed.

**Status: NOT READY for production deployment.** Seven blocking items are
identified in section 5, of which three (B-1, B-2, B-3) are application defects
with small, well-understood fixes, and seven (E-1 to E-7) are environment
obligations that no amount of code changes will satisfy.

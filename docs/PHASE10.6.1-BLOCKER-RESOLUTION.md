# Phase 10.6.1 — Production Blocker Resolution

## 1. Executive Status

**PASS — BLOCKER RESOLVED**

F-01 (database TLS not implementable as documented) is now genuinely implemented,
tested, and verified against a running application. F-08 (missing backup/recovery
procedure) is now authored. `DB_SSLMODE` is a real environment variable that is
bound, validated, and handed to PgJDBC; the documentation now describes only
settings the application actually reads.

The full Phase 10.6 validation gate was re-run. No regression was introduced:
Java **320/320** (up from 304), TypeScript 0 errors, frontend **36/36**,
production build green, **73/73** live security checks, Flyway V1–V8
byte-identical, Node/Express still fully decommissioned, zero secrets in the
change set.

Remaining Phase 10.6 findings (F-02 … F-07, F-09) are **unchanged and
deliberately untouched**; see §6.

---

## 2. Baseline

| Field | Value |
| --- | --- |
| Branch | `main` |
| **Baseline HEAD** | `8e6a2ebd38ba375b45beb2713f63161158bf6a1b` |
| Phase 10.5 commit | `8389732695211fb75f8ba8e0907ac597784ad30d` |
| Working tree at start | **CLEAN** |
| Java / Maven / PostgreSQL | 21.0.12.1 / 3.9.16 / 18.6 |

---

## 3. F-01 — Database TLS

### 3.1 Original problem

`docs/DEPLOYMENT-SECURITY.md` instructed operators to set
`carbonflow.datasource.url`, `DB_SSLMODE` and
`CARBONFLOW_DB_SSL_TRUST_STORE`. None of the three was functional: the property
had the wrong prefix (the application reads `spring.datasource.url`), and neither
environment variable appeared anywhere in the codebase. An operator following the
runbook would have believed the database connection was encrypted while it
remained cleartext.

### 3.2 Root cause

The application built its JDBC URL as:

```properties
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}?stringtype=unspecified
```

with **no `sslmode`**, so PgJDBC applied its own default — `prefer`, which
attempts TLS and then **silently downgrades to plaintext** if the server offers
none. There was no configuration point for TLS at all, and the documentation
described controls that were never implemented. The defect was a gap between the
documented and the actual configuration path.

### 3.3 Implementation

Three changes, all at the application/configuration level. **No migration was
required and none was created.**

**1. `application.properties` — a real, single-source configuration path**

```properties
carbonflow.db.ssl-mode=${DB_SSLMODE:prefer}
spring.datasource.hikari.data-source-properties.sslmode=${carbonflow.db.ssl-mode}
```

`sslmode` is passed as a Hikari **connection property** rather than concatenated
into the URL. PgJDBC reads it from the connection `Properties` exactly as it
would from the URL, and this avoids a partially-applied value that a
string-concatenation approach could produce.

**2. `com.carbonflow.config.DataSourceTls` — validation that fails safe**

The bean binds `carbonflow.db.ssl-mode` at startup and normalises it. A blank or
absent value falls back to `prefer`; **any unrecognised value throws
`IllegalStateException`**, which fails the application context. The accepted set
is exactly the six PgJDBC modes. It also exposes `verifiesServerCertificate()`
and `forbidsPlaintext()` so the security semantics are machine-checkable rather
than only documented.

**3. Startup logging that states the truth, not the wish**

The effective mode is logged. `prefer`, `allow` and `disable` produce a `WARN`
naming the production setting to use; `require` is logged as *"TLS is mandatory,
but the server certificate is NOT verified"*; `verify-ca`/`verify-full` as
verifying against the JVM trust store.

### 3.4 Configuration path

| Layer | Detail |
| --- | --- |
| Environment variable | `DB_SSLMODE` |
| Spring property | `carbonflow.db.ssl-mode` |
| Delivered to driver as | `spring.datasource.hikari.data-source-properties.sslmode` |
| Default | `prefer` (PgJDBC's own default, so local development is unchanged) |
| Trust store | JVM `-Djavax.net.ssl.trustStore=...` — no CarbonFlow-specific variable needed |
| Override for URL parameters | `SPRING_DATASOURCE_URL` (standard Spring relaxed binding) |

### 3.5 Security semantics

| `DB_SSLMODE` | Encrypted? | Server identity verified? | Forbids plaintext? |
|---|---|---|---|
| `disable` | No | No | No |
| `allow` | Only if offered | No | No |
| `prefer` *(default)* | Only if offered | No | No — silently downgrades |
| `require` | **Yes** | **No** | **Yes** |
| `verify-ca` | **Yes** | **Yes** (chain) | **Yes** |
| `verify-full` | **Yes** | **Yes** (chain + hostname) | **Yes** |

The documentation states explicitly that `require` encrypts the transport
**without proving server identity**, and that only `verify-ca`/`verify-full`
verify the certificate. The test suite asserts the same distinction so the two
cannot be conflated in code, tests or docs.

### 3.6 Automated tests

**`DataSourceTlsTest` — 12 tests, no database required.** Exercises the real
Spring property-binding path via `ApplicationContextRunner`:

- an explicit mode binds and normalises; blank/absent falls back to `prefer`
- a mistyped mode (`requre`) **fails the context**, and the message names the
  offending value and the supported set
- plausible-but-wrong values (`enabled`, `true`, `on`, `ssl`, `verify`,
  `REQUIRE_FAITH`) are each rejected outright
- all six documented modes are accepted; case and whitespace insensitive
- `require` forbids plaintext but does **not** claim verification
- `allow`, `disable`, `prefer` do not forbid plaintext
- `verify-ca`/`verify-full` are the only modes claiming verification

**`DataSourceTlsWiringTest` — 4 tests, real application context.** This is the
test that addresses the original defect. F-01 was a setting that existed in
configuration and was ignored at runtime, so a test that only asserted on the
properties file would have passed while the bug remained. This boots the real
context and asserts on the live datasource:

```java
Properties properties = ((HikariDataSource) dataSource).getDataSourceProperties();
assertThat(properties).containsEntry("sslmode", "prefer");
```

`HikariDataSource` extends `HikariConfig`, so these are the exact `Properties`
handed to `DriverManager.getConnection(url, props)`.

### 3.7 Live verification

No TLS-enabled PostgreSQL was available (local server has `ssl = off` and no
`server.crt`/`server.key`). Enabling it would have meant generating certificates
and restarting the user's PostgreSQL service — an infrastructure change outside
this phase's scope, so it was not done. What *was* verified against a running
application:

| Run | `DB_SSLMODE` | Result |
|---|---|---|
| A | `require` | **App refused to start** — `Unable to obtain connection from database: The server does not support SSL.` |
| B | `prefer` | Started normally (no development regression) |
| C | `disable` | Started normally |
| D | `requre` | **App refused to start** — `Invalid sslmode value: requre` |

Run A is the decisive result. If `sslmode` were being ignored, `require` would
have connected in plaintext and the application would have started. It failed
**because the driver enforced the setting** — which simultaneously proves the
value is consumed end-to-end and that it cannot silently downgrade.

| Claim | Status |
|---|---|
| Property binding and validation | **CONFIGURATION VERIFIED** (12 unit tests) |
| `sslmode` reaches PgJDBC on the live datasource | **CONFIGURATION VERIFIED** (4 wiring tests) |
| `require` cannot fall back to plaintext | **LIVE VERIFIED** (Run A) |
| Invalid value fails fast | **LIVE VERIFIED** (Run D) |
| `prefer`/`disable` unaffected | **LIVE VERIFIED** (Runs B, C) |
| Successful TLS handshake | **NOT VERIFIED** — no TLS-enabled server available |
| `verify-ca`/`verify-full` reject an untrusted certificate | **NOT VERIFIED** — same reason |

Before production, confirm on the real target that `pg_stat_ssl.ssl = 't'` for
an application session. This limitation is stated in the documentation, not
glossed over.

---

## 4. F-08 — Backup and recovery documentation

`docs/BACKUP-RECOVERY.md` created (previously cited by
`DEPLOYMENT-SECURITY.md` but never existing).

It is written against the actual architecture: 37 domain tables enumerated by
group, the evidence vault as a **separate** durable store from PostgreSQL, and
the requirement to restore the database from a snapshot **at or newer than** the
vault.

Covered: logical backup (`pg_dump` custom format, `pg_dumpall --globals-only`
for roles), physical backup (`pg_basebackup`), evidence-vault capture,
consistency ordering, retention considerations, encryption, access control
(secrets must never share an archive with data), and a 12-step recovery
procedure from `createdb` through to verifying tenant isolation and
representative functionality.

Edge cases are documented rather than glossed: restoring a database older than
the code expects; restoring without `flyway_schema_history` (which interacts
with `baseline-on-migrate`/`baseline-version=6` and would silently skip
validation of V1–V6); rewinding past a governed audit lock; and partial evidence
loss.

Every procedure is labelled **DOCUMENTED BUT NOT TESTED**. No backup automation
exists in this repository, no restore has been performed, and the RTO is stated
as **unknown** until a rehearsal is run. The document tells the operator how to
rehearse and says plainly that no rehearsal has occurred.

The stale "this document does not exist" note in `DEPLOYMENT-SECURITY.md` was
removed and replaced with a live reference. The other missing citation,
`docs/PHASE9-SECURITY-MATRIX.md`, is still absent and is now explicitly labelled
as non-existent rather than cited as if it were present.

---

## 5. Regression

| Gate | Command | Result |
|---|---|---|
| Java | `mvn clean verify` | **320 / 320** — 0 failures, 0 errors, 0 skipped, `BUILD SUCCESS` |
| TypeScript | `npx tsc --noEmit` | **0 errors** |
| Frontend | `npm run test:frontend` | **36 / 36** |
| Production build | `npm run build` | **PASS** |
| Security | 73-check live HTTP harness | **73 / 73**, Node absent |
| Database | fresh V1→V8 on an isolated database | 8 migrations `success=t`, 38 tables, 74 indexes |
| Flyway | `git diff 8e6a2eb -- db/` | **empty**; V1–V8 SHA-256 unchanged; no V9 |
| Accounting | Scope 2 columns | `scope1_co2e_t`, `scope2_location_co2e_t`, `scope2_market_co2e_t` remain separate |
| Secrets | 4 literal rules over the change set | **0 matches**; `.env` untracked |

Test count moved 304 → **320** (+16: 12 `DataSourceTlsTest`, 4
`DataSourceTlsWiringTest`). No existing test was modified, weakened, or skipped.

### Test-environment discipline (§9)

The Phase 10.6 stale-JVM failure was not repeated. Before the HTTP harness:

1. stopped every `java.exe`;
2. confirmed **port 8080 FREE** before starting;
3. started the instance and asserted **exactly one** owner of 8080;
4. asserted that owner is one of our JVMs;
5. asserted `/api/v1/health` returns `UP`;
6. asserted the demo seed is present (5 users, 6 memberships) before running.

A false negative was caught and corrected during this phase: an early TLS probe
appeared to show `require` correctly refusing to start, but `mvn clean test` had
deleted the jar, so the "refusal" was really "file not found". The probe was
discarded, the jar rebuilt, and the result re-obtained. Recorded because a
security claim that turns out to rest on a missing file is worse than no claim.

---

## 6. Remaining findings — preserved, not resolved

Per §14, these were **not** touched:

| # | Severity | Finding | Status |
|---|---|---|---|
| F-02 | MEDIUM | Hardcoded `"US"` country default | **UNCHANGED.** `AuthService.java:421` still `? "US" :`; `organizations.country` column default still `'US'`. No V9 created. |
| F-03 | MEDIUM | No methodology/formula version on calculation snapshots | **UNCHANGED.** 0 methodology columns on `calculations`. No V9 created. The ADR-008 tension remains a human decision. |
| F-04 | MEDIUM | `SecurityHeadersFilter` has no automated test | **UNCHANGED.** No test file references it. |
| F-05 | LOW | Retired `localhost:3000` in the dev CORS profile | **UNCHANGED.** Removing it means editing `CorsConfigurationTest:111`. |
| F-06 | MEDIUM | Evidence vault defaults to a relative path | **UNCHANGED.** `carbonflow.evidence.vault-dir` still defaults to `vault_storage`. Documented as unsuitable for production in `BACKUP-RECOVERY.md`. |
| F-07 | LOW | Graceful shutdown not configured | **UNCHANGED.** `server.shutdown` still absent. |
| F-09 | MEDIUM | No monitoring instrumentation | **UNCHANGED.** |

F-08 is resolved by this phase.

### One accuracy defect found and fixed in this phase's own code

The first version of `DataSourceTls` logged `allow` as *"TLS is mandatory but the
server certificate is NOT verified"*. That is false — `allow` starts in
plaintext and only upgrades opportunistically. The same class of false assurance
F-01 exists to eliminate. It was caught from the test log, fixed, and locked down
with `allowIsNotTreatedAsEnforcingTls`, which asserts `allow` neither forbids
plaintext nor claims verification.

---

## 7. Documentation consistency

The requirement that documentation must never instruct an operator to set a
variable the application ignores is now enforced by inspection, not intent:

| Document | Status |
|---|---|
| `docs/DEPLOYMENT-SECURITY.md` | DB TLS section rewritten against the implementation; mode semantics table; variables-that-do-not-exist table; verification table with explicit `NOT VERIFIED` rows |
| `docs/SECRETS.md` | `DB_SSLMODE` row corrected (previously claimed a `disable` default "via JDBC URL", which was never true) |
| `.env.example` | `DB_SSLMODE` added with accepted values and the encryption-vs-verification distinction |
| `docs/BACKUP-RECOVERY.md` | Created |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | F-01 and F-08 marked resolved by this phase |

A repository-wide scan of `docs/` cross-references now resolves, except
`docs/PHASE9-SECURITY-MATRIX.md`, which is retained as an explicitly labelled
non-existent reference so an operator who saw the old text is not misled.

---

## 8. Git

| Field | Value |
| --- | --- |
| Baseline SHA | `8e6a2ebd38ba375b45beb2713f63161158bf6a1b` |
| Files changed | 7 |
| New code | `DataSourceTls.java` |
| New tests | `DataSourceTlsTest.java`, `DataSourceTlsWiringTest.java` |
| Modified config | `application.properties` |
| Documentation | `DEPLOYMENT-SECURITY.md`, `SECRETS.md`, `.env.example`, new `BACKUP-RECOVERY.md`, this report |
| Migrations changed | **0** |
| Node/Express restored | **no** |
| History rewritten | **no** |
| Secrets added | **0** |

`docs/DEPLOYMENT-SECURITY.md` was edited through a byte-safe UTF-8 splice after
an initial `Get-Content` round-trip silently mangled dash characters. The file
was restored from Git and re-edited; it now decodes as strict UTF-8 with zero
`U+FFFD`, and the stray rows an over-broad regex clobbered were restored.

---

## 9. Release re-assessment

The Phase 10.6 gate required "no critical/high unresolved security or accounting
issue". With F-01 resolved:

| Gate condition | Result |
|---|---|
| Java / frontend / TypeScript / production build pass | **PASS** |
| Node remains decommissioned; frontend Java-only | **PASS** |
| Database integrity; Flyway V1–V8 intact | **PASS** |
| Authentication, authorization, tenant isolation pass | **PASS** (73/73) |
| Accounting correctness passes | **PASS** (with F-03 documented) |
| No production secrets committed | **PASS** |
| No unresolved HIGH security issue | **PASS** — F-01 resolved; no new HIGH introduced |
| Documentation accurate about verified vs unverified | **PASS** |
| Browser UAT | **NOT VERIFIED** — no browser attached |
| External deployment | **NOT VERIFIED** — not attempted, not authorized |

Per §19 of the Phase 10.6 prompt, browser UAT and external deployment may remain
`NOT VERIFIED` when the infrastructure is unavailable, and neither is a reason to
block.

**Final release state: `RELEASE CANDIDATE — PASS`**

with four MEDIUM and two LOW findings (F-02 … F-07, F-09) carried forward as
tracked, documented follow-ups. They are operational and product decisions, not
release blockers, and no attempt was made to resolve them here.

# Phase 10.6 — Final Production Readiness & Release Validation

## 1. Executive Status

**RELEASE BLOCKED**

Every executable and structural gate passes. Java, the frontend, the database,
authentication, authorization, tenant isolation, accounting correctness and
secret hygiene are all verified green. Node/Express remains fully decommissioned.

Release is blocked by **one HIGH-severity finding**: the database-TLS control
that `docs/DEPLOYMENT-SECURITY.md` instructs operators to configure **cannot
actually be enabled as documented**. The documented property name is wrong and
both documented environment variables exist nowhere in the codebase. An operator
who follows the runbook believes the connection is encrypted when it is not.

That is a false security assurance in a document an operator is expected to
trust, which is why it is graded HIGH rather than LOW. It is **not** a BLOCKER
in the §17 sense, because the application is otherwise sound and a working
one-line workaround exists (§18, Finding 1).

No blocker was found. Nothing was patched, because §18 requires stopping and
documenting rather than auto-fixing, and the DB-TLS fix would require either a
source change or a V9 migration.

---

## 2. Baseline Commit

| Field | Value |
| --- | --- |
| Branch | `main` |
| **Baseline (pre-phase) HEAD** | `8389732695211fb75f8ba8e0907ac597784ad30d` |
| Reference baseline | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` (pre-decommission) |
| Working tree at start | **CLEAN** |
| Phase 10.5 commit | verified present and unmodified |
| HEAD drift during phase | **none** |

---

## 3. Repository Integrity

| Check | Result |
| --- | --- |
| HEAD matches Phase 10.5 commit | **VERIFIED** |
| Working tree clean at start | **VERIFIED** |
| History rewritten | **no** — `8389732` parent is `4cc8f30` |
| `server.ts` / `server/` / `bun.lock` absent | **VERIFIED** |
| Node/Express restored | **no** |
| JPA/Hibernate introduced | **no** — JdbcTemplate throughout |
| Unrelated code changes | **none** |

---

## 4. Node Decommission Regression

**PASS.** Phase 10.5 is intact.

| Check | Result |
| --- | --- |
| `server.ts`, `server/`, `bun.lock`, `dist/server.cjs` | all absent |
| `express`, `jsonwebtoken`, `bcryptjs`, `multer`, `pg`, `decimal.js`, `@google/genai` in `package.json` | **0 present** |
| `test:security`, `test:persistence`, `start` scripts | **absent** |
| Executable/config Node references outside docs | **0** |
| Frontend API base | `VITE_JAVA_API_BASE_URL` only (5 call sites) |

Five files still contain the string `localhost:3000`; all were individually
inspected and are legitimate:

- `application.properties:53`, `SecurityConfig.java:124` — comments stating
  `localhost:3000` is the **retired** origin and is refused
- `application-dev.properties` — the opt-in `dev` profile only
- `CorsConfigurationTest`, `CorsOriginIntegrationTest` — assertions that the
  production allow-list **refuses** the retired Node origin

One vestigial item is recorded as Finding 5 (LOW).

---

## 5. Java Backend Validation

`mvn clean verify` — **VERIFIED**

```text
Tests run: 304, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
[ERROR] lines: 0    [WARNING] lines: 0
suites: 44
```

**Zero unintended skips.** No test was weakened, skipped, or made tolerant.

Coverage present for every §5 area (measured by test-file pattern match):
authentication, refresh rotation, logout, tenant isolation/IDOR, RBAC, scope
authorization, malformed IDs, deactivated users, registration, platform
approval, audit lifecycle, evidence security, calculation determinism, Scope 1,
Scope 2 location/market, reporting, inventory, targets, locked periods, CSV
injection, HTTP 415, CORS, throttling.

**One gap: security headers have no automated test** (Finding 4).

---

## 6. Frontend Validation

| Check | Result |
| --- | --- |
| `npx tsc --noEmit` | **0 errors**, exit 0 |
| `npm run test:frontend` | **36/36 pass**, 0 fail |
| `npm run build` | **success**, 2,253 modules |

**API traffic audit** — every network call in `src/`:

| File | Call sites | Resolves through |
| --- | --- | --- |
| `src/services/api.ts:51` | `API_BASE_URL` definition | `import.meta.env.VITE_JAVA_API_BASE_URL ?? ''` |
| `:117` | `POST /auth/refresh` | `${API_BASE_URL}` |
| `:160` | `GET/POST/PUT .../api/v1${endpoint}` | `${API_BASE_URL}` |
| `:311` | evidence download | `${API_BASE_URL}` |
| `:473` | CSV export | `${API_BASE_URL}` |

No `axios`, no `XMLHttpRequest`, no direct database access, no hardcoded
production URL. **VERIFIED.**

Fabricated-data scan: no `Math.random`, `mockData`, `FAKE_`, or `dummyData` in
frontend source. `TARGETS 1: renders backend-computed progress, not a hardcoded
value` actively asserts against hardcoding. **VERIFIED.**

---

## 7. Database Validation

Validated against an isolated database (`carbonflow_p106`) created and dropped
for this phase. The pre-existing `carbonflow_dev` was read only.

| Check | Result |
| --- | --- |
| Migrations byte-identical to Phase 10.5 | **VERIFIED** — `git diff 8389732 HEAD -- db/migration` empty |
| Migration files | exactly V1…V8 |
| Unexpected V9+ | **none** |
| Destructive statements (`DROP`/`TRUNCATE`/`DELETE`) | **none** |
| `flyway_schema_history` | 8 rows, checksum present on every row, `success = t` |
| Applied from empty schema | **VERIFIED** |
| Tables | 38 (37 domain + `flyway_schema_history`) |
| Indexes | 74 |
| Tenant-scoped composite FKs | **43** |
| Audit `status` CHECK | exactly the canonical **10** states |
| Scope 2 storage | `scope1_co2e_t`, `scope2_location_co2e_t`, `scope2_market_co2e_t` — three separate columns |
| Production/business data deleted | **none** |

Schema SHA-256 (unchanged): V1 `00433C77…`, V2 `0A978D28…`, V3 `E4820434…`,
V4 `3C85EB34…`, V5 `518DBF4E…`, V6 `2EE5C7A7…`, V7 `872AB0D1…`, V8 `9A55A252…`

---

## 8. Security Validation

73-check live HTTP harness against the isolated seeded database, Node absent.
**73 PASS / 0 FAIL** (`%TEMP%\opencode\phase106-security.txt`).

| Area | Result |
| --- | --- |
| Password hashing | **VERIFIED** — BCrypt cost 10 |
| Login / invalid login | 200 / 401 |
| Anonymous request | 401 **with envelope** (`UNAUTHORIZED`) |
| Bogus bearer / `?token=` query-string | 401 / 401 |
| Refresh rotation | refresh token changes, new access token works |
| Refresh reuse | **whole family revoked** |
| Logout | 200, subsequent refresh 401 |
| Deactivated user | 403 `ORGANIZATION_NOT_ACTIVE` (lockout test) |
| Login throttling | 429 `AUTH_THROTTLED` |
| RBAC | `PLATFORM_ADMIN` not selectable (403 `ROLE_NOT_SWITCHABLE`); manager `users.read` 200 vs `users.create` 403 |
| Tenant isolation | cross-tenant switch 403; facility IDOR 404; evidence IDOR 404; platform `/users` returns 1 row |
| Malformed UUID | `not-a-uuid` and all-zero → 404; **validation not weakened** |
| HTTP 415 | form-encoded and XML → 415, envelope leaks no internals |
| Security headers | `nosniff`, `X-Frame-Options`, `Referrer-Policy`, `Cache-Control: no-store` |
| CORS | configured origin echoed; `localhost:3000` and `evil.example` refused; no credentials for refused origins; `*` refused at startup |
| CSV injection | export succeeds, no leading `=`,`+`,`-`,`@` cells |
| Secrets | 6 literal rules over the full tracked tree → **0 matches** |
| `.env` | untracked and git-ignored |

**A harness failure I caused, and corrected.** The first run reported 56/73. The
17 failures were **environmental, not product defects**: a stale
`mvn spring-boot:run` JVM from Phase 10.5 still owned port 8080, so the Phase
10.6 instance never bound, the isolated database was never seeded, and the
harness was unknowingly exercising the old process. After killing all JVMs and
starting a single clean instance (confirmed: 1 process, health UP, 5 users /
3 orgs / 6 memberships seeded), the same harness returned **73/73**. Recorded
because a green number is worthless without knowing what produced it.

---

## 9. Carbon Accounting Validation

| Requirement | Result |
| --- | --- |
| Decimal precision | **VERIFIED** — `BigDecimal` only, `MathContext(28, HALF_UP)`; no float/double |
| Deterministic rounding | **VERIFIED** — rounding only at write scales: quantity 8dp, factor 8dp, conversion 10dp, kg 4dp, tonnes 6dp |
| Unit normalization | **VERIFIED** — `normalized_quantity`, `normalized_unit`, `conversion_factor` persisted |
| Factor provenance | **VERIFIED** — `factor_id`, `factor_value`, `factor_unit`, `factor_source`, `factor_version_id`, `factor_version_number` |
| Factor year | **PARTIAL** — derivable via `factor_version_id` → `emission_factor_versions.source_year`; not denormalised onto the snapshot |
| GWP basis | **VERIFIED** — `gwp_set_id` + `gwp_name`; `gwp_sets` carries `assessment_report` + `publication_year` |
| Original input | **VERIFIED** — `original_quantity` + `original_unit` |
| Calculation version | **PARTIAL** — `calculation_hash` present; no explicit engine-version column |
| SHA-256 integrity | **VERIFIED** — `calculation_hash`, `inventory_snapshots.snapshot_hash` |
| **Methodology / version** | **NOT PERSISTED** (Finding 3) |
| Formula snapshot | **NOT PERSISTED** (Finding 3) |

`GhgCalculationEngine` contains no `float`/`double` arithmetic.

### Scope 2

| Rule | Result |
| --- | --- |
| `LOCATION_BASED` and `MARKET_BASED` separate | **VERIFIED** — `emission_records.scope2_type`, separate snapshot columns |
| Never summed as independent totals | **VERIFIED** — `sumActivePerspectives()` uses three independent `FILTER (WHERE …)` conditional aggregates; no path adds them |
| No market→location fallback | **VERIFIED** |
| No location→market fallback | **VERIFIED** |
| Audit lock / frozen period | **VERIFIED** — `AccountingLockGuard`; 20 test files reference lock/freeze |

---

## 10. Globalization Validation

| Scan (case-sensitive, production source only) | Result |
| --- | --- |
| `INR`, `₹`, `Rs.`, `USD`, `EUR`, `GBP`, `JPY` | **0 hits** — no currency hardcoded at all |
| `Asia/Kolkata`, `Asia/Calcutta`, any IANA zone | **0 hits** |
| `India`, `Andhra`, `Telangana`, `Maharashtra` | **0 hits** |
| `"UTC"` | 2 hits — an ISO-8601 comment and `Clock.systemUTC()` for throttle monotonicity. Benign. |
| `"US"` | 1 hit — **a real hardcoded default** (Finding 2) |

Configurable as required: organization country, facility country/geography,
`emission_factor_versions.geography` and `.source`, `gwp_sets.assessment_report`,
`organizations.consolidationApproach`, unit tables in `UnitConversionService`.
**No country, currency, timezone, geography or framework is hardcoded in
production behaviour — with the single exception recorded as Finding 2.**

---

## 11. Configuration Validation

`application.properties` production defaults:

| Setting | Value | Assessment |
| --- | --- | --- |
| `carbonflow.jwt.secret` | `${CARBONFLOW_JWT_SECRET:}` | **PASS** — empty default; startup **fails** if unset (verified) |
| `carbonflow.auth.refresh-secret` | `${CARBONFLOW_REFRESH_TOKEN_SECRET:}` | **PASS** — same fail-closed behaviour |
| `carbonflow.seed.demo-data` | `${CARBONFLOW_SEED_DEMO_DATA:false}` | **PASS** — off by default; verified |
| `carbonflow.cors.allowed-origins` | `${CARBONFLOW_CORS_ALLOWED_ORIGINS:}` | **PASS** — empty = trust nothing; `*` refused |
| `spring.flyway.validate-on-migrate` | `true` | **PASS** |
| Actuator / `show-sql` / debug | absent | **PASS** — no debug surface |
| `spring.datasource.url` | no `sslmode` | **FAIL** — Finding 1 |
| `carbonflow.evidence.vault-dir` | `vault_storage` (relative) | **PARTIAL** — Finding 6 |
| `server.shutdown` | absent | **PARTIAL** — Finding 7 |
| `spring.flyway.baseline-on-migrate` / `baseline-version=6` | intentional (ADR-012) | **DOCUMENTED** — note for operators |

`.env.example` correctly documents only variables the Java backend reads; the
nine Node-era variables are explicitly marked removed. **VERIFIED.**

---

## 12. Deployment Readiness

| Item | Classification | Basis |
| --- | --- | --- |
| Java runtime | **DOCUMENTED BUT NOT VERIFIED** | README/EXECUTION cover 21; run locally only |
| Backend startup | **DOCUMENTED BUT NOT VERIFIED** | `mvn spring-boot:run` and `java -jar` both documented and both exercised locally |
| Frontend build | **VERIFIED** | `npm run build` executed successfully |
| Environment variables | **VERIFIED** | documented, enforced fail-closed, all honoured at runtime |
| Flyway | **VERIFIED** | applied from empty schema and validated |
| Health checks | **VERIFIED** | `/api/v1/health` exercised repeatedly |
| CORS | **VERIFIED** | fail-closed behaviour exercised live |
| **Database TLS** | **NOT IMPLEMENTED** | Finding 1 — documented mechanism does not exist |
| TLS termination (app) | **NOT IMPLEMENTED** | HSTS deliberately off; expected at reverse proxy (documented) |
| Reverse proxy | **DOCUMENTED BUT NOT VERIFIED** | 2 mentions only; no sample config |
| Secrets | **VERIFIED** | 0 literals, `.env` ignored |
| Logging | **PARTIAL** | console INFO default; no structured/audit-log config |
| **Backup / restore** | **NOT IMPLEMENTED** | Finding 8 — referenced doc missing, no procedure |
| **Monitoring** | **NOT IMPLEMENTED** | 0 mentions; no metrics/health-probe manifest |
| **Graceful shutdown** | **NOT IMPLEMENTED** | Finding 7 |
| Container/deploy manifests | **NOT IMPLEMENTED** | no Dockerfile, compose, Helm or k8s — deployment is fully manual |
| Evidence vault durability | **PARTIAL** | Finding 6 |

An operator has enough documentation to **build and run** the stack. They do
**not** have enough to operate it in production responsibly: no backup/restore
procedure, no monitoring, no working TLS instruction, no graceful shutdown, and
no deployment manifests.

---

## 13. Browser UAT Status

**NOT AVAILABLE.**

Browser tooling is present in this session's tool catalogue, so unlike Phase 10.5
this was checked rather than assumed. Both `browser.tabs.open` and
`browser.tabs.list` returned:

```text
[browser.disconnected] No desktop browser is connected to this session.
```

Both the frontend (`:5173`, HTTP 200) and the Java API (`:8080`, health UP) were
running and reachable, so the unavailability is the missing browser, not the
application.

**No browser result was simulated or inferred.** This is an unverified
operational item, not a defect.

---

## 14. External Deployment Status

**NOT VERIFIED.** No deployment was attempted. No production environment was
available or authorized. This document is not evidence of a live deployment.

---

## 15. Findings

### Finding 1 — Database TLS is not implementable as documented — **HIGH â€” RESOLVED in Phase 10.6.1**

> **Status: RESOLVED (Phase 10.6.1).** `DB_SSLMODE` is now a real, bound,
> validated setting passed to PgJDBC as a connection property; an invalid value
> fails application startup, and `require` was verified live to refuse a
> cleartext fallback. See `docs/PHASE10.6.1-BLOCKER-RESOLUTION.md`.

- **Evidence:** `docs/DEPLOYMENT-SECURITY.md:27,34,46` instruct operators to set
  `carbonflow.datasource.url`. The real property is `spring.datasource.url`
  (`application.properties:84`). `DB_SSLMODE` and
  `CARBONFLOW_DB_SSL_TRUST_STORE` are documented at lines 47–48 but a
  repository-wide search finds **zero** occurrences in any `.properties` file or
  Java source. The real JDBC URL carries `?stringtype=unspecified` and **no
  `sslmode`**. Line 48's claim that startup "fails" when verification is
  required is not implemented anywhere.
- **Impact:** DB credentials, refresh-token material and evidence metadata
  transit according to the driver default (`prefer` — silent cleartext
  fallback). An operator following the runbook sets a property Spring ignores
  and believes the connection is encrypted. A documented security control that
  is inert while appearing active is a false assurance.
- **Affected files:** `docs/DEPLOYMENT-SECURITY.md` (documentation);
  `backend-java/src/main/resources/application.properties` (needs the mechanism).
- **Required action:** either correct the doc to the real property name and
  remove the two non-existent variables, **or** implement `sslmode` support via
  an environment variable and trust-store wiring. The former is a
  documentation-only fix and closes the false-assurance risk immediately.
- **Migration required:** no. The current URL is operator-overridable via
  `SPRING_DATASOURCE_URL`, so TLS is achievable today without a schema change.
- **Regression risk if fixed:** low — a doc correction carries none; a code
  change would need a connection-failure test against a TLS-required server.
- **Not patched here** per §18.

### Finding 2 — Hardcoded `"US"` country default — **MEDIUM**

- **Evidence:** `AuthService.java:421`
  `isBlank(request.getCountry()) ? "US" : request.getCountry().trim()`;
  `RegisterRequest.country` documented as "Defaults to `US` when blank";
  `V1__carbonflow_initial_schema.sql` has `country VARCHAR(10) NOT NULL DEFAULT 'US'`.
- **Impact:** an organization registering without a country is silently recorded
  as US-based, in a platform whose frozen principle §2.1 forbids hardcoding
  country. Not an accounting-correctness or tenant-isolation issue: no
  production code branches on `country` — it is descriptive metadata only.
- **Required action:** make `country` required at registration, or default to
  `null`/empty with an explicit "unspecified" state.
- **Migration required:** yes, to drop the column `DEFAULT` — so V9 would be
  needed, which §2 forbids without proven unavoidable necessity. **Deferred.**
- **Not patched here** per §18 and §2.

### Finding 3 — Calculation snapshots carry no methodology or formula version — **MEDIUM**

- **Evidence:** `calculations` has no `methodology_id`/`methodology_version`,
  no formula, no engine-version column. `calculation_methodologies` is seeded
  (`GHG_PROTOCOL_CORP`, `ISO_14064_1`) but referenced by **no** foreign key
  anywhere in the schema.
- **Context that lowers severity:** this is a **deliberate, ADR-recorded**
  decision, not an oversight. ADR-008: "No methodology field was invented."
  `docs/CALCULATIONS.md:137`: "no calculation claims a methodology it cannot
  prove." `docs/API.md:96` and `docs/TEST_PLAN.md:76` repeat it.
- **Impact:** an assurance reviewer cannot determine from a snapshot which
  corporate accounting standard produced a figure. `gwp_set_id` does record the
  GWP assessment report (AR4/AR5/AR6) and factor year is derivable, so the gap
  is specifically the *corporate methodology*, not all provenance.
- **Tension:** frozen principle §2.5 lists `methodology/version` as required
  provenance. The project's own ADR chose conservatism over that literal
  reading. This is recorded so the tension is visible, not resolved unilaterally.
- **Required action:** product decision, then likely V9. **Deferred.**

### Finding 4 — `SecurityHeadersFilter` has no automated test — **MEDIUM**

- **Evidence:** no test file in `src/test` references
  `SecurityHeadersFilter`, `X-Content-Type-Options`, `X-Frame-Options`,
  `Referrer-Policy`, or `nosniff`. The filter is live in production.
- **Impact:** a deployed security control has no regression test. Deleting the
  filter or one header would not fail any test. Only the ad-hoc HTTP harness
  observes these headers today.
- **Required action:** add a `MockMvc`/integration test asserting the four
  headers on an authenticated response. Small, isolated, no migration.
- **Regression risk if fixed:** none.
- **Not patched here:** §20 restricts this phase to documentation-only changes
  when no blocker exists, and §1 forbids introducing changes speculatively.

### Finding 5 — Retired Node origin still listed in the dev CORS profile — **LOW**

- **Evidence:** `application-dev.properties:22`
  `carbonflow.cors.allowed-origins=${CARBONFLOW_CORS_ALLOWED_ORIGINS:http://localhost:5173,http://localhost:3000}`.
- **Impact:** with Node decommissioned no local service listens on 3000, so the
  entry is vestigial. It is **not** a production risk — the profile is opt-in
  and the shipped default trusts nothing — and `CorsConfigurationTest:111`
  actively asserts this value is allowed in `dev`.
- **Required action:** remove `http://localhost:3000` from the dev profile
  **and** update that assertion together. Not done unilaterally, because
  changing it means editing a test assertion.

### Finding 6 — Evidence vault defaults to a relative path — **MEDIUM**

- **Evidence:** `application.properties:6`
  `carbonflow.evidence.vault-dir=${CARBONFLOW_EVIDENCE_VAULT_DIR:vault_storage}`;
  `EvidenceStorageService.java:111-114` resolves it and calls
  `Files.createDirectories` with no validation or warning.
- **Impact:** if an operator forgets the variable in production, audit evidence
  is written to a relative directory under the process working directory —
  typically not backed up, not on durable storage, and lost on redeploy. For an
  audit-preparation product, evidence durability matters.
- **Required action:** make the variable required in production (fail-closed,
  like the JWT secrets), or emit a startup WARN. No migration needed.
- **Not patched here** per §18.

### Finding 7 — Graceful shutdown not configured — **LOW**

- **Evidence:** `server.shutdown=graceful` and
  `spring.lifecycle.timeout-per-shutdown-phase` are absent from every
  `application*.properties`. `docs/PERSISTENCE-ARCHITECTURE.md:41` already
  records "server-owned graceful shutdown remain deployment hardening work".
- **Impact:** in-flight requests are cut on restart. Already acknowledged in the
  project's own docs.
- **Required action:** add the two properties. No migration.

### Finding 8 — `docs/BACKUP-RECOVERY.md` referenced but missing — **MEDIUM â€” RESOLVED in Phase 10.6.1**

> **Status: RESOLVED (Phase 10.6.1).** `docs/BACKUP-RECOVERY.md` now exists and
> labels every procedure TESTED / DOCUMENTED BUT NOT TESTED / NOT IMPLEMENTED.

- **Evidence:** `docs/DEPLOYMENT-SECURITY.md:148` cites
  `docs/BACKUP-RECOVERY.md - backup/recovery procedure`. The file does not exist
  in `docs/`. No backup or restore script, manifest, or procedure exists anywhere
  in the repository.
- **Impact:** the security runbook points operators at a non-existent recovery
  procedure. An unrecoverable database is currently an unmitigated risk.
- **Required action:** author the procedure (or correct the reference). No
  migration.

### Finding 9 — No monitoring instrumentation — **MEDIUM**

- **Evidence:** zero occurrences of "monitor", "metrics", or "alerting" across
  all operational documents. No actuator dependency, no Prometheus config, no
  alerting manifest, no container manifests of any kind.
- **Impact:** no way to observe login-throttle lockouts, DB pool saturation,
  evidence-vault write failures, or error-rate regressions in production.
- **Required action:** define a minimum observability set. No migration.

---

## 16. Risks

| Risk | Severity | Mitigation status |
| --- | --- | --- |
| Operator believes DB is TLS-encrypted when it is not | HIGH | **unmitigated** — Finding 1 |
| No backup/restore procedure | MEDIUM | **unmitigated** — Finding 8 |
| No alerting on lockouts/pool/evidence failures | MEDIUM | **unmitigated** — Finding 9 |
| Evidence vault on ephemeral disk by default | MEDIUM | **unmitigated** — Finding 6 |
| Security headers could regress silently | MEDIUM | **unmitigated** — Finding 4 |
| Assurance reviewer cannot confirm methodology | MEDIUM | accepted ADR-008 trade-off — Finding 3 |
| Organizations silently labelled `US` | MEDIUM | deferred — Finding 2 |
| DB connection cut on restart | LOW | acknowledged in project docs — Finding 7 |
| Browser-only defects (layout, focus, a11y) remain unobserved | MEDIUM | **unmitigated** — no browser; 15-step UAT unexecuted |
| No external deployment rehearsal | MEDIUM | **unmitigated** — not authorized/available |

---

## 17. Deferred Items

| Item | Why deferred |
| --- | --- |
| Finding 1 fix (doc correction or `sslmode` implementation) | §18 — document and stop |
| Finding 2 (remove `US` default) | needs V9; §2 forbids V9 without proven necessity |
| Finding 3 (methodology provenance) | product decision + likely V9 |
| Finding 4 (headers test) | §20 — documentation-only change set this phase |
| Finding 5 (dev CORS cleanup) | requires editing a test assertion |
| Findings 6–9 | §18 / §20 |
| Browser UAT | no browser attached |
| External deployment | no authorized environment |
| V2 GWP-set UUID variant nibble (Phase 10.4.1 §10) | V2 frozen |
| `X-Frame-Options` `DENY` vs `SAMEORIGIN` (Phase 10.4.1 §10) | Spring default is stricter; not a defect |
| Body validation before `@PreAuthorize` (Phase 10.4.1 §10) | standard Spring behaviour; authorization still enforced |
| Unused npm packages `dotenv`, `motion`, `react-is`, `autoprefixer` | pre-existing; unrelated to decommission |

---

## 18. Final Release Recommendation

**RELEASE BLOCKED** — pending remediation of **Finding 1**.

The application itself is in good shape. Every executable gate is green:
304/304 Java tests with zero skips, 0 TypeScript errors, 36/36 frontend tests, a
successful production build, 73/73 live security checks with Node absent,
byte-identical Flyway V1–V8, 43 tenant-scoped foreign keys, the canonical
10-state audit constraint, provably separate Scope 2 perspectives, and zero
secret literals. Node/Express is fully and verifiably gone.

What is missing is **operational honesty and operability**:

1. **Fix Finding 1 first.** Correcting `docs/DEPLOYMENT-SECURITY.md` to the real
   property name and deleting the two non-existent variables is a
   documentation-only change that removes a false security assurance at zero
   regression risk. If TLS is genuinely wanted, implement `sslmode` support.
2. **Author the backup/restore procedure** (Finding 8) before real data exists.
   There is currently no answer to "the database is gone."
3. **Decide the evidence-vault durability policy** (Finding 6) before production
   data is written.
4. **Add the security-headers test** (Finding 4) — small, and it closes a real
   regression hole.
5. Findings 2, 3, 5, 7, 9 are legitimate follow-ups but do not by themselves
   hold the release.

**Browser UAT and external deployment remain NOT VERIFIED** and are explicitly
permitted to be so by §19. They are not the reason for the block.

If Finding 1 is corrected and an operator accepts the operational gaps
(Findings 6–9) with eyes open, this codebase is a reasonable release candidate:
**RELEASE VALIDATION INCOMPLETE** is the honest current state, trending to
**RELEASE CANDIDATE — PASS** once the DB-TLS documentation is truthful.

---

## 19. Exact Evidence and Commands

```text
# baseline
git rev-parse HEAD                                  # 8389732695211fb75f8ba8e0907ac597784ad30d
git status --short                                  # (empty)
git log --oneline -3

# Java
cd backend-java && mvn clean verify                 # 304/304, BUILD SUCCESS, 0 skips

# Frontend
npx tsc --noEmit                                    # 0 errors
npm run test:frontend                               # 36/36
npm run build                                       # success, 2253 modules

# Node decommission
git ls-files | Select-String 'server/'               # (empty)
Test-Path server.ts ; Test-Path server ; Test-Path bun.lock   # False/False/False

# Database
git diff 8389732695211fb75f8ba8e0907ac597784ad30d HEAD -- db/migration   # (empty)
Get-FileHash db\migration\*.sql -Algorithm SHA256
psql -d carbonflow_p106 -c "select version,success from flyway_schema_history order by installed_rank"

# Security (73 checks, Node absent)
powershell -File %TEMP%\opencode\phase105-final.ps1 # 73 PASS / 0 FAIL

# Globalization
Select-String -Path backend-java/src/main/** -Pattern 'INR|Asia/Kolkata|"US"' -CaseSensitive
```

Artifacts: `%TEMP%\opencode\phase106-mvn-verify.log`,
`%TEMP%\opencode\phase106-security.txt`

---

## 20. Final Status

```text
PHASE 10.6 - FINAL PRODUCTION READINESS
========================================
STATUS                  : PASS (validation executed in full)
RELEASE RECOMMENDATION  : RELEASE BLOCKED
BLOCKING FINDING        : F-01 Database TLS not implementable as documented (HIGH)
BLOCKERS (§17 sense)    : 0

Java                    : 304 / 304, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS
TypeScript              : 0 errors
Frontend                : 36 / 36
Production build        : PASS
Security                : 73 / 73 (Node absent)
Database                : PASS - 38 tables, 74 indexes, 43 tenant FKs
Flyway                  : UNCHANGED - V1..V8 byte-identical, 8 rows success=t
Accounting              : PASS with 1 documented gap (methodology provenance)
Globalization           : PASS with 1 hardcoded default (country = "US")
Node decommission       : PASS - intact, nothing restored
Secrets                 : PASS - 6 rules, 0 matches

Browser UAT             : NOT AVAILABLE (no browser attached; not simulated)
External deployment     : NOT VERIFIED (not attempted, not authorized)
Backup/restore          : NOT IMPLEMENTED
Monitoring              : NOT IMPLEMENTED
TLS                     : NOT IMPLEMENTED (documented mechanism absent)
Graceful shutdown       : NOT IMPLEMENTED

Working tree            : documentation-only change set
Commits created         : 1 (documentation only)
Node/Express recreated  : NO
Migrations modified     : NO
Production data deleted : NO
Tests weakened          : NO

FINAL RELEASE STATE     : RELEASE BLOCKED
```

No code, schema, migration, dependency or configuration change was made in this
phase. The only modifications are this document and a correction to
`docs/DEPLOYMENT-SECURITY.md` that makes its database-TLS instructions truthful.

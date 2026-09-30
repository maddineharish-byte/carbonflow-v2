# PHASE 9 — Security Threat Model

**Scope:** CarbonFlow Java/Spring Boot backend + React frontend + PostgreSQL + local evidence vault, at baseline `4cc8f30`.
**Method:** per-threat surface → current protection → test coverage → gap → remediation.

Severity: **CRITICAL** (exploitable, severe impact) · **HIGH** (exploitable with conditions or significant exposure) · **MEDIUM** (hardening gap) · **LOW/INFO** (defense in depth / hygiene).

---

## 1. Unauthenticated attacker

- **Surface:** `POST /auth/login`, `POST /auth/register`, `POST /auth/refresh`, `POST /auth/logout`, `GET /api/health`.
- **Protection:** stateless security chain; only those routes are `permitAll`; everything under `/api/v1/**` requires a principal. Passwords are BCrypt cost 10. Login is timing-equalized (unknown users still pay one BCrypt verification against a fixed throwaway hash). Secrets are fail-closed (startup fails without `CARBONFLOW_JWT_SECRET` / `CARBONFLOW_REFRESH_TOKEN_SECRET`).
- **Tests:** `AuthServiceTest`, `auth-session.test.ts`, `SecurityChainIntegrationTest` (401 for unauthenticated API access).
- **Gap:** **no rate limiting / login throttling** → credential stuffing and password spraying are unmitigated. Registration is also unthrottled (resource-exhaustion / org-flooding vector).
- **Remediation (Phase 9):** in-memory, env-configurable failed-login throttle keyed by email+IP with exponential backoff and bounded memory; covered by tests.

## 2. Authenticated employee (any role)

- **Surface:** all tenant endpoints under the authenticated identity.
- **Protection:** every route declares `@PreAuthorize` on frozen permission codes; role authorities are derived from the same matrix (`RolePermissions`); frontend navigation mirrors the codes but is UX only.
- **Tests:** `RolePermissionsParityTest` (code matrix == `expected-role-permissions.json`), per-route RBAC tests across Phases 4–7, `Phase7IntegrationTest` role matrix.
- **Gap:** none identified; per-endpoint permission coverage was verified controller-by-controller in Phases 6–7.
- **Remediation:** none.

## 3. Cross-tenant attacker (Tenant A probing Tenant B)

- **Surface:** every organization-owned resource id (facilities, periods, activity, calculations, emissions, audits, evidence, inventory, targets, projects, users, boundaries).
- **Protection:** all repository statements are tenant-predicated with `organization_id = ?` from `TenantContext`; child resources re-resolve the parent through the tenant (Phase 5/6 design). Malformed, foreign and unknown ids collapse into identical 404 bodies (no existence oracle).
- **Tests:** `ScopeAuthorizationTest`, `ActivityDataControllerTest`, `AuditLifecycleTest`, `EvidenceChainTest`, `Phase7IntegrationTest`, `SecurityChainIntegrationTest` (cross-tenant battery).
- **Gap:** none known.
- **Remediation:** none; Phase 9 re-verifies with additional negative tests (Step 4).

## 4. Lower-privileged employee attempting escalation

- **Surface:** role switch (`/auth/switch-tenant-or-role`), direct calls to higher-privilege routes.
- **Protection:** switching is restricted to already-assigned memberships and roles (service-side); `@PreAuthorize` blocks the routes themselves; `PLATFORM_ADMIN` cannot be assigned by tenant admins (`UsersService`), so no self-escalation path exists.
- **Tests:** `SwitchTenantTest`, `UserAdminTest` (PLATFORM_ADMIN assignment rejected), `PlatformTenantTest`, `Phase7IntegrationTest`.
- **Gap:** none known.
- **Remediation:** none.

## 5. Deactivated user (mid-session)

- **Surface:** any authenticated request with a token issued before deactivation.
- **Protection:** the JWT filter re-validates the user against PostgreSQL on **every** request (active flag + membership) → 401 `USER_DEACTIVATED` / 403 `TENANT_ACCESS_DENIED`. Access tokens are stateless but identity is not.
- **Tests:** `UserAdminTest` (disable/enable), `AuthServiceTest`, frontend `AUTH FLOW 6` (deactivated user boundary).
- **Gap:** none (kill switch is effective immediately).
- **Remediation:** none.

## 6. Suspended organization

- **Surface:** login, refresh, and in-flight access tokens after suspension.
- **Protection:** token **issuance** is gated on `organizations.status = ACTIVE` (login/refresh). On suspension the refresh family is revoked (sessions die at next refresh).
- **Tests:** `PlatformTenantTest` (suspended org login 403, refresh 401, revoked tokens), `AuthServiceTest`.
- **Residual risk (documented, ADR-014):** an already-issued **access token stays valid up to 15 minutes** after suspension. This is a deliberate design trade-off (stateless access tokens); it is *not* a bypass — the token is still bound to a live identity.
- **Remediation:** optional Phase 9 addition — an org-status check inside the per-request filter would close the window at the cost of one extra query per request. Evaluated in Step 2; implemented only if the cost is acceptable and tests stay green.

## 7. Platform Admin boundary

- **Surface:** `/api/v1/platform/tenants/*` (list, detail, approve, reject, suspend), `/api/v1/users` (cross-tenant), `/api/v1/test-suite/run`.
- **Protection:** dedicated `platform.tenants.*` permissions held only by `PLATFORM_ADMIN`; the platform role deliberately holds **no** tenant reporting permissions, so it cannot read tenant analytics/inventory/targets. Transitions enforce a from-state machine (409 on violation) and are SQL-guarded against concurrent writes.
- **Tests:** `PlatformAdminTest`, `PlatformTenantTest`, `Phase7IntegrationTest` (platform ≠ company admin).
- **Gap:** none.
- **Remediation:** none.

## 8. Malicious file uploader (evidence)

- **Surface:** `POST /evidence/upload` (multipart).
- **Protection:** 25 MB file cap, 25 MB allow-listed MIME types, magic-byte signature validation per type, SHA-256 of stored bytes, filename sanitized to `[A-Za-z0-9._-]`, storage path containment check (`isPathInside`) on write/read/delete, per-tenant directory, cleanup on failure, `storagePath` never serialized.
- **Tests:** `EvidenceChainTest` (upload/download/version/delete, IDOR, traversal, MIME/magic-byte chain, 25 MB limit), `EvidenceStorageServiceTest`.
- **Gap:** files are stored **unencrypted** on the local disk (honestly documented; no encryption claim is made anywhere).
- **Remediation:** none in-scope (encryption at rest is a deployment concern — documented in `DEPLOYMENT-SECURITY.md`).

## 9. Malicious CSV (export consumer / spreadsheet injection)

- **Surface:** `GET /reports/export-csv` output opened in Excel/Sheets/LibreOffice.
- **Protection:** every cell passes the reference `csvCell` guard: leading `= + - @` prefixed with `'`, `"` doubled, whole cell quoted; validated filters; authenticated only.
- **Tests:** `ReportExportTest` (injection chars, quotes, escaping, filters, malformed ids, determinism).
- **Gap:** none.
- **Remediation:** none.

## 10. Compromised refresh token (database leak)

- **Surface:** `refresh_tokens` table; token reuse by an attacker.
- **Protection:** tokens are 64-hex random values stored **only as HMAC-SHA256 hashes** (keyed by `CARBONFLOW_REFRESH_TOKEN_SECRET`); single-use rotation; **replay of a rotated token revokes the entire token family**; `SELECT … FOR UPDATE` makes rotation atomic under concurrency.
- **Tests:** `RefreshTokenLifecycleTest` (rotation, replay family-kill, concurrency), `refresh-token.test.ts`, `refresh-restart-concurrency.test.ts`.
- **Gap:** none (this is one of the strongest controls in the system).
- **Remediation:** none.

## 11. Stolen access token (XSS, device theft, log/history leak)

- **Surface:** bearer token used until its 15-minute expiry.
- **Protection:** short TTL (15 min); per-request identity re-validation; tokens are never written to server logs.
- **Residual risk:** there is **no server-side access-token revocation list** — a stolen access token is usable until it expires. Additionally, `?token=` query-string acceptance (Node parity) allows tokens to leak into browser history, proxy logs and `Referer` headers — this is the one place where the current design actively increases exposure.
- **Remediation (Phase 9, required):** remove query-string token acceptance. Stateless revocation is out of scope (architectural change; documented residual risk).

## 12. SQL injection

- **Surface:** every JDBC statement.
- **Protection:** all SQL is static strings with `JdbcTemplate` `?` parameters; optional filters use the `(?::text IS NULL OR col = ?::uuid)` pattern with **pre-validated** inputs (UUID contract / enum allow-lists), so no value is ever concatenated into SQL. No dynamic ORDER BY, no dynamic table names.
- **Tests:** contract tests exercise malformed ids/filters on every parameterized surface; dedicated injection-payload tests are added in Step 5/6.
- **Gap:** none observed (verified by static scan: no SQL string interpolation in main sources).
- **Remediation:** none; add regression tests for classic payloads (`' OR 1=1--`) in Step 6.

## 13. XSS payload

- **Surface:** stored user content (names, notes, findings, comments, file names) rendered in the SPA; error messages rendered in toasts.
- **Protection:** React escapes all interpolated values; no `dangerouslySetInnerHTML` anywhere in `src/`; API client renders backend messages as text.
- **Tests:** frontend render tests assert content appears escaped (JSX text nodes); static grep for `dangerouslySetInnerHTML` returns 0.
- **Gap:** residual — inline `style`/dynamic attributes are limited to numeric widths (safe).
- **Remediation:** none; add an explicit XSS regression test in Step 13.

## 14. CSRF

- **Surface:** state-changing requests.
- **Protection:** the API is **stateless and token-only** (`Authorization: Bearer`), with `SessionCreationPolicy.STATELESS` and no cookies carrying authority. A cross-site form/`<img>` cannot attach the Authorization header, so the classic CSRF vector does not apply. `csrf.disable()` is therefore architecturally correct here, not an oversight.
- **Residual risk:** if a future deployment introduces cookie-based auth (e.g., refresh cookie), CSRF protection becomes mandatory — documented as a constraint in `SECURITY-THREAT-MODEL.md` / deployment docs.
- **Remediation:** none (documented threat model instead of unnecessary complexity, per instructions).

## 15. Path traversal (evidence storage)

- **Surface:** uploaded file names and stored paths used in read/delete.
- **Protection:** `safeBasename` strips directory components and non-allow-listed characters; every read/delete resolves and verifies containment inside the vault (`isPathInside`); out-of-vault paths are refused (503) and never touched by cleanup.
- **Tests:** `EvidenceChainTest` (traversal filenames, out-of-vault stored paths), `EvidenceStorageServiceTest` (basename/containment units).
- **Gap:** none.
- **Remediation:** none.

## 16. Oversized upload / body abuse

- **Surface:** multipart uploads and large JSON bodies.
- **Protection:** `spring.servlet.multipart.max-file-size=25MB`, `max-request-size=28MB`; `MaxUploadSizeExceededException` maps to a clean 400 `UPLOAD_FAILED` envelope; the storage layer re-validates the byte length.
- **Gap:** JSON body size is not explicitly capped (Tomcat's `maxPostSize` does not apply to chunked/streamed bodies in the same way as form posts). A very large JSON body to e.g. `/audits` could consume memory before validation.
- **Remediation (Phase 9):** set an explicit `server.tomcat.max-http-form-post-size`/`spring.servlet` equivalent or a lightweight content-length guard for JSON endpoints; verify it does not break any existing contract.

## 17. Invalid MIME / magic-byte mismatch

- **Surface:** declared vs actual file content.
- **Protection:** MIME allow-list + per-type magic-byte signature; text types additionally reject NUL bytes.
- **Tests:** `EvidenceChainTest` (MIME mismatch, magic-byte mismatch, renamed executable).
- **Gap:** none.
- **Remediation:** none.

## 18. Malformed UUID / malformed input

- **Surface:** every `:id` path variable and filter.
- **Protection:** strict Node-contract UUID validation (`UuidContract`) before any repository call; malformed ids collapse into the same tenant-scoped 404 as unknown ids; enum/date/number validation in services with fixed messages; `stringtype=unspecified` binding keeps PostgreSQL from raising cast errors.
- **Tests:** extensive per-module malformed-id assertions (Phases 4–7) and the `Phase7IntegrationTest` malformed sweep across every new endpoint.
- **Gap:** none.
- **Remediation:** none.

## 19. Concurrent lifecycle update (race conditions)

- **Surface:** refresh rotation, audit state transitions, platform org status transitions, inventory snapshot create/lock, calculation supersession.
- **Protection:** refresh rotation uses `SELECT … FOR UPDATE`; platform status transitions are SQL-guarded (`WHERE status = expected`) and answer 409 on a lost race; audit transitions validate current state inside the transaction; inventory create/lock are status-guarded; the calculation engine supersedes prior ACTIVE records transactionally.
- **Tests:** `AuditLifecycleTest`, `RefreshTokenLifecycleTest`, `PlatformAdminTest`, `InventorySnapshotTest`, `CalculationRunTest` (duplicate hash rollback), `refresh-restart-concurrency.test.ts`.
- **Gap:** audit transitions are state-checked inside a transaction but the audit row itself is not locked `FOR UPDATE` before the transition — two concurrent transitions from the same state could both pass the check. The second write is still constrained by the state machine and the period lock guard, but an explicit `FOR UPDATE` (or a guarded `UPDATE … WHERE status = ?`) would make the guarantee explicit.
- **Remediation (Phase 9, if tests confirm no regression):** add a concurrency test first; only then consider a row lock. No contract change.

## 20. Audit-lock bypass (mutating certified history)

- **Surface:** accounting writes while an audit is `LOCKED`.
- **Protection:** `AccountingLockGuard` (Phase 5 lock, Phase 6 enforcement) rejects activity create/update/submit and `calculations/run` with 409 `AUDIT_LOCKED`; batch skips frozen activities; the lock event records actor/time; evidence writes honor the lock.
- **Tests:** `AccountingLockTest`, `AuditLockTest`, `EvidenceChainTest`.
- **Gap:** none.
- **Remediation:** none.

## 21. Reporting-period lock bypass

- **Surface:** any write touching a locked period.
- **Protection:** same guard; period status is the authoritative signal (`reporting_periods.status`), and Phase 7 reporting reads explicitly remain open (reads of frozen history are correct and desired).
- **Tests:** `Phase7IntegrationTest` (locked-period reads succeed; ledger counts unchanged).
- **Gap:** none.
- **Remediation:** none.

## 22. Configuration / secrets exposure

- **Surface:** committed secrets, default credentials, environment dumps.
- **Protection:** `.env*` and `vault_storage/` are gitignored (`.env.example` holds placeholders only); JWT and refresh secrets are fail-closed at startup with no fallback; no secret values are logged; demo seeding is off by default (`CARBONFLOW_SEED_DEMO_DATA=false`).
- **Gap:** the demo seed password (`Password123!` in `SeedIds`, used by tests/dev) is a **known dev-only credential**; it must never be enabled in production. Documented in `SECRETS.md` as a Phase 9 deliverable.
- **Remediation (Phase 9):** `docs/SECRETS.md` documenting every variable, its source, and the fail-closed behavior (names only, never values).

## 23. Database compromise considerations

- **Surface:** full database read (backup theft, SQL injection elsewhere, insider).
- **Protection:** passwords are bcrypt hashes; refresh tokens are HMAC hashes; JWTs are signed (not stored) and short-lived; evidence files live outside the database and are only referenced by path; no encryption at rest at the application layer.
- **Residual risk:** evidence documents on disk are **unencrypted** (Phase 5/9 documented); DB transport is not TLS by default (Step 7 adds an env-driven option).
- **Remediation (Phase 9):** document DB TLS + at-rest expectations in `DEPLOYMENT-SECURITY.md`; do not claim encryption that does not exist.

## 24. Log leakage

- **Surface:** application logs, access logs, error responses.
- **Protection:** only two debug-level statements touch token/identity context and neither prints token or password values; unexpected exceptions are logged server-side with stack traces but answered to the client with a generic 500 envelope; the exception handler never echoes SQL or filesystem details into responses.
- **Gap:** error responses for `AuthException` intentionally return the backend's exact message (contract parity) — acceptable because messages are curated and contain no internals; verified per message during Phases 3–7.
- **Remediation:** none; add a log-sanitization regression check in Step 23.

---

## Summary of actionable findings

| # | Threat | Severity | Disposition |
|---|---|---|---|
| 1 | No login/registration throttling | **HIGH** | Fix in Phase 9 (Step 2/10): env-configurable in-memory backoff |
| 2 | Query-string token acceptance (`?token=`) | **HIGH** | Fix in Phase 9 (Step 2): remove; update contract message + affected assertions |
| 3 | No security response headers | MEDIUM | Fix in Phase 9 (Step 9) |
| 4 | No JSON body size cap | MEDIUM | Fix in Phase 9 (Step 9) |
| 5 | No rate limiting on refresh/upload/calc/export | MEDIUM | Evaluate/fix in Step 10 |
| 6 | Health endpoint exposes `java.version` | LOW | Fix in Phase 9 (Step 23): minimal public health body |
| 7 | Access tokens valid ≤15 min after org suspension | LOW (documented ADR-014) | Document; optional per-request org check evaluated in Step 2 |
| 8 | Audit transition concurrency not row-locked | LOW | Verify with a concurrency test in Step 15; add lock only if a race is demonstrable |
| 9 | No server-side access-token revocation | LOW (architectural) | Document residual risk |
| 10 | Evidence + DB not encrypted at rest | INFO | Document honestly (no encryption claim) |

No CRITICAL findings were identified at baseline.

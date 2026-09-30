# PHASE 9 — Database TLS and Deployment Security

**Scope:** PostgreSQL connection security for the CarbonFlow Java Spring Boot backend, at baseline `4cc8f30`.

## DB TLS Configuration

### Risk Context
- **Development:** Local PostgreSQL may use non-TLS (cleartext) connections. The embedded PostgreSQL used by tests defaults to the client's configured `sslmode`.
- **Production:** Production deployment SHOULD require TLS-encrypted database connections to prevent eavesdropping on credentials, JWT refresh tokens, and evidence-related data in transit.

### Environment-Driven Configuration
The `spring.datasource.url` PostgreSQL connection parameter controls TLS behavior. The following `sslmode` values are supported by the PostgreSQL JDBC driver:

| `sslmode` | Behavior |
|---|---|
| `disable` | Cleartext connection (no encryption). **Default for local development.** |
| `allow` | Start without TLS, upgrade to TLS if the server supports it. |
| `prefer` | Try TLS first; fall back to cleartext if the server does not support it. |
| `require` | **TLS mandatory** — connection fails if the server does not speak TLS. **Recommended for production.** |
| `verify-ca` | TLS mandatory + server certificate verified against trusted CA. |
| `verify-full` | TLS mandatory + server certificate hostname verified. |

> **CORRECTED in Phase 10.6 (finding F-01).** An earlier version of this
> document told operators to set `carbonflow.datasource.url`, `DB_SSLMODE` and
> `CARBONFLOW_DB_SSL_TRUST_STORE`. **None of those three is real.**
> `carbonflow.datasource.url` is not a property the application reads (the
> correct name is `spring.datasource.url`), and `DB_SSLMODE` /
> `CARBONFLOW_DB_SSL_TRUST_STORE` exist nowhere in the codebase. Following the
> old text silently produced an unencrypted connection while appearing secured.
> The section below is the verified reality.

### Current state: TLS is NOT enabled by the application

`application.properties` builds the JDBC URL as:

```properties
spring.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT:5432}/${DB_NAME}?stringtype=unspecified
```

There is **no `sslmode` parameter and no environment variable that adds one**.
The connection therefore uses the PostgreSQL JDBC driver's default (`prefer`):
TLS if the server offers it, **silent cleartext fallback if it does not.**

### Recommended Production Configuration

Because the application does not add `sslmode`, set the whole URL explicitly
using Spring's standard relaxed-binding environment variable:

```bash
# Production: TLS mandatory, server certificate verified against a trusted CA.
# verify-ca requires the JVM trust store to contain the issuing CA.
export SPRING_DATASOURCE_URL='jdbc:postgresql://db.internal:5432/carbonflow?sslmode=verify-ca&stringtype=unspecified'
```

`SPRING_DATASOURCE_URL` overrides `spring.datasource.url` in `application.properties`.
Keep `stringtype=unspecified`; the repositories rely on it.

| `sslmode` | Behavior |
|---|---|
| `disable` | Cleartext connection, no encryption. Local development only. |
| `allow` | Start cleartext, upgrade to TLS if the server supports it. |
| `prefer` | **Current effective default.** Try TLS, fall back to cleartext. |
| `require` | TLS mandatory; the connection fails if the server does not speak TLS. |
| `verify-ca` | TLS mandatory; server certificate verified against a trusted CA. **Recommended for production.** |
| `verify-full` | TLS mandatory; certificate verified *and* hostname checked. |

If your managed PostgreSQL provider (Cloud SQL, RDS, Supabase, Azure) forces TLS
server-side, no client change is needed — but do not assume it; verify.

### TLS Certificate Expectations
- **Production:** use `verify-ca` (or `verify-full` when the URL host matches the
  certificate). The issuing CA must be in the JVM trust store
  (`javax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStore`).
- **Self-signed certificates:** mount the server certificate as a JVM trust store
  and run with `-Djavax.net.ssl.trustStore=<path>`. Prefer a real CA.
- **Not implemented:** the application has no trust-store environment variable.
  A JVM `trustStore` is the only supported route. There is also no startup check
  that fails when verification is required but unavailable — if the connection
  succeeds, TLS is in effect; if you expected TLS and it is not, the URL is
  almost certainly missing `sslmode`.
- **Development:** `sslmode=disable` is acceptable on a trusted local network.

### Configuration Variables

| Variable | Default | Description |
|---|---|---|
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | none / `5432` / none / none / none | Build the default JDBC URL. Placeholders are unresolved by default, so startup fails fast when unset. |
| `SPRING_DATASOURCE_URL` | (unset) | **Overrides the whole JDBC URL.** This is the only supported way to add `sslmode`. |
| `DB_SSLMODE` | **does not exist** | Not implemented. Use `SPRING_DATASOURCE_URL`. |
| `CARBONFLOW_DB_SSL_TRUST_STORE` | **does not exist** | Not implemented. Use the JVM `trustStore`. |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | (empty) | Comma-separated list of exact browser origins permitted to call the API with credentials. **No default** - see "CORS Allow-List" below. |
| `CARBONFLOW_JWT_SECRET` | (required, no default) | JWT signing secret — **must be set via environment, never committed** |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | (required, no default) | HMAC key for refresh-token hash storage — **must be set via environment, never committed** |

### CORS Allow-List (Phase 10.4.1, ADR-021)

| Variable | Default | Description |
|---|---|---|
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | (empty) | Comma-separated list of the exact browser origins allowed to call the API with credentials |
| `carbonflow.cors.allowed-origins` | (empty) | Spring property that the above resolves into; the only supported way to set the list |

Behavior:

- **No default, fail-closed.** With the variable unset the allow-list is empty, `Access-Control-Allow-Origin` is never emitted, and cross-origin browser requests are refused. Same-origin and server-to-server traffic are unaffected. A `WARN` at startup names the variable.
- **`*` is refused at startup.** `allowCredentials` is always `true`, and browsers reject a wildcard combined with credentials, so a list containing `*` throws `IllegalStateException` instead of silently degrading.
- **Origins are exact and scheme-qualified.** List the deployed frontend origins, for example `https://app.example.com,https://admin.example.com`. The port is part of the origin, so `https://app.example.com` and `https://app.example.com:8443` are two different entries.
- **Allowed methods/headers** are fixed: `GET, POST, PUT, DELETE, PATCH, OPTIONS` and `Authorization, Content-Type`, with `maxAge` 3600 s.
- **Local development** takes its localhost origins from the opt-in `dev` profile (`application-dev.properties`), not from the shipped default. That file lists `http://localhost:5173` and `http://localhost:3000`; the retired Node/Express origin is therefore still accepted **only** when the `dev` profile is active, and is refused by the shipped default. Activate the profile deliberately (`SPRING_PROFILES_ACTIVE=dev`) and never in a deployment. `CARBONFLOW_CORS_ALLOWED_ORIGINS` overrides the profile value when set.
- **`CORS_ORIGINS` is retired** and has no effect. This supersedes the name recorded in ADR-013 item 3: a deployment that still sets `CORS_ORIGINS` starts CORS-less and logs the `WARN`. An operator who believes CORS is configured but has not migrated the variable will see browser CORS failures, not a server error.

### Do Not Hardcode
- Do not hardcode certificates or passwords in source code, `application.properties` (without env override), or documentation.
- The `carflow.datasource.url` is constructed from `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` environment variables — these must be set at deployment time.
- If `DB_SSLMODE` is omitted, the URL defaults to `?sslmode=disable` — development-friendly but production-inadequate.

### Health Check Integration
The `/api/health` endpoint must NOT expose the `sslmode` or any database connection details. The health body should remain minimal:

```json
{
  "status": "UP",
  "service": "CarbonFlow Java Spring Boot Enterprise GHG Accounting Engine",
  "version": "1.0.0-PRO",
  "timestamp": "2026-09-28T..."
}
```

Database-specific details (including whether TLS is active) are for internal diagnostics only and must not appear in the public health response.

## Secret Management

### Fail-Closed Startup
The application **fails to start** if required secrets are missing. This is enforced by:

1. **JWT secret:** `CARBONFLOW_JWT_SECRET` — must be at least 32 bytes (256 bits) for HS256. Thrown at startup if missing or too short (`JwtTokenProvider`).
2. **Refresh-token secret:** `CARBONFLOW_REFRESH_TOKEN_SECRET` — must be at least 32 bytes. Thrown at startup if missing or too short (`AuthService`).
3. **Database credentials:** `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` — required for JDBC URL construction. Missing values cause connection failures at runtime.

### Documented Variables (names only, never values)

| Variable | Purpose | Source |
|---|---|---|
| `CARBONFLOW_JWT_SECRET` | HS256 signing key for access tokens | Environment |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | HMAC-SHA256 key for refresh-token hash storage | Environment |
| `DB_HOST` | PostgreSQL host address | Environment |
| `DB_PORT` | PostgreSQL port number | Environment |
| `DB_NAME` | Database name | Environment |
| `DB_USER` | Database user | Environment |
| `DB_PASSWORD` | Database password (BCrypt-hashed at seed) | Environment |
| `CARBONFLOW_JWT_EXPIRATION_MS` | Access-token TTL (15 min default) | Environment, `${900000}` |
| `CARBONFLOW_AUTH_REFRESH_SECRET` | Alias for `CARBONFLOW_REFRESH_TOKEN_SECRET` | Legacy / env compat |
| `carbonflow.jwt.expiration-ms` | Same as `CARBONFLOW_JWT_EXPIRATION_MS` | `application.properties` |
| `carbonflow.auth.refresh-secret` | Same as `CARBONFLOW_REFRESH_TOKEN_SECRET` | `application.properties` |
| `carbonflow.auth.throttle.max-failures` | Login throttle attempt limit (default 5) | Environment |
| `carbonflow.auth.throttle.window-ms` | Failure observation window (default 15 min) | Environment |
| `carbonflow.auth.throttle.lockout-ms` | Lockout duration (default 15 min) | Environment |
| `carbonflow.datasource.url` | Full JDBC URL (constructed from DB_*) | Constructed |
| `DB_SSLMODE` | **Does not exist** — use `SPRING_DATASOURCE_URL` with `sslmode` (see the DB TLS section) | — |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | Exact frontend origins allowed to call the API cross-origin (no default) | Environment |
| `carbonflow.cors.allowed-origins` | Same as `CARBONFLOW_CORS_ALLOWED_ORIGINS` | `application.properties` |

### .env and Git Hygiene
- `.env` and `.env*` files are **gitignored** (`.gitignore` contains `.env*` and `vault_storage/`).
- `.env.example` holds **placeholder values only** — never commit real secret values.
- The `CARBONFLOW_JWT_SECRET` and `CARBONFLOW_REFRESH_TOKEN_SECRET` values are generated at first deployment and stored in the deployment environment — they must **not** appear in any committed file.

### Evidence Vault
- Files live under a single configurable base directory (`carbonflow.evidence.vault-dir`, default `vault_storage` — already gitignored).
- No cloud/object-storage dependency is introduced (consistent with the Node `LocalStorageAdapter` design).
- Vault directory path is configurable via `CARBONFLOW_EVIDENCE_VAULT_DIR` environment variable.

## Audit

### What This Document Does NOT Claim
- Disaster recovery capability — **no backup or restore procedure exists in this repository** (see Phase 10.6 finding F-08). `docs/BACKUP-RECOVERY.md` is not present; do not look for it.
- Cryptographic immutability of evidence at rest — evidence files are stored unencrypted on local disk (honestly documented; no encryption claim is made anywhere in the codebase).
- Automatic TLS enforcement — the application does not set `sslmode`; the driver's `prefer` default applies until `SPRING_DATASOURCE_URL` overrides it.

### Remediation Path from Development to Production TLS
1. Set `SPRING_DATASOURCE_URL` to a URL containing `sslmode=verify-ca` (or `verify-full`), keeping `stringtype=unspecified`.
2. Ensure the PostgreSQL server presents a valid certificate trusted by a CA in the JVM trust store.
3. Set `CARBONFLOW_JWT_SECRET` and `CARBONFLOW_REFRESH_TOKEN_SECRET` to 32+ random bytes.
4. Verify `mvn clean verify` passes with the new configuration.
5. Run the live-stack smoke test (login → session → representative endpoints → calculation → audit → CSV export → logout) with TLS active.
6. Confirm on the server side that the connection is encrypted, rather than assuming it from configuration.

## References

- `docs/SECRETS.md` — variable names and setup expectations (never values).
- `docs/SECURITY.md` — security model and controls.
- `docs/SECURITY-THREAT-MODEL.md` — threat model.
- `docs/EXECUTION.md` — how to run and build the stack.
- **Missing (do not look for these):** `docs/BACKUP-RECOVERY.md` and
  `docs/PHASE9-SECURITY-MATRIX.md` are referenced by earlier revisions of this
  document but **have never existed**. Backup/recovery is a known open gap
  (Phase 10.6 finding F-08); the security test matrix is covered by the Maven
  suite under `backend-java/src/test/`.
- `RolePermissions.java` — the 9-role × 44-permission matrix (ported from the retired Node backend).
- `JwtTokenProvider.java` — fail-closed JWT secret validation.
- `AuthService.java` — fail-closed refresh-secret validation.
- `LoginThrottle.java` — env-configurable failed-login throttle (Phase 9 addition).
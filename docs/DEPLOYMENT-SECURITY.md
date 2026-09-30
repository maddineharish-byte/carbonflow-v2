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

### Recommended Production Configuration

```properties
# Production: TLS required, server certificate verified
carbonflow.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=verify-ca
```

### Development Configuration (no TLS required)

```properties
# Development: cleartext is acceptable (local trusted environment only)
carbonflow.datasource.url=jdbc:postgresql://${DB_HOST}:${DB_PORT}/${DB_NAME}?sslmode=disable
```

### TLS Certificate Expectations
- **Production:** The PostgreSQL server must present a valid certificate either signed by a trusted Certificate Authority (`verify-ca` or `verify-full`) or with a hostname matching the connection URL (`verify-full`).
- **Self-signed certificates** are acceptable in production when `CARBONFLOW_DB_SSL_TRUST_STORE` points to a trust store containing the server cert, but this adds operational complexity — prefer a proper CA deployment.
- **Development:** No certificate validation required when `sslmode=disable`.

### Configuration Variables

| Variable | Default | Description |
|---|---|---|
| `carbonflow.datasource.url` | constructed from `DB_*` env vars + `?sslmode=disable` | Full JDBC URL; the `sslmode` parameter controls TLS behavior |
| `DB_SSLMODE` | `disable` | Overrides the `sslmode` in the JDBC URL via `?sslmode=${DB_SSLMODE}` |
| `CARBONFLOW_DB_SSL_TRUST_STORE` | (empty) | Path to a JSSE trust store (JKS/PEM) for `verify-ca`/`verify-full` modes. If empty and `sslmode` requires verification, startup fails. |
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
| `DB_SSLMODE` | PostgreSQL SSL mode override | Environment |
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
- Disaster recovery capability — not tested.
- Cryptographic immutability of evidence at rest — evidence files are stored unencrypted on local disk (honestly documented; no encryption claim is made anywhere in the codebase).
- Automatic TLS enforcement on local development — `sslmode=disable` is the development default.

### Remediation Path from Development to Production TLS
1. Set `DB_SSLMODE=verify-ca` (or `verify-full`) in the production environment.
2. Ensure the PostgreSQL server presents a valid certificate trusted by the configured CA.
3. Set `CARBONFLOW_JWT_SECRET` and `CARBONFLOW_REFRESH_TOKEN_SECRET` to 32+ random bytes.
4. Verify `mvn clean verify` passes with the new configuration.
5. Run the live-stack smoke test (login → session → representative endpoints → calculation → audit → CSV export → logout) with TLS active.
6. Document the configuration in `docs/DEPLOYMENT-RUNBOOK.md`.

## References

- `docs/SECRETS.md` — variable names and setup expectations (never values).
- `docs/BACKUP-RECOVERY.md` — backup/recovery procedure.
- `docs/PHASE9-SECURITY-MATRIX.md` — comprehensive security test matrix.
- `RolePermissions.java` — the 9-role × 44-permission matrix (code-for-code port from Node).
- `JwtTokenProvider.java` — fail-closed JWT secret validation.
- `AuthService.java` — fail-closed refresh-secret validation.
- `LoginThrottle.java` — env-configurable failed-login throttle (Phase 9 addition).
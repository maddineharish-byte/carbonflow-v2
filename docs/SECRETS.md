# PHASE 9 — Secret Management

**Variable names and setup expectations only. Never document real secret values.**

## Purpose
This document lists every environment variable the CarbonFlow application requires to start, along with its expected format and source. It does **not** document any actual secret values, passwords, keys, or certificates.

## Required Variables (fail-closed at startup)

| Variable | Type / Format | Description | Failure behavior |
|---|---|---|---|
| `CARBONFLOW_JWT_SECRET` | At least 32 bytes (256 bits), any printable ASCII or binary safe | HS256 signing key for access tokens. Startup throws `IllegalStateException` if missing, blank, or shorter than 32 bytes. | `IllegalStateException` — app will not start |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | At least 32 bytes (256 bits), any printable ASCII or binary safe | HMAC-SHA256 key for refreshing-token hash storage. Startup throws `IllegalStateException` if missing, blank, or shorter than 32 bytes. | `IllegalStateException` — app will not start |
| `DB_HOST` | Non-empty string | PostgreSQL server host address. Used in JDBC URL construction. | Connection failure at runtime (no startup validation beyond URL construction) |
| `DB_PORT` | Integer, typically `5432` | PostgreSQL server port. Used in JDBC URL construction. | Connection failure at runtime |
| `DB_NAME` | Non-empty string | Database name. Used in JDBC URL construction. | Connection failure at runtime |
| `DB_USER` | Non-empty string | Database user name. Used in JDBC URL construction. | Connection failure at runtime |
| `DB_PASSWORD` | Non-empty string | Database password. Used in JDBC URL construction. | Connection failure at runtime |

## Recommended/Optional Variables

| Variable | Default | Description |
|---|---|---|
| `CARBONFLOW_JWT_EXPIRATION_MS` | `900000` (15 minutes) | Access-token TTL. Configured in `application.properties` as `carbonflow.jwt.expiration-ms`; overridden by this env var. |
| `CARBONFLOW_AUTH_REFRESH_SECRET` | Same as `CARBONFLOW_REFRESH_TOKEN_SECRET` | Legacy/alias name for the same variable. |
| `carbonflow.jwt.expiration-ms` | `900000` | Spring property; `CARBONFLOW_JWT_EXPIRATION_MS` takes precedence if both are set. |
| `carbonflow.auth.refresh-secret` | `undefined` | Spring property; same as `CARBONFLOW_REFRESH_TOKEN_SECRET`. |
| `carbonflow.auth.throttle.max-failures` | `5` | Login throttle attempt limit. See `LoginThrottle.java`. |
| `carbonflow.auth.throttle.window-ms` | `900000` (15 min) | Failure observation window. See `LoginThrottle.java`. |
| `carbonflow.auth.throttle.lockout-ms` | `900000` (15 min) | Lockout duration after budget exhausted. See `LoginThrottle.java`. |
| `DB_SSLMODE` | `prefer` | PostgreSQL transport security mode, passed to PgJDBC as a connection property. Accepted values: `disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`. **Any other value fails application startup.** `require` encrypts without verifying the server certificate; `verify-ca`/`verify-full` verify against the JVM trust store. Not a secret. Set `require` or `verify-ca` in production — `prefer` silently downgrades to plaintext. See `docs/DEPLOYMENT-SECURITY.md`. |
| `CARBONFLOW_EVIDENCE_VAULT_DIR` | `vault_storage` | Base directory for evidence file storage. Default `vault_storage` is gitignored. |
| `CARBONFLOW_SEED_DEMO_DATA` | `false` (production default) | When `true`, development-only seed data (including a demo organization/user with `Password123!` password) is loaded. Must be `false` in production. |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | (empty) | **Not a secret** - a public value listing the exact frontend origins allowed to call the API. No default: unset means no browser origin is trusted (fail-closed) and a `WARN` is logged. `*` is refused at startup because credentials are always allowed. Localhost origins live in the `dev` profile. Replaces the retired `CORS_ORIGINS` name. See ADR-021 and `docs/DEPLOYMENT-SECURITY.md`. |
| `VITE_JAVA_API_BASE_URL` | (empty) | **Not a secret.** Frontend build-time base URL for the Java API. Empty means same-origin relative `/api/v1/...` (reverse-proxy deployments); set it for direct dev-server access, which then requires the origin to be listed in `CARBONFLOW_CORS_ALLOWED_ORIGINS`. |

## Git-Ignored Files

| File | Purpose |
|---|---|
| `.env*` | Per-user environment variables — **never committed**. Contains real secret values for local development only. |
| `vault_storage/` | Local evidence file storage directory — gitignored to avoid committing binary evidence files. |
| `backend-java/target/` | Maven build output — standard gitignore. |

## `.env.example` — Placeholders Only

The `.env.example` file contains **placeholder strings** that document the expected variable names and a non-functional example value. It is committed to the repository so that new developers can see which variables are required, but no actual secret values are ever included.

Example `.env.example` content:

```
# Required for startup — generate 32+ random bytes and export these before running
CARBONFLOW_JWT_SECRET=replace-with-32-random-bytes
CARBONFLOW_REFRESH_TOKEN_SECRET=replace-with-32-random-bytes

# Database connectivity
DB_HOST=localhost
DB_PORT=5432
DB_NAME=carbonflow_dev
DB_USER=postgres
DB_PASSWORD=replace-with-secure-password

# Optional: TLS mode
DB_SSLMODE=disable

# Evidence vault
CARBONFLOW_EVIDENCE_VAULT_DIR=vault_storage

# Globalization / seeding
CARBONFLOW_SEED_DEMO_DATA=false
```

**Important:** The right-hand side values (`replace-with-...`) are **not** valid secrets and should not be used in any environment. They are documentation aids only.

## Secret Generation

### JWT Secret (`CARBONFLOW_JWT_SECRET`)
- Must be exactly 32 bytes (256 bits) minimum for HS256.
- Generate with: `openssl rand -hex 32` or `head -c 32 /dev/urandom | base64`.
- Example (shell): `export CARBONFLOW_JWT_SECRET=$(openssl rand -hex 32)`

### Refresh-Token Secret (`CARBONFLOW_REFRESH_TOKEN_SECRET`)
- Must be exactly 32 bytes (256 bits) minimum for HMAC-SHA256.
- Generate with the same method as the JWT secret.
- Example (shell): `export CARBONFLOW_REFRESH_TOKEN_SECRET=$(openssl rand -hex 32)`

**Never commit the output of these generation commands to the repository.**

## Secrets in Source Code
- The `carflow.jwt.secret` and `carflow.auth.refresh-secret` Spring properties are read from `${variable:}` SpEL evaluation — they are **not** hardcoded in `application.properties`.
- If `CARBONFLOW_JWT_SECRET` is not set in the environment, the application **fails to start** immediately during bean initialization — no request processing occurs.
- Similarly for `CARBONFLOW_REFRESH_TOKEN_SECRET`.

## Rotation
- **JWT secret:** Rotate by deploying a new value and restarting the application. Old access tokens remain valid until their 15-minute TTL expires. There is no server-side revocation list for access tokens.
- **Refresh-token secret:** Rotation invalidates all existing refresh tokens (they are hashed against the secret), forcing re-authentication. This is the primary mechanism for refreshing the HMAC key without changing user credentials.

## Do Not
- Commit real secret values to the repository at any point.
- Use the placeholder values from `.env.example` in any environment.
- Rely on default secrets for production deployment.
- Share secret values through version-controlled channels (email, chat, PR descriptions, etc.).

## References
- `docs/DEPLOYMENT-SECURITY.md` — DB TLS configuration and production deployment expectations.
- `docs/PHASE9-BASELINE.md` — baseline verification context.
- `JwtTokenProvider.java` — fail-closed JWT secret validation (32-byte minimum).
- `AuthService.java` — fail-closed refresh-secret validation (32-byte minimum).
- `.gitignore` — `.env*` and `vault_storage/` are gitignored.
- `.env.example` — committed placeholder file (never real values).
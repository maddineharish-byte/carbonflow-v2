# PHASE 9 — Database TLS and Deployment Security

**Scope:** PostgreSQL connection security for the CarbonFlow Java Spring Boot backend, at baseline `4cc8f30`.

## DB TLS Configuration

> **Status: IMPLEMENTED (Phase 10.6.1, finding F-01).** The `DB_SSLMODE` setting
> below is real, bound, validated, and reaches PgJDBC. The earlier revision of
> this document — which described `carbonflow.datasource.url`, `DB_SSLMODE` and
> `CARBONFLOW_DB_SSL_TRUST_STORE` — was fiction: the property name was wrong and
> neither environment variable was read by the application. Anything an operator
> followed it to do was silently ignored. Do not use that text; it is retained in
> Git history only.

### Risk Context
- **Development:** a local PostgreSQL typically offers no TLS, so a development
  connection is normally cleartext.
- **Production:** database traffic carries the `DB_PASSWORD`, refresh-token
  material, activity data, emission records and evidence metadata. It should be
  encrypted, and — where a party outside your trust boundary is in the path —
  the server's identity should be verified.

### How it is implemented

`DB_SSLMODE` is a single real environment variable. It is bound to
`carbonflow.db.ssl-mode` and passed to PgJDBC as a **connection property** (not
concatenated into the JDBC URL, so a value can never be half-applied):

```properties
carbonflow.db.ssl-mode=${DB_SSLMODE:prefer}
spring.datasource.hikari.data-source-properties.sslmode=${carbonflow.db.ssl-mode}
```

`com.carbonflow.config.DataSourceTls` validates the value at startup. An
unrecognised value **fails the application context** rather than being passed
through or ignored, so a typo such as `requre` can never degrade silently. The
accepted set is exactly the six PgJDBC `sslmode` values below.

### What each mode actually guarantees

| `DB_SSLMODE` | Encrypted? | Server identity verified? | Forbids plaintext? |
|---|---|---|---|
| `disable` | No | No | No |
| `allow` | Only if the server offers TLS | No | No |
| `prefer` *(default)* | Only if the server offers TLS | No | No — **silently downgrades** |
| `require` | **Yes** | **No** | **Yes** |
| `verify-ca` | **Yes** | **Yes** — certificate chain against a trusted CA | **Yes** |
| `verify-full` | **Yes** | **Yes** — chain **and** hostname | **Yes** |

Two distinctions that matter and are often conflated:

- **`require` encrypts but does not authenticate the server.** It defeats
  passive interception. It does **not** defeat an active man-in-the-middle,
  because no certificate is checked. Do not describe it as "verified".
- **`prefer` is not a safe production default** — it downgrades to plaintext when
  the server offers no TLS, and nothing fails. It remains the default only so that
  existing local development is unaffected. The application logs a `WARN` at
  startup whenever the effective mode is `prefer`, `allow` or `disable`.

### Recommended production configuration

```bash
# Transport encryption, no server-identity check. Adequate when the database is
# reachable only over a private network path you control.
export DB_SSLMODE=require

# Preferred: encryption plus server certificate verification.
export DB_SSLMODE=verify-ca
```

`verify-ca` and `verify-full` verify against the **JVM trust store**. There is no
separate CarbonFlow variable for this — supply the CA with the standard JVM
mechanism:

```bash
java -Djavax.net.ssl.trustStore=/opt/certs/pg-ca.jks \
     -Djavax.net.ssl.trustStorePassword="$PG_TRUSTSTORE_PASSWORD" \
     -jar carbonflow-backend-1.0.0-PRO.jar
```

Using `verify-full` additionally requires that the hostname in `DB_HOST` matches
the certificate's subject/SAN, so the connection string must use the DNS name the
certificate was issued for — not an IP address.

### `SPRING_DATASOURCE_URL` still works

The standard Spring relaxed-binding override remains available if you need to set
URL parameters the properties file does not expose. Keep `stringtype=unspecified`,
which the repositories depend on:

```bash
export SPRING_DATASOURCE_URL='jdbc:postgresql://db.internal:5432/carbonflow?stringtype=unspecified'
```

### Variables that do not exist

| Name | Reality |
|---|---|
| `carbonflow.datasource.url` | **Never existed.** The real property is `spring.datasource.url`. |
| `DB_SSLMODE` | **Now real** (Phase 10.6.1). Previously documented but not read by the application. |
| `CARBONFLOW_DB_SSL_TRUST_STORE` | **Does not exist and is not needed.** Verification uses the JVM trust store. |

### Verification performed

| Claim | Status |
|---|---|
| Property binding, validation, context failure on a bad value | **VERIFIED** — `DataSourceTlsTest` (11 tests) |
| `sslmode` reaches PgJDBC as a connection property on the live datasource | **VERIFIED** — `DataSourceTlsWiringTest` (4 tests) asserts on the real Hikari `dataSourceProperties` |
| `require` cannot silently fall back to plaintext | **VERIFIED LIVE** — against a `ssl=off` server the application refuses to start with `The server does not support SSL` |
| A mistyped mode fails fast | **VERIFIED LIVE** — `DB_SSLMODE=requre` aborts startup with `Invalid sslmode value: requre` |
| `prefer` / `disable` still connect (no development regression) | **VERIFIED LIVE** |
| A **successful** TLS handshake against a TLS-enabled server | **NOT VERIFIED** — no TLS-enabled PostgreSQL was available in the validation environment |
| `verify-ca` / `verify-full` rejection of an untrusted certificate | **NOT VERIFIED** — requires a TLS-enabled server presenting an untrusted certificate |

The two `NOT VERIFIED` rows are infrastructure limits of the validation
environment, not known gaps in the implementation. Before production, confirm on
the real target that `pg_stat_ssl.ssl = 't'` for an application session.
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
| `DB_SSLMODE` | `prefer` | PostgreSQL transport security mode (see the DB TLS section). Not a secret. Invalid values fail startup. | Environment |
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
- Disaster recovery capability — **see docs/BACKUP-RECOVERY.md (procedures documented, not rehearsed)** (see Phase 10.6 finding F-08). `docs/BACKUP-RECOVERY.md` now exists and carries the procedure.
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
- `docs/BACKUP-RECOVERY.md` - backup and recovery procedure (documented; restore not yet rehearsed).
- `docs/SECURITY-THREAT-MODEL.md` — threat model.
- `docs/EXECUTION.md` — how to run and build the stack.
- `docs/PHASE9-SECURITY-MATRIX.md` - **does not exist.** An earlier revision cited it; the security test matrix is the Maven suite under `backend-java/src/test/`.
- `RolePermissions.java` — the 9-role × 44-permission matrix (ported from the retired Node backend).
- `JwtTokenProvider.java` — fail-closed JWT secret validation.
- `AuthService.java` — fail-closed refresh-secret validation.
- `LoginThrottle.java` — env-configurable failed-login throttle (Phase 9 addition).
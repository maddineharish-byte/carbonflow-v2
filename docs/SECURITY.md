# CarbonFlow — Security Architecture & Threat Model

## 1. Multi-Tenant Isolation Model

### 1.1 Tenant Context Derivation
- Security context is **never** accepted from untrusted client headers (e.g. raw `X-Organization-Id`) or request parameters alone.
- Upon receiving a request with an `Authorization: Bearer <JWT>` header:
  1. Cryptographically verify signature against `JWT_SECRET`.
  2. Extract `user_id` and authorized tenant claims.
  3. Validate against `organization_memberships` that the user has an active, non-revoked assignment to the target organization.
  4. Inject verified `TenantContext` into the request scope.

### 1.2 Data Access Layer Enforcement
- Every database query for tenant-scoped entities contains an explicit predicate:
  `WHERE organization_id = :tenantId`.
- No operations bypass tenant qualification. Automated security tests verify that requests fabricated with another tenant's ID or entity ID result in an immediate `403 Forbidden` or `404 Not Found`.

### 1.3 Authenticated Tenant / Role Switching
- `POST /api/v1/auth/switch-tenant-or-role` requires a valid bearer access token.
- The server derives the user identity from the verified JWT and ignores any client-supplied user ID.
- The requested organization must match an active membership belonging to that user.
- The requested role must exactly match the role assigned to that active membership.
- The endpoint never creates, activates, or mutates memberships and never assigns roles.
- Unauthorized organization/role combinations return the standard API error envelope with HTTP 403 and no token.
- Inactive users and inactive memberships cannot switch.
- `PLATFORM_ADMIN` is not selectable through ordinary tenant switching because this codebase has no independently verified platform-admin switching model.
- Login and switch responses expose only sanitized user fields and organization/role membership options; password hashes and secrets are not returned.

### 1.4 Frontend Session Boundary
- CarbonFlow does not automatically log in as a seeded administrator or select a default organization, role, or user on startup.
- With no stored token pair, the frontend shows the login screen and does not load protected tenant data.
- With a stored access token, the frontend validates it through `GET /api/v1/auth/me` before restoring authenticated state.
- If the access token is expired, the API client attempts one server-side refresh, stores the rotated pair, and retries the original request once.
- Multiple simultaneous 401 responses share one refresh operation; the client does not enter a refresh loop.
- Invalid, expired, revoked, or replayed refresh tokens clear the frontend session and return the user to the login boundary.
- Logout calls `POST /api/v1/auth/logout`, then clears the frontend access token, refresh token, user, organization, role, memberships, tenant-scoped data, and authorization-dependent UI state.
- The current frontend stores both tokens in browser `localStorage`; this is an acknowledged XSS-exposure trade-off, not an HttpOnly-cookie design.
- HTTP 401 responses trigger the refresh/recovery path; HTTP 403 responses preserve the authenticated session and remain authorization errors.

---

## 2. Authentication & Token Lifecycle

### 2.1 Cryptographic Standards
- **Password Storage**: Argon2id or bcrypt (salt rounds = 10). Plaintext passwords are never logged, stored, or echoed.
- **Access Tokens**: HMAC-SHA256 (HS256) JWTs with 15-minute expiration time (`exp`).
- **Refresh Tokens**: Cryptographically random 256-bit opaque strings, stored as keyed HMAC-SHA256 hashes in PostgreSQL, with 7-day expiration. The raw token is returned only to the client.
- **Refresh Token Rotation**: Every successful refresh revokes the presented record, records its replacement, and issues a new single-use record. Reusing a rotated token returns `REFRESH_TOKEN_REVOKED` without issuing a pair.
- **Refresh Sessions**: Each login or tenant/role switch starts a family ID. Rotation records retain that family, and logout revokes the active family. This is single-use rotation plus family revocation; it is not advanced family-compromise detection.
- **Device Binding**: No device, user-agent, or IP binding is implemented; the family ID is a logical rotation/session lineage only.
- **Persistence**: Production refresh-token routes use PostgreSQL as the single source of truth. V3 and the repository are implemented. The legacy in-memory implementation has been removed; there is no refresh-token memory fallback.

### 2.2 Refresh Lifecycle
- `POST /api/v1/auth/refresh` validates the opaque token format, keyed hash lookup, expiration, revocation state, active user, and active membership with the recorded organization and role.
- The refresh request cannot change tenant or role context; authorization context comes from the server-side record and membership.
- `POST /api/v1/auth/logout` revokes the presented refresh-token family but does not invalidate already-issued access JWTs before their normal expiration.
- The Java backend remains a separate, unchanged implementation and is not part of the active Node refresh lifecycle.

### 2.3 TASK-004A-TEST-MIGRATION
- The legacy refresh tests now query PostgreSQL for persisted records instead of inspecting or mutating the in-memory refresh-token array.
- Login assertions verify the PostgreSQL hash, canonical user, organization, role, family, expiry, and initial state.
- Rotation assertions verify predecessor revocation, replacement lineage, successor state, and preserved context.
- Expiration is controlled by updating the test-owned PostgreSQL row.
- Organization/role override tests verify the persisted organization and role remain unchanged.
- The legacy refresh-token implementation has been removed. PostgreSQL remains the only refresh-token persistence source.

### 2.4 Post-TASK-004A Runtime Hardening
- Production startup fails closed when required PostgreSQL or signing configuration is missing.
- Security headers, explicit CORS origins, bounded JSON parsing, process-local rate limiting, and non-sensitive API errors are enabled by the server bootstrap.
- Evidence uploads validate file signatures, use path-boundary checks, and return explicit storage errors instead of simulated content.
- CSV exports neutralize spreadsheet formula prefixes.
- Inactive users are rejected at login before token issuance.
- The current rate limiter is process-local; a shared store is required before multi-process production deployment.

---

## 3. Evidence Vault File Security
1. **File Type Whitelist**:
   - `application/pdf`, `text/csv`, `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` (.xlsx), `application/vnd.ms-excel` (.xls), `application/vnd.openxmlformats-officedocument.wordprocessingml.document` (.docx), `image/png`, `image/jpeg`.
   - Content magic bytes are checked alongside MIME types.
2. **File Size Limit**: Strict 25 MB ceiling enforced at the gateway.
3. **Checksum Verification**: System generates SHA-256 hash upon streaming receipt and stores it for tamper detection.
4. **Storage Isolation**: Files are stored in tenant-partitioned private storage by the Java evidence vault service; path-boundary checks prevent traversal and direct filesystem-path disclosure.
5. **Metadata Authority**: Production evidence metadata and links are tenant-qualified in PostgreSQL. A link is inserted only after the referenced activity, audit, or facility is verified as owned by the authenticated organization.
6. **Transactional Cleanup**: Evidence metadata and optional link creation occur in one PostgreSQL transaction. If persistence fails after file storage, the route deletes the stored file.
7. **Download Authorization**: Download first fetches tenant-owned metadata from PostgreSQL. Cross-tenant IDs are indistinguishable from missing records, and internal `storagePath` values are omitted from API responses.
8. **Missing Objects**: Metadata with a missing backing file returns a structured `EVIDENCE_FILE_NOT_FOUND` response; other storage failures return a non-sensitive service error.

---

## 4. Calculation and Emission Ledger Security
1. **Tenant Context**: Calculation and emission queries derive `organization_id` exclusively from the authenticated PostgreSQL-backed tenant context; client-supplied organization IDs are ignored for authorization.
2. **Reference Integrity**: A calculation locks and validates the referenced activity, facility, reporting period, factor version, and GWP set before persistence. Composite tenant foreign keys reject cross-tenant activity, facility, reporting-period, and calculation relationships at the database layer.
3. **No IDOR/BOLA**: Cross-tenant calculation lookup, emission lookup, calculation execution, and emission listing return not-found or empty results without exposing internal SQL details.
4. **Parameterized SQL**: `calculation-repository.ts` uses parameterized queries only; no request value is interpolated into dynamic SQL.
5. **Fail Closed**: Production calculation and emission endpoints return structured 403/404/400/503 envelopes. PostgreSQL failures do not fall back to in-memory arrays and do not leak database error text.
6. **Snapshot Integrity**: Factor version, factor value/unit/source/year, GWP set/name, normalized quantity/unit, conversion factor, gas results, and calculation hash are persisted so historical results do not depend on a later active-factor change.
7. **No Double Counting**: Recalculation supersedes prior active emission records for the activity inside the same transaction; only `ACTIVE` records are summed.

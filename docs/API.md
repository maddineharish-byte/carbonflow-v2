# CarbonFlow — RESTful API Contracts Specification

Base URI: `/api/v1`

## 1. Unified Response Envelopes

### Success Response
```json
{
  "success": true,
  "data": {},
  "message": "Resource retrieved successfully"
}
```

### Error Response
```json
{
  "success": false,
  "error": {
    "code": "REPORTING_PERIOD_NOT_FOUND",
    "message": "Reporting period does not exist or access denied."
  }
}
```

---

## 2. API Endpoints by Domain

### 2.1 Authentication & Profile
- `POST /api/v1/auth/register` — Register user & organization. Creates the organization in `PENDING_ACTIVATION` with a `COMPANY_ADMIN` user and membership in one transaction; answers **201 without tokens** (sign-in is blocked until platform approval). Duplicate email → 409 `EMAIL_ALREADY_REGISTERED`; blank fields → 400 `VALIDATION_ERROR`.
- `POST /api/v1/auth/login` — Explicitly authenticate a user and receive `accessToken` (15m), opaque `refreshToken` (7d), and the user's active organization/role membership options. The refresh token is returned only in the authentication response; the server stores only its keyed hash.
- `POST /api/v1/auth/switch-tenant-or-role` — **Authenticated only.** Switch to an active organization/role combination already assigned to the authenticated user. The endpoint never creates or mutates memberships, and rejects unauthorized organization/role combinations. `PLATFORM_ADMIN` is not selectable through ordinary tenant switching.
- `POST /api/v1/auth/refresh` — Accept `{ "refreshToken": "..." }`, validate the server-side record, active user, and active organization/role membership, revoke the old token, and return a new access/refresh pair. The endpoint is single-use and rejects `userId`, `organizationId`, `targetOrgId`, and `targetRole` context overrides.
- `POST /api/v1/auth/logout` — Accept `{ "refreshToken": "..." }` and revoke the current refresh-token family. Access JWTs already issued are not retroactively invalidated and remain limited by their 15-minute expiration.
- `GET  /api/v1/auth/me` — Validate an existing access token and return the authenticated profile and active memberships. The frontend uses this endpoint for session restoration.
- Refresh/logout failures use the standard error envelope with non-sensitive codes such as `REFRESH_TOKEN_REQUIRED`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REVOKED`, `USER_DEACTIVATED`, and `REFRESH_MEMBERSHIP_INVALID`.

### 2.2 Organizations, Scope Structure & Boundaries
Organization settings:
- `GET  /api/v1/organizations/current` — Current tenant profile and settings.
- `PUT  /api/v1/organizations/current` — Update organization settings (`organization.update`).

Facilities (`facilities.read` / `facilities.create` / `facilities.update` / `facilities.delete`):
- `GET  /api/v1/facilities` — List tenant facilities ordered by name.
- `POST /api/v1/facilities` — Create facility → **201** "Facility registered successfully." Required: `name`, `facilityCode`, `country`, `gridRegion`; `facilityType` defaults to `MANUFACTURING` (`MANUFACTURING|OFFICE|DATA_CENTER|WAREHOUSE|RETAIL|LOGISTICS`); `floorAreaM2` ≥ 0; `legalEntityId` must belong to the caller's tenant (404 `LEGAL_ENTITY_NOT_FOUND`). A repeated code **within the tenant** → 409 `DUPLICATE_FACILITY_CODE` (uniqueness is `(organization_id, facility_code)` — other tenants may reuse a code).
- `GET  /api/v1/facilities/:id` — Facility detail (greenfield verb).
- `PUT  /api/v1/facilities/:id` — Full replace with create-time validation (`facilities.update`); code collision → 409 `DUPLICATE_FACILITY_CODE`.
- `DELETE /api/v1/facilities/:id` — Hard delete (`facilities.delete`). Departments and boundary memberships cascade with the facility; rows referenced by activity data or emissions (`ON DELETE RESTRICT`) → 409 `RESOURCE_IN_USE`. The schema has no status column, so delete is the only lifecycle verb.

Legal entities (`organization.read` / `organization.update`):
- `GET  /api/v1/legal-entities` — List tenant legal entities ordered by name.
- `POST /api/v1/legal-entities` — Create legal entity → **201**. `name` + `jurisdiction` required; `ownershipPercentage` 0–100 (default 100); `registrationNumber` optional. The schema defines no name/registration uniqueness, so duplicates are permitted.
- `GET  /api/v1/legal-entities/:id` — Detail (greenfield verb).
- `PUT  /api/v1/legal-entities/:id` — Partial update: only provided fields are validated and written (greenfield verb).
- `DELETE /api/v1/legal-entities/:id` — Hard delete (greenfield verb); linked facilities are unlinked (`ON DELETE SET NULL`), never removed.

Departments (greenfield — no Node counterpart; reuses `facilities.*` codes, ADR-015):
- `GET  /api/v1/departments?facilityId=` — List tenant departments ordered by name; optional tenant-validated facility filter (foreign or unknown filter → empty list; malformed → 400 `INVALID_ARGUMENT`).
- `POST /api/v1/departments` — Create → **201**. `facilityId` (must belong to the tenant, else 404 `FACILITY_NOT_FOUND`) + `name` (≤150).
- `GET  /api/v1/departments/:id` · `PUT /api/v1/departments/:id` (partial: `name` and/or `facilityId`, re-parent tenant-validated) · `DELETE /api/v1/departments/:id` — greenfield verbs; departments always belong to a facility (V1 `facility_id NOT NULL`).

Reporting periods (`reporting_periods.read` / `.create` / `.update`):
- `GET  /api/v1/reporting-periods` — List tenant periods, newest start date first.
- `POST /api/v1/reporting-periods` — Create → **201** "Reporting period created." `name`, `startDate`, `endDate` required (`YYYY-MM-DD`); inverted range → 400 `INVALID_DATE_RANGE`; unparseable date → 400 `VALIDATION_ERROR`; `status` defaults to `OPEN` (`OPEN|UNDER_AUDIT|LOCKED`). Overlapping periods are **not** restricted (no rule exists in schema/Node/docs).
- `GET  /api/v1/reporting-periods/:id` — Detail (greenfield verb).
- `PUT  /api/v1/reporting-periods/:id` — Partial update (greenfield verb): `name`; `startDate`+`endDate` always together; `status` validated against the V1 lifecycle values.
- No `DELETE` verb: the frozen permission matrix has no `reporting_periods.delete` — the endpoint answers 405; lifecycle is `status`.

Organizational boundaries (`reporting_periods.read` for reads, `reporting_periods.update` for writes; ADR-015):
- `GET  /api/v1/boundaries` — List boundary definitions with persisted `facilityIds` membership.
- `POST /api/v1/boundaries` — Configure a reporting boundary → **201**. Body: `reportingPeriodId` (required, tenant-validated else 404 `REPORTING_PERIOD_NOT_FOUND`), `consolidationApproach` (default `OPERATIONAL_CONTROL`; `OPERATIONAL_CONTROL|FINANCIAL_CONTROL|EQUITY_SHARE`), optional `notes`, optional `facilityIds[]` — **every id is tenant-validated (404 `FACILITY_NOT_FOUND`), and a cross-tenant pairing is rejected even when both UUIDs are valid; nothing is persisted on rejection (transactional)**.
- `GET  /api/v1/boundaries/:id` — Detail with membership (greenfield verb).
- `PUT  /api/v1/boundaries/:id` — Partial update of approach/notes (greenfield verb).
- `DELETE /api/v1/boundaries/:id` — Hard delete (greenfield verb); membership rows cascade.
- `POST /api/v1/boundaries/:id/facilities` — Attach `{ "facilityId": "..." }` → **201**; duplicate → 409 `DUPLICATE_BOUNDARY_FACILITY`; cross-tenant/unknown → 404 `FACILITY_NOT_FOUND` with no row written (greenfield verb).
- `DELETE /api/v1/boundaries/:id/facilities/:facilityId` — Detach → 200; not attached → 404 `BOUNDARY_FACILITY_NOT_FOUND` (greenfield verb).

**Scope rules (Phase 4):** every endpoint derives its organization exclusively from the authenticated tenant context — client-supplied organization ids are never accepted. Missing, malformed and cross-tenant ids are indistinguishable: all answer **404 `*_NOT_FOUND`** with "*… does not exist or access denied.*" so identifiers cannot be enumerated across tenants.

### 2.3 Activity Data & Data Requests
- `GET  /api/v1/activity-data` — Query activity data with period & facility filters (`activity_data.read`).
- `POST /api/v1/activity-data` — Create activity data record (`activity_data.create`).
- `PUT  /api/v1/activity-data/:id` — Update activity data (`activity_data.update`).
- `POST /api/v1/activity-data/:id/submit` — Submit activity data for review (`activity_data.submit`).
- `GET  /api/v1/data-requests` — List data collection requests.
- `POST /api/v1/data-requests` — Create data request assignment.

### 2.4 Reference Data (Factors & GWP)
- `GET  /api/v1/reference/gwp-sets` — List supported GWP sets (AR4, AR5, AR6).
- `GET  /api/v1/reference/emission-factors` — List versioned emission factors.
- `POST /api/v1/reference/emission-factors` — Add or version an emission factor (`emission_factors.manage`).

### 2.5 Calculations & Emission Ledger
- `POST /api/v1/calculations/run` — Run calculation for activity data record (`calculations.create`).
- `POST /api/v1/calculations/batch-run` — Trigger batch calculation for reporting period (`calculations.create`).
- `GET  /api/v1/calculations/:id` — Get calculation audit snapshot with formula trace.
- `GET  /api/v1/emissions` — List active emission records with Scope 1 / Scope 2 Dual Reporting breakdown.

### 2.6 Audit Workflow & Review Desk
- `GET  /api/v1/audits` — List audits for reporting periods (`audits.read`).
- `POST /api/v1/audits` — Initiate audit (`audits.create`).
- `GET  /api/v1/audits/:id` — Audit detail including checklist status and review history.
- `POST /api/v1/audits/:id/transition` — Transition audit state (`audits.submit`, `audits.review`, `audits.approve`, `audits.lock`).
- `POST /api/v1/audits/:id/findings` — Log review finding (`audits.review`).
- `POST /api/v1/audits/:id/comments` — Add audit comment (`audits.review`).
- `POST /api/v1/audits/:id/checklist/:itemId/verify` — Verify checklist requirement.

### 2.7 Evidence Vault
- `GET  /api/v1/evidence` — List uploaded evidence documents (`evidence.read`).
- `POST /api/v1/evidence/upload` — Upload file (multipart/form-data, ≤ 25MB) with SHA-256 generation (`evidence.upload`).
- `GET  /api/v1/evidence/:id/download` — Download private evidence file.
- `POST /api/v1/evidence/:id/link` — Link evidence to activity data or audit.

### 2.8 Inventory Snapshots, Targets & Reduction Projects
- `GET  /api/v1/inventory` — List inventory snapshots (`inventory.read`).
- `POST /api/v1/inventory/snapshot` — Generate immutable inventory snapshot (`inventory.create`).
- `POST /api/v1/inventory/:id/lock` — Lock inventory period (`inventory.lock`).
- `GET  /api/v1/targets` — List carbon targets (`targets.read`).
- `POST /api/v1/targets` — Create carbon target (`targets.create`).
- `GET  /api/v1/reduction-projects` — List reduction projects (`reduction_projects.read`).
- `POST /api/v1/reduction-projects` — Create reduction project (`reduction_projects.create`).

### 2.9 Analytics & Reports
- `GET  /api/v1/analytics/dashboard` — Executive summary KPIs, Scope breakdown, audit progress (`analytics.read`).
- `GET  /api/v1/analytics/breakdown` — Detailed scope, facility, and trend series.
- `GET  /api/v1/reports/export` — Export CSV format for emissions ledger and audit packs (`reports.read`).

### 2.10 Tenant User Administration & Platform Tenants (greenfield — Java only)
> **Not part of the Node reference contract.** The Node backend has no users or platform endpoints; these were specified in Phase 3 (ADR-014) because registration/approval and tenant user administration require them. They follow the standard envelope and permission model.

Tenant user administration (all scoped to the caller's organization; `users.read` / `users.create` / `users.update` / `users.disable`):
- `GET  /api/v1/users` — List the tenant's members (id, email, fullName, role, active, createdAt, lastLoginAt).
- `POST /api/v1/users` — Create a user with a role assignment (201). 409 `EMAIL_ALREADY_REGISTERED` for duplicates; `PLATFORM_ADMIN` cannot be assigned by tenant administrators.
- `PATCH /api/v1/users/:id` — Update fullName / role / active. Administrators cannot change their own role or disable themselves (400 `VALIDATION_ERROR`); unknown or cross-tenant ids → 404 `USER_NOT_FOUND`.
- `POST /api/v1/users/:id/disable` — Deactivate the global account (blocks sign-in and refresh with `USER_DEACTIVATED`).
- `POST /api/v1/users/:id/enable` — Reactivate.

Platform tenant administration (permissions `platform.tenants.read` / `platform.tenants.manage`, held only by `PLATFORM_ADMIN`):
- `GET  /api/v1/platform/tenants?status=` — List organizations by lifecycle status (`PENDING_ACTIVATION`, `ACTIVE`, `REJECTED`, `SUSPENDED`).
- `POST /api/v1/platform/tenants/:id/approve` — `PENDING_ACTIVATION|REJECTED|SUSPENDED` → `ACTIVE` ("Organization approved.").
- `POST /api/v1/platform/tenants/:id/reject` — → `REJECTED` ("Organization rejected."), optional `{ "note": "..." }`.
- `POST /api/v1/platform/tenants/:id/suspend` — → `SUSPENDED` ("Organization suspended."), optional `{ "note": "..." }`.
- Transitions record actor (`status_changed_by`), time and note (V8 columns). A non-`ACTIVE` organization can neither log in (403 `ORGANIZATION_NOT_ACTIVE` with a per-status message) nor refresh (401 `REFRESH_MEMBERSHIP_INVALID`); already-issued access tokens drain within their 15-minute lifetime.

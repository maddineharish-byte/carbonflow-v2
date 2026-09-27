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

Java (Phase 5, ADR-016) serves the canonical 10-state machine (`docs/AUDIT_WORKFLOW.md`) over the V1/V7 governance tables; the Node backend's legacy 6-state `/audit-rooms` model has no Java counterpart. Every transition is validated server-side (state ∩ permission ∩ gates) — never in the frontend.

Audits — `audits.read` for reads, `audits.create` for lifecycle writes:
- `GET  /api/v1/audits` — List the tenant's audits, each with `checklistSummary` and `openFindingsCount`.
- `POST /api/v1/audits` — Initiate an audit for a reporting period (201); seeds the 8 canonical checklist items. 409 `AUDIT_ALREADY_EXISTS` for a duplicate period; 404 `REPORTING_PERIOD_NOT_FOUND` for a foreign/unknown period.
- `GET  /api/v1/audits/:id` — Detail: `period`, `checklist`, `findings`, `comments`, `corrections`, `approvals`, `lockEvent`, `checklistSummary`, `openFindingsCount`. Unknown and cross-tenant ids are indistinguishable (404 `AUDIT_NOT_FOUND`).
- `PUT  /api/v1/audits/:id` — Update draft `notes` only; 409 `AUDIT_NOT_DRAFT` otherwise. Status is never client-settable.

Transition — edge permission from the frozen matrix (`docs/RBAC.md`):
- `POST /api/v1/audits/:id/transition` — `{ "targetState": "...", "reason": "..." }` (`audits.submit`, `audits.review`, `audits.approve` or `audits.lock` depending on the edge). 400 `INVALID_TRANSITION` with Node's exact message and valid-target list, 400 `CHECKLIST_INCOMPLETE` / unresolved-HIGH-finding gates before `APPROVED`/`AUDIT_READY`/`LOCKED`, 400 `NO_REVIEW_FINDING` for a correction without a logged finding, 400 `VALIDATION_ERROR` for a missing reject reason, 403 `FORBIDDEN` for a role without the edge's permission, 409 `AUDIT_LOCKED` once frozen. Success: "Audit successfully transitioned to X."

Checklist — `audits.review`:
- `POST /api/v1/audits/:id/checklist` — Add an item (201; `title` required, `code` ≤ 50); 409 `DUPLICATE_CHECKLIST_ITEM`.
- `PUT  /api/v1/audits/:id/checklist/:itemId` — Partial update of `title`/`isMandatory`/`notes` (absent = unchanged); 400 `VALIDATION_ERROR` when no field is supplied.
- `POST /api/v1/audits/:id/checklist/:itemId/verify` — `{ "isSatisfied": true, "notes": "..." }`; the flag is required (400 `VALIDATION_ERROR` — Node coerced an absent value to `false`; Java rejects the ambiguity) and stamps verifier + timestamp. 404 `CHECKLIST_ITEM_NOT_FOUND`.

Review desk:
- `GET  /api/v1/audits/:id/findings` (`audits.read`) / `POST` (201, `audits.review`) — `title` + `description` required (Node's "Title and description required."); `severity`/`status` are checked against the V1 CHECK constraint (400 `VALIDATION_ERROR`, never a 503).
- `PUT  /api/v1/audits/:id/findings/:findingId` — Partial update; `status: RESOLVED` stamps `resolvedBy`. 404 `FINDING_NOT_FOUND` (Node parity); unknown and cross-tenant ids are indistinguishable.
- `POST /api/v1/audits/:id/findings/:findingId/resolve` — "Finding marked as resolved."
- `GET  /api/v1/audits/:id/comments` (`audits.read`) / `POST` (201, `audits.review`) — blank text → 400 `EMPTY_COMMENT` ("Comment text cannot be blank."). Node left comment creation ungated; Java enforces the documented permission (deviation, ADR-016).
- `PUT  /api/v1/audits/:id/comments/:commentId` / `DELETE ...` — Author-only edit/removal (`audits.review`); anyone else → 403 `NOT_COMMENT_AUTHOR`. 404 `COMMENT_NOT_FOUND`.
- `GET  /api/v1/audits/:id/corrections` (`audits.read`) / `POST` (201, `audits.review`) — only while the audit is in `REVIEW` or `CORRECTION_REQUESTED`, else 409 `AUDIT_NOT_IN_CORRECTION_WINDOW`; `activityDataId` must be tenant-owned (404 `ACTIVITY_DATA_NOT_FOUND`).
- `PUT  /api/v1/audits/:id/corrections/:correctionId` — `{ "isResolved": true }` required (400 `VALIDATION_ERROR`).
- `GET  /api/v1/audits/:id/approvals` — Approval history with approver, role and SHA-256 signature hash (`audits.read`); `POST`/`PUT` on this path → 405 (approvals are written only by the `REVIEW → APPROVED` transition).

Governed lock: every governed write on a `LOCKED` audit answers 409 `AUDIT_LOCKED` (reads stay open). The lock persists an `audit_lock_events` row with a SHA-256 `governanceStateHash` over the frozen audit state and freezes the reporting period — a **governed audit lock (integrity checksum), not cryptographic immutability** (ADR-016).

### 2.7 Evidence Vault

Java (Phase 5, ADR-016) matches the Node upload chain in order — `FILE_MISSING` → entity relationship → 25 MB → MIME allow-list → magic bytes → SHA-256 — and cleans up the stored file if any step fails. `storagePath` is never serialized; bytes leave only through download after `evidence.read`.

- `GET  /api/v1/evidence` — List the tenant's evidence records (`evidence.read`).
- `GET  /api/v1/evidence/:id` — Record metadata (`evidence.read`); the storage path is excluded by construction. Malformed, unknown and cross-tenant ids are indistinguishable: 404 `EVIDENCE_NOT_FOUND` with Node's exact message.
- `POST /api/v1/evidence/upload` — multipart `file` plus optional `entityType`/`entityId` pre-validation (201, `evidence.upload`). ≤ 25 MB (over-limit file → 400 "File size N exceeds 25 MB limit.", multipart above the 28 MB limit → 400 `UPLOAD_FAILED`), MIME allow-list (`application/pdf`, `text/csv`, XLSX/XLS/DOCX, PNG, JPEG/JPG, `text/plain`) with matching magic bytes, SHA-256 over the exact stored bytes. 400 `FILE_MISSING` for an absent/empty part; 400 `INVALID_EVIDENCE_RELATIONSHIP` for an unknown/foreign target (`ACTIVITY_DATA|AUDIT|FACILITY`).
- `GET  /api/v1/evidence/:id/download` — Stream the current bytes (`evidence.read`); missing file → 404 `EVIDENCE_FILE_NOT_FOUND` ("The evidence file is unavailable."); file names are CR/LF/quote-sanitized in `Content-Disposition`.
- `POST /api/v1/evidence/:id/link` — `{ "entityType": "ACTIVITY_DATA|AUDIT|FACILITY", "entityId": "..." }` (201, `evidence.upload`), tenant-validated on both sides; duplicate → 409 `DUPLICATE_EVIDENCE_LINK`.
- `POST /api/v1/evidence/:id/versions` — Append a version (201, `evidence.version`); the record is repointed to the head and download always serves the head.
- `DELETE /api/v1/evidence/:id` — Governed delete (`evidence.delete`): the row and its versions/links cascade and stored files are removed best-effort; while the evidence is linked to an audit → 409 `EVIDENCE_IN_USE`.

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

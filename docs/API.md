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
    "code": "RESOURCE_NOT_FOUND",
    "message": "Reporting period does not exist or access denied"
  }
}
```

---

## 2. API Endpoints by Domain

### 2.1 Authentication & Profile
- `POST /api/v1/auth/register` — Register user & organization.
- `POST /api/v1/auth/login` — Explicitly authenticate a user and receive `accessToken` (15m), opaque `refreshToken` (7d), and the user's active organization/role membership options. The refresh token is returned only in the authentication response; the server stores only its keyed hash.
- `POST /api/v1/auth/switch-tenant-or-role` — **Authenticated only.** Switch to an active organization/role combination already assigned to the authenticated user. The endpoint never creates or mutates memberships, and rejects unauthorized organization/role combinations. `PLATFORM_ADMIN` is not selectable through ordinary tenant switching.
- `POST /api/v1/auth/refresh` — Accept `{ "refreshToken": "..." }`, validate the server-side record, active user, and active organization/role membership, revoke the old token, and return a new access/refresh pair. The endpoint is single-use and rejects `userId`, `organizationId`, `targetOrgId`, and `targetRole` context overrides.
- `POST /api/v1/auth/logout` — Accept `{ "refreshToken": "..." }` and revoke the current refresh-token family. Access JWTs already issued are not retroactively invalidated and remain limited by their 15-minute expiration.
- `GET  /api/v1/auth/me` — Validate an existing access token and return the authenticated profile and active memberships. The frontend uses this endpoint for session restoration.
- Refresh/logout failures use the standard error envelope with non-sensitive codes such as `REFRESH_TOKEN_REQUIRED`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_EXPIRED`, `REFRESH_TOKEN_REVOKED`, `USER_DEACTIVATED`, and `REFRESH_MEMBERSHIP_INVALID`.

### 2.2 Organizations & Boundaries
- `GET  /api/v1/organizations/current` — Current tenant profile and settings.
- `PUT  /api/v1/organizations/current` — Update organization settings (`organization.update`).
- `GET  /api/v1/facilities` — List tenant facilities (`facilities.read`).
- `POST /api/v1/facilities` — Create facility (`facilities.create`).
- `GET  /api/v1/legal-entities` — List legal entities (`organization.read`).
- `POST /api/v1/legal-entities` — Create legal entity (`organization.update`).
- `GET  /api/v1/reporting-periods` — List reporting periods (`reporting_periods.read`).
- `POST /api/v1/reporting-periods` — Create reporting period (`reporting_periods.create`).
- `GET  /api/v1/boundaries` — List organizational boundary definitions.
- `POST /api/v1/boundaries` — Configure reporting boundaries.

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

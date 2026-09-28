# CarbonFlow — React Frontend (Phase 8 Integration)

The frontend is the existing React 19 + TypeScript + Vite + Tailwind CSS 4 + Recharts single-page application. Phase 8 wired it to the verified Java backend (`/api/v1`) — no mock data, no demo fallbacks, no second calculation engine.

## Environment

| Variable | Required | Purpose |
|---|---|---|
| `VITE_JAVA_API_BASE_URL` | no (default `''`) | Java backend origin. Empty = same-origin relative `/api/v1` paths (reverse-proxy deployments). For direct dev-server access: `http://localhost:8080`. |

Vite injects `VITE_*` variables at build/dev time from the environment or `.env` (see `.env.example`).

## Startup (local development)

```bash
# 1. PostgreSQL running (carbonflow_dev)
# 2. Java backend on :8080 (see backend-java/README.md for DB + secret env vars)
# 3. Frontend dev server on :5173
VITE_JAVA_API_BASE_URL=http://localhost:8080 npx vite --port 5173
```

Production build: `npm run build` (static bundle) served behind the same origin as the API, or with `VITE_JAVA_API_BASE_URL` pointing at the Java service.

## Authentication flow

1. `POST /api/v1/auth/login` with `{email, password, organizationId?}` → `{accessToken, refreshToken}` + session (`user`, `organization`, `role`, `permissions`, `memberships`).
2. Tokens are stored in `localStorage` (`cf_access_token` / `cf_refresh_token`) and sent as `Authorization: Bearer …` on every request. **Tokens never appear in URLs.**
3. A 401 on any request triggers a single-flight `POST /api/v1/auth/refresh` (rotation) and one retry; concurrent 401s share the same refresh. A failed refresh clears the session and redirects to login.
4. `POST /api/v1/auth/logout` revokes the refresh family; local state is cleared regardless of the server outcome.
5. Session restore on page load: `GET /api/v1/auth/me` (no tokens in the response).

## Frontend/backend contract

- **Envelope**: success `{success: true, data, message?}` / error `{success: false, error: {code, message}}` — handled centrally in `src/services/api.ts`.
- **Permissions**: the session's `permissions[]` (frozen 44-code RBAC matrix) drives navigation (`src/services/permissions.ts`). Hiding a menu item is UX only — the backend re-authorizes every request.
- **Tenant context**: always derived from the authenticated session; the client never sends an organization id for tenant-scoped data.
- **Numbers**: Java strips trailing zeros (`PlainBigDecimalSerializer`); the frontend formats for display only (`.toFixed`, `.toLocaleString`).
- **Scope 2**: location-based and market-based values are separate fields everywhere; they are never summed in the UI.

## Module map

| View | Endpoint(s) | Permission |
|---|---|---|
| `DashboardView` | `GET /analytics/dashboard`, `POST /analytics/trend-insights` | `analytics.read` |
| `BoundariesView` | `GET/POST /facilities`, `GET/POST /reporting-periods`, `GET/PUT /organizations/current` | `facilities.*`, `reporting_periods.*`, `organization.*` |
| `ActivityDataView` | `GET/POST /activity-data`, `POST /calculations/run`, `/batch-run` | `activity_data.*`, `calculations.create` |
| `EmissionsView` | `GET /emissions`, `GET /calculations/:id`, `GET /reports/export-csv` | `reports.read` |
| `FactorsView` | `GET /reference/gwp-sets`, `/reference/emission-factors` | `emission_factors.read` |
| `AuditView` | `GET /audits`, `GET /audits/:id`, `POST /audits`, `POST /audits/:id/transition`, checklist/findings/comments | `audits.*` |
| `EvidenceView` | `GET /evidence`, `POST /evidence/upload`, `GET /evidence/:id/download` | `evidence.*` |
| `InventoryView` | `GET /inventory`, `POST /inventory/snapshot`, `POST /inventory/:id/lock` | `inventory.*` |
| `AnalyticsView` | `GET /analytics/periods/:id/summary`, `GET /analytics/breakdown` | `analytics.read` |
| `TargetsView` | `GET/POST /targets`, `PUT /targets/:id`, `GET/POST /reduction-projects`, `PUT /reduction-projects/:id` | `targets.*`, `reduction_projects.*` |
| `AdminView` | `GET/POST /users`, `PATCH /users/:id`, `POST /users/:id/disable|enable` | `users.*` |
| `PlatformAdminView` | `GET /platform/tenants`, `GET /platform/tenants/:id`, `POST …/approve|reject|suspend` | `platform.tenants.*` |

## Tests

```bash
npm run test:frontend   # node:test suites in src/ (auth boundary, refresh races, auth flow, integration)
```

36 tests: auth boundary (4), API refresh races (4), auth flow (6), integration (22 — navigation gating, dashboard real/empty states, backend-driven target progress, audit transitions, inventory/analytics contracts, CSV auth header, 403/404/409/500 mapping, no-demo-fallback assertions).

## Known limitations

- Legal entities, departments and organizational boundaries have backend APIs but no UI yet (no existing frontend surface to wire; deferred).
- Evidence detail/versions/link/delete endpoints exist in the backend; the UI covers list/upload/download only.
- Activity update/submit and facility update/delete verbs exist in the backend; the UI covers create/list.
- Browser UAT could not be automated in the build environment (no desktop browser connected); an API-level smoke test against the live stack (login → session → all module endpoints → CSV export → logout) was performed instead.

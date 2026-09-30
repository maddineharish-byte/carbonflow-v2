# CarbonFlow Persistence Architecture

**PHASE 2 IN PROGRESS — IDENTITY, SCOPE, ACTIVITY, EVIDENCE METADATA, CALCULATIONS, AND EMISSION RECORDS IMPLEMENTED**

## Objective

Make PostgreSQL the source of truth for identity, authorization, and business domains without introducing an ORM or a second persistence abstraction.

## Repository boundaries

| Domain | PostgreSQL source | Planned boundary | Tenant key |
|---|---|---|---|
| Identity | `organizations`, `users`, `roles`, `permissions`, `organization_memberships` | `identity-repository.ts` | `organization_id` |
| Reference data | `gwp_sets`, `gwp_values`, `emission_factors`, `emission_factor_versions`, `calculation_methodologies` | `calculation-repository.ts` (**reference reads implemented**; `calculation_methodologies` has no current route) | Global/reference |
| Scope | `legal_entities`, `facilities`, `departments`, `reporting_periods`, `organizational_boundaries` | `scope-repository.ts` (**implemented for facilities, legal entities, reporting periods**) | `organization_id` |
| Activity | `activity_data` (`data_requests` has no current route and is not migrated) | `activity-repository.ts` (**implemented**) | `organization_id` |
| Calculation | `calculations`, `calculation_gas_results`, `emission_records` | `calculation-repository.ts` (**implemented; calculation and emission aggregate persist transactionally**) | `organization_id` |
| Audit | `carbon_audits`, `audit_checklist_items`, `review_findings`, `review_comments`, approvals/locks | `audit-repository.ts` | `organization_id` through audit |
| Evidence | `evidence_records`, `evidence_links` (`evidence_versions` has no current route and is not migrated) | `evidence-repository.ts` + `storage.ts` (**metadata/links implemented**) | `organization_id` |
| Inventory | `inventory_snapshots` | `inventory-repository.ts` | `organization_id` |
| Projects | `carbon_targets`, `reduction_projects` | `target-repository.ts` | `organization_id` |

## Repository rules

- SQL is parameterized with the existing `pg` driver.
- Repository methods accept an explicit trusted tenant context; they never accept tenant identity from an untrusted request body.
- Every business query includes `organization_id` or resolves through a tenant-owned parent.
- Foreign-key ownership is validated before writes.
- Multi-table writes use an explicit `PoolClient` transaction.
- PostgreSQL errors are mapped to safe domain errors without exposing SQL details.
- PostgreSQL is the source of truth for identity, scope, activity, evidence metadata/links, calculations, and emission records in production; calculation and emission writes share the `calculation-repository.ts` transaction boundary.
- No repository falls back to in-memory arrays. PostgreSQL is the single source of truth for every domain. (The Node-era in-memory `server/db.ts` store was decommissioned in Phase 10.5.)
- No in-memory fallback is allowed for production domain operations.

## Service rules

Services own business workflows and transaction boundaries. Controllers perform HTTP parsing, call services, and map domain errors to the standard API envelope. Services do not depend on web-framework request/response objects.

## Connection ownership

Repositories currently own environment-configured `pg` pools, matching the existing TASK 2.1 implementation pattern. A shared injected pool and server-owned graceful shutdown remain deployment hardening work. Pool configuration must support environment-specific limits and TLS before production deployment.

## Identity mapping

ADR-006 remains authoritative:

- PostgreSQL UUIDs are persistence identities.
- User lookup uses normalized email.
- Organization lookup uses a stable organization business key.
- Role lookup uses canonical role name/UUID.
- Membership lookup uses user, organization, and role relationship.
- No legacy mapping table is introduced.

## Migration sequencing constraint

Identity persistence was implemented as the first vertical slice. Production authentication, `/auth/me`, tenant/role switching, and refresh identity validation now use PostgreSQL-backed users, organizations, memberships, and roles. Activity data and evidence metadata are now PostgreSQL-backed in production through `activity-repository.ts` and `evidence-repository.ts`; routes retain the explicit development-only fixture path. Calculations, calculation gas results, and emission records are now PostgreSQL-backed in production through `calculation-repository.ts`; the route executes the existing deterministic engine and then commits the calculation, gas rows, emission record, supersession updates, and activity status atomically. Reference GWP/factor reads used by production calculation and reference endpoints are also PostgreSQL-backed. V5 adds composite tenant foreign keys for activity/facility and activity/reporting-period relationships; V6 adds calculation snapshot columns, calculation/emission tenant composite foreign keys, uniqueness, and nonnegative checks. Evidence file bytes remain behind `storage.ts`; only metadata and links are persisted. Evidence metadata and an optional association are inserted in one transaction, and the route deletes the stored file if metadata/link persistence fails. Every production activity/evidence/calculation/emission query is tenant-qualified from `TenantContext`; client organization IDs are not accepted for authorization.

The development-only compatibility path is explicit and is not used when `NODE_ENV=production`.

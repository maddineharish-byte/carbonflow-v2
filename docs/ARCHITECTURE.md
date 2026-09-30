# CarbonFlow — System Architecture & Design

## 1. Architectural Overview

CarbonFlow is designed with enterprise-grade multi-tenancy, clean domain separation, deterministic calculation isolation, and an auditable event ledger.

> **Phase 10.5:** the Node/Express gateway is **DECOMMISSIONED**. The API
> layer below is the Java 21 / Spring Boot backend. Node/Express survives only
> in Git history and in the Phase 10 evidence documents.

```
┌────────────────────────────────────────────────────────────────────────┐
│                        React / TypeScript SPA UI                       │
│  - Executive Dashboard           - Activity Data & Bulk Ledger         │
│  - Boundary & Facility Config    - Versioned Factor Catalog            │
│  - Audit Workflow & Review Desk  - Evidence Vault & Hash Verifier      │
│  - Inventory Snapshots           - Target & Reduction Project Tracker  │
└────────────────────────────────────┬───────────────────────────────────┘
                                     │ REST / JSON (Bearer JWT)
┌────────────────────────────────────▼───────────────────────────────────┐
│                 Java 21 / Spring Boot Application Layer                │
│  - JWT Authentication (Access Token, Refresh Token Rotation)          │
│  - Method-Level Authorization (@PreAuthorize, 9 roles x 44 codes)    │
│  - Tenant Context Resolution (Validates User-to-Tenant Membership)    │
│  - Login Throttling, Input Validation, Unified API Envelope           │
│  - Security Headers, Fail-Closed CORS Allow-List                     │
└───────┬──────────────┬──────────────┬──────────────┬─────────────┬─────┘
        │              │              │              │             │
┌───────▼──────┐┌──────▼──────┐┌──────▼──────┐┌──────▼─────┐┌─────▼──────┐
│ Organization ││ Activity &  ││ Calculation ││ Audit &    ││ Evidence   │
│ & Boundary   ││ Data Request││ Engine      ││ Review     ││ Storage    │
│ Domain       ││ Domain      ││ (BigDecimal)││ State Mach.││ Service    │
└───────┬──────┘└──────┬──────┘└──────┬──────┘└──────┬─────┘└─────┬──────┘
        │              │              │              │            │
┌───────▼──────────────▼──────────────▼──────────────▼────────────▼──────┐
│                    PostgreSQL / Enterprise Relational Schema           │
│   - UUID Primary Keys        - Tenant Foreign Key Isolation            │
│   - Versioned Factor Tables  - Immutable Calculation Snapshots         │
│   - Audit Event Ledger       - Check Constraints & Index Tuning        │
│   - Flyway V1-V8 (applied automatically at startup)                    │
└────────────────────────────────────────────────────────────────────────┘
```

### Runtime Persistence Reality (TASK-004A)
- Production login, refresh, and logout routes now use a PostgreSQL-backed refresh repository as the single source of truth.
- V3 is applied to PostgreSQL 18.6 at `localhost:5432/carbonflow_dev`; V1 and V2 remain unchanged.
- ADR-006 semantic-to-UUID identity resolution and ADR-007 organization/role, family, replacement, row-locking, and family-revocation design are implemented in the repository.
- Activity and evidence metadata routes now use PostgreSQL repositories in production; the local storage adapter remains the boundary for evidence bytes. V5 enforces tenant-consistent facility and reporting-period references. Development fixture compatibility remains explicit and is not a production fallback.
- Calculation execution and emission-ledger routes now use `calculation-repository.ts` in production. The deterministic engine remains the formula authority; the repository resolves PostgreSQL reference factors/GWP values, freezes the snapshot, and transactionally persists calculation, gas results, emission records, supersession, and activity status. V6 adds snapshot columns and tenant-integrity constraints. Development fixture compatibility remains explicit and is not a production fallback.
- TASK-004A cleanup is complete: the legacy in-memory refresh-token implementation has been removed. Production refresh routes use PostgreSQL exclusively.

---

## 2. Layered Component Responsibilities

### 2.1 Identity & Authorization Layer
- **Token Manager**: Issues short-lived access JWTs (15 min) and cryptographically random, single-use refresh tokens stored as keyed hashes in PostgreSQL; successful refresh rotates and revokes the previous record transactionally.
- **HTTP Hardening**: Startup configuration validation, security headers, explicit CORS origins, bounded JSON parsing, rate limiting, and non-sensitive API error responses are applied by the server bootstrap.
- **Tenant Context Interceptor**: Derives the caller's authorized tenant from cryptographically signed tokens and verified organization memberships. Reject any impersonation attempts or unverified headers.
- **RBAC Engine**: Matches granular canonical permissions (`activity_data.submit`, `audits.approve`, `calculations.create`) against the role-permission matrix.

### 2.2 Domain Services
- **Organization & Boundary Service**: Manages legal entities, facilities, boundary rules (Operational Control, Financial Control, Equity Share).
- **Activity Data Service**: Handles data collection, unit conversion guards, and links to evidence records.
- **Calculation Engine Service**: An isolated mathematical pipeline executing deterministic decimal math, resolving effective factor versions and GWP tables, creating frozen calculation snapshots, and outputting active emission records. Production persistence is handled by `calculation-repository.ts`, which commits calculations, gas results, emission records, supersession, and activity status atomically in PostgreSQL.
- **Audit & Governance Service**: Enforces the 8-state audit machine (`DRAFT` to `LOCKED`), validates checklist criteria, and coordinates review findings and correction cycles.
- **Inventory Service**: Aggregates verified emissions into tamper-resistant snapshots per reporting period, maintaining distinct Scope 1, Scope 2 Location, and Scope 2 Market lines.
- **Evidence Storage Service**: Pluggable storage abstraction supporting local filesystem and Supabase S3-compatible cloud storage with SHA-256 integrity hashing.

---

## 3. Technology Stack Specification

| Component | Technology | Specification / Standard |
| :--- | :--- | :--- |
| **Frontend** | React 19 + TypeScript | Vite, Tailwind CSS v4, Lucide Icons, Recharts, Motion |
| **API Server (active, sole backend)** | Java 21 + Spring Boot 3 (Maven, `backend-java/`) | Spring Security JWT + RBAC (9 roles × 44 permissions), plain JDBC — no ORM (ADR-009), Flyway, Controller → Service → Repository |
| **API Server (DECOMMISSIONED Phase 10.5)** | ~~Node.js (TypeScript / Express)~~ | Removed from the repository. Retained in Git history at `4cc8f30` and in the Phase 10 evidence documents. Served the same REST/JSON contract (RFC 7519 JWT, RFC 6749 Refresh Tokens) that the Java backend now owns. |
| **Calculation Engine** | Deterministic Decimal (Java `BigDecimal`) | 8-decimal precision, ROUND_HALF_UP, in `GhgCalculationEngine` |
| **Relational Database** | PostgreSQL | Schema migrations with Flyway naming standard (`V1__...`, `V8__...`), applied at startup |
| **Storage** | Private filesystem evidence vault | Multi-part uploads, SHA-256 hashing, 25 MB file limit |

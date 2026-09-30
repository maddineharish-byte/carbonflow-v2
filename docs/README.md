# CarbonFlow

Enterprise Greenhouse Gas (GHG) Accounting, Audit Preparation, Evidence Management, Inventory, Analytics, Targets & Reduction Projects SaaS.

---

## 1. Product Capabilities
- **Multi-Tenant Hierarchy**: Organizations, Legal Entities, Facilities, Departments, and Scope Consolidation Boundaries.
- **Deterministic Calculation Engine**: Implements high-precision decimal math (Java `BigDecimal` equivalent) avoiding floating-point drift. Preserves immutable calculation snapshots.
- **Scope 1 & Scope 2 Dual Reporting**: Stationary combustion, mobile fleet, process emissions, fugitive gases, and side-by-side Location-Based vs Market-Based grid accounting.
- **10-State Governed Audit Machine**: `DRAFT` → `SUBMITTED` → `DATA_COLLECTION` → `VALIDATION` → `REVIEW` → `APPROVED` → `AUDIT_READY` → `LOCKED` (with `CORRECTION_REQUESTED` / `REJECTED` branches).
- **Evidence Vault**: Secure attachment of utility bills and metering data with SHA-256 integrity verification (≤ 25MB).
- **Inventory Snapshots & Restatements**: Frozen reporting periods with zero double-counting.
- **Targets & Reduction Projects**: Decarbonization goals with projected vs realized carbon savings.

---

## 2. Documentation Index
- [Product Requirements Document (PRD)](./PRD.md)
- [System Architecture](./ARCHITECTURE.md)
- [Database Schema & Indexes](./DATABASE.md)
- [RESTful API Contracts](./API.md)
- [Security & Tenant Isolation](./SECURITY.md)
- [Role-Based Access Control (RBAC)](./RBAC.md)
- [GHG Calculation Engine & Precision](./CALCULATIONS.md)
- [Audit & Assurance Workflow](./AUDIT_WORKFLOW.md)
- [UI/UX Information Architecture](./UI_UX.md)
- [Implementation Tasks Roadmap](./TASKS.md)
- [Quality Assurance & Test Plan](./TEST_PLAN.md)
- [Frontend Integration Guide](./FRONTEND.md)
- [Architectural Decision Records](./DECISIONS.md)
- [Execution & Operational Runbook](./EXECUTION.md)

---

## 3. Quickstart

Two processes. Start the API first (it applies Flyway migrations and listens on 8080):

```bash
cd backend-java
mvn spring-boot:run
```

Then start the frontend dev server in a second terminal:

```bash
npm install
npm run dev
```

- API: `http://localhost:8080` (health: `http://localhost:8080/api/v1/health`)
- Frontend: `http://localhost:5173`

The frontend calls the API through `VITE_JAVA_API_BASE_URL`. See
`docs/EXECUTION.md` for the full runbook.

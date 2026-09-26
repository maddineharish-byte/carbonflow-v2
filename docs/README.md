# CarbonFlow

Enterprise Greenhouse Gas (GHG) Accounting, Audit Preparation, Evidence Management, Inventory, Analytics, Targets & Reduction Projects SaaS.

---

## 1. Product Capabilities
- **Multi-Tenant Hierarchy**: Organizations, Legal Entities, Facilities, Departments, and Scope Consolidation Boundaries.
- **Deterministic Calculation Engine**: Implements high-precision decimal math (Java `BigDecimal` equivalent) avoiding floating-point drift. Preserves immutable calculation snapshots.
- **Scope 1 & Scope 2 Dual Reporting**: Stationary combustion, mobile fleet, process emissions, fugitive gases, and side-by-side Location-Based vs Market-Based grid accounting.
- **8-State Governed Audit Machine**: `DRAFT` ➔ `SUBMITTED` ➔ `DATA_COLLECTION` ➔ `VALIDATION` ➔ `REVIEW` ➔ `APPROVED` ➔ `AUDIT_READY` ➔ `LOCKED`.
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
- [Architectural Decision Records](./DECISIONS.md)
- [Execution & Operational Runbook](./EXECUTION.md)

---

## 3. Quickstart
```bash
npm install
npm run dev
```
Access the application at `http://localhost:3000`.

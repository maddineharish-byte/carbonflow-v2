# CarbonFlow — Product Requirements Document (PRD)

## 1. Product Overview
CarbonFlow is an enterprise SaaS platform for greenhouse gas (GHG) accounting, organizational boundary management, emission calculations, audit preparation, evidence management, inventory snapshots, and reduction projects.

### Disclaimer & Positioning
CarbonFlow is **accounting and assurance-preparation software**. It is NOT:
- A certification authority
- An assurance provider
- A regulator
- A legal compliance guarantee
- A replacement for independent assurance

CarbonFlow does not claim to certify emissions; it provides verifiable data, rigorous calculation lineage, and audit trails for independent assurance providers.

---

## 2. Product Lifecycle
```
COLLECT ──> CALCULATE ──> VERIFY ──> REPORT
```
1. **Collect**: Ingestion and validation of activity data from facilities and legal entities with attached auditable evidence.
2. **Calculate**: Deterministic decimal engine resolving versioned emission factors and GWP sets to produce reproducible carbon ledger entries.
3. **Verify**: Formal audit workflow, reviewer findings, checklist validation, correction cycles, and tamper-resistant period locking.
4. **Report**: Inventory snapshots, dual-reporting (Scope 2 location vs market), reduction project tracking, and exportable audit packages.

---

## 3. Key Capabilities & Functional Requirements

### 3.1 Organizational Hierarchy & Boundaries
- Multi-tenant architecture isolated by `Organization`.
- Multi-entity modeling: Legal Entities, Facilities, Departments.
- Organizational Boundaries: Operational Control, Financial Control, and Equity Share boundary definitions.
- Facility mapping to reporting periods and boundary configurations.

### 3.2 Reporting Periods & Governance
- Defined temporal periods (e.g., FY2024, Q1-2025).
- Start date, end date, status (`OPEN`, `UNDER_AUDIT`, `LOCKED`).
- Prevention of uncoordinated overlapping reporting periods.

### 3.3 Activity Data Ingestion
- Activity types across Scope 1 (Stationary Combustion, Mobile Combustion, Process Emissions, Fugitive Emissions) and Scope 2 (Purchased Electricity - Location and Market).
- Explicit unit enforcement (kWh, MWh, Therms, Litres, Gallons, m3, kg, t).
- Metadata tracking: source, timestamp, facility, submitter, and evidence link.

### 3.4 Emission Factors & GWP Reference Data
- Versioned factors preserving source, publication year, gas, scope, category, and geographical applicability.
- Version immutability: Updates create new versions and never silently mutate historical calculation snapshots.
- Reference GWP sets: IPCC AR4, AR5, and AR6 100-year time horizons.

### 3.5 Deterministic Calculation Engine
- Calculation pipeline:
  ```
  Activity Data ──> Unit Validation & Normalization ──> Factor Resolution ──> GWP Resolution ──> Raw Gas Emission ──> CO2e ──> Snapshot Persisted ──> Emission Record
  ```
- Strict decimal arithmetic (BigDecimal / Decimal.js) preventing IEEE-754 floating point drift.
- Immutable calculation snapshots storing full formula, inputs, factors, and GWP values.

### 3.6 Scope 2 Dual-Reporting
- Location-based accounting using regional grid emission factors.
- Market-based accounting using supplier-specific contractual instruments, RECs, or residual mix.
- **Strict Rule**: Location and Market figures are presented strictly side-by-side. They are never summed into a blended total.

### 3.7 Audit & Assurance Workflow
- Formal state machine:
  `DRAFT` ──> `SUBMITTED` ──> `DATA_COLLECTION` ──> `VALIDATION` ──> `REVIEW` ──> `APPROVED` ──> `AUDIT_READY` ──> `LOCKED`
- Correction loop: `REVIEW` ──> `CORRECTION_REQUESTED` ──> `DATA_COLLECTION`
- Rejection loop: `REVIEW` ──> `REJECTED` ──> `DATA_COLLECTION`
- Mandatory checklist validation before moving to `APPROVED` or `LOCKED`.
- Reviewer finding logs, timestamped comments, and sign-offs.

### 3.8 Evidence Management Vault
- Supported formats: PDF, CSV, XLSX, XLS, DOCX, PNG, JPG, JPEG (≤ 25 MB).
- Content-type validation and SHA-256 hash generation for integrity assurance.
- Storage abstraction decoupling local development from cloud production (Supabase Storage compatible).

### 3.9 Inventory Snapshots & Restatements
- Periodic inventory snapshots summarizing active emission records without double-counting.
- Superseded calculation tracking.
- Immutability guarantees once periods are marked `LOCKED`.

### 3.10 Carbon Targets & Reduction Projects
- Baseline and target year definition, absolute and intensity reduction goals.
- Reduction project management: baseline, anticipated vs realized tCO2e reduction, status, timelines.
- Real-time progress tracking against verified inventory data.

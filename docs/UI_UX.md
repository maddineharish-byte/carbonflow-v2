# CarbonFlow — UI/UX Information Architecture & Design System

## 1. Visual Design Philosophy & Anti-Slop Enforcement

- **Palette**: Sophisticated, high-contrast palette tailored for enterprise carbon accounting. Crisp slate grays (`slate-900`, `slate-800`, `slate-700`, `slate-50`), subtle emerald and teal accents for verified carbon metrics (`emerald-600`, `teal-700`), and warm amber for review findings (`amber-600`).
- **Typography**: Paired display typography (Plus Jakarta Sans) with tabular numbers and monospace traces (JetBrains Mono) for calculations and SHA-256 hashes.
- **Hierarchy**: Clean single-level surface architecture. Deeply nested cards, purple gradients, and floating neon drop shadows are strictly banned.
- **Micro-Interactions**: Predictable state badges, deterministic recalculation indicators, and progress meters.

---

## 2. Navigation Architecture

```
CARBONFLOW NAVIGATION
├── 1. Executive Dashboard (KPIs, Scope 1/2 Dual Cards, Period Status, Review Alerts)
├── 2. Activity Data & Ledger (Inputs, Units, Facilities, Calculate Trigger, Status)
├── 3. Calculation Studio & Trace (Lineage, Factor Resolution, GWP Matrix, Math Lineage)
├── 4. Audit & Assurance Desk (State Machine, Reviewer Findings, Mandatory Checklist)
├── 5. Evidence Vault (Upload, SHA-256 Checksum Inspector, Activity Association)
├── 6. Inventory Snapshots (Period Aggregates, Non-Double Counting Verification, Lock)
├── 7. Targets & Reduction Projects (Decarbonization Roadmap, Projected vs Actual)
├── 8. Factor Library (Versioned Reference Factors: eGRID, DEFRA, EPA, IEA, GWP Sets)
└── 9. Organization & Facilities (Entities, Facilities, Boundaries, Memberships)
```

---

## 3. Key Screen Layouts & User Workflows

### 3.1 Executive Dashboard
- **Period Selector**: Switch between reporting periods (e.g. FY2024, FY2025).
- **Dual-Reporting Metric Banner**:
  - Total Emissions (Location-Based): `Scope 1 + Scope 2 Location` ($tCO_2e$).
  - Total Emissions (Market-Based): `Scope 1 + Scope 2 Market` ($tCO_2e$).
- **Audit Health Indicator**: Displays current audit state, verified checklist item ratio, and open findings.
- **Scope 1 vs Scope 2 Breakdown**: Recharts bar and distribution cards showing category shares.

### 3.2 Activity Data & Calculation Desk
- **Data Table**: Displays scope, category, activity type, facility, raw quantity, unit, status, and linked evidence badge.
- **Calculation Simulator**: Allows testing or running calculations with real-time conversion breakdown and factor lineage preview.

### 3.3 Audit & Review Desk
- **State Progression Stepper**: Visualizes the 8 audit states and enables authorized transitions.
- **Checklist Inspector**: Interactive completion tracker with audit trail of who verified each requirement.
- **Reviewer Finding Panel**: Allows reviewers to create findings with severity (Low, Medium, High, Critical) and request corrections.

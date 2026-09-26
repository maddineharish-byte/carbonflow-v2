# CarbonFlow — Audit & Assurance Workflow State Machine

## 1. Governance State Machine

The carbon audit workflow governs the progression of activity data, emission calculations, evidence verification, and period closure.

```
       ┌────────────────┐
       │     DRAFT      │
       └───────┬────────┘
               │ (submit)
               ▼
       ┌────────────────┐
       │   SUBMITTED    │
       └───────┬────────┘
               │ (initiate data collection)
               ▼
 ┌───> ┌────────────────┐
 │     │DATA_COLLECTION │ <───────────────────────┐
 │     └───────┬────────┘                         │
 │             │ (submit all activity data)       │
 │             ▼                                  │
 │     ┌────────────────┐                         │
 │     │   VALIDATION   │                         │
 │     └───────┬────────┘                         │
 │             │ (pass validation checks)         │
 │             ▼                                  │
 │     ┌────────────────┐                         │
 │     │     REVIEW     │ ──(request correction)──┤
 │     └───────┬────────┘                         │
 │             │                                  │
 │             ├──(reject)────────────────────────┘
 │             ▼ (approve)
 │     ┌────────────────┐
 │     │    APPROVED    │
 │     └───────┬────────┘
 │             │ (checklist 100% verified)
 │             ▼
 │     ┌────────────────┐
 │     │  AUDIT_READY   │
 │     └───────┬────────┘
 │             │ (formal freeze & hash)
 │             ▼
 │     ┌────────────────┐
 │     │     LOCKED     │ (Immutable state)
 └─────┴────────────────┘
```

---

## 2. Transition Guard Matrix & Validation Rules

| Current State | Target State | Permitted Roles | Mandatory Prerequisites |
| :--- | :--- | :--- | :--- |
| `DRAFT` | `SUBMITTED` | `COMPANY_ADMIN`, `SUSTAINABILITY_MANAGER` | Organizational boundaries and reporting period dates defined. |
| `SUBMITTED` | `DATA_COLLECTION` | `SUSTAINABILITY_MANAGER` | Data collection requests generated for required facilities. |
| `DATA_COLLECTION` | `VALIDATION` | `CARBON_ACCOUNTANT`, `SUSTAINABILITY_MANAGER` | 100% of required activity data submitted; evidence attached to high-materiality entries. |
| `VALIDATION` | `REVIEW` | `CARBON_ACCOUNTANT` | Deterministic calculations run on all submitted activity data without calculation errors. |
| `REVIEW` | `CORRECTION_REQUESTED`| `REVIEWER`, `ASSURANCE_PROVIDER` | Formal finding logged stating reason for correction. |
| `REVIEW` | `REJECTED` | `REVIEWER` | Reason logged; reverts audit to `DATA_COLLECTION`. |
| `REVIEW` | `APPROVED` | `REVIEWER`, `SUSTAINABILITY_MANAGER` | Zero unresolved high-severity findings; all mandatory checklist items satisfied. |
| `APPROVED` | `AUDIT_READY` | `SUSTAINABILITY_MANAGER`, `COMPANY_ADMIN` | Final inventory snapshot generated and verified against ledger. |
| `AUDIT_READY` | `LOCKED` | `COMPANY_ADMIN` | Final sign-off logged; generates cryptographic SHA-256 inventory state hash. |

---

## 3. Mandatory Audit Checklist Items

Every audit instance initializes the following canonical checklist items:

1. `CHK-BND-01`: **Organizational Boundary Confirmation** — Boundary consolidation approach (Operational vs Financial) verified.
2. `CHK-FAC-02`: **Facility Completeness** — All active physical facilities accounted for in reporting period.
3. `CHK-DAT-03`: **Activity Data Ingestion** — All Scope 1 and Scope 2 energy utility bills entered without date gaps.
4. `CHK-EVD-04`: **Primary Evidence Reconciliation** — Materiality threshold (≥ 5% of gross emissions) has verified primary utility bills attached.
5. `CHK-FAC-05`: **Emission Factor Integrity** — Factors derived from authoritative versioned sources (eGRID, DEFRA, EPA, IEA).
6. `CHK-GWP-06`: **GWP Reference Consistency** — GWP set aligned across all emission calculations.
7. `CHK-S2D-07`: **Scope 2 Dual-Reporting Verification** — Separate location and market methodologies verified side-by-side.
8. `CHK-FIN-08`: **Finding Resolution** — All reviewer findings and correction requests marked `RESOLVED`.

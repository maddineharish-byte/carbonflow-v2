# CarbonFlow — Documentation Index

Enterprise greenhouse-gas (GHG) accounting, audit preparation, evidence
management, inventory, analytics, targets and reduction projects.

> **Start with [`HANDOVER.md`](./HANDOVER.md)** — the current, authoritative
> technical and client handover, including what has *not* been verified.
> If something is broken, start with [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md).
> To understand how the project got here, read
> [`PHASE-HISTORY.md`](./PHASE-HISTORY.md).

---

## 1. Product capabilities

- **Multi-tenant hierarchy** — organizations, legal entities, facilities,
  departments, scope consolidation boundaries.
- **Deterministic calculation engine** — high-precision decimal math (`BigDecimal`
  only, no floating point) with immutable calculation snapshots.
- **Scope 1 & Scope 2 dual reporting** — stationary combustion, mobile fleet,
  process emissions, fugitive gases, and side-by-side location-based vs
  market-based grid accounting that is never summed.
- **Ten-state governed audit machine** — `DRAFT` → `SUBMITTED` →
  `DATA_COLLECTION` → `VALIDATION` → `REVIEW` → `APPROVED` → `AUDIT_READY` →
  `LOCKED`, with `CORRECTION_REQUESTED` and `REJECTED` branches.
- **Evidence vault** — utility bills and metering data with size, MIME,
  magic-byte and SHA-256 validation (≤ 25 MB) and tenant-owned links.
- **Inventory snapshots & restatements** — frozen reporting periods with zero
  double-counting.
- **Targets & reduction projects** — decarbonization goals with projected vs
  realized savings.

---

## 2. Documentation index

### Start here

| Document | Purpose |
| --- | --- |
| [`HANDOVER.md`](./HANDOVER.md) | **Current authoritative handover.** What CarbonFlow is, how to run it, limitations, open findings |
| [`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md) | Symptoms, causes and actions for operational problems |
| [`PHASE-HISTORY.md`](./PHASE-HISTORY.md) | Project and phase history; which decisions were deliberate |
| [`../README.md`](../README.md) | Repository entry point |

### Product and design

| Document | Purpose |
| --- | --- |
| [`PRD.md`](./PRD.md) | Product requirements |
| [`ARCHITECTURE.md`](./ARCHITECTURE.md) | System architecture |
| [`PERSISTENCE-ARCHITECTURE.md`](./PERSISTENCE-ARCHITECTURE.md) | Persistence design |
| [`DECISIONS.md`](./DECISIONS.md) | Architecture decision records (ADR-001–021) |
| [`UI_UX.md`](./UI_UX.md) | UI/UX information architecture |
| [`TASKS.md`](./TASKS.md) | Implementation roadmap (historical task log) |

### Technical reference

| Document | Purpose |
| --- | --- |
| [`API.md`](./API.md) | REST API contracts |
| [`DATABASE.md`](./DATABASE.md) | Schema, indexes, tenant integrity constraints |
| [`RBAC.md`](./RBAC.md) | Role and permission matrix |
| [`CALCULATIONS.md`](./CALCULATIONS.md) | Calculation specification and precision |
| [`AUDIT_WORKFLOW.md`](./AUDIT_WORKFLOW.md) | Audit state machine and governance |
| [`../FRONTEND.md`](../FRONTEND.md) | Frontend integration guide |
| [`../backend-java/README.md`](../backend-java/README.md) | Backend module reference |

### Security

| Document | Purpose |
| --- | --- |
| [`SECURITY.md`](./SECURITY.md) | Security model |
| [`SECURITY-THREAT-MODEL.md`](./SECURITY-THREAT-MODEL.md) | Threat model |
| [`DEPLOYMENT-SECURITY.md`](./DEPLOYMENT-SECURITY.md) | Deployment configuration, TLS, CORS |
| [`SECRETS.md`](./SECRETS.md) | Secret variable inventory |

### Operations and recovery

| Document | Purpose |
| --- | --- |
| [`EXECUTION.md`](./EXECUTION.md) | How to run and operate the stack |
| [`DEPLOYMENT-READINESS.md`](./DEPLOYMENT-READINESS.md) | Deployment readiness assessment, environment separation, deployment and rollback checklists |
| [`BACKUP-RECOVERY.md`](./BACKUP-RECOVERY.md) | Backup and recovery procedures, plus the operational runbook |
| [`RECOVERY-CONTROLS-DESIGN.md`](./RECOVERY-CONTROLS-DESIGN.md) | Recovery control design, rationale and measured results |
| [`OPERATIONAL-RECOVERY-REQUIREMENTS.md`](./OPERATIONAL-RECOVERY-REQUIREMENTS.md) | Approved RTO/RPO, backup frequency, retention, ownership, escalation |
| [`OPERATIONAL-VALIDATION.md`](./OPERATIONAL-VALIDATION.md) | Operational validation and recovery-drill evidence |

### Quality

| Document | Purpose |
| --- | --- |
| [`TEST_PLAN.md`](./TEST_PLAN.md) | Quality assurance and test plan. *Historical task log; not valid UTF-8 (pre-existing)* |
| [`UI-AUDIT.md`](./UI-AUDIT.md) | UI audit |
| [`PHASE10.11-ACCESSIBILITY.md`](./PHASE10.11-ACCESSIBILITY.md) | Accessibility hardening |

### Historical — read as a record, not as current state

| Document | Phase | Note |
| --- | --- | --- |
| [`PHASE9-BASELINE.md`](./PHASE9-BASELINE.md) | 9 | Baseline before Java hardening |
| [`PHASE10-NODE-OFF-TEST.md`](./PHASE10-NODE-OFF-TEST.md) | 10.4 | Node-off rehearsal |
| [`PHASE10-FINDINGS-RESOLUTION.md`](./PHASE10-FINDINGS-RESOLUTION.md) | 10.4.1 | Findings resolution |
| [`PHASE10-CUTOVER-GAP-REPORT.md`](./PHASE10-CUTOVER-GAP-REPORT.md) | 10 | Reconstructed pre-cutover gaps |
| [`PHASE10-FRONTEND-CUTOVER.md`](./PHASE10-FRONTEND-CUTOVER.md) | 10 | Reconstructed frontend cutover |
| [`PHASE10-NODE-DECOMMISSION-PLAN.md`](./PHASE10-NODE-DECOMMISSION-PLAN.md) | 10.5 | Decommission plan and gate |
| [`PHASE10.5-NODE-DECOMMISSION.md`](./PHASE10.5-NODE-DECOMMISSION.md) | 10.5 | Decommission record |
| [`PHASE10.5-CUTOVER-REPORT.md`](./PHASE10.5-CUTOVER-REPORT.md) | 10.5 | Final cutover report |
| [`PHASE10.6-PRODUCTION-READINESS.md`](./PHASE10.6-PRODUCTION-READINESS.md) | 10.6 | Readiness validation |
| [`PHASE10.6.1-BLOCKER-RESOLUTION.md`](./PHASE10.6.1-BLOCKER-RESOLUTION.md) | 10.6.1 | TLS blocker resolution |
| [`FINAL-RELEASE-REPORT.md`](./FINAL-RELEASE-REPORT.md) | 10.6.2 | Frozen release record (`d42af8b`) |
| [`RELEASE-HANDOVER.md`](./RELEASE-HANDOVER.md) | 10.6.2 | Frozen handover — **superseded by `HANDOVER.md`** |

These documents describe Node/Express, or a codebase from before the recovery
controls of Phases 10.7–10.9, because that is what existed when they were
written. They are **retained as evidence and are not rewritten**. Where they
disagree with `HANDOVER.md`, the handover is current.

---

## 3. Quickstart

Two processes. Start the API first (it applies Flyway migrations and listens on
8080):

```bash
cd backend-java
mvn spring-boot:run
```

Then the frontend dev server in a second terminal:

```bash
npm install
VITE_JAVA_API_BASE_URL=http://localhost:8080 npm run dev
```

- API: `http://localhost:8080` (health: `http://localhost:8080/api/v1/health`)
- Frontend: `http://localhost:5173`

The frontend calls the API through `VITE_JAVA_API_BASE_URL`. The Java backend
**refuses to start** unless `CARBONFLOW_JWT_SECRET` and
`CARBONFLOW_REFRESH_TOKEN_SECRET` are each at least 32 random bytes.

Full runbook: [`EXECUTION.md`](./EXECUTION.md). Troubleshooting:
[`TROUBLESHOOTING.md`](./TROUBLESHOOTING.md).
# CarbonFlow Operational Recovery Requirements

> ## STATUS: APPROVED — 2026-10-01
>
> **CarbonFlow now has approved recovery requirements.** RTO, RPO, backup
> frequency, retention, operational ownership, on-call responsibility,
> escalation, monitoring and restore-drill frequency were decided by the named
> business owner on 2026-10-01 (see §10).
>
> **Approving a requirement is not implementing it, and implementing it is not
> proving it.** Every technical control required by §2 is currently
> **NOT IMPLEMENTED**, and no compliance test has been performed against the
> approved values. Sections §2, §11 and §12 keep those three things separate.
>
> **These are project-level requirements for the current CarbonFlow stage. They
> are not contractual SLA commitments** unless separately approved.
>
> Approval recorded 2026-10-01 against the frozen release candidate
> (`d42af8b`). Release state remains `RELEASE CANDIDATE — FROZEN`.

---

## ⚠ READ THIS BEFORE QUOTING ANY NUMBER

Recovery capability was **demonstrated** on 2026-09-30. Recovery requirements
were **approved** on 2026-10-01. Compliance is **NOT YET TESTED**. These are
three different things, and merging them is the specific error this document
exists to prevent.

### The three concepts that must never be merged

| # | Concept | Meaning | Current state |
| --- | --- | --- | --- |
| 1 | **Observed capability** | What a recovery drill actually demonstrated on a local synthetic environment | **DEMONSTRATED** — see §5 |
| 2 | **Approved requirement** | What the business formally requires | **APPROVED** 2026-10-01 — see §2 |
| 3 | **Compliance result** | Whether measurement satisfies the approved requirement | **NOT YET TESTED** — no validation performed |

### Prohibited statements

The following remain **false** until an actual compliance test is performed and
recorded against the approved values:

```text
✗ RTO = 66.4 seconds
✗ RTO = 177.9 seconds
✗ RPO = 245.8 seconds
✗ RTO MET
✗ RTO MISSED
✗ RPO MET
✗ RPO MISSED
✗ RTO COMPLIANT
✗ RPO COMPLIANT
✗ CarbonFlow has an SLA of ...
✗ CarbonFlow meets its recovery objectives
```

> The approved RTO and RPO are **4 hours** and **1 hour** respectively. Neither
> number came from a drill measurement. The drill measurements are not, and must
> never be restated as, the approved targets.

### Permitted statements

```text
✓ Approved RTO = 4 hours   (business-approved 2026-10-01)
✓ Approved RPO = 1 hour    (business-approved 2026-10-01)
✓ Technical compliance validation: NOT YET TESTED

✓ Observed recovery time = 66.4 s (failure → health UP)
  Environment = local developer workstation
  Dataset     = synthetic, 115,047-byte database, 1 evidence file (154 bytes)
  Network     = loopback only; no failover, no HA, no replication
  Date        = 2026-09-30
  NOTE: an observed drill measurement. NOT an approved service objective.

✓ Recovery capability has been demonstrated.
✓ Recovery requirements have been approved.
✓ Required technical controls are NOT IMPLEMENTED.
```

> **A future engineer or agent reading only a drill result must not be able to
> infer a commitment.** If a future change makes the drill figures appear
> adjacent to any target-shaped field, this document has failed.

### The principle

> **The business defines acceptable recovery.**
> **Engineering implements the approved requirements.**
> **Testing determines whether the implementation actually satisfies them.**
>
> RTO, RPO, retention, backup frequency, ownership and escalation are
> business/operational decisions requiring explicit approval. That approval now
> exists (§10). Implementation and validation do not — they belong to later
> phases.

---

## 1. Approval status summary

| Question | Answer |
| --- | --- |
| Is there an approved RTO? | **YES — 4 hours** (approved 2026-10-01) |
| Is there an approved RPO? | **YES — 1 hour** (approved 2026-10-01) |
| Is there a backup schedule? | **APPROVED — at least once every hour.** Not yet **IMPLEMENTED**; backups remain manual and on demand |
| Is there a retention policy? | **APPROVED — 30 days**, database and evidence vault. Not yet **IMPLEMENTED** |
| Does evidence have its own requirement? | **DECIDED — SAME REQUIREMENT** as the database |
| Is there a named operational owner? | **YES — CarbonFlow Project Owner**, CarbonFlow Operations |
| Is there an on-call / escalation path? | **YES — see §2** |
| Are required controls implemented? | **NO — all NOT IMPLEMENTED.** See §6 and §12 |
| Has compliance been validated? | **NO — NOT YET TESTED** |

---

## 2. REQUIRED APPROVAL DECISION TABLE

> Populated 2026-10-01 from explicit decisions supplied by the named business
> owner. **Approved Value** is the committed requirement. **Implementation** and
> **Validation** are tracked separately and are deliberately *not* implied by an
> approved value.

| Decision | Current State | Business Decision | Approved Value | Approver | Approval Date | Implementation | Validation |
| --- | --- | --- | --- | --- | --- | --- | --- |
| RTO | NOT DEFINED | **APPROVED** | **4 hours** | CarbonFlow Project Owner | 2026-10-01 | Procedure documented (`docs/BACKUP-RECOVERY.md` §4) | **NOT YET TESTED** |
| RPO | NOT DEFINED | **APPROVED** | **1 hour** | CarbonFlow Project Owner | 2026-10-01 | **NOT IMPLEMENTED** — no scheduler | **NOT YET TESTED** |
| Backup frequency | NOT DEFINED | **APPROVED** | **At least once every hour** — database + evidence vault | CarbonFlow Project Owner | 2026-10-01 | **NOT IMPLEMENTED** — manual, on demand | **NOT YET TESTED** |
| Retention period | NOT DEFINED | **APPROVED** | **30 days** — database backups + evidence-vault backups | CarbonFlow Project Owner | 2026-10-01 | **NOT IMPLEMENTED** | **NOT YET TESTED** |
| Operational owner | NOT DEFINED | **APPROVED** | **CarbonFlow Project Owner** (CarbonFlow Operations) | CarbonFlow Project Owner | 2026-10-01 | n/a — organisational | n/a |
| On-call responsibility | NOT DEFINED | **APPROVED** | **CarbonFlow Operations** — business hours, critical-incident escalation out of hours | CarbonFlow Project Owner | 2026-10-01 | **NOT IDENTIFIED** — no rotation or contact path recorded | n/a |
| Escalation path | NOT DEFINED | **APPROVED** | **CarbonFlow Operations → Project Owner → designated technical/hosting administrator** | CarbonFlow Project Owner | 2026-10-01 | **NOT IDENTIFIED** — no named individual at tier 3 | n/a |
| Backup monitoring | NOT DEFINED | **APPROVED** | **Automated** monitoring of execution, freshness, failures, vault status — **alert** on failure, staleness beyond the recovery window, or unverifiable backup | CarbonFlow Project Owner | 2026-10-01 | **NOT IMPLEMENTED** (F-09) | **NOT YET TESTED** |
| Restore-drill frequency | NOT DEFINED | **APPROVED** | **Quarterly**, evidence per §2.1 | CarbonFlow Project Owner | 2026-10-01 | n/a — organisational | **1 drill performed 2026-09-30**; cadence **NOT SCHEDULED** |

### 2.1 Approved definitions, scope and conditions

Recorded verbatim in substance from the approved decision so the requirements
are testable later.

#### RTO — 4 hours

- **Definition.** Maximum acceptable time from a **qualifying failure** until
  CarbonFlow is restored to a usable state.
- **Qualifying failure.** A failure requiring restoration of CarbonFlow
  infrastructure, database or evidence-vault state.
- **Explicitly NOT qualifying failures:** individual failed uploads, individual
  user errors, and ordinary application-level incidents.
- **"Usable state" means all of the following:**
  1. authentication works;
  2. the application is operational;
  3. the database is available;
  4. the evidence vault is available;
  5. core existing data can be accessed.

> The RTO clock starts at the qualifying failure. Because detection is
> currently `NOT IMPLEMENTED`, the point at which the failure is *known* is
> undefined — see §12 GAP-01.

#### RPO — 1 hour

- **Definition.** Maximum acceptable loss of **committed** CarbonFlow database
  data and evidence-vault data following a qualifying failure.
- Measured as the interval between the last recoverable backup and the
  qualifying failure.

#### Backup frequency — at least once every hour

- **Type:** database backup **plus** evidence-vault backup.
- **Coverage:** both stores.
- **Consistency condition.** The database and evidence vault must be
  recoverable to a **consistent recovery boundary**.

#### Retention — 30 days

- **Scope:** both database backups and evidence-vault backups.
- Note: `docs/BACKUP-RECOVERY.md` §3.1 mentions a possible 7-year baseline for
  GHG inventory data. **The approved retention is 30 days.** The 7-year figure
  remains an open regulatory question for a later decision and is **not** part
  of the current approval — see §13.

#### Operational ownership, on-call, escalation

- **Primary operational owner:** CarbonFlow Project Owner (CarbonFlow
  Operations).
- **On-call:** CarbonFlow Operations — business hours initially, with
  critical-incident escalation outside business hours.
- **Escalation path:** CarbonFlow Operations → Project Owner → designated
  technical/hosting administrator.
- **Escalation triggers:** a qualifying production failure; a failed recovery
  attempt; a missed required backup; suspected backup corruption; inability to
  meet the approved RTO/RPO.

#### Backup monitoring — required

- **Monitoring:** backup execution, backup freshness, backup failures, and
  evidence-vault backup status.
- **Alerts** when: a required backup fails; a backup becomes stale beyond the
  approved recovery window; a backup cannot be verified.
- **Responsible team:** CarbonFlow Operations.

#### Restore drill — quarterly

- **Frequency:** quarterly.
- **Responsible owner:** CarbonFlow Operations.
- **Required evidence:** database restore result; evidence-vault restore
  result; checksum/integrity verification; application retrieval verification;
  tenant-isolation verification; observed recovery duration; documented
  findings.

#### Evidence vault requirement

**SAME REQUIREMENT.** The evidence vault carries the **same approved RTO and
RPO** as the database. The vault is part of the recoverable CarbonFlow data set.

**Consistency requirement.** Database metadata and evidence-vault content must
be restored to a **consistent recovery boundary**. A database-only restore
without corresponding evidence-vault recovery **does not constitute a complete
recovery**. This is consistent with `docs/BACKUP-RECOVERY.md` §1.2 and §2.5:
restore the database first, then the vault from the **same or later**
timestamp; never restore a database newer than the vault.

#### Commitment classification

The approved values are **project-level requirements for the current
CarbonFlow stage**. They **must not be represented as contractual SLA
commitments** unless separately approved.

---

## 3. Decision rules

These rules constrained how each decision was made. A value was never copied
from an example, inferred from a measurement, or inherited by accident.

### 3.1 RTO

- Approved: **4 hours**. Supplied explicitly by the business owner.
- The measured 66.4 s and 177.9 s figures were **not** transcribed into the
  Approved Value field. They remain observations in §5.

### 3.2 RPO

- Approved: **1 hour**. Supplied explicitly by the business owner.
- The measured 245.8 s observed recovery point was **not** used as the RPO. It
  was, and remains, an artifact of operator timing rather than a capability
  statement.

### 3.3 Backup frequency

- Approved: **at least once every hour**, database plus evidence vault.
- Consistent with the approved 1-hour RPO. No interval was inferred silently —
  the owner stated it explicitly.

### 3.4 Retention

- Approved: **30 days**, both database and evidence-vault backups.
- The 7-year GHG baseline mentioned in `docs/BACKUP-RECOVERY.md` §3.1 was
  **not** adopted. See §13.

### 3.5 Operational owner

- Approved: **CarbonFlow Project Owner**, role/team CarbonFlow Operations.
- Assigned by role, as the organisation formally assigns responsibility. No
  individual was nominated by engineering.

### 3.6 On-call / escalation

- Approved as recorded in §2.1. Names, contact details and a rotation were **not**
  invented; see §12 GAP-05.

---

## 4. Questions presented to the decision-maker — RESOLVED

> Retained for audit. Each question was put to the business owner without any
> option pre-selected, and each is now answered.

| # | Question | Resolution |
| --- | --- | --- |
| Q1 | If CarbonFlow stops working, how long can users wait? | **4 hours** |
| Q2 | If CarbonFlow loses recently entered data, how much loss is acceptable? | **1 hour** |
| Q3 | How often should backups run? | **At least once every hour**, both stores |
| Q4 | How long should backups be retained? | **30 days**, both stores |
| Q5 | Does evidence have the same recovery requirement as the database? | **SAME REQUIREMENT** — the vault carries the same RTO and RPO |

Follow-up questions raised at approval, and their answers:

| # | Question | Resolution |
| --- | --- | --- |
| Q6 | What counts as a qualifying failure? | A failure requiring restoration of infrastructure/database/evidence-vault state. Not individual upload failures, user errors, or ordinary application incidents |
| Q7 | What does "usable state" mean? | Authentication works, application operational, database available, evidence vault available, core existing data accessible |
| Q8 | Is the 4-hour RTO / 1-hour RPO a contractual SLA? | **No.** Project-level requirement for the current stage; not an SLA unless separately approved |

---

## 5. Observed capability (measurements, not commitments)

Recorded 2026-09-30. **These figures did not produce the approved RTO/RPO** and
must not be cited as the source of them.

### Conditions — required whenever these figures are quoted

| Condition | Value |
| --- | --- |
| Environment | Local developer workstation (Windows 11 Pro) |
| Database | PostgreSQL 18.6 on `localhost` |
| Application | Frozen jar `carbonflow-backend-1.0.0-PRO.jar`, local single instance |
| Network | **Loopback only** — no network transfer, no failover, no HA |
| Dataset | **Synthetic**: 2 activities, 2 calculations, 2 emission records, 1 audit, 1 evidence file |
| Database size | **115,047 bytes** |
| Vault size | **1 file, 154 bytes** |
| Backup type | `pg_dump` logical, **manual, on demand** |
| Hardening | **None** — `DB_SSLMODE=prefer`, no encryption at rest, no monitoring |
| Date measured | 2026-09-30 |

### Observed results

| Measure | Value |
| --- | --- |
| Observed recovery time (failure → health `UP`) | **66.4 s** |
| Observed recovery time (failure → application verified usable) | **177.9 s** |
| — detection-to-restore-start gap | 36.1 s |
| — `createdb` | 1.42 s |
| — `pg_restore` | 2.04 s |
| — evidence vault restore | 0.06 s |
| — application restart | 22.9 s |
| Observed recovery point (failure − last backup) | **245.8 s (4 min 6 s)** |

Data recovery was 3.5 s of a 66.4 s total; application startup alone was 22.9 s.
The headline figure is dominated by JVM startup on a warm machine, not by
recovering data.

### What these figures do not mean

- They do **not** mean CarbonFlow meets its approved RTO or RPO. No compliance
  test has been performed.
- They do **not** mean recovery would take 3 minutes in production. Restoring
  115 KB is not a measurement of restoring a real dataset; a dataset 10,000×
  larger would invert the relative weight of JVM startup, `pg_restore`, and any
  offsite network transfer.
- They say **nothing about detection**. The 36 s gap was the engineer deciding
  to restore. Real detection depends on monitoring that is `NOT IMPLEMENTED`
  (F-09), so an undetected outage could run for hours — and the approved RTO
  clock starts at the failure, not at detection.
- They say **nothing about failover**. No standby, no replica, no HA. The drill
  assumed the same host returns.
- The **4-minute observed recovery point is an artifact of the engineer's own
  timing** — the interval between the backup they chose to take and the failure
  they then caused. With no scheduler, the real recovery point today is
  *whatever an operator happens to do*, which is unbounded and therefore does
  **not** currently satisfy the approved 1-hour RPO.

### What *was* demonstrated

The documented procedure in `docs/BACKUP-RECOVERY.md` was executed end to end
against a real outage: database and evidence vault both destroyed, both
restored, application restarted, login and data access re-verified, and an
evidence file recovered with a SHA-256 identical to the original at four
independent points. Full evidence in `docs/OPERATIONAL-VALIDATION.md`.

**Recovery capability: DEMONSTRATED (2026-09-30).**
**Recovery requirements: APPROVED (2026-10-01).**
**Compliance against those requirements: NOT YET TESTED.**

---

## 6. Capability that must exist before a target could be met

**None of the following is implemented.** These are the build commitments the
approved requirements create. **No work has been started**, and none is
authorised by this document.

| Capability | State | Required by approved |
| --- | --- | --- |
| Scheduled/automated hourly backup job (DB + vault) | **NOT IMPLEMENTED** | Backup frequency "at least once every hour"; RPO 1 hour |
| Backup retention enforcement (30 days) | **NOT IMPLEMENTED** | Retention 30 days |
| Backup monitoring / alerting | **NOT IMPLEMENTED** (F-09) | Monitoring requirement; prerequisite for the RTO clock |
| Backup verification automation | **NOT IMPLEMENTED** | Alert on a backup that "cannot be verified" |
| Evidence-vault backup on a consistent boundary with the database | **NOT IMPLEMENTED** | Consistent recovery boundary; vault same-requirement decision |
| Point-in-time recovery / WAL archiving | **NOT IMPLEMENTED** (depends on PostgreSQL config outside this repo) | Not strictly required by an hourly RPO; see §13 |
| Offsite / cross-region replication | **NOT IMPLEMENTED** | Surviving loss of the primary host or region |
| Encryption at rest for backups | **NOT IMPLEMENTED** (`docs/BACKUP-RECOVERY.md` §3.2 advisory only) | Handling customer documents in backup storage |
| Named on-call rotation and tier-3 escalation contact | **NOT IDENTIFIED** | On-call and escalation requirements |

> **Backup monitoring is a prerequisite for meeting the approved RTO, not a
> nice-to-have.** Without alerting, a missed backup is discovered only when a
> restore is needed, and the start of the RTO clock is unknown.

---

## 7. Business Approval Checklist — COMPLETE

Decided by the CarbonFlow Project Owner, 2026-10-01.

- [x] **RTO** — 4 hours
- [x] **RPO** — 1 hour
- [x] **Backup frequency** — at least once every hour, database + evidence vault
- [x] **Retention period** — 30 days, database backups *and* evidence vault copies
- [x] **Operational owner** — CarbonFlow Project Owner (CarbonFlow Operations)
- [x] **On-call responsibility** — CarbonFlow Operations; business hours with critical-incident escalation outside business hours
- [x] **Escalation path** — Operations → Project Owner → designated technical/hosting administrator
- [x] **Notification mechanism** — automated monitoring with alerts on failure, staleness beyond the recovery window, or unverifiable backup
- [x] **Backup monitoring requirement** — CarbonFlow Operations
- [x] **Restore-drill frequency** — quarterly, with the evidence listed in §2.1
- [x] **Evidence recovery requirement** — SAME REQUIREMENT as the database

---

## 8. REQUIREMENTS MATRIX

**Approved requirement ≠ implemented control. Implemented control ≠ validated
compliance.** The last two columns are independent of the approved value.

| Requirement | Approved Value | Owner | Status | Implementation | Validation |
| --- | --- | --- | --- | --- | --- |
| RTO | 4 hours | CarbonFlow Operations | **APPROVED** | Procedure documented; no automation | **NOT YET TESTED** |
| RPO | 1 hour | CarbonFlow Operations | **APPROVED** | **NOT IMPLEMENTED** | **NOT YET TESTED** |
| Backup frequency | ≥ once every hour, DB + vault | CarbonFlow Operations | **APPROVED** | **NOT IMPLEMENTED** | **NOT YET TESTED** |
| Retention | 30 days, DB + vault | CarbonFlow Operations | **APPROVED** | **NOT IMPLEMENTED** | **NOT YET TESTED** |
| Operational owner | CarbonFlow Project Owner | CarbonFlow Operations | **APPROVED** | n/a | n/a |
| On-call | CarbonFlow Operations, business hours + critical escalation | CarbonFlow Operations | **APPROVED** | **NOT IDENTIFIED** | n/a |
| Escalation | Ops → Project Owner → technical/hosting admin | CarbonFlow Operations | **APPROVED** | **NOT IDENTIFIED** | n/a |
| Backup monitoring | Automated execution/freshness/failure/vault monitoring with alerts | CarbonFlow Operations | **APPROVED** | **NOT IMPLEMENTED** | **NOT YET TESTED** |
| Restore drill | Quarterly, with defined evidence set | CarbonFlow Operations | **APPROVED** | 1 drill performed 2026-09-30; **NOT SCHEDULED** | Procedure **DEMONSTRATED**; RTO/RPO compliance **NOT YET TESTED** |

---

## 9. Consistency check of the approved requirements

Checked 2026-10-01 against each other and against the documented recovery model.

### 9.1 RPO vs backup frequency — CONSISTENT

```text
Approved RPO            = 1 hour
Approved backup freq    = at least once every hour
```

An hourly-or-better backup interval can satisfy a 1-hour RPO. No conflict.

> **Observation, not a change:** the interval exactly consumes the RPO window.
> With backups at the maximum permitted interval, worst-case loss approaches 1
> hour, leaving no margin. Tightening either value is a **business** decision and
> has not been made.

### 9.2 Retention vs backup frequency — CONSISTENT

30 days at hourly frequency implies roughly 720 database backups plus 720 vault
snapshots. Storage volume, cost and the encryption requirement in
`docs/BACKUP-RECOVERY.md` §3.2 are consequences to be planned, not contradictions.

### 9.3 RTO vs RPO and backup frequency — CONSISTENT

Restoring from an hourly backup is compatible with a 4-hour restore window; the
drill restored a full database and vault well inside that window.

### 9.4 RTO vs observed recovery — REQUIREMENT NOT DEMONSTRATED

```text
Approved RTO                     = 4 hours
Observed recovery (usable)       = 177.9 s
```

The observed time is **shorter** than the approved RTO, so the drill does not
contradict the target. It **does not demonstrate it**, because:

- the drill ran on a local synthetic 115 KB dataset over loopback, with no HA,
  no failover and no network transfer;
- the 36 s detection-to-restore-start gap was an engineer manually deciding to
  restore, and detection is `NOT IMPLEMENTED` in the approved world;
- there is no measurement at production data volumes, on production
  infrastructure, or across the two-store consistent-boundary restore.

**Status: REQUIREMENT NOT DEMONSTRATED.** The approved 4-hour target is
unchanged. See §12 GAP-01, GAP-02.

### 9.5 RPO vs observed recovery point — NOT DEMONSTRATED

```text
Approved RPO                     = 1 hour
Observed recovery point          = 245.8 s
```

The drill's recovery point fell inside the approved RPO, and the backup boundary
was verified exact — data written before the backup survived, data written after
it was correctly lost. That demonstrates the **backup boundary is exact**.

It does **not** demonstrate the RPO, because the 245.8 s interval was the
engineer's own timing, not a schedule. With no scheduler, the real recovery point
today is **unbounded**.

**Status: REQUIREMENT NOT DEMONSTRATED.** The approved 1-hour target is
unchanged. See §12 GAP-03.

### 9.6 Evidence vault same-requirement decision — CONSISTENT

Applying the same RTO/RPO to the vault is coherent with
`docs/BACKUP-RECOVERY.md` §1.2 and §2.5, which already require both stores to be
recovered together and require the vault to be restored from the same or later
timestamp than the database. The approved "consistent recovery boundary"
condition matches the documented §2.5 ordering rule.

### 9.7 Approval completeness gate — COMPLETE

```text
RTO                  → supplied  (4 hours)
RPO                  → supplied  (1 hour)
Backup frequency     → supplied  (at least once every hour, DB + vault)
Retention            → supplied  (30 days, DB + vault)
Operational owner    → supplied  (CarbonFlow Project Owner)
On-call              → supplied  (CarbonFlow Operations)
Escalation           → supplied  (Ops → Project Owner → technical/hosting admin)
Monitoring           → supplied  (automated, with alerts)
Restore frequency    → supplied  (quarterly)
Approver             → supplied  (CarbonFlow Project Owner)
Approval date        → supplied  (2026-10-01)
```

All eleven items supplied. **BUSINESS APPROVAL — COMPLETE.**

### 9.8 Items reported, not decided

- The approved owner, on-call and escalation are expressed as **roles**, not
  named individuals. The tier-3 "designated technical/hosting administrator" is
  **not yet identified by name**. Recorded as GAP-05; engineering has not
  nominated anyone.
- The **7-year regulatory retention question** remains open and is outside this
  approval. See §13.

---

## 10. Recovery Requirements Approval

```text
Status:            APPROVED
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Approval date:     2026-10-01
Decision status:   APPROVED
Approval reference: CarbonFlow Recovery Requirements — MVP/Initial Production Policy
Comments:          Project-level requirements for the current CarbonFlow stage.
                   Not contractual SLA commitments unless separately approved.
                   Approval covers requirements only. Required technical controls
                   are NOT IMPLEMENTED and compliance is NOT YET TESTED.
```

### 10.1 Requirement status model

Only these terms are used:

```text
NOT DEFINED          no approved value exists
PENDING APPROVAL     decision not yet made
APPROVED             value approved by the business owner
REQUIRES CLARIFICATION  decision supplied but ambiguous or incomplete
NOT DEMONSTRATED     requirement approved, but evidence does not support it
DEMONSTRATED         capability proven under stated conditions
```

`PASS`, `FAIL`, `COMPLIANT` and `NON-COMPLIANT` are **not** used. They require
an actual technical validation performed against the approved requirement, which
has not occurred.

### 10.2 Per-decision record

```text
Decision:          RTO
Status:            APPROVED
Approved value:    4 hours
Scope:             qualifying failure → usable state (auth, application, database,
                   evidence vault, core existing data accessible)
Qualifying failure: restoration of infrastructure/database/evidence-vault state
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Validation:        NOT YET TESTED
```

```text
Decision:          RPO
Status:            APPROVED
Approved value:    1 hour
Scope:             maximum acceptable loss of committed database and evidence-vault
                   data following a qualifying failure
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Validation:        NOT YET TESTED — no scheduler; actual recovery point unbounded
```

```text
Decision:          Backup frequency
Status:            APPROVED
Approved value:    at least once every hour
Type:              database backup plus evidence-vault backup
Coverage:          both stores, recoverable to a consistent recovery boundary
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Validation:        NOT YET TESTED
```

```text
Decision:          Retention period
Status:            APPROVED
Approved value:    30 days
Scope:             database backups and evidence-vault backups
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Validation:        NOT YET TESTED
```

```text
Decision:          Operational owner
Status:            APPROVED
Approved value:    CarbonFlow Project Owner
Role/team:         CarbonFlow Operations
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
```

```text
Decision:          On-call / escalation
Status:            APPROVED
Approved value:    On-call = CarbonFlow Operations; business hours initially, with
                   critical-incident escalation outside business hours
Escalation path:   CarbonFlow Operations → Project Owner → designated
                   technical/hosting administrator
Escalation trigger: qualifying production failure; failed recovery attempt;
                   missed required backup; suspected backup corruption;
                   inability to meet the approved RTO/RPO
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Note:              tier-3 administrator not yet named — see GAP-05
```

```text
Decision:          Backup monitoring
Status:            APPROVED
Approved value:    Automated monitoring of backup execution, backup freshness,
                   backup failures, and evidence-vault backup status
Alert requirement: alert when a required backup fails, becomes stale beyond the
                   approved recovery window, or cannot be verified
Responsible team:  CarbonFlow Operations
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Validation:        NOT YET TESTED — monitoring NOT IMPLEMENTED (F-09)
```

```text
Decision:          Restore-drill frequency
Status:            APPROVED
Approved value:    quarterly
Required evidence: database restore result; evidence-vault restore result;
                   checksum/integrity verification; application retrieval
                   verification; tenant-isolation verification; observed recovery
                   duration; documented findings
Responsible owner: CarbonFlow Operations
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
Note:              one drill performed 2026-09-30. Cadence NOT SCHEDULED.
```

```text
Decision:          Evidence recovery requirement
Status:            APPROVED
Approved value:    SAME REQUIREMENT as the database — the vault carries the same
                   4-hour RTO and 1-hour RPO
Consistency:       database metadata and evidence-vault content must be restored
                   to a consistent recovery boundary; a database-only restore
                   does not constitute a complete recovery
Approver:          CarbonFlow Project Owner
Role:              Project Owner / Business Owner
Decision date:     2026-10-01
```

### 10.3 Change log

| Date | Decision | From | To | Approver | Notes |
| --- | --- | --- | --- | --- | --- |
| 2026-09-30 | All | — | `PENDING BUSINESS APPROVAL` | — | Document created; no approval supplied |
| 2026-10-01 | All 11 items + evidence-vault requirement | `PENDING BUSINESS APPROVAL` | **`APPROVED`** | CarbonFlow Project Owner | Explicit business decisions recorded. RTO 4 h, RPO 1 h, hourly backups, 30-day retention, ownership/on-call/escalation/monitoring/quarterly drill. Validation remains NOT YET TESTED; controls remain NOT IMPLEMENTED. Release state unchanged: `RELEASE CANDIDATE — FROZEN` |

---

## 11. Business requirement vs observed technical evidence

Kept in separate columns on purpose. The right-hand column does not become the
left-hand column.

### RTO

| | |
| --- | --- |
| **Approved requirement** | 4 hours from a qualifying failure to a usable state |
| **Observed technical evidence** | 66.4 s to health `UP`; 177.9 s to verified usability; local synthetic 115 KB dataset; loopback only; no HA; detection manual |
| **Implementation** | Documented 12-step procedure (`docs/BACKUP-RECOVERY.md` §4); no automation, no failover |
| **Technical compliance validation** | **NOT YET TESTED** |

### RPO

| | |
| --- | --- |
| **Approved requirement** | 1 hour maximum loss of committed database and evidence-vault data |
| **Observed technical evidence** | 245.8 s observed recovery point; backup boundary verified exact (pre-backup data survived, post-backup data correctly lost); backup was **manual, operator-timed** |
| **Implementation** | **NOT IMPLEMENTED** — no scheduler; actual recovery point today is unbounded |
| **Technical compliance validation** | **NOT YET TESTED** |

### Backup frequency

| | |
| --- | --- |
| **Approved requirement** | At least once every hour, database + evidence vault, consistent boundary |
| **Observed technical evidence** | Manual `pg_dump` and vault copy, performed on demand; vault drill used `robocopy`, not the documented `rsync` (recorded deviation) |
| **Implementation** | **NOT IMPLEMENTED** |
| **Technical compliance validation** | **NOT YET TESTED** |

### Retention

| | |
| --- | --- |
| **Approved requirement** | 30 days, database backups + evidence-vault backups |
| **Observed technical evidence** | None — no retention enforcement exists; no measurement possible |
| **Implementation** | **NOT IMPLEMENTED** |
| **Technical compliance validation** | **NOT YET TESTED** |

### Monitoring

| | |
| --- | --- |
| **Approved requirement** | Automated monitoring of execution, freshness, failures, vault status; alerts on failure, staleness, unverifiable backup |
| **Observed technical evidence** | None — finding F-09 remains open |
| **Implementation** | **NOT IMPLEMENTED** |
| **Technical compliance validation** | **NOT YET TESTED** |

### Restore drill

| | |
| --- | --- |
| **Approved requirement** | Quarterly, with a defined evidence set |
| **Observed technical evidence** | One drill performed 2026-09-30 against an isolated scratch environment; all §4 steps executed; restored evidence file SHA-256 identical at four independent points; tenant isolation verified |
| **Implementation** | n/a — organisational. Cadence **NOT SCHEDULED** |
| **Technical compliance validation** | Procedure **DEMONSTRATED** (under drill conditions). RTO/RPO compliance **NOT YET TESTED** |

---

## 12. Gaps between approved requirements and implemented controls

Reported, **not resolved**. Resolving these is technical implementation work and
is **not authorised by this document**.

| ID | Approved requirement | Current control state | Gap |
| --- | --- | --- | --- |
| **GAP-01** | RTO 4 hours measured from the qualifying failure | Monitoring/alerting **NOT IMPLEMENTED** (F-09) | The start of the RTO clock is **undefined**. An undetected outage could consume the 4 hours before anyone begins. Detection is the highest-priority prerequisite. |
| **GAP-02** | RTO 4 hours to a usable state | Recovery is a **documented manual procedure** on a single instance; no standby, replica, failover or HA | The procedure assumes the same host returns and an operator is present. No evidence supports the 4-hour target on production infrastructure or at production data volumes. **REQUIREMENT NOT DEMONSTRATED.** |
| **GAP-03** | RPO 1 hour | Backups are **manual, on demand**; no scheduler | Actual recovery point is **unbounded**. An hourly RPO is not currently supported by any control. **REQUIREMENT NOT DEMONSTRATED.** |
| **GAP-04** | Hourly backups, both stores, consistent recovery boundary | No scheduled job; vault backup is a manual copy; the vault drill substituted `robocopy` for the documented `rsync` | No automation, no boundary enforcement. `docs/BACKUP-RECOVERY.md` §2.5 ordering (database first, vault same-or-later) is documented but unenforced. |
| **GAP-05** | On-call and escalation to a "designated technical/hosting administrator" | **NOT IDENTIFIED** | Tier-3 owner is unnamed. No rotation, contact path or notification mechanism is recorded. Engineering has not nominated anyone. |
| **GAP-06** | Retention 30 days, both stores | Retention enforcement **NOT IMPLEMENTED** | Backups accumulate or are deleted by hand. No control satisfies the commitment. |
| **GAP-07** | Alert when a backup "cannot be verified" | Backup verification automation **NOT IMPLEMENTED** | A backup is currently known to be restorable only by attempting a restore — which the quarterly drill is the only mechanism for. |
| **GAP-08** | Retention of evidence-vault backups containing customer documents | Encryption at rest **NOT IMPLEMENTED** (`docs/BACKUP-RECOVERY.md` §3.2 advisory only); `CARBONFLOW_EVIDENCE_VAULT_DIR` default path unsuitable for production (F-06) | Retaining confidential documents for 30 days in unencrypted backups is a confidentiality exposure created by the approved retention decision. |
| **GAP-09** | Quarterly restore drill | One drill performed 2026-09-30; **no schedule, owner rota or calendar** | Cadence not established. Next drill not scheduled or tracked. |

### 12.1 Consistency issues requiring business or technical review

No internal contradiction was found among the approved values (§9. The
following are observations and open questions, **not** changes):

```text
OBSERVATION
The approved backup interval exactly equals the approved RPO window, leaving no
margin at the worst case. Tightening either value is a business decision.
No value has been changed.

OPEN QUESTION — REGULATORY RETENTION
docs/BACKUP-RECOVERY.md §3.1 notes a possible 7-year baseline for GHG inventory
data. The approved retention is 30 days. Confirming the applicable regulatory
retention regime requires legal and regulatory input this repository does not
have. Not decided; not assumed.

OPEN QUESTION — PITR
The approved 1-hour RPO does not by itself require WAL archiving / point-in-time
recovery; hourly backups can satisfy it. PITR would be the mechanism to guarantee
the recovery boundary precisely. Recorded as NOT IMPLEMENTED, unchanged.
```

---

## 13. Deferred and out of scope

- **7-year regulatory retention baseline** — unconfirmed, outside this approval.
- **Named individuals** for on-call and tier-3 escalation — not nominated.
- **All technical implementation** — backup scheduler, backup automation,
  retention automation, monitoring, alerts, offsite replication, encryption,
  operational dashboards, RTO/RPO testing, deployment and infrastructure
  changes. **Not authorised by this document.**
- **Release status change** — none made. The release remains
  `RELEASE CANDIDATE — FROZEN` at `d42af8b`.

---

## 14. Document history — auditable timeline

Stages are recorded separately and **must not be collapsed**. Each was a
distinct event at a distinct time.

```text
Original state                          no recovery procedure, no targets
    ↓  docs/BACKUP-RECOVERY.md created (Phase 10.6.1, finding F-08)
Recovery procedure documented           procedures written, NOT TESTED
    ↓  9895688 — backup + restore drill against scratch environment
Recovery drill performed                procedure executed and verified
    ↓  8072a62 — evidence-vault recovery drill
Recovery capability demonstrated         DEMONSTRATED (2026-09-30)
    ↓  001516f, dc21491 — requirements document created, awaiting decision
Business requirement defined            APPROVED (2026-10-01)
    ↓  — not started —
Technical control implemented           NOT IMPLEMENTED
    ↓  — not started —
Formal compliance test                  NOT YET TESTED
```

| Stage | State | Date | Evidence |
| --- | --- | --- | --- |
| Recovery procedure documented | **COMPLETE** | 2026-09-30 | `docs/BACKUP-RECOVERY.md` |
| Recovery drill performed | **COMPLETE** | 2026-09-30 | `docs/OPERATIONAL-VALIDATION.md` |
| Recovery capability demonstrated | **DEMONSTRATED** | 2026-09-30 | `docs/OPERATIONAL-VALIDATION.md` |
| Business requirement defined | **APPROVED** | 2026-10-01 | This document, §10 |
| Technical control implemented | **NOT IMPLEMENTED** | — | §6, §12 |
| Formal compliance test | **NOT YET TESTED** | — | §11 |

> Earlier release records (`docs/RELEASE-HANDOVER.md`, `docs/FINAL-RELEASE-REPORT.md`)
> state *"the RTO is unknown"* / *"RTO is NOT DEFINED"*. Those statements were
> accurate when written. They are **superseded on one point only**: an approved
> RTO and RPO now exist (§10). The surrounding warnings — that recovery
> requirements were pending, that backups are manual, and that no service levels
> should be assumed — **remain accurate and are retained**. The historical
> statements have not been rewritten.

---

## 14a. Cross-document discrepancy register

**RESOLVED — documentation-only consistency pass, 2026-10-01.** The stale
pre-approval wording identified below has been corrected in all five documents.
Each correction either updates a *current-state* claim or annotates a
*historical* statement as superseded, preserving it unchanged.

| Document | Location | Former stale statement | Disposition |
| --- | --- | --- | --- |
| `README.md` | Documentation table + recovery note | *"Pending business approval… not yet defined"*, *"no approved RTO and no approved RPO"* | **Updated** to current approved state |
| `README.md` | Known limitations | *"Backup/restore procedures have not been verified."* | **Updated** — procedure rehearsed 2026-09-30; compliance NOT YET DEMONSTRATED |
| `docs/BACKUP-RECOVERY.md` | Status banner | *"CarbonFlow has no approved RTO and no approved RPO… pending business approval"* | **Superseded banner added**; original retained as historical |
| `docs/BACKUP-RECOVERY.md` | §3.1 Retention | No approved retention | **Updated** to 30 days, enforcement NOT IMPLEMENTED |
| `docs/BACKUP-RECOVERY.md` | §4 warning | *"CarbonFlow has no approved RTO"* | **Updated** — RTO is 4 hours |
| `docs/BACKUP-RECOVERY.md` | §6 warning + rehearsal status | *"CarbonFlow currently has no approved RTO"*; *"Formal RTO: NOT DEFINED"* | **Updated** with historical note; *"did not establish an RTO"* claim preserved |
| `docs/BACKUP-RECOVERY.md` | §6 rehearsal frequency | *"Rehearsal frequency is NOT DEFINED"* | **Updated** to quarterly, cadence NOT SCHEDULED |
| `docs/BACKUP-RECOVERY.md` | §7 not-implemented table | Listed capabilities without approved-requirement linkage | **Updated** with required-by column and encryption/HA rows |
| `docs/OPERATIONAL-VALIDATION.md` | RTO/RPO definition section | `APPROVED RTO/RPO: NOT DEFINED`; *"no approved RTO exists"* | **Historical framing added** + current-state block |
| `docs/OPERATIONAL-VALIDATION.md` | Drill results (≈L844, L911) | `FORMAL RTO: UNKNOWN` | **Annotated historical**, dated, with supersession note |
| `docs/OPERATIONAL-VALIDATION.md` | Results against target | *"NOT TESTED — no approved RTO exists to compare against"* | **Updated** to NOT YET TESTED / REQUIREMENT NOT DEMONSTRATED; historical note retained |
| `docs/OPERATIONAL-VALIDATION.md` | Requirements still to be decided | All values `NOT DEFINED` | **Updated** to approved values |
| `docs/OPERATIONAL-VALIDATION.md` | Verdict + final verdict | *"RECOVERY REQUIREMENTS — PENDING BUSINESS APPROVAL"* | **Updated**; OPERATIONAL VALIDATION — PARTIAL retained |
| `docs/RELEASE-HANDOVER.md` | §4 update block | *"The RTO is still NOT DEFINED… pending business approval"* | **Superseded banner added**; original retained and marked historical |
| `docs/RELEASE-HANDOVER.md` | §7 NOT VERIFIED table | *"The RTO is still `NOT DEFINED`"* | **Annotated historical**; RTO/RPO compliance row added |
| `docs/RELEASE-HANDOVER.md` | §8 operational warning 3 | *"no approved RTO or RPO"* | **Updated** with 2026-10-01 approval and NOT IMPLEMENTED controls |
| `docs/FINAL-RELEASE-REPORT.md` | Operational state table | *"The RTO is still `NOT DEFINED`"* | **Annotated historical**; RTO/RPO compliance row added |
| `docs/FINAL-RELEASE-REPORT.md` | Known limitation 3 | *"The RTO remains `NOT DEFINED` — pending business approval"* | **Dated addendum added**; original clause retained |

### Verification after this pass

No document claims `RTO COMPLIANT`, `RPO COMPLIANT`, `PRODUCTION READY`,
`FULL OPERATIONAL VALIDATION`, or that the RTO/RPO are met. Remaining
occurrences of pre-approval wording are explicitly labelled historical with a
date, or describe the 2026-09-30 validation window as it stood.

| Document | Location | Stale statement | Correction |
| --- | --- | --- | --- |
| `README.md` | §Recovery objectives (≈L149–156) | *"Pending business approval… not yet defined"*, *"no approved RTO and no approved RPO"* | RTO 4 h and RPO 1 h **APPROVED** 2026-10-01; controls NOT IMPLEMENTED; validation NOT YET TESTED |
| `docs/BACKUP-RECOVERY.md` | Status banner (≈L14) | *"CarbonFlow has no approved RTO and no approved RPO… pending business approval"* | As above |
| `docs/BACKUP-RECOVERY.md` | §6 warning (≈L443) | *"CarbonFlow currently has no approved RTO"* | As above |
| `docs/BACKUP-RECOVERY.md` | §6 Rehearsal status (≈L461) | *"Did NOT establish an RTO or RPO. Formal RTO: NOT DEFINED"* | Still correct that the **drill did not establish** an RTO; only the *"NOT DEFINED"* clause is superseded |
| `docs/OPERATIONAL-VALIDATION.md` | ≈L844, L911, L1102, L1283, L1333 | *"no approved RTO exists"*, *"Formal RTO / RPO … STILL NOT DEFINED"* | These describe the **2026-09-30** validation state and remain accurate **as of that date**. Approval post-dates the validation. |
| `docs/RELEASE-HANDOVER.md` | ≈L245, L332 | *"The RTO is still NOT DEFINED — no business owner has approved a target"* | Approved 2026-10-01 |
| `docs/FINAL-RELEASE-REPORT.md` | ≈L108 | *"The RTO is still NOT DEFINED"* | Approved 2026-10-01 |

> **None of these statements is a compliance claim.** No document asserts
> `RTO COMPLIANT`, `RPO COMPLIANT`, `PRODUCTION READY` or
> `FULL OPERATIONAL VALIDATION`. The staleness is limited to the existence of an
> approved target, and correcting it is documentation work for the next
> documentation-only pass once the freeze permits it.

---

## 15. Related documents

| Document | Relevance |
| --- | --- |
| `docs/BACKUP-RECOVERY.md` | The procedure these requirements govern; §2 procedures, §3 retention/encryption, §4 recovery, §6 rehearsal |
| `docs/OPERATIONAL-VALIDATION.md` | Evidence for the observed figures, including the evidence-vault recovery drill |
| `docs/RELEASE-HANDOVER.md` | Operator handover; warns against assuming service levels |
| `docs/FINAL-RELEASE-REPORT.md` | Release record; carries the same warning |
| `docs/DEPLOYMENT-SECURITY.md` | DB TLS, secrets, CORS for a restored deployment |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | Finding F-09 (no backup monitoring), F-06 (vault default path unsuitable for production) |

---

**Document status: `APPROVED` (business requirements, 2026-10-01).**
**Control implementation status: `NOT IMPLEMENTED`.**
**Compliance validation status: `NOT YET TESTED`.**
**Release state: `RELEASE CANDIDATE — FROZEN` (baseline `d42af8b`).**
**No engineering work is authorised or implied by this document.**
# CarbonFlow Operational Recovery Requirements

> ## ⚠ STATUS: PENDING BUSINESS APPROVAL
>
> **Until business approval exists, CarbonFlow has no formal RTO/RPO
> commitment.** No RTO, RPO, retention period, backup frequency, operational
> owner or escalation path has been decided by anyone.
>
> **This document sets no target.** Every approval field below is deliberately
> empty and must be completed by a named human. Values shown elsewhere in this
> document as *observed* are drill measurements, not service objectives.
>
> Recorded 2026-09-30 against the frozen release candidate (`d42af8b`).

---

## ⚠ READ THIS BEFORE QUOTING ANY NUMBER

Recovery capability has been **demonstrated**. Recovery requirements remain
**pending business approval**. These are different things, and conflating them
is the specific error this document exists to prevent.

### The three concepts that must never be merged

| # | Concept | Meaning | Current state |
| --- | --- | --- | --- |
| 1 | **Observed capability** | What a recovery drill actually demonstrated on a local synthetic environment | **Measured** — see §5 |
| 2 | **Approved requirement** | What the business formally requires | **NOT DEFINED** — no approver, no decision |
| 3 | **Compliance result** | Whether measurement satisfies the approved requirement | **CANNOT BE DETERMINED** — there is nothing to compare against |

### Prohibited statements

The following are **false** and must never appear in any CarbonFlow document,
report, status update or commitment:

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
✗ CarbonFlow has an RTO of ...
✗ CarbonFlow has an RPO of ...
```

### Permitted statements

```text
✓ Observed recovery time = 66.4 s (failure → health UP)
  Environment = local developer workstation
  Dataset     = synthetic, 115,047-byte database, 1 evidence file (154 bytes)
  Network     = loopback only; no failover, no HA, no replication
  Date        = 2026-09-30
  NOTE: an observed drill measurement. NOT an approved service objective.

✓ Formal RTO = NOT DEFINED (pending business approval)
✓ Formal RPO = NOT DEFINED (pending business approval)
✓ Recovery capability has been demonstrated.
  Recovery requirements remain pending business approval.
```

> **A future engineer or agent reading only a drill result must not be able to
> infer a commitment.** If a future change makes the drill figures appear
> adjacent to any target-shaped field, this document has failed.

### The principle

> **Engineering may demonstrate recovery capability.**
> **Engineering may measure recovery performance.**
> **Engineering must not invent the business's acceptable recovery objectives.**
>
> RTO, RPO, retention, backup frequency, ownership and escalation are
> business/operational decisions requiring explicit approval.

---

## 1. Approval status summary

| Question | Answer |
| --- | --- |
| Is there an approved RTO? | **NO — NOT DEFINED** |
| Is there an approved RPO? | **NO — NOT DEFINED** |
| Is there a backup schedule? | **NO — backups are manual and on demand** |
| Is there a retention policy? | **NO — explicitly left to the operator** |
| Does evidence have its own requirement? | **NOT DECIDED** |
| Is there a named operational owner? | **NO — none identified in this repository** |
| Is there an on-call / escalation path? | **NO — none identified in this repository** |

---

## 2. REQUIRED APPROVAL DECISION TABLE

> **Do not populate the empty columns.** They are for a named human to complete.
> Every row is currently `PENDING`. If no approver has signed, no value in the
> "Approved Value" column may be treated as authoritative — regardless of
> whether a number has been typed there.

| Decision | Current State | Business Decision | Approved Value | Approver | Approval Date | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| RTO | NOT DEFINED | PENDING |  |  |  |  |
| RPO | NOT DEFINED | PENDING |  |  |  |  |
| Backup frequency | NOT DEFINED | PENDING |  |  |  |  |
| Retention period | NOT DEFINED | PENDING |  |  |  |  |
| Operational owner | NOT DEFINED | PENDING |  |  |  |  |
| On-call / escalation | NOT DEFINED | PENDING |  |  |  |  |
| Backup monitoring | NOT DEFINED | PENDING |  |  |  |  |
| Restore-drill frequency | NOT DEFINED | PENDING |  |  |  |  |

**Column notes**

- **Current State** — what is true of the system today. Factual; engineering-maintained.
- **Business Decision** — `PENDING` / `APPROVED` / `REJECTED` / `NEEDS REVISION`. Business-maintained.
- **Approved Value** — the committed value, or empty. Business-maintained.
- **Approver** — the named accountable person. **Engineering must not nominate an individual.**
- **Approval Date** — when the decision was made.
- **Evidence** — the drill or document supporting the decision, once one exists to compare against.

### Supported approval states

```text
PENDING BUSINESS APPROVAL   (default — current state)
APPROVED
REJECTED
NEEDS REVISION
```

**Nothing may be marked `APPROVED` unless an actual approval is supplied by the
user or business owner.** The default state of every row remains
`PENDING BUSINESS APPROVAL`.

---

## 3. Decision rules

These rules constrain how each decision may be made. They exist so a value is
never copied from an example, inferred from a measurement, or inherited by
accident.

### 3.1 RTO

**Definition.** RTO means the **maximum acceptable time to restore CarbonFlow to
the business-defined usable state** after a qualifying failure.

- *"Qualifying failure"* — the conditions that trigger the objective. An operator
  mistake or a single user's failed upload is not a qualifying failure. The
  threshold must be stated when the RTO is approved.
- *"Business-defined usable state"* — **not** "the process is running". It must
  be defined in terms a user would recognise, for example: users can sign in,
  load their tenant's data, and retrieve previously stored evidence. The business
  defines this; engineering does not.

**The measured 66.4 s and 177.9 s figures are observations only. They do not
constitute an approved RTO and must not be transcribed into the "Approved Value"
field as though they were one.**

### 3.2 RPO

**Definition.** RPO means the **maximum acceptable amount of data loss, measured
against the recovery point**, after a qualifying failure.

**The measured 245.8 s observed recovery point is an observation from one drill.
It does not constitute an approved RPO.** It was also an artifact of when the
operator chose to back up, not a capability statement.

### 3.3 Backup frequency

**Backup frequency must be selected based on the approved RPO.** It is a
consequence of the RPO, not an independent preference.

**Do not assume** hourly, every 15 minutes, daily, continuous, or any other
interval **unless explicitly approved.** No interval is pre-selected in this
document.

| If the approved RPO is… | Implied minimum capability | Current state |
| --- | --- | --- |
| Minutes | WAL archiving / point-in-time recovery | **NOT IMPLEMENTED** |
| 1 hour | Automated hourly job | **NOT IMPLEMENTED** — manual only |
| 4–24 hours | Automated daily job + documented procedure | **NOT IMPLEMENTED** — manual only |

### 3.4 Retention

**Do not invent a retention period.** It must be a business/operational decision.

It is **two** decisions, not one:

- **Database backup retention** — how many restore points, over what period?
- **Evidence vault retention** — these contain customer documents and need
  their own retention, encryption and access-control treatment. A vault backup
  that outlives the database it pairs with is a confidentiality exposure, not
  just a storage cost.

`docs/BACKUP-RECOVERY.md` §3.1 notes a possible 7-year baseline for GHG
inventory data but explicitly directs the reader to *"confirm against the
applicable regime rather than copying that number."* That confirmation requires
legal and regulatory input this repository does not have.

### 3.5 Operational owner

**A named accountable owner must be identified.** Engineering must **not**
nominate an individual.

Recovery objectives without a named accountable person are unenforceable in
practice: nobody is required to notice when they are missed, and nobody is
required to run the drill.

### 3.6 On-call / escalation

The business/operations owner must identify:

- **Primary responder** — who is accountable first
- **Secondary / escalation responder** — who is accountable if the primary is unavailable
- **Escalation path** — the order and conditions of escalation
- **Notification mechanism** — how an outage is detected and communicated

**Do not invent names, contact details or a rotation.**

---

## 4. Open questions for the decision-maker

> Presented as discussion prompts. **No option is selected, and no default is
> implied by the ordering.**

### Q1 — If CarbonFlow stops working, how long can users wait?

Candidate magnitudes to discuss: minutes · 1 hour · 4 hours · 8 hours · 24 hours

Context:
- Users are sustainability and finance teams preparing GHG disclosures. Usage is
  very likely **business-hours**, not 24×7. An RTO framed in hours may be more
  honest than one framed in minutes.
- Reporting deadlines are seasonal and externally imposed. A single annual
  disclosure window may justify a much tighter RTO for part of the year than the
  rest — which a single RTO number cannot express.
- If CarbonFlow is not the system of record for emissions (spreadsheets or
  consultant tooling may be), a long RTO may be tolerable. If it is, it is not.

### Q2 — If CarbonFlow loses recently entered data, how much loss is acceptable?

Candidate magnitudes to discuss: 5 minutes · 15 minutes · 1 hour · 4 hours · 24 hours

Context:
- Carbon accounting records are the **evidentiary basis for disclosure**. Data
  entered but not yet locked may be re-entered by hand. Data inside a `LOCKED`
  audit cycle may be effectively unrecoverable by hand, because it carries a
  governed seal and an audit hash trail (`docs/BACKUP-RECOVERY.md` §3.1, §5).
- The practical cost of data loss therefore **rises sharply** as an audit
  approaches `LOCKED`, and is low immediately after. A single RPO flattens that.
- An RPO measured in minutes is only achievable with WAL archiving / PITR, which
  is recorded as **NOT IMPLEMENTED**. Choosing a tight RPO creates a build
  commitment, not just a documentation change.

### Q3 — How often should backups run?

Follows from Q2. See rule 3.3. **Do not select an interval independently.**

### Q4 — How long should backups be retained?

See rule 3.4. Two decisions, not one.

### Q5 — Does evidence have the same recovery requirement as the database?

Answer with one of: **YES** / **NO** / **SEPARATE REQUIREMENT** / **UNKNOWN**

**Currently UNKNOWN.** Not a technicality: `docs/BACKUP-RECOVERY.md` §1.2
establishes the database and vault as two **separate** stores that must both be
recovered, and §2.5 documents a specific unsafe ordering:

> *"The unsafe ordering is the reverse: a database restored to `T2` referencing
> vault files from `T1`, where the vault is older and the bytes are gone."*

If evidence may warrant a different (typically tighter) requirement — it may
include signed supplier certificates and PPA guarantees — the two stores need
**separate schedules and separate retention**, not one policy.

---

## 5. Observed capability (measurements, not commitments)

Recorded to inform the decisions above.

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

Note that **data recovery was 3.5 s of a 66.4 s total**, and application
startup alone was 22.9 s. The headline figure is dominated by JVM startup on a
warm machine, not by recovering data.

### What these figures do not mean

- They do **not** mean CarbonFlow meets an RTO. **No RTO exists to meet.**
- They do **not** mean recovery would take 66 s in production. Restoring 115 KB
  is not a measurement of restoring a real dataset; a dataset 10,000× larger
  would invert the relative weight of JVM startup, `pg_restore`, and any
  offsite network transfer.
- They say **nothing about detection**. The 36 s gap was the engineer deciding
  to restore. Real detection depends on monitoring that is `NOT IMPLEMENTED`
  (F-09), so an undetected outage could run for hours.
- They say **nothing about failover**. No standby, no replica, no HA. The drill
  assumed the same host returns.
- The **4-minute observed recovery point is an artifact of the engineer's own
  timing** — simply the interval between the backup they chose to take and the
  failure they then caused. It is **not** a capability statement. With no
  scheduler, the real RPO is *whatever an operator happens to do*, which is
  unbounded.

### What *was* demonstrated

The documented procedure in `docs/BACKUP-RECOVERY.md` was executed end to end
against a real outage: database and evidence vault both destroyed, both
restored, application restarted, login and data access re-verified, and an
evidence file recovered with a SHA-256 identical to the original at four
independent points. Full evidence in `docs/OPERATIONAL-VALIDATION.md`.

**Recovery capability has been demonstrated. Recovery requirements remain
pending business approval.**

---

## 6. Capability that must exist before a target could be met

Recorded so the cost is visible **before** a target is chosen. This is a list of
commitments, not a plan; **no work has been started**.

| Capability | State | Needed for |
| --- | --- | --- |
| Scheduled/automated backup job | **NOT IMPLEMENTED** | Any RPO shorter than "when an operator remembers" |
| Point-in-time recovery / WAL archiving | **NOT IMPLEMENTED** (depends on PostgreSQL config outside this repo) | Any RPO measured in minutes |
| Offsite / cross-region replication | **NOT IMPLEMENTED** | Surviving loss of the primary host or region |
| Backup retention enforcement | **NOT IMPLEMENTED** | Meeting any retention commitment |
| Encryption at rest for backups | **NOT IMPLEMENTED** (§3.2 advisory only) | Handling customer documents offsite |
| Backup monitoring / alerting | **NOT IMPLEMENTED** (F-09) | **Detecting** a missed backup or an overdue recovery |
| Backup verification automation | **NOT IMPLEMENTED** | Knowing a backup is restorable without trying it |
| Named operations owner / on-call | **NOT IDENTIFIED** | Any of the above being acted upon |

> **Backup monitoring is a prerequisite for meeting an RTO, not a nice-to-have.**
> Without alerting, a missed backup is discovered only when a restore is needed.

---

## 7. Business Approval Checklist

The approver must decide each item below. **All items remain unchecked** — leave
them unchecked unless actual approval information is supplied.

- [ ] **RTO** — maximum acceptable time to restore to the business-defined usable state
- [ ] **RPO** — maximum acceptable data loss measured against the recovery point
- [ ] **Backup frequency** — derived from the approved RPO, not chosen independently
- [ ] **Retention period** — database backups *and* evidence vault copies
- [ ] **Operational owner** — a named accountable individual
- [ ] **On-call responsibility** — primary and secondary responder
- [ ] **Escalation path** — order and conditions of escalation
- [ ] **Notification mechanism** — how an outage is detected and communicated
- [ ] **Backup monitoring requirement** — who is alerted, on what failure
- [ ] **Restore-drill frequency** — how often the drill in §5 is repeated
- [ ] **Evidence recovery requirement** — YES / NO / SEPARATE REQUIREMENT (Q5)

Once decided, record the outcome in §8 and update the table in §2.

---

## 8. Approval record

> **Do not fill these fields with invented information.** Each stays empty until
> a named person supplies it.

```text
Status: PENDING BUSINESS APPROVAL

Approver:
Role:
Decision date:
Decision:
Comments:
```

### Per-decision record

> One block per row of the §2 table. Reproduce as needed; leave blank until decided.

```text
Decision:          <RTO | RPO | Backup frequency | Retention period | Operational owner
                    | On-call / escalation | Backup monitoring | Restore-drill frequency>

Status:            PENDING BUSINESS APPROVAL
Approved value:
Rationale:
Approver:
Role:
Decision date:
Comments:
```

### Change log

> Record every subsequent state change here: `PENDING → APPROVED`,
> `NEEDS REVISION`, `REJECTED`. Include date and approver.

| Date | Decision | From | To | Approver | Notes |
| --- | --- | --- | --- | --- | --- |
| 2026-09-30 | All | — | `PENDING BUSINESS APPROVAL` | — | Document created; no approval supplied |

---

## 9. Related documents

| Document | Relevance |
| --- | --- |
| `docs/BACKUP-RECOVERY.md` | The procedure these requirements govern; §3 retention/encryption, §4 recovery, §6 rehearsal |
| `docs/OPERATIONAL-VALIDATION.md` | Evidence for the observed figures, including the evidence-vault recovery drill |
| `docs/RELEASE-HANDOVER.md` | Operator handover; warns against assuming service levels |
| `docs/FINAL-RELEASE-REPORT.md` | Release record; carries the same warning |
| `docs/DEPLOYMENT-SECURITY.md` | DB TLS, secrets, CORS for a restored deployment |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | Finding F-09 (no backup monitoring), F-06 (vault default path unsuitable for production) |

---

**Document status: `PENDING BUSINESS APPROVAL`.**
**Release state: `RELEASE CANDIDATE — FROZEN`.**
**No engineering work is authorised or implied by this document.**

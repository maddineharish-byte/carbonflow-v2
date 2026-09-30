# CarbonFlow Operational Recovery Requirements

> **STATUS: PENDING BUSINESS APPROVAL**
>
> **No RTO or RPO has been decided by anyone.** This document records the
> decision that must be made and the evidence gathered to inform it. It does
> **not** set any target. Every value below is either `NOT DEFINED` or was
> measured by an automated drill on a local synthetic environment.
>
> Recorded 2026-09-30 against the frozen release candidate.

---

## The short version

| Question | Answer |
| --- | --- |
| Is there an approved RTO? | **NO — NOT DEFINED** |
| Is there an approved RPO? | **NO — NOT DEFINED** |
| Is there a backup schedule? | **NO — backups are manual and on demand** |
| Is there a retention policy? | **NO — explicitly left to the operator** |
| Does evidence have its own requirement? | **NOT DECIDED** |
| Is there a named approving authority? | **NO — none identified in this repository** |

An automated drill was run to measure *current capability*. It produced an
observed recovery time and an observed recovery point. **Those are measurements,
not commitments, and they cannot be compared against a target that does not
exist.**

---

## 1. Terms

**RTO — Recovery Time Objective.** *"How quickly must CarbonFlow become usable
again after a serious outage?"* Example only: `RTO = 4 hours`. **This is not the
CarbonFlow target unless a named person approves it.**

**RPO — Recovery Point Objective.** *"How much recently created data can we
afford to lose?"* Example only: `RPO = 1 hour`. **Again, not the CarbonFlow
target unless approved.**

The distinction matters for reading the rest of this document: an **observed**
figure is what this codebase did on one machine with one small synthetic
dataset. A **target** is what a business has decided it requires. Only the
second can be "met" or "not met".

---

## 2. What the repository already says

A search of every Markdown file for `RTO`, `RPO`, `recovery time`, `recovery
point`, `backup frequency`, `business continuity`, `disaster recovery`,
`downtime`, `data loss`, `retention`, `SLA`, `uptime` and `availability`
returned **no numeric target of any kind**.

The only existing statements are explicit acknowledgements that the values are
absent:

| Source | What it says |
| --- | --- |
| `docs/BACKUP-RECOVERY.md` §6 | *"Record the elapsed time — that is the actual RTO — and the outcome."* |
| `docs/BACKUP-RECOVERY.md` §6 | *"**No rehearsal has been performed for this document.** Until one is, the RTO of CarbonFlow is unknown."* |
| `docs/BACKUP-RECOVERY.md` §3.1 | *"No retention policy is implemented in this repository — an operator must set one."* |
| `docs/BACKUP-RECOVERY.md` §3.1 | *"A common baseline is 7 years… Confirm against the applicable regime rather than copying that number."* |
| `docs/FINAL-RELEASE-REPORT.md` | *"No performance or HA data. **Do not assume service levels.**"* |
| `docs/RELEASE-HANDOVER.md` | *"No performance data exists. **Do not assume service levels.**"* |

**Conclusion: `Formal RTO: NOT DEFINED` / `Formal RPO: NOT DEFINED`.** The
prior reports of `UNKNOWN` were accurate. `UNKNOWN` is not being replaced by an
invented number here.

---

## 3. The decisions required

> These questions are for the person accountable for CarbonFlow operations.
> **No option has been selected.** No default is implied by the ordering.

### Q1 — If CarbonFlow stops working, how long can users wait?

Candidate magnitudes, to be discussed — **not a menu with a default**:

- Minutes
- 1 hour
- 4 hours
- 8 hours
- 24 hours

Relevant context to inform the discussion:

- CarbonFlow's users are sustainability and finance teams preparing GHG
  disclosures. Usage is very likely **business-hours**, not 24×7. An RTO framed
  in hours may be more honest than one framed in minutes.
- Reporting deadlines are seasonal and externally imposed. A single annual or
  quarterly disclosure window may justify a much tighter RTO for part of the
  year than the rest — which a single RTO number cannot express.
- If CarbonFlow is not the system of record for emissions (spreadsheets or
  consultant tooling may be), a long RTO may be tolerable. If it *is*, it is not.

### Q2 — If CarbonFlow loses recently entered data, how much loss is acceptable?

Candidate magnitudes — **again, no default**:

- 5 minutes
- 15 minutes
- 1 hour
- 4 hours
- 24 hours

Context:

- Carbon accounting records are the **evidentiary basis for disclosure**. Data
  entered but not yet locked may be re-entered. Data inside a `LOCKED` audit
  cycle may be effectively unrecoverable by hand, because it carries a governed
  seal and an audit hash trail (`docs/BACKUP-RECOVERY.md` §3.1, §5).
- The practical cost of data loss therefore **rises sharply** as an audit
  approaches `LOCKED`, and is low immediately after. A single RPO flattens that.
- An RPO of minutes is only achievable with WAL archiving / point-in-time
  recovery, which this repository records as **NOT IMPLEMENTED** (dependent on
  infrastructure outside it). Choosing a tight RPO creates a build commitment,
  not just a documentation change.

### Q3 — How often should backups run?

**This follows from Q2 and must not be chosen independently.** An RPO of *X*
requires a recovery capability at least as good as *X*.

| If the approved RPO is | The current capability is | Gap |
| --- | --- | --- |
| Minutes | Backup is manual and on demand; no scheduler; no WAL archiving | **Large.** Requires WAL archiving / PITR, and an automated job. |
| 1 hour | Manual, on demand | **Large.** Requires an automated hourly job. |
| 4–24 hours | Manual, on demand | **Moderate.** A daily automated job plus documented operator procedure may suffice. |

**Current state: there is no scheduler at all.** Every backup in this project's
history was taken by hand, by an operator, at a moment of their choosing.

### Q4 — How long should backups be retained?

**NOT DEFINED.** `docs/BACKUP-RECOVERY.md` §3.1 points at a possible 7-year
baseline for GHG inventory data but explicitly says to confirm against the
applicable regime rather than copy the number. That confirmation requires legal
and regulatory input this repository does not have.

Two sub-questions that must be answered together:

- **Database backups** — how many restore points, over what period?
- **Evidence vault copies** — these contain customer documents and need their
  own retention, encryption and access-control treatment (§3.2, §3.3). A vault
  backup that outlives the database it pairs with is a confidentiality exposure,
  not just a storage cost.

### Q5 — Does evidence have the same recovery requirement as the database?

Answer with one of: **YES** / **NO** / **SEPARATE REQUIREMENT** / **UNKNOWN**.

**Currently UNKNOWN.** This is not a technicality. `docs/BACKUP-RECOVERY.md`
§1.2 establishes that the database and the vault are two **separate** stores
which must both be recovered, and §2.5 documents a specific unsafe ordering:

> *"The unsafe ordering is the reverse: a database restored to `T2` referencing
> vault files from `T1`, where the vault is older and the bytes are gone."*

If evidence may have a different (typically tighter, because it may include
signed supplier certificates and PPA guarantees) requirement, then the two
stores need **separate schedules and separate retention**, not one policy. If
evidence is treated identically, one policy suffices — but that is a decision,
not a default.

---

## 4. Record of approval

> To be completed by the accountable person. Until this table is filled in, no
> RTO or RPO exists and none may be quoted, reported or relied upon.

```text
RTO:                              NOT DEFINED
RPO:                              NOT DEFINED
Backup frequency:                 NOT DEFINED
Retention (database):             NOT DEFINED
Retention (evidence vault):       NOT DEFINED
Evidence recovery requirement:    NOT DEFINED (YES / NO / SEPARATE REQUIREMENT)

Approving authority:              NOT IDENTIFIED
Approval date:                    NOT APPROVED
```

### Who must approve this

No operational owner, approver, on-call rotation, escalation path or RACI is
recorded anywhere in this repository. That is itself a gap: recovery objectives
without a named accountable person are unenforceable in practice, because
nobody is required to notice when they are missed.

This document does **not** nominate an approver. Nominating one is a management
decision.

---

## 5. Current measured capability

Recorded to inform the discussion above. **These are observations, not
commitments.**

### What was measured, and under what conditions

| Condition | Value |
| --- | --- |
| Environment | Local developer workstation (Windows 11 Pro) |
| Database | PostgreSQL 18.6 on `localhost` |
| Application | Frozen jar `carbonflow-backend-1.0.0-PRO.jar`, local single instance |
| Network | Loopback only — **no network transfer, no failover, no HA** |
| Dataset | **Synthetic**: 2 activities, 2 calculations, 2 emission records, 1 audit, 1 evidence file |
| Database size | **115,047 bytes** |
| Vault size | **1 file, 154 bytes** |
| Backup type | `pg_dump` logical, manual, on demand |
| Environment hardening | **None** — no TLS (`DB_SSLMODE=prefer`), no encryption at rest, no WAF, no monitoring |

### Observed results

| Measure | Value |
| --- | --- |
| **Observed recovery time** (failure → health `UP`) | **66.4 s** |
| **Observed recovery time** (failure → application verified usable) | **177.9 s** |
| — of which detection-to-restore-start gap | 36.1 s |
| — of which database restore | 3.46 s |
| — of which vault restore | 0.06 s |
| — of which application restart | 22.9 s |
| **Observed recovery point** (failure − last backup) | **245.8 s (4 min 6 s)** |

### What these numbers do and do not mean

**They do mean:** the documented recovery procedure in `docs/BACKUP-RECOVERY.md`
works. It was executed end to end against a real outage — database and vault
destroyed, both restored, application restarted, login and data access
re-verified, and an evidence file recovered with a SHA-256 identical to the
original at four independent points.

**They do NOT mean:**

- that CarbonFlow meets any RTO — **no RTO exists to meet**
- that recovery would take 66 seconds in production. This figure is dominated by
  **application startup on a warm JVM**, not by data recovery. Restoring 115 KB
  is not a measurement of restoring a real dataset.
- anything about scale. A production dataset 10,000× larger would change the
  database restore time by orders of magnitude; the relative weight of JVM
  startup, `pg_restore`, and any network transfer to offsite storage would
  invert.
- anything about **detection**. The 36 s "gap" was me deciding to restore. Real
  detection depends on monitoring that **does not exist in this repository**
  (`NOT IMPLEMENTED` — finding F-09). Undetected outages could run for hours.
- anything about **failover**. There is no standby, no replica and no HA. The
  drill assumed the same host came back.
- anything about the **RPO achievable in production**. The observed 4 minutes is
  an artifact of *me* choosing the gap between backup and failure. It is not a
  capability statement — with no scheduler, the real RPO is *whatever the
  operator happens to do*, which is unbounded.

### The honest summary

CarbonFlow's recovery capability is **demonstrated but ungoverned**. The
procedure works. Nobody has decided how often it must be run, how fast it must
finish, or who is accountable when it does not.

---

## 6. What must change to make an RPO achievable

Recorded so the cost of choosing a tight RPO is visible before it is chosen.
**This is a list of commitments, not a plan, and no work has been started.**

| Capability | State | Needed for |
| --- | --- | --- |
| Scheduled/automated backup job | **NOT IMPLEMENTED** | Any RPO shorter than "whenever an operator remembers" |
| Point-in-time recovery / WAL archiving | **NOT IMPLEMENTED** (depends on PostgreSQL config outside this repo) | Any RPO measured in minutes |
| Offsite / cross-region replication | **NOT IMPLEMENTED** | Surviving loss of the primary host or region |
| Backup retention enforcement | **NOT IMPLEMENTED** | Meeting any retention commitment |
| Encryption at rest for backups | **NOT IMPLEMENTED** (§3.2 is advisory) | Handling customer documents offsite |
| Backup monitoring / alerting | **NOT IMPLEMENTED** (F-09) | *Detecting* that a backup failed, or that recovery is overdue |
| Backup **verification** (restore rehearsal automation) | **NOT IMPLEMENTED** | Knowing a backup is restorable without trying it |
| Named operations owner / on-call | **NOT IDENTIFIED** | Any of the above being acted upon |

Note the dependency: **backup monitoring is a prerequisite for meeting an RTO**,
not a nice-to-have. Without alerting, a missed backup is discovered only when a
restore is needed.

---

## 7. Related documents

| Document | Relevance |
| --- | --- |
| `docs/BACKUP-RECOVERY.md` | The procedure these requirements govern; §3 retention/encryption, §4 recovery, §6 rehearsal |
| `docs/OPERATIONAL-VALIDATION.md` | Evidence for the observed figures, including the evidence-vault drill |
| `docs/DEPLOYMENT-SECURITY.md` | DB TLS, secrets, CORS for a restored deployment |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | Finding F-09 (no backup monitoring), F-06 (vault default path unsuitable for production) |
| `docs/RELEASE-HANDOVER.md` | Operator handover; warns against assuming service levels |

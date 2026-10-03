# CarbonFlow — Backup & Recovery Procedure

> **Phase 10.6.1 (finding F-08).** This document did not previously exist even
> though `DEPLOYMENT-SECURITY.md` cited it. It is written against the actual
> CarbonFlow architecture. No backup automation exists in this repository, and
> no restore has been performed against production data.

> ### ⚠ Status update — 2026-09-30 (rehearsal)
>
> The procedures in §2.1, §2.2 and §2.4 and the recovery sequence in §4 have now
> been **rehearsed** against an isolated scratch environment and are marked
> **TESTED** where noted below. **No production restore has been performed.**
>
> Rehearsal evidence: `docs/OPERATIONAL-VALIDATION.md`.

> ### ⚠ Status update — 2026-10-01 (requirements approved)
>
> **Recovery requirements were approved on 2026-10-01:**
>
> ```text
> APPROVED RTO:  4 hours
> APPROVED RPO:  1 hour
> BACKUP FREQUENCY: at least once every hour (database + evidence vault)
> RETENTION:     30 days (database backups + evidence-vault backups)
> EVIDENCE VAULT: same RTO/RPO and same recovery boundary as the database
> RESTORE DRILL: quarterly
> ```
>
> These are **project-level requirements, NOT contractual SLAs.**
>
> **Approved target ≠ implemented control ≠ validated compliance.** As of
> 2026-10-01, every control these targets depend on is **NOT IMPLEMENTED**:
> backup scheduling, retention enforcement, automated backup monitoring,
> encryption at rest, and production HA/failover. The rehearsal below
> demonstrated the **procedure**, not compliance with the 4-hour RTO or 1-hour
> RPO — that validation is **NOT YET TESTED**.
>
> The 2026-09-30 status note above is retained as the historical record of the
> rehearsal. Its statement that *"CarbonFlow has no approved RTO and no approved
> RPO"* was **accurate when written; superseded by the approved project-level
> recovery requirements dated 2026-10-01.**
>
> Full decision record: `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.

## What "tested" means here

| Label | Meaning |
|---|---|
| **TESTED** | Actually executed in this repository/environment, with the result recorded. |
| **DOCUMENTED BUT NOT TESTED** | Written from the real schema and tooling. Sound in principle, but not executed end-to-end here. |
| **NOT IMPLEMENTED** | No tooling, script, or configuration exists in this repository. An operator must supply it. |

Nothing in this document claims a restore has been rehearsed. Before relying on
it, rehearse the restore into a scratch environment (see §6).

---

## 1. What must be backed up

CarbonFlow has exactly two pieces of durable state. Backing up the database
without the evidence vault produces a system whose metadata references files that
no longer exist.

### 1.1 PostgreSQL — authoritative

Every business record lives here. This is the only thing that must be recovered
to return to service.

| Group | Tables |
| --- | --- |
| Identity & tenancy | `users`, `organizations`, `organization_memberships`, `refresh_tokens` |
| Structure & scope | `legal_entities`, `facilities`, `departments`, `reporting_periods`, `organizational_boundaries`, `boundary_facilities`, `organization_settings` |
| Carbon data | `activity_data`, `emission_factors`, `emission_factor_versions`, `gwp_sets`, `gwp_values`, `calculations`, `calculation_gas_results`, `emission_records`, `inventory_snapshots` |
| Governance | `carbon_audits`, `audit_checklist_items`, `review_findings`, `review_comments`, `correction_requests`, `audit_approvals`, `audit_lock_events` |
| Evidence metadata | `evidence_records`, `evidence_versions`, `evidence_links` |
| Planning | `carbon_targets`, `reduction_projects`, `data_requests` |
| Reference / RBAC | `calculation_methodologies`, `permissions`, `roles`, `role_permissions`, `attributes` |
| Migration state | `flyway_schema_history` |

**`flyway_schema_history` must be included.** It carries the applied version and
the checksum of every migration. Restoring a database without it makes Flyway
attempt to re-apply `V1`–`V8` against a populated schema and fail.

### 1.2 Evidence vault — file bytes

Evidence **file content** does not live in PostgreSQL. Only metadata and
tenant-owned links do.

- Location: `carbonflow.evidence.vault-dir` (env `CARBONFLOW_EVIDENCE_VAULT_DIR`).
- Default when unset: the relative path `vault_storage`, resolved to an absolute
  path under the process working directory at startup.
- Layout: one sub-directory per tenant, then per evidence record/version.
- **This default is unsuitable for production** (see finding F-06): a relative
  path under the working directory is typically not on durable, backed-up
  storage. Set `CARBONFLOW_EVIDENCE_VAULT_DIR` to a dedicated, backed-up mount.

The two must be backed up on a consistent schedule. A database snapshot taken at
`T1` paired with a vault snapshot at `T2` can reference evidence files that did
not yet exist at `T1`, which is safe (an evidence link may point at a
not-yet-present file only if the upload had not completed). The unsafe ordering is
the reverse: a database restored to `T2` referencing vault files from `T1`, where
the vault is older and the bytes are gone.

### 1.3 What is NOT backed up

| Item | Why not |
| --- | --- |
| `db/migration/*.sql` | Version-controlled in Git. Not runtime state. |
| `backend-java/target/*.jar` | Build output, reproducible from source. |
| `node_modules/`, `dist/` | Build artifacts. Node/Express is decommissioned. |
| `vault_storage/` in Git | Git-ignored by design. |
| Secrets (`CARBONFLOW_JWT_SECRET`, `CARBONFLOW_REFRESH_TOKEN_SECRET`, `DB_PASSWORD`) | Must live in a secret manager, **never** in a backup archive alongside data. See §5. |

---

## 2. Backup procedures

> **Rehearsal status:** §2.1, §2.2 and §2.4 have been **TESTED** against an
> isolated scratch environment (2026-09-30). §2.3 (`pg_basebackup`) remains
> **DOCUMENTED BUT NOT TESTED**. No script in this repository performs any of
> these procedures; each was executed by hand, by an operator.

### 2.1 Logical backup — `pg_dump` (recommended)

Portable, schema-explicit, restorable across PostgreSQL versions. Preferred for
most deployments.

```bash
# Custom format: compressed, selectable on restore, parallel-restorable.
pg_dump \
  --host="$DB_HOST" --port="${DB_PORT:-5432}" \
  --username="$DB_USER" \
  --dbname="$DB_NAME" \
  --format=custom \
  --compress=9 \
  --file="carbonflow-$(date -u +%Y%m%dT%H%M%SZ).dump"

# Record the schema version alongside the dump, so a restore can be reasoned about.
psql --host="$DB_HOST" --username="$DB_USER" --dbname="$DB_NAME" -t -A -c \
  "select version, description, success from flyway_schema_history order by installed_rank"
```

Take the password from `PGPASSWORD` or a `.pgpass` entry — never from a command
line argument, which is visible in the process table.

### 2.2 Logical backup — `pg_dumpall` (globals)

Roles are not inside the database dump, so global objects need a separate capture:

```bash
pg_dumpall --host="$DB_HOST" --username="$DB_USER" --globals-only \
  --file="carbonflow-globals-$(date -u +%Y%m%dT%H%M%SZ).sql"
```

This matters for `roles`, `permissions` and `role_permissions`, which the
frozen 9-role x 44-permission matrix is seeded into and which the application
reads at runtime.

### 2.3 Physical backup — `pg_basebackup`

Lower recovery time for large databases. Use only on a primary or a standby.

```bash
pg_basebackup \
  --host="$DB_HOST" --username="$DB_USER" \
  --pgdata="/backup/base-$(date -u +%Y%m%d)" \
  --format=tar --gzip --wal-method=stream --progress
```

A physical backup **requires** the exact same PostgreSQL major version on
restore. Record `SELECT version();` with every physical backup.

### 2.4 Evidence vault

```bash
# Quiesced, consistent copy. Stop the application first, or accept that an
# upload in flight may be captured mid-write.
rsync -a --delete \
  --exclude '*.tmp' \
  "$CARBONFLOW_EVIDENCE_VAULT_DIR/" \
  "/backup/evidence/$(date -u +%Y%m%dT%H%M%SZ)/"
```

The vault holds customer-supplied documents, which are frequently confidential.
See §4 and §5 for handling and encryption.

### 2.5 Consistency

| Situation | Requirement |
| --- | --- |
| Application stopped | Any backup method is consistent. Preferred for evidence. |
| Application running | `pg_dump` is transactionally consistent on its own. The evidence vault is not — an in-flight upload may be captured partially. Quiesce for a strict guarantee. |
| Cross-object consistency (DB + vault) | Restore the **database first**, then the vault from the **same or later** timestamp. Never restore a database newer than the vault. |

---

## 3. Retention, encryption, access control

### 3.1 Retention

> **Approved retention: 30 days**, covering both database backups and
> evidence-vault backups (`docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`,
> approved 2026-10-01). This is a **project-level requirement, not a
> contractual SLA**, and **retention enforcement is NOT IMPLEMENTED** — an
> operator must set one up. The observations below remain open context for that
> implementation.

Considerations:

- Carbon accounting records are the evidentiary basis for disclosure. Retention
  is a business and regulatory decision; align it with the jurisdictions the
  organization reports in (see the globalization note in
  `docs/ARCHITECTURE.md`).
- A common baseline is 7 years for GHG inventory data supporting a disclosure.
  Confirm against the applicable regime rather than copying that number. **The
  approved retention is 30 days; the 7-year figure remains an open regulatory
  question and is not part of the current approval.**
- `audit_lock_events` and the audit state machine represent governed sealing
  decisions. Restoring to a point *before* a governed lock must be a conscious,
  recorded choice, because it can un-seal an audit that was reported as locked.

### 3.2 Encryption

| At rest | Recommendation |
| --- | --- |
| Database dump | Encrypt the archive, not just the volume. AES-256 via `gpg`/`age`, or the storage layer's server-side encryption plus a separate archive copy. |
| Evidence vault | Same. These are customer documents. |
| In transit to backup storage | TLS. Do not copy unencrypted dumps over plain HTTP to object storage. |

```bash
gpg --symmetric --cipher-algo AES256 \
  --output "carbonflow-$(date -u +%Y%m%dT%H%M%SZ).dump.gpg" \
  "carbonflow-$(date -u +%Y%m%dT%H%M%SZ).dump"
```

`--symmetric` prompts for a passphrase. Use asymmetric encryption with a managed
key if backup operations are performed by more than one person.

### 3.3 Access control

- Backups contain every tenant's data. Treat a backup archive with the same
  sensitivity as the production database itself.
- Separate the backup credential from the application credential.
- Restrict restore capability to a small named group; a restore is equivalent to
  a production write.
- **Never place application secrets in the same archive as the data.** A backup
  that contains `DB_PASSWORD` or a JWT signing key must be handled as a secret
  store, not as a data archive.

---

## 4. Recovery procedure

> **TESTED against an isolated scratch environment (2026-09-30).** The full §4
> sequence — database restore, vault restore, application restart, health,
> login, tenant isolation and evidence download — was executed end to end and
> verified. **No restore has been performed against production data.** Rehearse
> into a scratch environment before relying on this in production.
>
> **The elapsed times observed in rehearsal are NOT the RTO.** The approved RTO
> is **4 hours** (approved 2026-10-01); the observed figures are drill
> measurements on a synthetic local dataset and do not demonstrate compliance
> with it. See `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.

### Step 1 — Prepare PostgreSQL

```bash
createdb -h "$DB_HOST" -U "$DB_ADMIN_USER" carbonflow_restore
```

Confirm the target PostgreSQL **major version** is compatible:
- logical (`pg_dump --format=custom`): generally forward-compatible across
  supported major versions
- physical (`pg_basebackup`): must match exactly

### Step 2 — Restore

**Logical:**
```bash
pg_restore --host="$DB_HOST" --username="$DB_ADMIN_USER" \
  --dbname=carbonflow_restore --no-owner --no-privileges \
  "carbonflow-YYYYMMDDTHHMMSSZ.dump"
```

**Physical:** stop the target server, replace `PGDATA` with the base backup
contents, fix ownership, start the server.

**Globals** (roles/permissions), if captured:
```bash
psql -h "$DB_HOST" -U "$DB_ADMIN_USER" -d carbonflow_restore -f carbonflow-globals-*.sql
```

### Step 3 — Verify database integrity

```sql
-- Every migration present and successful.
SELECT version, description, success
  FROM flyway_schema_history ORDER BY installed_rank;
-- expect 8 rows, versions 1..8, success = t on every row

-- Expected object count (37 domain tables + flyway_schema_history).
SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public';
-- expect 38
```

If `flyway_schema_history` is missing or short, **stop** — do not let the
application start and attempt to migrate a populated schema. Restore the
history table from the backup or re-baseline deliberately (see §5).

### Step 4 — Verify Flyway state

Start the application **with the restored database** and confirm from the log:

```text
Successfully validated 8 migrations
Schema "public" is up to date. No migration necessary.
```

"No migration necessary" is the expected healthy outcome for a restore. If the
log shows migrations being applied, the dump and the migration files disagree —
investigate before proceeding; do not let Flyway mutate a restored production
schema.

### Step 5 — Restore the evidence vault

```bash
# Only from a snapshot at or NEWER than the database snapshot.
rsync -a /backup/evidence/YYYYMMDDTHHMMSSZ/ "$CARBONFLOW_EVIDENCE_VAULT_DIR/"
```

Confirm `CARBONFLOW_EVIDENCE_VAULT_DIR` points at the restored location, and
that the process can read and write it (the application creates the directory if
absent, but it will not repair permissions).

### Step 6 — Verify application configuration

Re-supply the runtime environment. These are **not** in the backup:

| Variable | Requirement |
| --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Point at the restored database |
| `CARBONFLOW_JWT_SECRET` | **Must be the same value as before the incident.** Changing it invalidates every outstanding access token, forcing all users to re-authenticate. |
| `CARBONFLOW_REFRESH_TOKEN_SECRET` | **Must be the same value** to keep existing sessions. Changing it invalidates all refresh tokens. |
| `CARBONFLOW_CORS_ALLOWED_ORIGINS` | Explicit production origins. Empty trusts no origin — correct for production. |
| `CARBONFLOW_EVIDENCE_VAULT_DIR` | Restored vault location |
| `DB_SSLMODE` | `require` or `verify-ca` for production (see `docs/DEPLOYMENT-SECURITY.md`) |
| `CARBONFLOW_SEED_DEMO_DATA` | Leave `false` for production |

> **Consequence of losing the signing secrets:** sessions are invalidated, but no
> data is lost and no integrity guarantee is broken. Access tokens and refresh
> tokens are HMAC-signed with these values; a different value makes them
> unverifiable, so they are correctly rejected. This is fail-safe behaviour, not
> corruption.

### Step 7 — Start the backend

```bash
java -jar carbonflow-backend-1.0.0-PRO.jar
```

Or with certificate verification for the database:
```bash
java -Djavax.net.ssl.trustStore=/opt/certs/pg-ca.jks \
     -Djavax.net.ssl.trustStorePassword="$PG_TRUSTSTORE_PASSWORD" \
     -jar carbonflow-backend-1.0.0-PRO.jar
```

### Step 8 — Verify health

```bash
curl -s http://localhost:8080/api/v1/health
# expect: {"status":"UP", ...}
```

A `WARN` about `sslmode` being `prefer` means DB transport security is not
enforced. Investigate before declaring the restore complete.

### Step 9 — Verify authentication

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"<known-user>","password":"<password>"}'
# expect HTTP 200 with accessToken + refreshToken
```

Also confirm a wrong password returns 401 and that a deactivated organization is
refused (403 `ORGANIZATION_NOT_ACTIVE`).

### Step 10 — Verify tenant isolation

This is the most important post-restore check. A restore that mixes tenant data
would be a serious data-integrity incident.

1. Sign in as a **Company Admin** of tenant A.
2. `GET /api/v1/organizations/current` — must return tenant A.
3. `GET /api/v1/activity-data` — must return only tenant A's rows.
4. Attempt `GET /api/v1/facilities/<a-known-facility-id-from-tenant-B>` — must
   return 404 or 403, never tenant B's data.
5. Sign in as a **PLATFORM_ADMIN**; `GET /api/v1/users` must return only that
   platform tenant's users, not all tenants.
6. Confirm the 43 tenant-scoped foreign keys survived (they are schema objects;
   verify with `\d activity_data` or `information_schema`).

### Step 11 — Verify representative functionality

| Area | Check |
| --- | --- |
| Emissions | `GET /api/v1/emissions` returns Scope 1 plus both Scope 2 perspectives, and the two perspectives are **not** summed together |
| Calculations | `GET /api/v1/calculations/{id}` returns a snapshot with factor value/unit/source/version and GWP set |
| Reporting | `GET /api/v1/analytics/dashboard` and `GET /api/v1/reports/export-csv` succeed; CSV shows no leading `=`, `+`, `-`, `@` cells |
| Audit | `GET /api/v1/audits` returns audits; confirm an audit that was `LOCKED` before the incident is still `LOCKED` |
| Evidence | `GET /api/v1/evidence` lists records; a known evidence id downloads successfully from the restored vault |
| Inventory | `GET /api/v1/inventory` returns snapshots |
| Targets | `GET /api/v1/targets` returns targets |
| Platform admin | `GET /api/v1/platform/tenants` returns tenants; `GET /api/v1/platform/tenants/{id}` returns 200 for a known id and 404 for a malformed one |

### Step 12 — Record the recovery

Record: the backup identifier restored, the restore timestamp, who performed it,
the schema version confirmed, and the result of each check above. CarbonFlow
audits are governed records; an unrecoverable restore is a reportable event.

---

## 5. Edge cases

### Restoring a database older than the code expects

If `flyway_schema_history` shows a version lower than the migration files in the
build, the application will apply the newer migrations on startup. That is the
normal upgrade path and is safe **only if** no rows violate the newer constraints.
Test on a copy first.

### Restoring without `flyway_schema_history`

If the table is missing from the dump, the application will treat the populated
schema as unmanaged and, because
`spring.flyway.baseline-on-migrate=true` with `baseline-version=6`, baseline it
at 6 and apply only `V7` and `V8` — silently skipping validation of `V1`–`V6`.
This is the documented Node-to-Java transition behaviour (ADR-012) and is a poor
fit for a normal restore. Prefer restoring a dump that includes the history
table. If it is genuinely absent, baseline deliberately and record the decision.

### Restoring to an un-audited state

If a restore rewinds past a governed audit lock, the audit may return to an
unlocked state. That is a real change to a sealed record. It must be recorded,
and the affected organizations notified — CarbonFlow does not claim
cryptographic immutability, so the seal is a governed state that operators
maintain, not a tamper-proof guarantee.

### Partial evidence loss

Metadata can outlive the files. The application will list evidence records whose
bytes are missing. Treat this as data loss to be reported, not silently repaired.

---

## 6. Rehearsal

A backup that has never been restored is an assumption, not a control. Before
production, and at a defined interval thereafter:

1. Provision a scratch PostgreSQL of the same major version and a scratch vault
   directory.
2. Restore the most recent backup following §3–§5.
3. Run the full §3 verification: health, login, tenant isolation, emissions,
   audit, evidence download, CSV export.
4. Record the elapsed time **and the outcome**. The elapsed time is an
   *observed recovery time* — it is **not** an RTO. See the warning below.
5. Destroy the scratch environment.

> ### ⚠ The elapsed time is NOT the RTO
>
> Step 4 produces an **observed recovery time**. It becomes evidence of meeting
> the RTO only if a named business owner has approved a target **and** the
> measurement is compared against it under representative conditions.
>
> **The approved RTO is 4 hours and the approved RPO is 1 hour** (approved
> 2026-10-01). Comparing a rehearsal elapsed time against those targets at the
> scale of a synthetic local dataset establishes **nothing** about compliance.
> Compliance validation is **NOT YET TESTED**.
>
> See `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.

### Rehearsal status

**A rehearsal HAS been performed** (2026-09-30), against an isolated scratch
environment. Database backup, database destruction, database restore, evidence
vault backup, vault destruction, vault restore, application restart, login,
tenant isolation and evidence download were all executed and verified; a
restored evidence file matched its original SHA-256 at four independent points.
Full evidence: `docs/OPERATIONAL-VALIDATION.md`.

What that rehearsal established, and what it did **not**:

- **Established:** the procedures in this document work, including the §2.5
  ordering rule and the §4 Step 5 vault restore.
- **Did NOT establish compliance with the approved RTO or RPO.** Targets have
  since been approved (4 hours / 1 hour, 2026-10-01), but this rehearsal does
  not validate them: it ran against a synthetic local dataset over loopback with
  no scheduler, no monitoring and no HA. **RTO validation: NOT YET TESTED.
  RPO validation: NOT YET TESTED.** See
  `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md`.
- **Did NOT rehearse:** scheduled backup, retention, encryption at rest, offsite
  replication, backup monitoring, or `rsync` itself (this host is Windows; the
  drill used the platform-equivalent `robocopy`).

**Rehearsal frequency: `QUARTERLY`** (approved 2026-10-01 as a project-level
requirement). **The cadence is NOT SCHEDULED** — one drill has been performed
(2026-09-30); no calendar, owner rota or tracking exists. Responsible owner:
CarbonFlow Operations. Required evidence per drill: database restore result,
evidence-vault restore result, checksum/integrity verification, application
retrieval verification, tenant-isolation verification, observed recovery
duration, documented findings.

---

## 7. Not implemented in this repository

> **Status update — 2026-10-02 (Phase 10.8).** The table below is retained as the
> record of the freeze date. Its "NOT IMPLEMENTED" entries are **superseded**
> where noted inline by Phase 10.8 automation; the surrounding warnings — that no
> production infrastructure exists here, that every step is performed by an
> operator against infrastructure they provide — **remain accurate**.

| Capability | State |
| --- | --- |
| Scheduled/automated backup job | **IMPLEMENTED** (Phase 10.8, REC-13) — `RecoveryBackupScheduler`, hourly in UTC by default, **disabled unless `carbonflow.recovery.backup.enabled=true`** |
| Backup verification / restore rehearsal automation | **IMPLEMENTED** — REC-06 verification, REC-10 drill, REC-11/REC-12 validation |
| Point-in-time recovery | **NOT IMPLEMENTED** — depends on PostgreSQL WAL archiving configured outside this repository |
| Offsite / cross-region replication | **NOT IMPLEMENTED** |
| Backup monitoring and alerting | **PARTIAL** (REC-08/REC-14) — detects and records; the shipped provider writes a structured notification to the application log and **does not email or page a human** |
| Retention enforcement | **IMPLEMENTED** (REC-07) — 30 days, runs after each successful scheduled backup |
| Encryption at rest for backups | **IMPLEMENTED** (REC-09) — asymmetric gpg; `age` is preferred where installed |
| Production HA / failover | **NOT IMPLEMENTED** — single instance; the drill assumed the same host returns |
| Application-level write quiescence during backup | **NOT IMPLEMENTED** — `QuiesceGuard.NoOp`; sets are recorded in the manifest as *not quiesced* |
| Cross-host scheduling lock | **IMPLEMENTED** (Phase 10.9) — a PostgreSQL session-level advisory lock (`PostgreSqlBackupLease`), held for the duration of the run. See §8.1a. **Verified by a two-instance test inside one JVM; two-host behaviour NOT VERIFIED** |
| **The scheduled trigger actually firing** | **NOT IMPLEMENTED — found in Phase 10.14 (F-10).** `@EnableScheduling` is **absent from the repository**, so the `@Scheduled` method on `RecoveryBackupScheduleConfiguration` cannot run even with `carbonflow.recovery.backup.enabled=true`. Setting the flag does **not** start automatic backups. The scheduler tests call `runOnce(...)` directly, which is why they pass |
| Human notification delivery | **NOT IMPLEMENTED** — the shipped provider writes a structured log line and reports `deliversOutOfBand() == false`. Nobody is emailed or paged |

This repository contains no Dockerfile, container manifest, CI pipeline or
infrastructure-as-code definition. Every operational step in this document is
performed by the operator against infrastructure they provide.

---

## 8. OPERATIONAL RUNBOOK

> **What this section is.** A procedure for the operator on call. It assumes the
> Phase 10.7/10.8 controls exist, and it is explicit about the cases where a
> human is still required.
>
> **Reminder.** Approved RTO 4 hours, RPO 1 hour, hourly backups, 30-day
> retention — all **project-level requirements, not a contractual SLA**.

### 8.1 Enabling automated backup

> ### ⚠ Read this first — Phase 10.14 (F-10)
>
> **Enabling automation does not currently start automatic backups.**
> `@EnableScheduling` does not appear anywhere in the repository, so the
> `@Scheduled` method on `RecoveryBackupScheduleConfiguration` is never
> registered with Spring and cannot fire. The configuration below will produce
> the startup log lines, and then **nothing will be backed up on a schedule**.
>
> The scheduler, the cycle, the monitoring and the retention all work — they are
> exercised by tests that call `runOnce(...)` directly, and an operator can do
> the same. What is missing is the timer that calls it.
>
> Until this is resolved: **take backups on demand, and do not rely on §8.2 as a
> confirmation that automation is working** — that check assumes sets exist.
> Tracked as finding **F-10** in `docs/HANDOVER.md` §16.

Automation is **off by default**. Nothing is backed up until it is turned on.

| Property | Default | Meaning |
| --- | --- | --- |
| `carbonflow.recovery.backup.enabled` | `false` | Master switch. No scheduler bean exists while false |
| `carbonflow.recovery.backup.root` | *(required)* | Absolute directory for backup sets. Must be durable, backed-up storage |
| `carbonflow.recovery.backup.cron` | `0 0 * * * *` | Top of every hour |
| `carbonflow.recovery.backup.zone` | `UTC` | Zone the cron is evaluated in |
| `carbonflow.recovery.backup.timeout` | `PT30M` | Bound on one backup attempt |
| `carbonflow.recovery.backup.copied-with` | `robocopy` | Recorded in the set for auditability |

Set these, then confirm the startup log line:

```text
Recovery backup schedule configured: enabled cron="0 0 * * * *" zone=UTC
Recovery notifications enabled with provider(s) [structured-log] (out-of-band delivery to a human: false)
```

If recovery notifications are **not** configured, that second line will say so.
A backup that fails will then be visible only in the log.

### 8.1a Concurrent-run exclusion (added Phase 10.9)

A backup run must hold **two** gates, not one:

1. a per-process guard, which stops two runs overlapping inside one JVM; and
2. a **PostgreSQL advisory lock**, which stops two runs overlapping across
   different application instances.

The second gate is held for the duration of the run on a dedicated database
session. Because a session-level advisory lock dies with its session, a crashed
or killed backup host releases it automatically - there is no lock expiry to
wait out and no manual cleanup step.

Operational consequences:

| Log line | Meaning | Action |
| --- | --- | --- |
| `another host holds the cross-host backup lease` | Another instance is already backing up. **This run was skipped deliberately** | None. This is correct behaviour, not a failure |
| `Cross-host backup lease unavailable (...)` | The lock could not be checked, usually because the database was unreachable. The run proceeded on the per-process guard only | Investigate connectivity. **That run was not cross-host protected** |

The second row is a deliberate trade-off, not an oversight: refusing to back up
because the database is down would disable recovery from that very outage. The
cost is that such a run has no cross-host exclusion, and it is logged rather
than assumed.

Exclusion has been demonstrated by a two-instance test against a real
PostgreSQL server. It has **not** been tested across two separate machines - see
`RECOVERY-CONTROLS-DESIGN.md` §29.5.

### 8.2 Daily check

Roughly a minute, once a day:

1. **Newest backup exists and is recent.** The newest set's age must be under the
   1-hour RPO window.
2. **It is `VERIFIED`.** The manifest's `verification.status` must be `VERIFIED`.
   `UNVERIFIABLE` is **not** a pass.
3. **No alert is present.** Look for `Recovery notification:` lines at `WARN`
   or `ERROR`.
4. **Nothing is being suppressed indefinitely.** A repeatedly suppressed
   notification means the condition is still unresolved.

The fastest check: the newest set's manifest `createdAt`, and its
`verification.status`.

```bash
ls -1t <backupRoot> | head -1
cat <backupRoot>/<newestSet>/manifest.json | grep -E 'createdAt|"status"'
```

### 8.3 When a backup fails

1. **Read the alert.** It carries the `backupSetId`, the failure detail, and the
   severity.
2. **Find the run.** Search the application log for that `backupSetId`.
3. **Determine the cause.** Common causes:
   - `pg_dump`/`pg_dumpall` not resolvable → set `CARBONFLOW_PG_DUMP` /
     `CARBONFLOW_PG_DUMPALL`
   - authentication failure → `DB_PASSWORD` unset or wrong
   - evidence file missing → the database references bytes not on disk; see
     `docs/BACKUP-RECOVERY.md` §5 "Partial evidence loss"
   - timeout → the backup exceeded its bound; investigate size or storage
     latency before simply raising it
4. **Rerun safely.** The scheduler is idempotent: the next hour will try again.
   An immediate run is also safe. An existing set directory is **never**
   overwritten, and a failed attempt leaves no manifest, so a partial set cannot
   be mistaken for a recovery point.
5. **Verify the replacement** before considering the incident closed: the new
   set must be `VERIFIED`.
6. **Document the incident** in the drill/incident log with the `backupSetId`,
   cause, and resolution.

### 8.4 When a backup is stale

**Why it is stale.** Look for, in order:

```text
RPO_AT_RISK  newest set is 45+ minutes old  → the last run did not complete
BACKUP_MISSING  no backup set exists        → automation never ran, or the root is wrong
BACKUP_STALE  older than 60 minutes         → runs are failing, or the scheduler is off
```

**Execute a manual backup.** With automation enabled, simply wait for the next
hourly trigger after correcting the cause. To confirm immediately, trigger one
cycle and check the outcome.

**Restore normal scheduling.** Verify:

- `carbonflow.recovery.backup.enabled=true`
- the cron and zone are what you intend
- `carbonflow.recovery.backup.root` is writable and durable
- the next run produced a `VERIFIED` set

**A note on margin.** The approved interval equals the approved RPO, so there is
**zero slack**: a set older than one hour means the RPO can no longer be met.
Tightening the interval is a business decision, not an engineering one.

### 8.5 Recovery procedure

Identical whether triggered by an incident or a drill:

```text
1  identify the backup set      newest VERIFIED set; record its backupSetId
2  verify the manifest          parses; boundary is coherent
3  verify the database          database.dump non-empty, digest matches,
                                pg_restore --list succeeds
4  verify the vault             every file in vault-integrity.json present,
                                correct size, SHA-256 matches
5  restore an ISOLATED database never into the live database; use a name that
                                differs from DB_NAME
6  restore the vault            to the ORIGINAL vault path, or rewrite
                                storage_path; a database-only restore is not a
                                complete recovery
7  start the application        re-supply the environment; JWT and refresh
                                secrets must be the SAME values as before
8  verify authentication       login 200; wrong password 401; deactivated org 403
9  verify data                  core tables, rows readable, FK constraints intact
10 verify evidence              download a record; SHA-256 matches evidence_records
11 verify tenant isolation      tenant A sees only A; tenant B's id → 403/404
```

**Stop conditions.** Stop and investigate if step 3 or 4 fails, if Flyway reports
applying migrations (it must say "No migration necessary"), or if any tenant
sees another tenant's data.

**Record.** The restored `backupSetId`, who restored it, when, the result of each
step, and the elapsed time.

### 8.6 Drill procedure (quarterly)

Cadence: quarterly, in `carbonflow.recovery.drill.zone` (UTC by default). The
scheduler **refuses** to run a drill unless a safe isolated environment is
configured, reporting `DRILL_NOT_EXECUTABLE` and notifying rather than guessing.

```text
1  select a backup          newest VERIFIED set
2  set the isolated target  a database name DIFFERENT from the live DB_NAME
3  run the drill            scheduler trigger, or RecoveryDrill directly
4  validate                 all checks in §8.5 steps 2-11 must pass
5  collect evidence         DrillResult JSON: checks, timings, durations,
                            findings, environment, and its limitations
6  measure duration         the RTO clock runs from the declared failure to
                            verified usability, not to "health is UP"
7  record findings          including any limitation the drill reports
8  sign off                 CarbonFlow Operations
```

**The drill must never touch the live database.** The safety gate refuses a
recovery target whose name matches `spring.datasource.url`'s database. There is
no override flag.

**Always record the drill's stated limitations.** A local drill demonstrates the
recovery *procedure*. It does not demonstrate production RTO, production RPO, high
availability, or disaster recovery.

### 8.7 Escalation

Preserved verbatim from the approved decision (`docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` §2.1):

| Tier | Owner | Trigger |
| --- | --- | --- |
| 1 | CarbonFlow Operations | Any recovery alert |
| 2 | Project Owner | Anything unresolved at Tier 1, or any missed required backup |
| 3 | **Designated technical/hosting administrator** | Failed recovery attempt, suspected backup corruption, or inability to meet the approved RTO/RPO |

Escalation triggers, as approved:

- a qualifying production failure
- a failed recovery attempt
- a missed required backup
- suspected backup corruption
- inability to meet the approved RTO/RPO

> **Tier 3 remains unnamed.** The approval records a *role*, not a person. Naming
> an individual is an organisational decision and has **not** been made. Until it
> is, escalation to Tier 3 is **BLOCKED** — resolve that before relying on this
> path. No name is invented here.

### 8.8 What this runbook does not cover

| Limitation | Consequence |
| --- | --- |
| **The scheduled trigger does not fire (F-10, Phase 10.14)** | `@EnableScheduling` is absent from the repository, so enabling `carbonflow.recovery.backup.enabled=true` does **not** start automatic backups. Until this is fixed, backups must be taken on demand. See §8.1 |
| **No out-of-band notification** | Nobody is emailed or paged. Alerts appear in the application log only |
| **No application-level quiescence** | A backup is not quiesced; a concurrent evidence upload may fall outside the set. The manifest records this |
| **Cross-host exclusion is single-JVM-tested only** | The advisory lock (§8.1a, Phase 10.9) is enforced by PostgreSQL and was demonstrated with two scheduler instances in one JVM. Behaviour across two genuinely separate hosts is **NOT VERIFIED** and is not claimed |
| **No HA or failover** | Recovery assumes the same host returns |
| **Local-scale validation only** | Every measured RTO/RPO figure came from a synthetic dataset over loopback |
| **Retention is 30 days** | The possible 7-year GHG regulatory baseline is unresolved and not implemented |

---

## 9. Related documents

| Document | Relevance |
| --- | --- |
| `docs/DEPLOYMENT-SECURITY.md` | DB TLS (`DB_SSLMODE`), secrets, CORS for the restored deployment |
| `docs/EXECUTION.md` | Starting the backend and frontend |
| `docs/DATABASE.md` | Schema, migrations, tenant integrity constraints |
| `docs/SECRETS.md` | Secret variables — never stored in a backup |
| `docs/SECURITY.md` | Security model |
| `docs/PHASE10.6-PRODUCTION-READINESS.md` | Findings F-06, F-08, F-09 |

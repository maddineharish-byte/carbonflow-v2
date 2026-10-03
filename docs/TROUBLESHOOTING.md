# CarbonFlow — Troubleshooting

Operational first aid. Companion to `docs/HANDOVER.md`.

Every entry states what you will **observe**, what it **means**, and what to
**do**. Where a fix requires a code change, that is said plainly rather than
offered as a workaround.

---

## 1. Backend will not start

### 1.1 `CARBONFLOW_JWT_SECRET` / `CARBONFLOW_REFRESH_TOKEN_SECRET` rejected

**Observe:** startup fails with a message naming the variable and the 32-byte
minimum.

**Means:** fail-closed secret validation working as designed.

**Do:**

```bash
export CARBONFLOW_JWT_SECRET="$(openssl rand -base64 48)"
export CARBONFLOW_REFRESH_TOKEN_SECRET="$(openssl rand -base64 48)"
```

Both must be **≥ 32 random bytes**. Note that rotating either one later
**invalidates every outstanding session** and forces all users to sign in again.

---

### 1.2 Database connection refused / authentication failed

**Observe:** Hikari startup failure, or `PSQLException`.

**Do:** confirm `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` are
set. Placeholders are unresolved by default, so an unset variable fails fast
rather than connecting somewhere unexpected.

Confirm connectivity independently:

```bash
psql -h "$DB_HOST" -p "${DB_PORT:-5432}" -U "$DB_USER" -d "$DB_NAME" -c 'SELECT 1;'
```

---

### 1.3 `Invalid sslmode value` / `The server does not support SSL`

**Observe:** the application refuses to start.

**Means:** `DataSourceTls` validated `DB_SSLMODE` against the PgJDBC set and
refused. This is the intended fail-closed behaviour, not a bug.

**Do:** set a value PgJDBC accepts — `disable`, `allow`, `prefer`, `require`,
`verify-ca`, `verify-full`. For local development `prefer` is fine; for
production see the TLS table in `docs/HANDOVER.md` §13.

---

### 1.4 Flyway checksum mismatch

**Observe:** `Validate failed: Migration checksum mismatch`.

**Means:** a migration file was edited after being applied. `V1`–`V8` are frozen
**for this reason**.

**Do:**

1. Confirm `git status db/migration` — a tracked migration must be unmodified.
2. If the file is unmodified, the **database** was modified out of band.
   Investigate before repairing; a repaired checksum hides real drift.
3. Never "fix" this by deleting `flyway_schema_history`.

---

### 1.5 Flyway reports it is applying migrations when you expected none

**Observe during a restore:** migrations are applied instead of
*"No migration necessary"*.

**Means:** the restored database is at a **different schema version** than the
manifest recorded. Stop and investigate — do not accept the migration.

**Do:** compare `manifest.schema.versions` with
`SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;`
against the restored database.

---

### 1.6 `carbonflow.recovery.backup.root` unresolved

**Observe:** a placeholder error when
`carbonflow.recovery.backup.enabled=true`.

**Means:** the property has no default, deliberately — a backup root must be
chosen by an operator.

**Do:** set it to an absolute, durable, backed-up path.

---

## 2. Frontend

### 2.1 Login fails with a network error, not a 401

**Observe:** the browser cannot reach the API; no HTTP status is shown.

**Do:** the frontend resolves the API from `VITE_JAVA_API_BASE_URL`. If it is
empty, calls go to **same-origin relative `/api/v1/...`** — correct behind a
reverse proxy, wrong for a standalone Vite dev server. For local development:

```bash
VITE_JAVA_API_BASE_URL=http://localhost:8080 npm run dev
```

Also confirm the backend is actually up: `curl http://localhost:8080/api/v1/health`.

---

### 2.2 Every cross-origin call is blocked by CORS

**Observe:** browser console reports a blocked preflight.

**Means:** `CARBONFLOW_CORS_ALLOWED_ORIGINS` is empty, which **trusts no
origin**.

**Do:** set an explicit comma-separated list of exact origins. `*` is refused
at startup on purpose — credentials are always enabled and browsers reject that
pairing. For local development the opt-in `dev` profile supplies the localhost
origins:

```bash
SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run
```

---

### 2.3 User signs in and is immediately signed out

**Observe:** session is established, then a 401 ends it.

**Check, in order:**

1. **The access token expired.** 15-minute TTL. A refresh should handle this —
   if it does not, the refresh token may also have expired (7-day TTL).
2. **The refresh token was replayed.** Rotation is single-use; replaying a
   rotated token **revokes the whole family** by design. If two tabs, a retry,
   or a proxy retried the refresh, the family is revoked and everyone must sign
   in again.
3. **The user or membership was deactivated.** The JWT filter re-reads identity
   from PostgreSQL on **every** request, so a deactivation takes effect
   immediately and is not a token problem.
4. **The organisation is not `ACTIVE`.** Registration creates
   `PENDING_ACTIVATION`; only a `PLATFORM_ADMIN` can approve it. A company
   admin cannot self-approve.
5. **The secrets changed.** Rotating `CARBONFLOW_JWT_SECRET` or
   `CARBONFLOW_REFRESH_TOKEN_SECRET` invalidates every session at once.

---

### 2.4 A menu item or button is missing

**Observe:** navigation is narrower than expected.

**Means:** the frontend filtered it on a permission the session does not hold.

**Do:** check `GET /api/v1/auth/me` and read `permissions[]`. Hiding is **UX
only** — the backend re-authorizes every request, so a hidden control is a
cosmetic symptom, not a security control. If the permission *should* be held,
the role/membership is wrong, not the frontend.

---

### 2.5 The frontend test suite fails

**Observe:** a failure in one of the `src/*.test.ts(x)` suites.

**Do not** delete or skip a failing guard — each one encodes a real defect
class. The suite is **86 tests** and passed in full at `cebb10f` (2026-10-03).

Two guards are worth knowing about, because they fail on defects that are
invisible in a single locale:

- `src/globalization.test.ts` fails if the backend derives "now" from the system
  default zone (`OffsetDateTime.now()` without an explicit zone), or if a
  hardcoded country, currency or timezone is reintroduced into source or
  responses. An audit timestamp displayed in an unlabelled reader-local zone is
  a genuine finding: two auditors in two countries must see the same instant.
- `src/accessibility.test.tsx` fails if a control loses its accessible name, a
  dialog loses its semantics, a progress indicator loses its textual
  equivalent, or the full 64-character SHA-256 stops being rendered.

---

## 3. Database and accounting

### 3.1 `AUDIT_LOCKED` (409) on a write

**Observe:** writes to activity data, calculations or checklist items return
`409 AUDIT_LOCKED`.

**Means:** the reporting period is sealed by a `LOCKED` audit. This is the
governed state working as designed.

**Do:** this is **not** a bug and **not** something to bypass. If the figures
are wrong, the correct path is a new correction cycle, not an unlock. Note
that the lock is a **governed state seal, not cryptographic immutability** — a
restore that rewinds past a lock returns the audit to an unlocked state, and
that must be recorded as an operational event.

A batch calculation run **skips** locked-period activity instead of failing.

---

### 3.2 Scope 1 and Scope 2 totals disagree with a manual calculation

**Check, in order:**

1. **Which Scope 2 perspective are you comparing?** Location-based and
   market-based are separate columns and are **never summed**. They are
   aggregated with independent `FILTER (WHERE …)` conditionals, not added
   together.
2. **Which GWP set?** `IPCC_AR6`, `AR5` and `AR4` differ materially for CH4 and
   N2O. The GWP set is recorded on the calculation.
3. **Which factor version?** Provenance columns (`factor_id`,
   `factor_version_number`, `factor_source`) are recorded on every calculation.
4. **Unit conversion.** `UnitConversionService` converts exactly; confirm the
   input and target units.

The engine is `BigDecimal` with `MathContext(28, HALF_UP)` — there is no
floating-point drift to explain a discrepancy. If the numbers still do not
reconcile, the inputs differ, not the arithmetic.

---

### 3.3 `INVALID_TRANSITION` (400) on an audit

**Means:** the requested transition is not in the state machine.

```text
DRAFT → SUBMITTED → DATA_COLLECTION → VALIDATION → REVIEW
REVIEW → APPROVED | CORRECTION_REQUESTED | REJECTED
CORRECTION_REQUESTED → DATA_COLLECTION
REJECTED            → DATA_COLLECTION
APPROVED → AUDIT_READY → LOCKED          (LOCKED is terminal)
```

**Do:** read the current state first (`GET /api/v1/audits/{auditId}`). There is
no override and there should not be one.

---

### 3.4 `CHECKLIST_INCOMPLETE` or `UNRESOLVED_FINDINGS`

**Means:** a governed prerequisite blocks the transition to `APPROVED`,
`AUDIT_READY` or `LOCKED`. Mandatory checklist items must be verified and
high-severity findings must be resolved.

**Do:** complete the checklist items and resolve the findings. The gate is the
control.

---

### 3.5 A cross-tenant id returns 404, not 403

**This is correct behaviour.** Malformed, unknown and cross-tenant ids all
collapse to byte-identical 404s through `ScopeService`, so the API never
confirms that an id exists in another tenant.

**Do not** "fix" this to a 403 — returning 403 for a foreign id and 404 for a
missing one is an enumeration oracle.

---

### 3.6 `EVIDENCE_IN_USE` (409) on delete

**Means:** the evidence record is linked to an audit. Evidence deletion is
governed.

**Do:** leave it. Audit-linked evidence is retained deliberately.

---

### 3.7 Evidence upload rejected

All three failures return `400 UPLOAD_FAILED`; the message says which:

| Message fragment | Cause |
| --- | --- |
| `exceeds 25 MB limit` | Size. Hard limit; not configurable. |
| `MIME type '…' is not supported` | Not in the 9-entry allow-list. |
| `File content does not match declared MIME type` | Magic bytes disagree with the declared type. |

**Do:** all three clean up after themselves — a rejected upload leaves no
partial file and no database row. Retrying with a conforming file is safe.

---

### 3.8 Evidence download returns 404 or 503

**Observe:** `404 EVIDENCE_FILE_NOT_FOUND` or `503 EVIDENCE_STORAGE_ERROR` —
both carry the same message text, *"The evidence file is unavailable."*

**Means:** either the bytes are genuinely missing from the vault, or the
database references a path outside the tenant directory. The identical message
is intentional and must not be "improved" into a distinction.

**Do:** check the vault path actually contains the file. **If it does not, this
is evidence loss** — go to §4.3.

---

## 4. Recovery

### 4.1 No backup is appearing

**This is the expected symptom of finding F-10 at commit `cebb10f`.** Read on.

**Do not assume the scheduler is running.** First check the master switch:

```bash
# is it even enabled?
carbonflow_recovery_backup_enabled=true
carbonflow_recovery_backup_root=/var/backups/carbonflow
```

**Then check F-10.** As of commit `cebb10f`, `@EnableScheduling` **does not
appear anywhere in the repository**, so the `@Scheduled` hourly trigger cannot
fire even when the switch is on. The scheduler unit and integration tests call
`RecoveryBackupScheduler.runOnce(...)` **directly**, which is why they pass.

Until that is resolved, **no automated backup is actually running.** Until it
is, take backups on demand and do not rely on `docs/BACKUP-RECOVERY.md` §8's
daily check to find out — that check assumes sets exist.

See `docs/HANDOVER.md` §16, finding F-10.

---

### 4.2 `another host holds the cross-host backup lease`

**Means:** another instance is already backing up. **This run was skipped
deliberately. This is correct behaviour, not a failure.** Take no action.

---

### 4.3 A backup set is missing files

**Means:** the database references evidence bytes that are not on disk.

**Do:**

1. `vault-integrity.json` lists every required file with its expected size and
   SHA-256. Compare against the vault.
2. A restore into an isolated environment is still valid for the **database** —
   record explicitly that evidence recovery was partial.
3. **A database-only restore is not a complete recovery.** Escalate; the
   database and vault share one recovery boundary.

---

### 4.4 `RPO_AT_RISK` / `BACKUP_STALE` / `BACKUP_MISSING`

| Alert | Meaning | First action |
| --- | --- | --- |
| `RPO_AT_RISK` | Newest set 45+ minutes old | The last run did not complete |
| `BACKUP_MISSING` | No set exists at all | Automation never ran, or the root is wrong |
| `BACKUP_STALE` | Older than 60 minutes | Runs are failing, or the scheduler is off |

**Do:** find the run by searching the log for the `backupSetId`, then read the
failure. Common causes:

- `pg_dump`/`pg_dumpall` not resolvable → set `CARBONFLOW_PG_DUMP` /
  `CARBONFLOW_PG_DUMPALL`
- authentication failure → `DB_PASSWORD` unset or wrong
- timeout → the backup exceeded its bound; **investigate size or storage
  latency before simply raising it**

Reruns are safe: an existing set directory is never overwritten, and a failed
attempt leaves no manifest.

---

### 4.5 Nobody was told about any of this

**That is the current design, not a misconfiguration.** The only notification
provider is `LoggingNotificationProvider`; it writes `WARN`/`ERROR` log lines
and reports `deliversOutOfBand() == false`. There is **no mail, SMS, pager or
webhook code in this repository.** Notifications are in-memory only and are not
persisted.

**Do:** build log-based alerting on the `Recovery notification:` lines. Until
then, **someone must check the logs and the newest set manually, every day.**
Do not describe CarbonFlow as alerting a human.

---

### 4.6 Restoring

Follow `docs/BACKUP-RECOVERY.md` §8.5. The non-negotiables:

- **Restore into an isolated database whose name differs from `DB_NAME`.** The
  drill safety gate refuses a live target **by name, with no override flag.**
- Restore the vault too, to the original path or by rewriting `storage_path`.
- JWT and refresh secrets must be the **same values** as before the incident.
- Flyway must say *"No migration necessary"*.
- Verify tenant isolation: tenant A sees only A; tenant B's id returns 403/404.

**Stop conditions:** step 3 or 4 of the procedure fails, Flyway applies
migrations, or any tenant sees another tenant's data.

---

### 4.7 RPO margin

If you are being asked "does CarbonFlow meet its 1-hour RPO?", the honest
answer is: **the approved interval equals the approved RPO, so there is zero
worst-case margin**, and the RPO is conditional on no consecutive backup
failures — a condition that is not monitored as an RPO statement. Arithmetic:
`docs/RECOVERY-CONTROLS-DESIGN.md` §30. Tightening the interval is a **business
decision**, not an engineering one.

---

## 5. Operations

### 5.1 Deploying cut in-flight requests

**Expected.** Graceful shutdown is not configured (F-07). Plan a short drain
window around restarts, or accept the truncation knowingly.

---

### 5.2 Evidence vanishes after a restart

**Check `CARBONFLOW_EVIDENCE_VAULT_DIR`.** The default is the **relative** path
`vault_storage`, so unset in production, evidence is written under the process
working directory and will not survive a redeploy or land on durable storage
(F-06).

---

### 5.3 Login lockout that nobody expected

**Observe:** `429 AUTH_THROTTLED` from one account.

**Means:** 5 failed logins in 15 minutes → 15-minute lockout.

**Do:** wait out the lockout, or correct the password. Counters are **per JVM
and lost on restart**, so a restart clears them — and, on a multi-instance
deployment, the limit is effectively per instance. There is no shared rate-limit
store.

---

### 5.4 The application looks healthy but nothing is working

`GET /api/v1/health` reports `UP`. That proves **the process is serving HTTP**,
nothing more. There is **no Spring Actuator dependency and no `management.*`
configuration** — no metrics, no dependency health, no probe manifest.

**Do:** check the database yourself, check the vault is writable, and read the
logs. CarbonFlow will not tell you (F-09).

---

## 6. Known traps summary

| Trap | Reality |
| --- | --- |
| "Backups are automated" | The scheduler cannot fire — F-10. Verify before relying on it. |
| "Alerts page someone" | Log lines only. No out-of-band delivery exists. |
| "The RPO is met" | Zero worst-case margin; unvalidated at production scale. |
| "Audit lock is immutable" | A governed state seal. A restore can rewind it. |
| "Retention meets the 7-year GHG baseline" | 30 days, and the 7-year question is unresolved. |
| "`server.ts` is part of the app" | Untracked Node/Express residue. Decommissioned in Phase 10.5. |
| "Cross-tenant id should be 403" | Byte-identical 404s are deliberate. |
| "Health UP means healthy" | It means the process answers HTTP. Nothing more. |
| "Evidence is encrypted at rest" | It is not. Protect the volume. |
| "I can set my reporting currency" | `V9` removed the frozen `'USD'` default, but **no DTO carries the field**. It is `NULL` ("not captured") until a follow-up lands. See `docs/GLOBALIZATION.md` |
| "Timestamps differ per user, that's localisation" | No. Rendering is UTC and locale-independent. If two users see different times for one instant, that is a defect — `src/services/format.ts` |

---

## 7. Related documents

| Document | Relevance |
| --- | --- |
| `docs/HANDOVER.md` | Current state, limitations, open findings |
| `docs/EXECUTION.md` | Starting the stack |
| `docs/DEPLOYMENT-SECURITY.md` | TLS, CORS, secrets |
| `docs/DATABASE.md` | Schema, migrations, tenant integrity |
| `docs/BACKUP-RECOVERY.md` | Full backup and recovery procedures and runbook |
| `docs/RECOVERY-CONTROLS-DESIGN.md` | Recovery control design and rationale |
| `docs/OPERATIONAL-RECOVERY-REQUIREMENTS.md` | Approved RTO/RPO and escalation |
| `docs/SECURITY.md`, `docs/SECURITY-THREAT-MODEL.md` | Security model |
| `docs/CALCULATIONS.md`, `docs/AUDIT_WORKFLOW.md` | Accounting and governance rules |
# PHASE 10.4.1 — Findings Resolution Report?
?
> **Phase:** 10.4.1 (disposition of the five Phase 10.4 findings)?
> **Predecessor:** `docs/PHASE10-NODE-OFF-TEST.md` §33.1?
> **Single final status:** **PASS** — all five findings **RESOLVED** (see §12)?
> **Phase 10.5:** **DO NOT START** (see §13)?
?
---?
?
## 1. Document Control?
?
| Field | Value |?
| --- | --- |?
| Document | `docs/PHASE10-FINDINGS-RESOLUTION.md` |?
| Phase | 10.4.1 — findings resolution |?
| Date of run | 2026-09-29 |?
| Repository | `carbonflow` |?
| Branch | `main` |?
| **START COMMIT** | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` ("Phase 8: Frontend Integration — wire React to the verified Java backend") |?
| **END COMMIT** | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` — **identical to START**; nothing was committed in this phase |?
| Working tree | 31 dirty entries, all intentional (16 modified, 15 untracked); 0 deletions |?
| Verdict | **PASS** |?
| Next phase | Phase 10.5 — Node decommission (**not executed, not started**) |?
?
### 1.1 Scope constraints honoured?
?
Every constraint set for this phase was honoured, and each is re-verified in §11:?
?
- **V1–V8 untouched.** Byte-identical to HEAD; no `V9` created; no migration file?
  edited, added or removed.?
- **Frozen surfaces untouched.** The 9-role x 44-permission RBAC matrix, the?
  10-state audit lifecycle, the Scope 2 rules and the accounting formulas are?
  unchanged. Finding 2 changed *fixture literals*, never the validation gate.?
- **`carbonflow.seed.demo-data` default remains `false`.** No production default?
  was relaxed.?
- **No production, user or business data deleted.** All runtime testing used a?
  disposable clean-room database (`carbonflow_nodeoff`), dropped and recreated?
  per run.?
- **No test weakened.** No assertion was relaxed, skipped, deleted or made?
  tolerant. 44 new/extended cases were added; every pre-existing case still?
  passes unchanged.?
- **No secret printed.** This report names variables and files only. The?
  secret-presence scan reports counts, never values.?
- **Phase 10.5 not started.** No `server/` file, no `server.ts`, no package?
  script and no dependency was removed or edited.?
?
### 1.2 How to read this document?
?
Every finding is treated with the same six headings, in this order:?
?
| Heading | Question it answers |?
| --- | --- |?
| **FINDING** | What was reported, and at what severity? |?
| **ROOT CAUSE** | Why did it happen? What mechanism, in which file? |?
| **FIX / DISPOSITION** | What changed, or why nothing needed to? |?
| **TEST** | Which automated tests prove it, by name? |?
| **DOCUMENT** | Which documents record the decision? |?
| **VERIFY** | The single word: `RESOLVED`, `NOT A PRODUCTION DEFECT — DOCUMENTED`, or `BLOCKED`. |?
?
Each finding ends in exactly one of those three words. No finding is left with?
"probably", "should be", or "appears fixed".?
?
### 1.3 Verification environment?
?
| Component | Version / state |?
| --- | --- |?
| Java | OpenJDK 21.0.12.1 |?
| Maven | 3.9.16 |?
| Node | v25.8.2 (frontend build/dev/test only — **backend OFF**) |?
| npm | 11.11.1 |?
| PostgreSQL | 18.6 |?
| Database under test | `carbonflow_nodeoff` — disposable clean-room, dropped and recreated for the official run |?
| Java backend | `carbonflow-backend-1.0.0-PRO.jar`, started from the verified build |?
| Frontend dev server | Vite on `127.0.0.1:5173` (permitted; it is not the Node backend) |?
| Node/Express backend | **OFF** — required condition, verified before and after the run |?
?
### 1.4 Evidence artifacts?
?
| File | Purpose |?
| --- | --- |?
| `%TEMP%\opencode\phase1041-mvn-verify.log` | `mvn clean verify` transcript (304 tests) |?
| `%TEMP%\opencode\phase1041-tsc.log` | `npx tsc --noEmit` transcript (0 bytes = no output) |?
| `%TEMP%\opencode\phase1041-frontend-tests.txt` | `npm run test:frontend` transcript (36 tests) |?
| `%TEMP%\opencode\phase1041-smoke2.ps1` | Node-off regression harness (79 checks) |?
| `%TEMP%\opencode\phase1041-smoke2-output.txt` | Full harness transcript |?
| `%TEMP%\opencode\phase1041-results.csv` | Machine-readable per-check results |?
| `%TEMP%\opencode\phase1041-java-run1.log` | Java boot log for the official run (Flyway, CORS, seeder, request handling) |?
| `%TEMP%\opencode\phase1041-java-run2.log` | Java boot log for the idempotency re-run against the same database |?
| `%TEMP%\opencode\phase1041-start-java.ps1` | Launcher: clean-room DB recreate + start |?
| `%TEMP%\opencode\phase1041-restart-java.ps1` | Launcher: start against the existing database (no recreate) |?
?
---?
?
## 2. Finding Severity Summary?
?
| # | Finding | Severity | Status |?
| --- | --- | --- | --- |?
| 1 | Demo seeder cannot self-heal an email collision | **Medium** | `RESOLVED` |?
| 2 | Demo-seeded tenant ids 404 on the detail endpoint | **Low** | `RESOLVED` |?
| 3 | `HttpMediaTypeNotSupportedException` mapped to 500 | **Low** | `RESOLVED` |?
| 4 | Two cutover documents absent | **Low** | `RESOLVED` |?
| 5 | CORS default allow-list trusts the Node origin | **Medium** | `RESOLVED` |?
?
**Counts: Critical 0, High 0, Medium 2, Low 3.** No finding was escalated or?
de-escalated relative to Phase 10.4; Phase 10.4 classified all five as?
non-blocking, and this phase agrees with that classification.?
?
---?
?
## 3. FINDING 1 — Demo seeder cannot self-heal an email collision?
?
**Severity: Medium. Status: `RESOLVED`.**?
?
### FINDING?
?
`DemoDataSeeder` used a blanket `ON CONFLICT DO NOTHING` when inserting demo?
users. When a demo email was already held by a *different* user id — for?
example an account self-registered before the seeder ran — the seed's own user?
row was silently skipped, the expected seed id never existed, and the following?
membership insert violated `organization_memberships_user_id_fkey`. A foreign-key?
violation is **not** suppressed by `ON CONFLICT`, so the seed aborted, leaving?
all three demo organizations with zero memberships.?
?
### ROOT CAUSE?
?
Two independent mechanisms combined, both in?
`backend-java/src/main/java/com/carbonflow/repository/DemoDataSeeder.java`:?
?
1. **`ON CONFLICT` without a conflict target suppresses unique violations, not?
   referential ones.** The user insert was?
   `INSERT INTO users (...) ON CONFLICT DO NOTHING`. With no target, PostgreSQL?
   swallows the `users_email_key` violation, so the caller is told the write?
   "succeeded" when nothing was written. The membership insert then used the?
   *expected* seed id rather than the id that actually exists, producing a?
   foreign-key error that no `ON CONFLICT` clause can mask.?
2. **The write never asked what was already there.** The seeder had no?
   read-before-write step, so it had no way to distinguish "already seeded by?
   me" (safe, skip) from "someone else owns this email" (must not touch) — both?
   looked identical to it.?
?
A contributing subtlety made detection harder: `users.email` is UNIQUE?
**case-sensitively**, while `IdentityRepository` resolves users with?
`lower(u.email) = lower(?)`. A row differing only in case is therefore a live?
collision for authentication but invisible to an exact-match check.?
?
### FIX / DISPOSITION?
?
**The seeder now refuses; it never repairs.** Each demo identity is classified by?
reading before writing, and every refusal is reported rather than absorbed:?
?
| Database state | Action |?
| --- | --- |?
| Email absent, seed id free | Insert the demo user and its memberships |?
| Email present **with the expected seed id** | Leave untouched — idempotent re-run |?
| Email present under a **different** id (case-insensitive match) | **`SEED CONFLICT`** — refuse; touch nothing |?
| Seed id present but holding a **different** email | **`SEED CONFLICT`** — refuse; touch nothing |?
| Membership pair already present | `ON CONFLICT (organization_id, user_id) DO NOTHING` — pair-duplicate guard only |?
| Known org id, drifted org name | Log at INFO, leave the name alone — **the id is the identity** |?
?
Consequences, each deliberate:?
?
- **Memberships are written only for identities the seeder itself confirmed.** This?
  is what makes the original foreign-key failure *unreachable*, rather than?
  merely less likely.?
- **The seeder never deletes, renames, or re-points an identity.** No user or?
  organization row is removed to make room; no email is rewritten; a foreign?
  user is never attached to a demo tenant. A refused identity is skipped and the?
  remaining demo identities still seed.?
- **A refusal is sticky and quiet.** A conflict found on one run is found again on?
  the next; it never oscillates and never escalates into a partial write.?
- **`ON CONFLICT (organization_id, user_id) DO NOTHING` is retained**, but now?
  only as a narrow pair-duplicate guard. It is no longer a way to hide a failed?
  write.?
- **The outcome is returned, not inferred.** `seed()` returns a?
  `SeedOutcome(skipped, organizations, users, memberships, conflicts)` whose?
  counts describe the state present *afterwards*; the disabled path returns?
  `SeedOutcome.disabled()`.?
- **The production gate is unchanged:** `carbonflow.seed.demo-data` still defaults?
  to `false`, and the seeder runs only on explicit opt-in.?
?
### TEST?
?
`DemoDataSeederTest` — **15 cases, all new**, named for the behaviour they pin:?
?
| Test | Pins |?
| --- | --- |?
| `freshSeedProducesTheCompleteFixture` | Happy path: 3 orgs, 5 users, 6 memberships |?
| `reRunningTheSeedIsANoOp` | Second run changes nothing |?
| `existingSeedUserWithTheExpectedIdIsLeftUntouched` | Idempotent path leaves rows alone |?
| `sameEmailUnderADifferentIdIsRefusedWithoutReplacingTheIdentity` | The exact Phase 10.4 failure — refused, not repaired |?
| `aSeedIdOccupiedByAnotherAccountIsAlsoRefused` | The mirror case: id squatted, different email |?
| `aRefusedIdentityDoesNotStopTheOtherDemoIdentities` | A conflict is contained, not fatal |?
| `aRefusedIdentityStaysRefusedOnEverySubsequentRun` | Refusal is deterministic and sticky |?
| `aMissingMembershipIsRepairedOnTheNextRun` | Self-healing still works where it is safe |?
| `aMembershipOwnedByTheSeededPairIsNeverDuplicated` | Pair-duplicate guard |?
| `realTenantsAndUsersAreNeverTouchedOrPulledIn` | **No cross-tenant attachment, no deletion** |?
| `theAuditorKeepsBothTenantsAndGainsNoThird` | Multi-tenant membership shape preserved |?
| `repeatedRunsNeverDuplicateThePlatformAdministrator` | No accumulation across runs |?
| `theDemoFixtureIsCompleteAndTenantConsistent` | Memberships are confined to the six seeder-owned ids |?
| `seedingStaysOffWhenTheGateIsClosed` | Default `false` really is off |?
| `theSeedIsOnlyEnabledByAnExplicitOptIn` | Opt-in required |?
?
**Runtime proof (live, not mocked).** The same jar was started twice against the?
*same* `carbonflow_nodeoff` database:?
?
```text?
run 1  INFO c.carbonflow.repository.DemoDataSeeder : Demo identities seeded (idempotent): 3 organizations, 5 users, 6 memberships.?
run 2  INFO c.carbonflow.repository.DemoDataSeeder : Demo identities seeded (idempotent): 3 organizations, 5 users, 6 memberships.?
SEED CONFLICT occurrences: 0        ERROR-level log lines: 0?
```?
?
`run 2` also logged `Current version of schema "public": 8` and?
`Schema "public" is up to date. No migration necessary.` — so the re-run was?
against an already-seeded database, which is the whole point.?
?
**Harness checks (7):** `F1-three-demo-orgs-present-and-ACTIVE`,?
`F1-demo-identity-admin@acmeglobal.com`, `...-manager@acmeglobal.com`,?
`...-auditor@ey-assurance.com`, `...-admin@apexcorp.com`, `F1-switch-tenant-works`,?
`F1-platform-admin-not-switchable` — all PASS.?
?
### DOCUMENT?
?
- `docs/DECISIONS.md` **ADR-021**, decision 2 ("The demo seeder refuses; it never?
  repairs").?
- `backend-java/src/main/resources/application.properties` — the seeding gate?
  comment block, unchanged in default.?
?
### VERIFY?
?
`RESOLVED`?
?
---?
?
## 4. FINDING 2 — Demo-seeded tenant ids 404 on the detail endpoint?
?
**Severity: Low. Status: `RESOLVED`.**?
?
### FINDING?
?
`GET /api/v1/platform/tenants/{id}` returned `404` for the demo-seeded?
organizations, while `GET /api/v1/platform/tenants` happily listed them. The?
asymmetry is the defect: the same organization was both present and absent.?
?
### ROOT CAUSE?
?
The `SeedIds` literals were not RFC-4122 conformant. The demo organization ids?
used the shape `22222222-2222-2222-2222-…`, and two separate fields are wrong:?
?
- **Group 3 is the version**, which RFC 4122 restricts to `1`–`5`. `2222` is not?
  a legal version.?
- **Group 4 is the variant**, whose leading bits must be `8`, `9`, `a` or `b`.?
  `2222` does not satisfy that.?
?
`UuidContract.isNodeUuid` correctly rejects such ids, so the detail endpoint's?
validation gate turned them into `404`. The list endpoint did not validate, which?
is why only the detail path failed. Registration-created organizations, whose ids?
are genuine random UUIDs, behaved correctly throughout.?
?
### FIX / DISPOSITION?
?
`SeedIds` literals are now pinned to **version 4 / variant 8**, keeping the?
readable prefixes so the ids stay recognisable in a database dump:?
?
| Entity | Prefix | Example |?
| --- | --- | --- |?
| Organizations | `22222222-2222-4222-8222-` | `22222222-2222-4222-8222-222222222201` |?
| Users | `33333333-3333-4333-8333-` | `33333333-3333-4333-8333-333333333301` |?
| Memberships | `44444444-4444-4444-8444-` | `44444444-4444-4444-8444-444444444401` |?
?
Two things were deliberately **not** done:?
?
- **The validation gate was not weakened.** `UuidContract` is untouched. The fix?
  moved the data into compliance; it did not relax the rule to admit the old?
  shapes. This is the load-bearing decision in this finding.?
- **No migration was added.** These are development fixtures created by an?
  opt-in seeder, not production rows; a migration would have been a schema change?
  to accommodate a literal.?
?
### TEST?
?
`PlatformTenantTest` — **17 cases** (8 new, 9 pre-existing and unchanged):?
?
| Test | Pins |?
| --- | --- |?
| `everyDemoSeedIdSatisfiesTheUuidContract` | All 14 demo ids are conformant |?
| `platformAdminCanReadEveryDemoTenantById` | acme, apex and platform all return 200 with the right id |?
| `platformAdminCanReadATenantRegisteredOutsideItsOwnContext` | Non-demo tenants still work |?
| `tenantDetailStillCollapsesMalformedAndUnknownIdsIntoTheSame404` | **Validation not weakened** — the gate still rejects |?
| `companyAdminCannotReadTenantDetails` | 403, authorization unchanged |?
| `anonymousTenantDetailIsUnauthorized` | 401, unchanged |?
| `formEncodedBodyOnAJsonEndpointIs415Not500` | See Finding 3 |?
| `jsonBodyOnTheSameEndpointStillWorks` | See Finding 3 |?
| (9 pre-existing) | Lifecycle, registration, suspension, rejection |?
?
**Harness checks (8):** `F2-tenant-list-reachable`, `F2-tenant-detail-acme-200`,?
`...-apex-200`, `...-platform-200` (all `HTTP 200`, `status=ACTIVE` — all three?
were `404` before the fix), `F2-legacy-shape-still-404` (the old?
`22222222-2222-2222-2222-…` shape still answers `404 ORGANIZATION_NOT_FOUND`),?
`F2-malformed-and-unknown-collapse-to-404`, `F2-company-admin-403`,?
`F2-anonymous-401` — all PASS.?
?
The two rows that matter most are paired deliberately: the fix made the *valid*?
id work and the *invalid* id keep failing. A fix that had relaxed the gate would?
have passed the first and failed the second.?
?
### DOCUMENT?
?
- `docs/DECISIONS.md` **ADR-021**, decision 4 ("Demo fixture ids are RFC-4122?
  conformant"), which records explicitly that the gate was not weakened.?
- `backend-java/src/main/java/com/carbonflow/repository/SeedIds.java` class?
  javadoc.?
?
### VERIFY?
?
`RESOLVED`?
?
---?
?
## 5. FINDING 3 — `HttpMediaTypeNotSupportedException` mapped to 500?
?
**Severity: Low. Status: `RESOLVED`.**?
?
### FINDING?
?
A form-encoded body sent to `POST /api/v1/platform/tenants/{id}/suspend` returned?
`500 INTERNAL_ERROR` where `415 Unsupported Media Type` is correct. This was the?
**only** `ERROR`-level entry in the entire Phase 10.4 run, and it carried a full?
`HttpMediaTypeNotSupportedException` stack trace into the log.?
?
The React client never sends that content type, so the path was not reachable?
through the UI — but a correct client, a proxy, or a curl-based integration would?
all have seen a server fault for what is plainly a client error.?
?
### ROOT CAUSE?
?
The exception itself was raised *correctly* by Spring MVC: content negotiation?
fails during argument resolution, before the controller method body is entered.?
The defect was purely in the mapping layer. `ApiExceptionHandler` had no?
`@ExceptionHandler` for `HttpMediaTypeNotSupportedException`, so the exception?
fell through to the generic `Exception` branch, which logs full detail and returns?
`500 INTERNAL_ERROR`. The same fall-through is what produced the `ERROR` log line.?
?
### FIX / DISPOSITION?
?
One **specific** handler was added:?
?
```java?
@ExceptionHandler(HttpMediaTypeNotSupportedException.class)?
public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType(?
        HttpMediaTypeNotSupportedException ex) {?
    return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",?
            "The request content type is not supported by this endpoint. Use application/json.");?
}?
```?
?
Properties of the fix, each of which was a stated requirement:?
?
- **Specific exception type only.** One type, one handler. A blanket?
  `@ExceptionHandler(Exception.class)` returning 415 would have been?
  categorically wrong — it would misreport genuine server faults as client?
  errors — and was deliberately **not** introduced.?
- **The platform envelope is preserved**, so a client parsing errors sees the same?
  `{success, error:{code, message}}` shape as every other error.?
- **The message is fixed and safe.** It names the fix ("use application/json") and?
  leaks nothing: no stack frames, no SQL, no file paths, no framework or?
  application class names. The `ex` parameter is deliberately unused, so the?
  exception's own text — which does contain the caller's content type and?
  converter internals — never reaches the response.?
- **No other branch changed.** Malformed JSON still yields `400 INVALID_JSON` and?
  unrelated failures keep their existing classification.?
?
### TEST?
?
`ApiExceptionHandlerTest` — **12 cases** (6 new, 6 pre-existing and unchanged):?
?
| Test | Pins |?
| --- | --- |?
| `unsupportedContentTypeYields415Envelope` | 415 with the right code |?
| `unsupportedXmlContentTypeYields415Envelope` | Not just `form-urlencoded` |?
| `unsupportedMediaTypeEnvelopeLeaksNoInternals` | No class names, no stack frames |?
| `supportedJsonContentTypeStillSucceeds` | `application/json` still reaches the handler |?
| `malformedJsonStillYields400Not415` | The two client errors stay distinct |?
| `unrelatedFailureIsNotReclassifiedByTheMediaTypeHandler` | **The anti-catch-all guard** |?
?
`PlatformTenantTest` adds the end-to-end pair on a real endpoint:?
`formEncodedBodyOnAJsonEndpointIs415Not500` and?
`jsonBodyOnTheSameEndpointStillWorks`.?
?
**Harness checks (5):** `F3-form-encoded-body-415`,?
`F3-415-envelope-code`, `F3-415-no-internals`,?
`F3-json-body-accepted-and-transitions`, `F3-malformed-json-400` — all PASS.?
?
**Log-level proof.** Phase 10.4's headline symptom was a single `ERROR` entry in?
the run. In the Phase 10.4.1 run log:?
?
```text?
ERROR-level lines (case-sensitive level column): 0?
"Unhandled exception" occurrences:                0?
HttpMediaTypeNotSupported occurrences:           0?
```?
?
The only `WARN` lines are 6 pre-existing Flyway notices?
(`DB: there is already a transaction in progress`, PostgreSQL 18.6 newer than the?
bundled Flyway's tested range). Neither is related to this finding.?
?
### DOCUMENT?
?
- `docs/DECISIONS.md` **ADR-021**, decision 3 ("Exception mapping stays specific;?
  no blanket handler is introduced").?
- `backend-java/src/main/java/com/carbonflow/config/ApiExceptionHandler.java`?
  javadoc, which records why the type is handled and why a catch-all was refused.?
?
### VERIFY?
?
`RESOLVED`?
?
---?
?
## 6. FINDING 4 — Two prior-phase cutover documents are absent?
?
**Severity: Low. Status: `RESOLVED`.**?
?
### FINDING?
?
`docs/PHASE10-CUTOVER-GAP-REPORT.md` and `docs/PHASE10-FRONTEND-CUTOVER.md` did?
not exist, so any claim attributed to them could not be cross-checked.?
?
### ROOT CAUSE?
?
They were never written. This is a process gap, not a code defect, and it has a?
hard consequence for the remedy: **there is no original to recover.** Git history?
contains 9 commits, none of which is a Phase 9 or Phase 10 commit, and no Phase?
10.1, 10.2 or 10.3 artifact exists anywhere in the repository. A faithful?
"restoration" is therefore impossible; only a fresh, evidence-based analysis is?
honest.?
?
### FIX / DISPOSITION?
?
Both documents were **created from evidence**, and each is explicit about being a?
reconstruction rather than a recovery:?
?
- `docs/PHASE10-CUTOVER-GAP-REPORT.md` — Node-vs-Java capability comparison, an?
  11-item gap register (`G1`–`G11`) with severity and disposition, an explicit?
  "not gaps" section, limitations, and the commands that re-derive every number.?
- `docs/PHASE10-FRONTEND-CUTOVER.md` — the frontend's cutover state, its single?
  API surface, the 28 endpoints it calls, its auth-state guarantees, a 4-item gap?
  register (`F-G1`–`F-G4`), and what the document explicitly does **not** claim.?
?
Deliberate properties of both:?
?
- **No fabricated history, commands, or results.** Every claim is either a?
  `file:line` citation or a command that reproduces it.?
- **Evidence is labelled** `[HISTORICAL]` (from committed history or an existing?
  document) or `[CURRENT]` (observed during this phase), so a reader can tell?
  record from observation.?
- **Limitations are stated**, including the ones that weaken the documents: no?
  Phase 10.1–10.3 record exists; the API comparison is by reading mappings, not by?
  executing all 37 Node routes; no browser was attached; severities are judgement,?
  not measurement.?
- **Gap `G10` was closed here.** ADR-013 item 3 still named `CORS_ORIGINS` as the?
  CORS variable, which would have left an operator setting a dead variable. That?
  cross-reference is now annotated as superseded, and the live variable is?
  documented (§7).?
?
**What this finding's resolution does *not* claim:** it does not reconstruct the?
Phase 10.1–10.3 record, because that record does not exist. The gap registers?
describe the repository's state on 2026-09-29, and their severities are about?
cutover readiness.?
?
### TEST?
?
Documentation change — no runtime test applies, and none is claimed. Verification?
is by re-derivation, and each document carries its own command list:?
?
```text?
git rev-parse HEAD                                     # 4cc8f30...?
git rev-list --count HEAD                              # 9?
(Get-ChildItem server -File).Count                      # 27?
(Select-String server/routes.ts -Pattern "apiRouter\.(get|post|put|delete|patch)\('").Count   # 37?
(Get-ChildItem backend-java/.../controller -File).Count  # 23?
npx tsc --noEmit                                       # 0 errors?
npm run test:frontend                                  # 36/36?
```?
?
The numeric claims in both documents were re-derived before this report was?
written, not copied from Phase 10.4.?
?
### DOCUMENT?
?
- `docs/PHASE10-CUTOVER-GAP-REPORT.md` (new)?
- `docs/PHASE10-FRONTEND-CUTOVER.md` (new)?
- `docs/DECISIONS.md` **ADR-021**, decision 5 ("Absent documents are written from?
  evidence, never from recollection").?
?
### VERIFY?
?
`RESOLVED`?
?
---?
?
## 7. FINDING 5 — CORS default allow-list still contains the Node origin?
?
**Severity: Medium. Status: `RESOLVED`.**?
?
### FINDING?
?
`application.properties` set?
?
```properties?
carbonflow.cors.allowed-origins=${CORS_ORIGINS:http://localhost:3000,http://localhost:5173}?
```?
?
The allow-list itself was enforced correctly — `http://localhost:5173` was allowed?
and `http://evil.example` was refused. The defect is the **default**: a deployment?
that configured nothing inherited two localhost origins, one of them the retired?
**Node/Express** origin.?
?
### ROOT CAUSE?
?
An inline default in a shipped property file. Two distinct problems follow:?
?
1. **A production deployment could trust `http://localhost:3000` by doing nothing.**?
   Any process able to bind that port on the host — including a malicious local?
   program, or a browser extension or user script running on `localhost:3000` —?
   would be a credentialed, trusted origin. `allowCredentials` is always `true`?
   for this API, so the browser would attach the session on the attacker's?
   request.?
2. **The variable name was undocumented and is now retired.** `CORS_ORIGINS` was?
   referenced only inside this one property line. ADR-013 item 3 recorded it as?
   *the* CORS variable. An operator setting `CORS_ORIGINS` after this change?
   would get a fail-closed backend and no error.?
?
The localhost entries were originally load-bearing for local development, which is?
why they were not simply deleted — that is Finding 5's actual shape: a?
*deployment* default doing a *development* job.?
?
### FIX / DISPOSITION?
?
The allow-list is now **environment-owned and fail-closed**, and the development?
concern moved to a development-only profile.?
?
| Change | Detail |?
| --- | --- |?
| Shipped default is empty | `carbonflow.cors.allowed-origins=${CARBONFLOW_CORS_ALLOWED_ORIGINS:}` — with nothing set, **no browser origin is trusted** |?
| Unset is safe and visible | No `Access-Control-Allow-Origin` is emitted; a `WARN` names the variable |?
| Localhost moved to the `dev` profile | New `application-dev.properties` supplies `http://localhost:5173,http://localhost:3000` |?
| Wildcard refused at startup | `*` throws `IllegalStateException` — `allowCredentials` is always `true` and browsers reject that pairing, so failing loudly beats degrading silently |?
| Testable seam | The list is built in one package-private static method, `SecurityConfig.corsConfigurationSource(String)`, callable directly from a unit test |?
| `CORS_ORIGINS` retired | Annotated as superseded in ADR-013 item 3; the new variable is documented in both security documents |?
?
**Stated precisely, because it matters:** the retired Node origin?
`http://localhost:3000` is **refused under the shipped default** — that is what?
the runtime rehearsal proved. Under the opt-in `dev` profile it is still?
accepted, deliberately, so a developer debugging a same-machine client is not?
broken. That is a development-profile affordance and not a trust relationship,?
and `application-dev.properties` says so in its own comments. The `dev` profile?
is never active in a deployment.?
?
### TEST?
?
`CorsConfigurationTest` — **9 cases, all new**, unit-level on the extracted seam:?
?
| Test | Pins |?
| --- | --- |?
| `productionDefaultTrustsNoOriginAtAll` | The core claim: unset trusts nothing |?
| `explicitlyConfiguredProductionOriginIsAllowed` | A real configured origin works |?
| `unconfiguredOriginIsNotAllowed` | Unlisted origins refused |?
| `developmentValueAllowsTheLocalhostDevServerOrigins` | Local dev still works |?
| `credentialsAreAllowedOnlyForListedOrigins` | No credentials for unlisted origins |?
| `authorizationAndContentTypeHeadersAreAllowed` | Preflight allows the `Authorization` header |?
| `multipleConfiguredOriginsAreAllHonoured` | Comma-separated list parses |?
| `wildcardOriginIsRefusedAtStartup` | `*` throws rather than degrades |?
| `nullAndBlankValuesDegradeToAnEmptyAllowList` | Null/blank cannot crash startup into a permissive state |?
?
`CorsOriginIntegrationTest` — **6 cases, all new**, through the full filter chain:?
?
| Test | Pins |?
| --- | --- |?
| `developmentPreflightFromTheViteDevServerIsAllowedWithCredentials` | Local dev unblocked |?
| `developmentPreflightFromTheRetiredNodeOriginIsStillAllowed` | The `dev`-profile affordance, pinned deliberately |?
| `unlistedOriginPreflightReceivesNoAllowOriginHeader` | No header leak |?
| `unlistedOriginIsRefusedEvenOnAnAuthenticatedRequest` | A valid token does not buy a trusted origin |?
| `authenticatedRequestFromAConfiguredOriginKeepsBearerAndCredentials` | The allowed path keeps working |?
| `sameOriginRequestNeedsNoCorsHeaders` | Non-browser clients unaffected |?
?
**Harness checks (9):** `F5-preflight-configured-origin-allowed`,?
`F5-preflight-credentials-true`, `F5-preflight-authorization-header-allowed`,?
`F5-retired-node-origin-REFUSED`, `F5-unlisted-origin-REFUSED`,?
`F5-authenticated-call-configured-origin-echoed`,?
`F5-authenticated-call-retired-origin-no-ACAO`,?
`F5-same-origin-call-needs-no-ACAO`, `F5-react-dev-server-still-served` — all?
PASS, with Node/Express off for the whole run.?
?
The decisive pair is `F5-retired-node-origin-REFUSED` together with?
`F5-unlisted-origin-REFUSED`: the Node origin is now refused because it is *not?
listed*, not because a special case forbids it. There is no Node-specific rule?
left in the code.?
?
### DOCUMENT?
?
- `docs/DECISIONS.md` **ADR-021**, decision 1.?
- `docs/DECISIONS.md` ADR-013 item 3 — annotated `SUPERSEDED by ADR-021`, naming?
  the retired variable and its consequence.?
- `docs/DEPLOYMENT-SECURITY.md` — new "CORS Allow-List" section, plus rows in?
  both variable tables.?
- `docs/SECRETS.md` — `CARBONFLOW_CORS_ALLOWED_ORIGINS` and?
  `VITE_JAVA_API_BASE_URL` rows, both marked as non-secrets.?
- `backend-java/src/main/resources/application-dev.properties` — new; its comment?
  block states the profile is for local development and not a trust relationship.?
?
### VERIFY?
?
`RESOLVED`?
?
---?
?
## 8. Verification Gates?
?
### 8.1 Build and test gates?
?
| Gate | Command | Result |?
| --- | --- | --- |?
| Java | `mvn clean verify` | **304 tests, 0 failures, 0 errors, 0 skipped — BUILD SUCCESS**, 0 `[ERROR]` lines, exit 0 |?
| TypeScript | `npx tsc --noEmit` | **0 errors**, exit 0 (0-byte log) |?
| Frontend | `npm run test:frontend` | **36/36 passed, 0 failed**, exit 0 |?
?
Test count moved **260 → 304 (+44)**, all additions:?
?
| Suite | Before | After | Delta |?
| --- | --- | --- | --- |?
| `DemoDataSeederTest` | 0 (new) | 15 | +15 |?
| `CorsConfigurationTest` | 0 (new) | 9 | +9 |?
| `CorsOriginIntegrationTest` | 0 (new) | 6 | +6 |?
| `ApiExceptionHandlerTest` | 6 | 12 | +6 |?
| `PlatformTenantTest` | 9 | 17 | +8 |?
| **Total** | **260** | **304** | **+44** |?
?
44 surefire reports, all green. The frontend suite is unchanged at 36/36 because?
the frontend was not modified in this phase — it is a regression gate here, not?
new coverage.?
?
### 8.2 Node-Off regression?
?
`docs/PHASE10-NODE-OFF-TEST.md` §7 defines Node-off as: zero `node.exe` processes?
matching `server.ts`/`server.cjs`, port 3000 not listening, and?
`GET localhost:3000/api/v1/health` unreachable. All three were asserted at the?
**start and the end** of the run.?
?
| Category | Checks | Result |?
| --- | --- | --- |?
| Node-off proof | 5 | PASS |?
| Stack + security headers | 8 | PASS |?
| Finding 1 (demo identities) | 7 | PASS |?
| Finding 2 (tenant detail) | 8 | PASS |?
| Finding 3 (415 mapping) | 5 | PASS |?
| Finding 5 (CORS) | 9 | PASS |?
| Lifecycle (suspend/re-approve/restore) | 3 | PASS |?
| Security / RBAC | 16 | PASS |?
| Contract regression | 18 | PASS |?
| **Total** | **79** | **79 PASS / 0 FAIL** |?
?
Two harness defects were found and fixed during this phase's own verification —?
both were **my test's** error, not the product's, and both are recorded here?
because a passing number is worthless without knowing what was measured:?
?
- The first draft asserted `Referrer-Policy: no-referrer` and treated a?
  platform-admin `GET /users` 200 as a failure. Both expectations were wrong?
  against the shipped code: the filter sets `strict-origin-when-cross-origin`,?
  and `PLATFORM_ADMIN` legitimately holds `users.read` (`RolePermissions.java:228`).?
  The corrected check asserts `GET /users` returns **only the platform tenant's own?
  single row**, which is a stronger statement than the 403 it replaced.?
- A destructive control call suspended the Apex demo tenant, which broke every?
  later login to it. The harness was reordered to run the tenant tests first and?
  to **restore state** afterwards, turning the accident into three additional?
  checks (`LIFECYCLE-*`) that verify suspension blocks login and re-approval?
  restores it.?
?
### 8.3 Security regression?
?
16 dedicated `SEC-*` checks, all PASS. Highlights, each confirmed at runtime:?
?
| Property | Evidence |?
| --- | --- |?
| Anonymous 401 carries the platform envelope | `HTTP 401`, `error.code=UNAUTHORIZED`, 104-byte body |?
| Query-string tokens refused | `GET /auth/me?token=<valid>` → **401** |?
| Bogus bearer refused | `Authorization: Bearer not.a.jwt` → **401** |?
| Refresh rotation | rotated token differs from the presented one |?
| Refresh reuse detection | Replaying a rotated token revokes the **whole family** (`AuthService.java:230-234`) — stricter than Node, which leaves the replacement usable |?
| Logout revokes; post-logout refresh | `200` with `revoked=true`, then **401** |?
| Login throttle | repeated failures → **429 `AUTH_THROTTLED`** |?
| Tenant boundary | company admin on platform tenant detail → **403** |?
| Platform-admin scoping | `GET /users` returns **exactly one row**, its own org's — no cross-tenant leak |?
| Least privilege | manager: `users.read` → 200, `users.create` with a **valid** body → 403 |?
| Platform role not switchable | `switch-tenant-or-role` to `PLATFORM_ADMIN` → **403** |?
| Method / path errors | `405`, `404` in envelope |?
| Security headers | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-XSS-Protection: 1; mode=block`, `Cache-Control: no-store`, no HSTS over cleartext |?
| Tenant lifecycle enforcement | suspended org → login `403 ORGANIZATION_NOT_ACTIVE` |?
?
### 8.4 Database and Flyway regression?
?
| Check | Result |?
| --- | --- |?
| `git diff --exit-code -- db/migration` | **UNCHANGED vs HEAD** |?
| `git status --porcelain -- db/` | **clean** — no modified, no untracked |?
| Migration files | Exactly `V1` … `V8`; **no `V9` created** |?
| SHA-256 of V1–V8 | Recorded and identical to the pre-phase measurement (V1 `00433C77…`, V8 `9A55A252…`) |?
| `flyway_schema_history` | 8 rows, `success = t` on every row, descriptions matching V1–V8 |?
| Fresh-schema boot | `Successfully applied 8 migrations to schema "public", now at version v8` |?
| Re-run boot | `Current version of schema "public": 8` / `Schema "public" is up to date. No migration necessary.` |?
| Fixture state | 3 organizations, 5 users, 6 memberships; all three organizations `ACTIVE` |?
| Business data | Nothing deleted; the target was a disposable clean-room database recreated per run |?
?
### 8.5 Secret hygiene?
?
Scanned the tracked diff with five rules (JWT secret literal, DB password /?
connection-string literal, `Bearer` JWT literal, cloud API key literal, private?
key block). **All five: 0 matches.** No secret value is printed anywhere in this?
report — variable names and file paths only. `.env` is git-ignored (`.gitignore`:?
`.env*`).?
?
### 8.6 Git safety?
?
| Check | Result |?
| --- | --- |?
| START COMMIT | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` |?
| END COMMIT | `4cc8f30d1f9dbcd2ae2edae354f569aecb345852` — **unchanged** |?
| Commits made | **0** — history untouched |?
| Deleted or renamed tracked files | **0** |?
| Files touched outside `backend-java/src/**` and `docs/**` | **0** |?
| `db/migration` | untouched |?
?
---?
?
## 9. Files Changed by Phase 10.4.1?
?
### 9.1 Modified (7)?
?
| File | Finding |?
| --- | --- |?
| `backend-java/src/main/java/com/carbonflow/repository/DemoDataSeeder.java` | 1 |?
| `backend-java/src/main/java/com/carbonflow/repository/SeedIds.java` | 2 |?
| `backend-java/src/main/java/com/carbonflow/config/ApiExceptionHandler.java` | 3 |?
| `backend-java/src/main/java/com/carbonflow/config/SecurityConfig.java` | 5 |?
| `backend-java/src/main/resources/application.properties` | 5 |?
| `backend-java/src/test/java/com/carbonflow/config/ApiExceptionHandlerTest.java` | 3 |?
| `backend-java/src/test/java/com/carbonflow/controller/PlatformTenantTest.java` | 2, 3 |?
?
### 9.2 Added (10)?
?
| File | Finding |?
| --- | --- |?
| `backend-java/src/main/resources/application-dev.properties` | 5 |?
| `backend-java/src/test/java/com/carbonflow/repository/DemoDataSeederTest.java` | 1 |?
| `backend-java/src/test/java/com/carbonflow/config/CorsConfigurationTest.java` | 5 |?
| `backend-java/src/test/java/com/carbonflow/config/CorsOriginIntegrationTest.java` | 5 |?
| `docs/PHASE10-FINDINGS-RESOLUTION.md` | this document |?
| `docs/PHASE10-CUTOVER-GAP-REPORT.md` | 4 |?
| `docs/PHASE10-FRONTEND-CUTOVER.md` | 4 |?
| `docs/DECISIONS.md` (ADR-021 appended; ADR-013 item 3 annotated) | 1–5 |?
| `docs/DEPLOYMENT-SECURITY.md` (CORS Allow-List section + table rows) | 5 |?
| `docs/SECRETS.md` (two variable rows) | 5 |?
?
### 9.3 Annotated, not substantively changed (1)?
?
`docs/PHASE10-NODE-OFF-TEST.md` — three amendment blocks added (header pointer,?
§32 CORS note, §33.1 disposition table) plus two corrected bullets in §33.5. The?
Phase 10.4 findings text is preserved **verbatim** as the record of what that run?
observed; the amendments are clearly marked and dated.?
?
### 9.4 Pre-existing uncommitted work, not touched by this phase?
?
Phase 9 and 10.4 left changes uncommitted in the working tree. They are listed?
for completeness and were **not** modified here: `JwtAuthenticationFilter.java`,?
`AuthService.java`, `SecurityHeadersFilter.java`, `LoginThrottle.java`,?
`SecurityChainIntegrationTest.java`, `AuthServiceTest.java`, `AuthHardeningTest.java`,?
`LoginThrottleTest.java`, `CalculationBatchTest.java`, `CalculationRunTest.java`,?
`EmissionLedgerTest.java`, `ReferenceDataTest.java`, `docs/PHASE9-BASELINE.md`,?
`docs/SECURITY-THREAT-MODEL.md`.?
?
### 9.5 Incidental encoding repair in `docs/DECISIONS.md` (disclosed)?
?
This is not one of the five findings and it is not a code change. It is disclosed?
here because it modified lines outside the intended edits, and because the first?
attempt at fixing it made things worse before it was caught and corrected.?
?
- **What was found.** `docs/DECISIONS.md` as committed at START COMMIT was **not?
  valid UTF-8**. It contained 21 stray single bytes — 17 x `0x97`, 2 x `0x96`,?
  2 x `0xA7` — which are the Windows-1252 em dash, en dash and section sign?
  written into a file that is otherwise UTF-8. Strict UTF-8 decoding of the HEAD?
  blob fails on exactly those 21 sites.?
- **What went wrong.** The first edit to that file re-encoded it, and each stray?
  byte was replaced by the Unicode replacement character `U+FFFD`. That silently?
  damaged **16 pre-existing lines** (218, 220, 223, 224, 226, 229, 235, 237, 239,?
  241, 242, 245, 249, 251, 256, 257), rendering as a broken glyph where an em?
  dash belongs — for example the ADR-018 heading became?
  `## ADR-018: Reporting & Analytics Aggregation ? Persisted Records Only`.?
- **What was done.** Those 16 lines were restored from the HEAD blob, decoding?
  valid UTF-8 sequences as UTF-8 and the stray bytes as Windows-1252. The?
  characters are therefore the author's own characters: nothing was reworded,?
  dropped or invented. A backup was kept throughout, and an earlier repair?
  attempt that damaged line 1 was reverted from it before the correct repair was?
  applied.?
- **Verified.** Line-by-line comparison of HEAD against the working tree, after?
  normalising **only** the three smart characters to ASCII equivalents, shows?
  **exactly one** pre-existing line with a genuine content difference: line 123,?
  the intended ADR-013 annotation. The 16 repaired lines are character-identical?
  to HEAD. Lines 260-273 are the new ADR-021 block. `U+FFFD` count is now **0**?
  and the file decodes as strict UTF-8.?
- **Why the bytes still differ from HEAD.** HEAD stores those characters as raw?
  Windows-1252 bytes; the repaired file stores them as proper UTF-8. Git?
  consequently still reports the 16 lines as changed. The rendered text is?
  identical, and leaving the file as valid UTF-8 means the next edit to it cannot?
  silently corrupt it again. Restoring the original invalid bytes was the only?
  alternative, and it would have preserved a latent trap.?
- **Scope of the scan.** All **32** changed and added files were checked for the?
  same defect. `docs/DECISIONS.md` was the only one affected; the other 31?
  contain no `U+FFFD` and all decode as strict UTF-8.?
?
---?
?
## 10. Observations Recorded but Deliberately Not Fixed?
?
These were found during this phase's verification. Each is **out of the five?
findings' scope**, and each is reported rather than changed. None is a Phase?
10.4.1 finding and none is a cutover blocker.?
?
1. **V2 seeds GWP-set ids that the UUID gate rejects.** `V2__seed_reference_data.sql`?
   seeds `gwp_sets` ids with a variant nibble of `2` (for example?
   `22222222-2222-2222-2222-…`), which `UuidContract.isNodeUuid` rejects — the same?
   class of problem as Finding 2, but in a **frozen** migration rather than in a?
   seeder literal. `gwpSetId` is client-supplied on `POST /calculations/run`, so?
   seeded GWP ids are effectively unaddressable through that path. Existing tests?
   pass random UUIDs and never exercise the seeded ids. **Not fixed**: V2 is?
   frozen, and changing seeded reference data is a schema-decision, not a?
   Phase 10.4.1 fix. Recommended for Phase 10.5.?
2. **`SecurityHeadersFilter` sets `X-Frame-Options: SAMEORIGIN`, but the live?
   response is `DENY`.** Spring Security's default header writer runs later in the?
   filter chain and overwrites it. `DENY` is the **stricter** value and is what?
   Phase 10.4 §32 recorded, so behaviour is correct and no test fails. **Not?
   fixed**: the filter is a Phase 10.4 artifact and this is a?
   documentation-accuracy issue, not a defect. Recorded so the mismatch between?
   the filter's javadoc and the wire value is not mistaken later for a regression.?
3. **Body validation runs before `@PreAuthorize`.** Spring resolves and validates?
   handler arguments before invoking the secured method, so an unauthorized caller?
   sending an *invalid* body receives `400 VALIDATION_ERROR` where `403` would be?
   expected. Authorization is still fully enforced — the same call with a valid?
   body returns `403` (verified). **Not fixed**: this is standard Spring?
   behaviour, changing it means a custom argument-validation ordering, and no?
   data or capability is exposed.?
?
---?
?
## 11. Blockers and Not-Run Items?
?
| Item | Status | Reason |?
| --- | --- | --- |?
| Browser UAT | **NOT AVAILABLE** | No desktop browser was connected to this environment. No browser-based claim is made anywhere in this report. All verification is HTTP-, source- or database-level. Never counted as PASS. |?
| Node oracle suites (`test:security`, `test:persistence`) | **NOT RUN DURING NODE-OFF** | Both suites boot `server.ts`. Running them would violate the Node-off condition that this phase exists to test. No Node-oracle result is claimed. The aggregate `npm test` was consequently also not run, since it chains `test:security`. |?
?
Neither item is a defect and neither blocks the cutover. Both are recorded so?
that neither is ever mistaken for a pass.?
?
---?
?
## 12. Per-Finding Final Status?
?
| # | Finding | Severity | Status | Primary proof |?
| --- | --- | --- | --- | --- |?
| 1 | Demo seeder cannot self-heal an email collision | Medium | **`RESOLVED`** | `DemoDataSeederTest` 15/15 + two live boots on the same DB, 0 `SEED CONFLICT` |?
| 2 | Demo-seeded tenant ids 404 on the detail endpoint | Low | **`RESOLVED`** | `PlatformTenantTest` 17/17 + 8 `F2-*` checks; legacy shape still 404 |?
| 3 | `HttpMediaTypeNotSupportedException` mapped to 500 | Low | **`RESOLVED`** | `ApiExceptionHandlerTest` 12/12 + 5 `F3-*` checks; 0 ERROR-level log lines |?
| 4 | Two prior-phase cutover documents absent | Low | **`RESOLVED`** | Both created from evidence, marked reconstructed, with limitations |?
| 5 | CORS default allow-list trusts the Node origin | Medium | **`RESOLVED`** | `CorsConfigurationTest` 9/9 + `CorsOriginIntegrationTest` 6/6 + 9 `F5-*` checks |?
?
**All five findings are `RESOLVED`.** None is `NOT A PRODUCTION DEFECT`, and none?
is `BLOCKED`.?
?
---?
?
## 13. Final Status Block?
?
```text?
================================================================================?
PHASE 10.4.1 - FINDINGS RESOLUTION - FINAL STATUS?
================================================================================?
?
START COMMIT : 4cc8f30d1f9dbcd2ae2edae354f569aecb345852  (main)?
END COMMIT   : 4cc8f30d1f9dbcd2ae2edae354f569aecb345852  (unchanged, 0 commits)?
DATE         : 2026-09-29?
PREDECESSOR  : docs/PHASE10-NODE-OFF-TEST.md  (Phase 10.4, PASS)?
?
---------------------------------------------------------------- PER-FINDING ----?
FINDING 1  DemoDataSeeder email collision / silent FK failure   RESOLVED?
FINDING 2  Demo-seeded tenant ids 404 on detail endpoint         RESOLVED?
FINDING 3  HttpMediaTypeNotSupportedException mapped to 500      RESOLVED?
FINDING 4  Two cutover documents absent                          RESOLVED?
FINDING 5  CORS default allow-list trusts the Node origin         RESOLVED?
                                                                 RESOLVED: 5 / 5?
SEVERITY COUNT: Critical 0 | High 0 | Medium 2 | Low 3?
?
------------------------------------------------------------- TEST COUNTS ------?
mvn clean verify      : 304 tests | 0 failures | 0 errors | 0 skipped?
                        (260 -> 304, +44 new/extended cases) | BUILD SUCCESS?
npx tsc --noEmit      : 0 errors | exit 0?
npm run test:frontend : 36/36 passed | 0 failed | exit 0?
Node-off regression   : 79 checks | 79 PASS | 0 FAIL?
?
----------------------------------------------------------------- NODE-OFF ------?
node.exe matching server.ts/server.cjs : 0?
port 3000 listening                    : False?
GET :3000/api/v1/health                : curl exit 7 (connection refused)?
                                       : asserted at start AND end of run?
Node/Express backend                   : OFF (required condition, met)?
Vite dev server (127.0.0.1:5173)       : UP (permitted; not the Node backend)?
PostgreSQL / Java backend / React      : ON?
?
---------------------------------------------------------------- SECURITY ------?
anonymous 401 envelope                 : PASS  (code=UNAUTHORIZED, 104 bytes)?
?token= query-string auth              : PASS  (401, Phase 9 removal holds)?
bogus bearer token                     : PASS  (401)?
refresh rotation                       : PASS?
refresh reuse revokes whole family     : PASS  (stricter than Node)?
logout + post-logout refresh           : PASS  (200 revoked=true, then 401)?
login throttle                         : PASS  (429 AUTH_THROTTLED)?
tenant boundary (company admin)        : PASS  (403 on platform detail)?
platform-admin /users scoping          : PASS  (1 row, own org only, no leak)?
least privilege (manager)              : PASS  (users.read 200, users.create 403)?
PLATFORM_ADMIN not tenant-switchable   : PASS  (403)?
CORS: configured origin / unlisted     : PASS  (echoed / refused, preflight+authed)?
CORS: http://localhost:3000 (default)  : PASS  (refused - retired Node origin)?
CORS: '*' at startup                   : PASS  (IllegalStateException)?
security headers                       : PASS  (nosniff, DENY,?
                                                strict-origin-when-cross-origin,?
                                                X-XSS-Protection, no-store,?
                                                no HSTS over cleartext)?
ERROR-level log lines during run       : 0   (Phase 10.4 recorded 1)?
?
--------------------------------------------------------------------- DB -------?
db/migration vs HEAD                   : UNCHANGED?
db/ untracked or modified              : none?
migration files                        : V1..V8 only, no V9 created?
SHA-256 V1..V8                         : identical to pre-phase measurement?
flyway_schema_history                  : 8 rows, success = t on all?
fresh-schema boot                      : "applied 8 migrations ... now at version v8"?
re-run boot                            : "schema public is up to date"?
demo fixtures after re-run             : 3 organizations | 5 users | 6 memberships?
organization statuses                  : all 3 ACTIVE?
business / production data deleted     : none (disposable clean-room DB only)?
?
------------------------------------------------------------------ FLYWAY -------?
migrations modified                    : 0?
migrations added                       : 0?
frozen surfaces touched                : 0  (RBAC 9x44 matrix, 10-state audit?
                                            lifecycle, Scope 2 rules, accounting?
                                            formulas all unchanged)?
carbonflow.seed.demo-data default      : false (unchanged, not relaxed)?
?
----------------------------------------------------------------- SECRETS ------?
JWT secret literal in diff             : PRESENT=0?
DB password literal in diff            : PRESENT=0?
Bearer JWT literal in diff             : PRESENT=0?
cloud API key literal in diff          : PRESENT=0?
private key block in diff              : PRESENT=0?
.env git-ignored                       : yes?
secret values printed in this report   : none (names and file paths only)?
?
--------------------------------------------------------------- GIT SAFETY -----?
HEAD unchanged                         : yes?
commits created                        : 0?
tracked files deleted or renamed       : 0?
files touched outside?
  backend-java/src/** and docs/**      : 0?
?
---------------------------------------------------------------- FILES ---------?
MODIFIED (7) : DemoDataSeeder.java, SeedIds.java, ApiExceptionHandler.java,?
               SecurityConfig.java, application.properties,?
               ApiExceptionHandlerTest.java, PlatformTenantTest.java?
ADDED    (10): application-dev.properties, DemoDataSeederTest.java,?
               CorsConfigurationTest.java, CorsOriginIntegrationTest.java,?
               PHASE10-FINDINGS-RESOLUTION.md, PHASE10-CUTOVER-GAP-REPORT.md,?
               PHASE10-FRONTEND-CUTOVER.md, DECISIONS.md (ADR-021),?
               DEPLOYMENT-SECURITY.md, SECRETS.md?
ANNOTATED (1): PHASE10-NODE-OFF-TEST.md (amendment blocks only)?
ENCODING  (1): DECISIONS.md - 16 pre-existing lines re-encoded from stray?
               Windows-1252 bytes to valid UTF-8; characters unchanged,?
               verified character-identical to HEAD (see section 9.5)?
?
---------------------------------------------------------------- BLOCKERS ------?
browser UAT                             : NOT AVAILABLE (no desktop browser?
                                           attached; never counted as PASS)?
Node oracle (test:security,?
  test:persistence)                    : NOT RUN DURING NODE-OFF?
                                           (they boot server.ts; running them?
                                           would violate the test condition)?
open code defects blocking cutover     : none?
frozen-surface violations              : none?
untouched requirements violated         : none?
?
============================================================= FINAL VERDICT ====?
FINAL VERDICT: PASS?
?
All five Phase 10.4 findings are RESOLVED with root cause, fix, named tests,?
documentation and independent runtime verification for each. mvn clean verify is?
304/304, the TypeScript and frontend gates are clean, and the 79-check Node-off?
regression passes with Node/Express objectively absent throughout. The database?
is unchanged (V1-V8 byte-identical, no new migration), Flyway re-runs as a no-op,?
no secret appears in the diff, and no commit was made. Two items remain?
explicitly NOT RUN (browser UAT, Node oracle) and are recorded as such rather?
than claimed. Three out-of-scope observations are reported without being fixed.?
No cutover blocker remains.?
?
=============================== PHASE 10.5: DO NOT START ========================?
Phase 10.5 (Node decommission) was NOT started. No server/ file, no server.ts,?
no package script, no dependency and no migration was deleted or modified.?
================================================================================?
```?
?
---?
?
## 14. What This Report Does Not Claim?
?
- **No browser verification.** No desktop browser was attached. Nothing here is a?
  claim about rendering, focus, or interaction.?
- **No performance claim.** No performance tooling was run. No latency, throughput?
  or resource statement is made.?
- **No Node-oracle parity claim.** The Node test suites were not run; no?
  differential-parity result is claimed.?
- **No reconstruction of Phase 10.1-10.3.** Those records do not exist. The two?
  new cutover documents describe the repository's state on 2026-09-29 and are?
  labelled as reconstructions, not recoveries.?
- **No claim that the seeder's refusal paths were exercised against production?
  data.** They were exercised in unit tests and in a disposable clean-room?
  database.?
- **No change to frozen surfaces.** The RBAC matrix, audit lifecycle, Scope 2 rules?
  and accounting formulas are byte-for-byte as they were at START COMMIT.?

# CarbonFlow performance harness (`com.carbonflow.perf`)

Test-scope measurement code for **Phase 10.10 — Performance & Scalability**.

## Why this lives in `src/test/java`

Because it must never be able to affect the correctness gate. Every class here
is gated on the `carbonflow.perf` system property, so a plain `mvn verify`
compiles them and skips them. A benchmark that silently joined the regression
suite would either slow every run or make it flaky, and neither is acceptable.

## Classes

| Class | What it measures |
| --- | --- |
| `PerfScale` | The three controlled dataset cardinalities (small / medium / large). |
| `PerfDataset` | Builds one deterministic synthetic tenant per scale and **verifies** its row counts against the database before any measurement is taken. |
| `PerfStats` | Nearest-rank percentiles and spreads, computed only from samples actually collected. |
| `PerfLoadDriver` | Real-HTTP load generation over loopback TCP against the running Tomcat, with a discarded warm-up pass. |
| `PerfReport` | Collects measurements and writes `perf-*.json` (primary artefact) plus `perf-*.md`. |
| `PerfBaselineTest` | Read-path latency, throughput, response size, connection pool, memory, CSV export, analytics, and `EXPLAIN (ANALYZE, BUFFERS)` plans for the statements the hot repositories actually issue. |
| `PerfWritePathTest` | Write latency, transaction-boundary cost, login/BCrypt cost, and evidence upload/download at three sizes. |
| `PerfRecoveryTest` | `pg_dump`, coordinated backup set, verification, evidence vault backup, drill restore and retention — timed separately, with the drill's own limitations copied into the report. |

## Running them

All three write into a **disposable** database that Flyway migrates to V1..V8,
and `PerfBaselineTest` truncates every domain table before seeding so a run is
always repeatable.

Against the embedded PostgreSQL the rest of the test suite uses (nothing to
set up):

```sh
cd backend-java
mvn test -Dtest=PerfBaselineTest  -Dcarbonflow.perf=true
mvn test -Dtest=PerfWritePathTest -Dcarbonflow.perf=true
mvn test -Dtest=PerfRecoveryTest   -Dcarbonflow.perf=true
```

Against a real PostgreSQL server — which is how the reported baseline was
produced, because the engine version a deployment actually runs changes every
plan and every number:

```sh
cd backend-java
mvn test -Dtest=PerfBaselineTest -Dcarbonflow.perf=true \
  -Dcarbonflow.perf.jdbc=jdbc:postgresql://localhost:5432/carbonflow_perf?stringtype=unspecified \
  -Dcarbonflow.perf.user=postgres \
  -Dcarbonflow.perf.password=...
```

Run each class in its **own** invocation. They write real data, and the read
measurements must not share a machine with the write or recovery measurements.

## Artefacts

Written to `backend-java/target/perf/`:

- `perf-baseline.json` / `.md` — read paths, pool, memory, CSV, analytics, plans
- `perf-write-path.json` / `.md` — writes, transactions, login, evidence
- `perf-recovery.json` / `.md` — backup, verification, vault, drill, retention
- `plans/*.json` — one `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` per measured query

The JSON is the primary artefact. If a table in the markdown report ever
disagrees with the JSON, the JSON is what was measured and the prose is the bug.

## Rules this harness holds itself to

1. **No invented numbers.** Every recorded value was observed in that run.
   Where a measurement could not be taken, the harness records that it could
   not, rather than substituting a plausible value.
2. **The dataset is asserted, not assumed.** A cardinality mismatch fails the
   run instead of producing a measurement against unverified data.
3. **Percentiles are observed values.** Nearest-rank, so no reported percentile
   is a number the system never produced.
4. **Recovery durations are not RTOP/RPO.** `PerfRecoveryTest` copies
   `DrillResult.limitations()` into the report verbatim so the durations cannot
   be quoted without them.

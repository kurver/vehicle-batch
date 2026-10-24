# Volume test

How the compliance job behaves at production size: about 12M vehicles. The normal test suite proves the job is
correct, but it uses at most 960 rows. This test shows how run time and database work grow with the size of the
table. That decides whether the job fits its overnight window and how hard it leans on the database.

It runs against Postgres, not DB2. See [What it can't tell you](#what-it-cant-tell-you).

## Running it

```bash
./mvnw verify -Pvolume                           # 12M vehicles
./mvnw verify -Pvolume -Dvolume.vehicles=200000  # quick trial run of the same scenarios
```

- Docker must be running. The test starts its own `postgres:17-alpine` container and removes it afterwards.
- The test is tagged `volume`. A plain `./mvnw verify` skips it; `-Pvolume` runs only the volume test.
- Results are printed at the end and written to `target/volume-test-results.md`.
- At 12M rows the database is about 1.7 GB, and the whole test takes about 4 minutes, including starting the
  container and loading the data (about 25s).

## Approach

The test is `src/test/java/.../infrastructure/ComplianceVolumeTests.java`. It runs the real job through the real
Spring context, with the default chunk size of 1000, against one data set. The scenarios run in order.

### Data

The data is generated in the database with `generate_series`, using a fixed random seed so every run gets the same
data. Dates are generated around the day of the run (`DAY`, today):

| Column | Distribution |
|---|---|
| `inspection_expiry_dt` | 3% `NULL`, otherwise spread evenly over the year either side of `DAY` (about 16k a day) |
| `licence_expiry_dt` | 5% `NULL`, otherwise spread evenly over 6 months either side of `DAY` (about 31k a day) |
| `vehicle_usage_cd` | about 3% code 10 and 1% code 11 (A or B rego), otherwise codes 1–9 |
| `regt_status_cd` | 6% code 5 (lapsed), otherwise 1 |
| `exempt_end_dt` | 98% `NULL`, otherwise spread over the 90 days around `DAY` |

`vehicle_status` is filled with the flags as of the day before `DAY`, which is the state after yesterday's run. The
trigger is disabled during the load (one row-level trigger call per row would make loading very slow) and enabled
again afterwards. Both tables are then vacuumed and analysed, so the planner has statistics, as it would on a
long-lived table.

The schema is `src/test/resources/db/test-schema.sql`: both tables have a primary key on `vehicle_id` and no other
indexes. That matches what the job assumes of the legacy tables.

### Scenarios

| # | Scenario | Set-up | Expected |
|---|---|---|---|
| 1 | Normal day | Flags correct as of yesterday; run as of `DAY` | Reads and updates exactly the vehicles with a date on the day before `DAY` (about 47k), counted independently in SQL |
| 2 | Rerun with nothing to do | Straight after scenario 1 | Reads nothing. This is the cost of a check that finds nothing, such as a retry |
| 3 | Backfill of a new flag column | `exempt_fl` dropped and re-added, so it is `NULL` on every row, as when the DBAs add a column | Updates every row; afterwards no `exempt_fl` is `NULL` and the `Y` count matches the source data |

Each scenario checks its counts, so the test also fails if the job gets the wrong answer at this size.

### What is measured

- **Run time** of each job run, and the step's read, update, unchanged and commit counts.
- **Per-statement timings** from `pg_stat_statements` (reset before each run): calls, mean, max and total time for the
  first-page query, the later-page query and the update.
- **Query plans**: `EXPLAIN (ANALYZE, BUFFERS)` of the job's page query before scenarios 1 and 3, both for the first
  page and for a page starting halfway through the table. The query text comes from the same Spring Batch query
  provider the job uses, so the plan is for the SQL the job actually runs.

The halfway page matters most. Keyset paging only stays cheap if every page starts reading at its own key. If a page
has to read from the start of a table to get there, each page costs more than the last, and total work grows with the
square of the table size.

### Database settings

The container runs Postgres with `shared_buffers=2GB`, `max_wal_size=8GB` and `pg_stat_statements` loaded. The
command replaces Testcontainers' default, which turns `fsync` off, so commits cost what they would on a real server.
Everything else is the Postgres default. The database runs on local disk on the same machine as the job, so there is
no network latency between them; in production every page and every batched update is a round trip to DB2.

## Results

12M vehicles, chunk size 1000, run on 2026-10-24 on a 28-core developer machine with Postgres 17 in Docker on local
NVMe disk. Use these numbers to compare runs and to spot scaling problems, not as a forecast for DB2.

The first run found a problem with the page query, which was then fixed. Both runs are shown.

| Scenario | Before the fix | After the fix |
|---|---|---|
| Normal day (50,002 rows, 51 pages) | 21.4s | 7.6s |
| Rerun with nothing to do (one page) | 5.1s | 5.1s |
| Backfill (12M rows, 12,000 pages) | did not finish: about 10M rows done after 80 min, when the machine shut down | 3m 06s |

| Page query | Before the fix | After the fix |
|---|---|---|
| Halfway page, normal day | 552 ms | 114 ms |
| Halfway page, backfill | 574 ms, read 6.0M `vehicle_status` index entries to return 1,000 rows | 0.7 ms, read 1,000 |
| Mean of the later pages, normal day | 383 ms (max 683) | 101 ms (max 117) |
| Mean of the later pages, backfill | 21 ms at 200k rows and climbing with position; about 0.6 s per page projected at 12M | 0.9 ms (max 3.9), flat across the table |

### The problem the test found

Each page carried the paging condition `vehicle_id > last` on `vehicle` only. Postgres doesn't carry a range condition
across a join (only equalities), so the merge join read `vehicle_status` through its index **from the start of the
table** on every page, until it reached the page's position. A page's cost grew with how far through the table it was,
and total work grew with the square of the table size. For the backfill that came to about 2 hours of page queries
alone.

The fix (`StaleCompliancePagingQueryProvider`, `ComplianceSql#nextPage`) states the condition on both tables:
`v.vehicle_id > :last AND s.vehicle_id > :last`. Both index scans now start at the key (`Index Cond: (vehicle_id >
6000000)` on both tables in the plan), and every page costs the same wherever it is. This replaced Spring Batch's
generated per-database page queries with one plain SQL query for both databases.

### Reading the numbers after the fix

- **Backfill: 3 minutes for 12M updates.** Database time was 72s in updates and 10s in page queries. The rest is round
  trips between the job and the database and one commit (with `fsync`) per chunk. Against DB2 over a network, the round
  trips will cost more; 12,000 pages plus 12,000 batched updates is the number to multiply by your round-trip time.
- **Normal day: 7.6s.** Finding about 50,000 stale rows among 12M means scanning the whole join once: each page scans
  about 240,000 rows to find its 1,000 stale ones, about 100 ms. That is the cost of the job's design (no index on the
  flags), and it is now proportional to the table size, not its square.
- **Rerun with nothing to do: 5.1s.** One page query that scans the whole join and finds nothing. This is the floor for
  any run.

The full output of a run, including the complete query plans, is in `target/volume-test-results.md`.

## What it can't tell you

- **DB2 performance.** The job runs on DB2 in production, and DB2's optimizer, costs and locking are different. The
  plans here show what Postgres does with the query, not what DB2 will do. The DBAs should run `EXPLAIN` on the page
  query against production DB2. See [Checking DB2](#checking-db2).
- **Contention.** Nothing else is using the database during the test. In production the agency's overnight batch also
  writes to `vehicle`, and other systems read `vehicle_status`.
- **Production hardware and data.** The real distribution of dates and codes will differ, and so will the server.

## Checking DB2

Ask the DBAs to `EXPLAIN` the page query the job sends to DB2, for a page starting part-way through the table. It is
the same SQL on both databases, built in `ComplianceSql#nextPage`:

```sql
SELECT <ComplianceSql.SELECT>
  FROM vehicle v JOIN vehicle_status s ON s.vehicle_id = v.vehicle_id
 WHERE v.vehicle_id > ? AND s.vehicle_id > ? AND (<ComplianceSql.WHERE>)
 ORDER BY v.vehicle_id FETCH FIRST 1000 ROWS ONLY
```

Both `?` are the last `vehicle_id` of the previous page.

What to look for: the access to **both** `vehicle` and `vehicle_status` should start at the page's key, as a matching
range on the `vehicle_id` index of each table, not at the start of either table. The query states the condition on both
tables so the optimizer doesn't have to work it out; with it on `vehicle` alone, Postgres read `vehicle_status` from the
start on every page (see [Results](#results)).

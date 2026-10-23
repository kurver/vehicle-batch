# Vehicle compliance batch job

A command-line Spring Batch job that keeps the derived Y/N status flags in `vehicle_status` correct as time
passes. It runs as a Kubernetes CronJob against a busy legacy DB2 database. Tests run against Postgres.

Java 25, Spring Boot 4.1.1 (Spring Batch 6), Maven, Lombok, SLF4J.

## The problem

The legacy `vehicle` table holds the facts about each vehicle. `vehicle_status` holds `Y`/`N` flags (`CHAR(1)`)
derived from them, so other systems don't have to work them out themselves.

| Flag | From `vehicle` | `Y` when | `NULL` source means | Changes with time? |
|---|---|---|---|---|
| `inspection_valid_fl` | `inspection_expiry_dt` | today is on or before the expiry date | `Y` (nothing to expire) | yes |
| `licence_valid_fl` | `licence_expiry_dt` | today is on or before the expiry date | `Y` (nothing to expire) | yes |
| `a_or_b_rego_fl` | `vehicle_usage_cd` | the code is 10 or 11 (farm vehicles, "A or B" rego) | `N` | no |
| `lapsed_fl` | `regt_status_cd` | the code is 5 (lapsed; 1 is active) | `N` | no |
| `exempt_fl` | `exempt_end_dt` | today is on or before the end date | `N` (not exempt) | yes |

The `NULL` rule for `exempt_end_dt` is the opposite of the expiry dates: no expiry date means valid, but no exemption
end date means not exempt.

The flags can go wrong in three ways:

| Cause | Handled by |
|---|---|
| The vehicle row is inserted, updated or deleted | A database trigger on `vehicle`, which updates the flags as part of the same transaction |
| Nothing changes, but a date passes (an expiry or exemption ends) | This batch job, run daily |
| A flag drifts anyway: a change bypassed the trigger, or a new flag column hasn't been filled in yet | This batch job too; it corrects any flag that doesn't match the rules |

## Architecture

```
            ┌──────────── legacy DB2 ─────────────┐
 apps ────► │ vehicle ──trigger──► vehicle_status │ ◄── k8s CronJob (this job)
 (CRUD)     │                      (Y/N flags)    │     reads stale rows, updates flags
            └─────────────────────────────────────┘
```

- **The trigger covers changes to a vehicle; the job covers the passage of time, and acts as a safety net.** Each run
  compares every stored flag with the rules as of today and rewrites only the rows that disagree.
- **Stateless.** The job uses Spring Batch's in-memory job repository. It creates no tables, and the only writes it
  makes are the `vehicle_status` updates. Nothing is kept on disk.
- **Idempotent.** The job only reads rows that are still stale. Once a chunk is committed, those rows no longer match the
  query, so a re-run, retry or overlap-free restart just carries on with whatever is left. No restart bookkeeping is
  needed.
- **Portable SQL.** All of the job's SQL is plain ANSI SQL that runs on both DB2 and Postgres. Trigger DDL differs by
  database and lives in `db/<database>/`.

### Approach to the job, and why it's cheap on a busy database

The job reads and writes in chunks: it reads stale rows a page at a time, works out the new flags, and updates them in
batches.

1. **Only rows that need fixing reach the job.** The query joins `vehicle` to `vehicle_status`, works out each flag in
   SQL as of `asOf`, and returns only rows where a stored flag differs or is `NULL`. The database still scans the join,
   but only rows that need an update are sent to the job, and only those are written. On a normal day that's the
   vehicles whose dates passed since the last run, not the ~11M rows in the table.
2. **Keyset paging.** Each page is `... AND vehicle_id > :lastSeen ORDER BY vehicle_id FETCH FIRST n ROWS`. There's no
   OFFSET, so every page is a short range read and no cursor or transaction is held open across the run. The join is
   wrapped in a derived table so the paging key `vehicle_id` isn't ambiguous between the two tables. Both databases fold
   the derived table back into the main query.
3. **Short transactions.** Each chunk (`compliance.chunk-size`, default 1000) commits in one short transaction:
   - one page read;
   - one batched `UPDATE vehicle_status ... WHERE vehicle_id = ?`.
4. **No lock waits on DB2.** The DB2 URL sets `concurrentAccessResolution=2`, so reads use the last committed row
   instead of waiting on other transactions' locks.
5. **Rows deleted mid-run are skipped.** If a vehicle is deleted after it was read, its status row is gone too, and the
   update quietly affects 0 rows.
6. **Only `vehicle_status` is written.** The job never writes to `vehicle`, and none of its updates fire the trigger.
   The Postgres trigger also only fires on `UPDATE` when an expiry column changes (`UPDATE OF ...`).

The rules live in three places. The domain (`Vehicle#flagsOn`) is the authoritative version. The `WHERE` clause in
`ComplianceSql` and the trigger repeat the rules so the database can filter rows before they reach the job. If a rule
changes, update all three.

## Code structure

The code follows a light domain-driven layout. The domain package has no Spring or SQL dependencies, and all database
details live in `infrastructure`.

```
src/main/java/nz/co/krbconsulting/batch/
├── BatchApplication.java          entry point; exits with the job's exit code
├── domain/                        pure Java, no Spring or SQL
│   ├── Vehicle.java               the facts flags derive from; flagsOn(day) returns what the flags should be
│   ├── Expiry.java                value object: valid on a given day? (NULL = always valid)
│   ├── Exemption.java             value object: exempt on a given day? (NULL = not exempt)
│   ├── VehicleUsage.java          value object: usage code 10/11 = A or B rego
│   ├── RegistrationStatus.java    value object: code 5 = lapsed, 1 = active
│   ├── ComplianceFlags.java       the five Y/N flags, as booleans
│   ├── VehicleCompliance.java     a vehicle plus its recorded flags; reassessedOn(day) returns an update, or empty
│   └── ComplianceUpdate.java      corrected flags to store for one vehicle
├── infrastructure/                adapters between the legacy tables and the domain
│   ├── ComplianceSql.java         every table and column name and every query, in one place
│   └── ComplianceJdbc.java        keyset-paged reader, batch update writer, row mapping and Y/N conversion
└── config/
    ├── ComplianceJobConfig.java   job and step wiring: reader → processor (domain) → writer, plus a run summary log line
    └── ComplianceProperties.java  compliance.* settings

src/main/resources/
├── application.yml                no web server, job runs at startup, chunk size
├── application-postgres.yml       local/test datasource (the default profile)
├── application-db2.yml            production datasource
└── db/postgres/vehicle_status_trigger.sql   trigger keeping vehicle_status in step with vehicle

src/test/
├── java/.../domain/VehicleComplianceTest.java   unit tests for the validity rule
├── java/.../ComplianceJobTests.java             integration tests: Testcontainers Postgres, trigger and job
└── resources/db/test-schema.sql                 stand-in for the legacy tables
```

The processor is the domain: `compliance -> compliance.reassessedOn(day).orElse(null)`. It returns `null` for a row
whose flags are already right, so Spring Batch drops it and doesn't write it. If any stored flag is `NULL`, the
recorded flags are treated as unknown and the row is always rewritten.

### Schema the job expects

```sql
vehicle        (vehicle_id INTEGER, inspection_expiry_dt DATE, licence_expiry_dt DATE,
                vehicle_usage_cd SMALLINT, regt_status_cd SMALLINT, exempt_end_dt DATE, ...legacy columns)
vehicle_status (vehicle_id INTEGER, inspection_valid_fl CHAR(1), licence_valid_fl CHAR(1),
                a_or_b_rego_fl CHAR(1), lapsed_fl CHAR(1), exempt_fl CHAR(1))  -- 'Y' / 'N'
```

## Building and testing

```bash
./mvnw verify          # unit + integration tests; Docker must be running (Testcontainers starts Postgres)
./mvnw package -Pdb2   # production jar, including the DB2 JDBC driver
```

- The DB2 driver is only included with `-Pdb2`. The default build has only the Postgres driver.
- Lombok's annotation processor is set explicitly in the compiler plugin, because JDK 23+ no longer runs annotation
  processors found on the classpath by default.
- Integration tests use `postgres:17-alpine`, with `test-schema.sql` and the Postgres trigger applied as init scripts.
  DB2 isn't tested directly, so keep all job SQL DB2-compatible. For example, don't use `RETURNING`, `ILIKE`, `::` casts
  or `ON CONFLICT`.

## Run book

### Configuration

| Setting | How it's set | Notes |
|---|---|---|
| Database | `SPRING_PROFILES_ACTIVE=db2` | The default profile is `postgres` (localhost), for local use only |
| `DB_HOST`, `DB_NAME` | env | DB2 host and database. The port is fixed at 50000 in `application-db2.yml` |
| `DB_USER`, `DB_PASSWORD` | env, from a k8s Secret | Needs `SELECT` on `vehicle` and `vehicle_status`, and `UPDATE` on `vehicle_status`. Nothing else |
| `COMPLIANCE_CHUNK_SIZE` | env, optional | Rows per transaction, default 1000. Lower it to go easier on the database, raise it to finish faster |
| `TZ` | built into the image: `Pacific/Auckland` | Decides what "today" is. Must match the database's `CURRENT_DATE`, which the trigger uses. The base image needs timezone data (`tzdata`) |
| `asOf=YYYY-MM-DD` | job argument, optional | Overrides "today", for catch-up or testing |

### Scheduling (Kubernetes CronJob)

Run it once a day, shortly after midnight NZ time:

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: vehicle-compliance
spec:
  schedule: "15 0 * * *"
  timeZone: Pacific/Auckland
  concurrencyPolicy: Forbid          # never let two runs overlap
  startingDeadlineSeconds: 3600
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 5
  jobTemplate:
    spec:
      backoffLimit: 2                # retrying is always safe
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: job
              image: <registry>/vehicle-compliance:<version>
              env:
                - { name: SPRING_PROFILES_ACTIVE, value: db2 }
                - { name: DB_HOST, value: <host> }
                - { name: DB_NAME, value: <database> }
              envFrom:
                - secretRef: { name: vehicle-compliance-db }   # DB_USER, DB_PASSWORD
```

### What a healthy run looks like

The job writes to stdout. Look for these two lines:

```
Reassessing vehicle compliance as of 2026-10-24
Compliance run COMPLETED: read 1234, updated 1234, unchanged 0, commits 2
```

- `read`: stale rows found.
- `updated`: rows corrected.
- `unchanged`: rows read whose flags were already right, for example changed by the trigger since. Normally 0 or close.
- `commits`: transactions used.

The pod exits `0` on success and non-zero on failure, so the k8s Job shows as Failed.

On a normal day `read` is roughly the number of vehicles whose dates passed since the last run. A run after a long gap
will be larger. So will the first run after a new flag column is added: the column starts as `NULL`, and that run
fills it for every vehicle (up to ~11M updates, in chunks). Schedule it at a quiet time, or have the DBAs backfill
the column first.

### When a run fails

1. **Read the pod log.** `kubectl logs job/<job-name>`. The stack trace shows the cause: connection refused, timeout,
   permissions, lock timeout and so on.
2. **Fix the cause, then run it again.** Re-running is always safe. Rows already committed won't be read again, and the
   job carries on with what's left. Either wait for the next schedule, or trigger a run now:
   ```bash
   kubectl create job --from=cronjob/vehicle-compliance vehicle-compliance-manual-$(date +%s)
   ```
3. If retries keep failing, `backoffLimit` stops them. The cause is outside the job: database availability,
   credentials, or schema changes to the legacy tables.

### Common operational tasks

| Task | What to do |
|---|---|
| Missed one or more days | Run once normally. It catches up everything that's stale as of today; there's no need to run once per missed day |
| Set flags as of a specific date (testing, investigation) | Run with the argument `asOf=YYYY-MM-DD`, for example a manual Job with `args: ["asOf=2026-10-24"]`. In production this sets the date-based flags as of that date, not today, until the next normal run puts them back |
| The database is under pressure | Lower `COMPLIANCE_CHUNK_SIZE`, for example to 200, to make transactions shorter, or move the schedule to a quieter time |
| Stop a run that's in progress | `kubectl delete job <job-name>`. Committed chunks stay committed, the current chunk rolls back, and the next run carries on |
| Check whether any flags are stale | Run the job's own query by hand: the `FROM` and `WHERE` in `ComplianceSql`, with `:asOf` replaced by `CURRENT_DATE`, as a `SELECT COUNT(*)`. This should be 0 after a successful run |
| Check for missing or orphaned status rows (trigger health) | Look for vehicles with no `vehicle_status` row, and status rows with no vehicle. The job doesn't create or delete status rows; that's the trigger's job |

### Known limitations and follow-ups

- **DB2 trigger not written yet.** Only the Postgres version exists (`db/postgres/vehicle_status_trigger.sql`).
- **Each run scans the whole join.** The job checks every flag on every row, and the legacy schema has no index that
  would help. If run times or database load become a problem, ask the DBAs about indexes, or narrow the job's query to
  the date-based flags and leave the code-based flags to the trigger.
- **No run history.** This is deliberate, because the job keeps no database state. Run history lives in the pod logs
  and the k8s Job history, so keep those for as long as you need them.

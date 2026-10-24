# Vehicle compliance job: delta run options

24 October 2026 · Rachael

> A copy of the shared doc [Vehicle compliance job: delta run options](https://claude.ai/artifact/92Ax3CjgCB3n7oP6UYqFcJ)
> as it stood on 24 October 2026. Edits made in the doc after that date aren't reflected here.

We want each daily run to read and update only the flags that have gone wrong since the last run, using one indexed query per flag. There are two ways to make those queries small, and both need the DBAs' agreement before we test them at production size.

## Decided so far

Today each run compares all five flags on every row of the `vehicle` / `vehicle_status` join, using only the primary-key indexes. In the 12M-row Postgres volume test, a run that finds nothing still takes 5.1s, a normal day 7.6s, and a full backfill 3m 06s. On DB2 the full scan is the part that grows with the table.

- **One daily query per flag, for all five.** Inspection, licence and exemption go stale as dates pass. A/B rego and lapsed only go stale if something bypasses the trigger. Every flag is also checked for `NULL`, for when a new flag column is added.
- **The full run (today's query) is run by hand only.** It's for the first run, after long outages, and when the date is overridden for testing. There's no weekly schedule.
- **The domain code still decides every flag's value.** The queries only choose which rows to re-check, so the rules stay authoritative in one place.

## Why new indexes alone aren't enough

A wrong flag is a mismatch between two tables: the date or code is in `vehicle`, but the flag is in `vehicle_status`. An index covers one table, so no index can go straight to "inspection passed but the flag still says `Y`". The job is also stateless, so it doesn't know when it last ran.

That leaves two ways to make each query small:

- **A:** limit each date query to a recent window of days, and accept that code-based checks read every A/B or lapsed vehicle.
- **B:** keep a copy of the source columns next to the flags in `vehicle_status`, so each check is one index range on one table.

## Option A: look-back window, indexes on `vehicle`

No schema change. Each date check reads only vehicles whose date fell in the last N days (`compliance.lookback-days`, for example 7). Each code check reads every vehicle with the code, and every status row flagged `Y`.

| Flag | Daily query finds | Index used |
| --- | --- | --- |
| Inspection | `inspection_expiry_dt` in `[asOf − N, asOf)` and flag still `Y` | `vehicle(inspection_expiry_dt)` |
| Licence | `licence_expiry_dt` in `[asOf − N, asOf)` and flag still `Y` | `vehicle(licence_expiry_dt)` |
| Exemption | `exempt_end_dt` in `[asOf − N, asOf)` and flag still `Y` | `vehicle(exempt_end_dt)`, 98% `NULL`: `EXCLUDE NULL KEYS` on DB2 |
| A/B rego | code 10 or 11 but flag not `Y`; flag `Y` but code not 10 or 11 | `vehicle(vehicle_usage_cd)`, `vehicle_status(a_or_b_rego_fl)` |
| Lapsed | code 5 but flag not `Y`; flag `Y` but code not 5 | `vehicle(regt_status_cd)`, `vehicle_status(lapsed_fl)` |
| Any, `NULL` | the flag is `NULL` | an index on each flag column of `vehicle_status` |

That's 5 new indexes on `vehicle` and up to 5 on `vehicle_status`. Each query joins the other table by primary key and pages by `vehicle_id` on both tables, as now.

**Cost per run.** With the volume test's data (12M vehicles) and N = 7, the date checks read about 350k index entries to find about 50k stale rows. The code checks read about 2.4M index entries every day, because they visit every A/B (about 4%) and lapsed (about 6%) vehicle from both sides, even when nothing is wrong. That's bounded, but it isn't delta-sized.

**Gaps.** If runs fail for more than N days, the older expiries stay `Y` until someone runs the full job. A wider window shrinks the risk but makes every run bigger: 30 days is about 1.5M index entries a day for the dates.

**What it catches.** The code checks compare against `vehicle` itself, so they catch changes that bypassed the trigger.

## Option B: copy the source columns into `vehicle_status`

Add a copy of the five source columns to `vehicle_status`. The trigger writes them in the same `UPDATE` that sets the flags. Each flag then gets one composite index on `vehicle_status`, and every check becomes a single index range on that one table.

| Flag | Copied column | Index | Daily query finds |
| --- | --- | --- | --- |
| Inspection | `inspection_expiry_dt` | `(inspection_valid_fl, inspection_expiry_dt)` | flag `Y` and date before `asOf`; flag `N` and date `NULL` or on or after `asOf`; flag `NULL` |
| Licence | `licence_expiry_dt` | `(licence_valid_fl, licence_expiry_dt)` | the same, on licence |
| Exemption | `exempt_end_dt` | `(exempt_fl, exempt_end_dt)` | flag `Y` and end date before `asOf`; flag `N` and end date on or after `asOf`; flag `NULL` |
| A/B rego | `vehicle_usage_cd` | `(a_or_b_rego_fl, vehicle_usage_cd)` | flag `Y` and code not 10 or 11; flag `N` and code 10 or 11; flag `NULL` |
| Lapsed | `regt_status_cd` | `(lapsed_fl, regt_status_cd)` | flag `Y` and code not 5; flag `N` and code 5; flag `NULL` |

That's 5 new indexes, all on `vehicle_status`, and none on `vehicle`. The daily run doesn't read `vehicle` at all.

**Cost per run.** Each query reads only the rows that are wrong: about 50k a day with the volume test's data, and close to nothing for the code-based flags. This holds however long it has been since the last run, because no window is involved.

**One-off work.**

- Add five columns to `vehicle_status`: about 16 bytes a row, or roughly 200 MB at 12M rows before indexes.
- Backfill them once from `vehicle`, which means updating every row in chunks.
- Add the copies to the trigger. The DB2 trigger isn't written yet in either option.

**What it misses.** If something changes `vehicle` while bypassing the trigger, the copy goes stale along with the flag, so the daily check can't see it. Only a full run by hand catches that.

**Other readers.** `vehicle_status` is read by other systems, so new columns need checking against anything that relies on its layout, for example `SELECT *` or a fixed-format extract.

## A and B side by side

B makes every daily run small and has no window gap, but it changes a legacy table. A changes no tables, but its runs are larger and it relies on a manual full run after long gaps. Run sizes use the volume test's data: 12M vehicles and about 50k stale rows a day.

|  | A: look-back window | B: copy into `vehicle_status` |
| --- | --- | --- |
| Schema change | None | 5 columns added to `vehicle_status` |
| New indexes on `vehicle` | 5 | None |
| New indexes on `vehicle_status` | Up to 5, single-column on the flags | 5 composite: (flag, copied column) |
| Index entries read per normal day | About 2.75M (dates 350k, codes 2.4M) | About 50k, the stale rows only |
| Reads `vehicle` during the run | Yes, by index and primary key | No |
| After runs fail for more than N days | Older expiries stay wrong until a manual full run | No gap |
| Catches changes that bypass the trigger | Code flags yes; dates only a Y that should be N, inside the window | No: only a manual full run does |
| One-off work | Build the indexes | Add the columns, backfill 12M rows, build the indexes |
| Trigger change | None | Also write the copied columns |
| Extra write cost on `vehicle` for the agency's overnight batch | 5 more indexes to maintain on every insert and on updates to those columns | None on `vehicle`; the trigger's existing `UPDATE` writes more columns |

## Questions for the DBAs

- [ ] Which DB2 is production: Linux/UNIX/Windows or z/OS? This affects `EXCLUDE NULL KEYS`, the job's lock timeout setting, and how indexes are built online.
- [ ] Are new indexes on `vehicle` acceptable, given the agency's overnight batch? Which of A's five, if not all?
- [ ] Can `vehicle_status` take new columns? Who else reads it, and does anything depend on its layout?
- [ ] Will DB2 use single-column indexes on `CHAR(1)` flag columns, where most rows share a value? Is `(flag, column)` better?
- [ ] How would you build an index on a 12M-row table that's in use, and how long would it take?
- [ ] Does the agency's batch ever load `vehicle` with triggers off, or in another way that bypasses them? That decides how much B's blind spot matters.
- [ ] Could you `EXPLAIN` the job's current page query on production DB2? The query and what to look for are in [README-volume-test.md](README-volume-test.md#checking-db2).

## Next step: measure both

Once the DBAs have answered, we build whichever options are still in play into the 12M-row volume test and decide from the numbers. The test runs on Postgres, so the DB2 plan still needs the DBAs' `EXPLAIN`.

For each option, the test would measure:

- Run time and index entries read for a normal day, a day with nothing to do, and a run after a 10-day gap.
- The size of the new indexes, and of the new columns for B.
- The extra write cost of an agency-style batch of inserts and updates on `vehicle`, with A's indexes or with B's longer trigger update.
- For B, how long the one-off backfill takes.
- That the delta job, run every day from day 0 to day n, leaves exactly the same flags as one full run on day n.

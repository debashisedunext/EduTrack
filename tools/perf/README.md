# tools/perf — A-073

**Owner: Stream A · Shivendra**

A-073 is three sentences from the plan, and this directory is what makes each of
them checkable rather than assertable:

| Where | What it asks for |
|---|---|
| PLAN.md §6, M6 exit | dashboard first paint under 1.5 s on a seeded 50,000-ticket dataset |
| PLAN.md §8 | k6 — dashboard and ticket-list p95 on a 50,000-ticket dataset |
| PLAN.md §6, M7 | index review against real query plans |

Nothing here is compiled or shipped. It is operator tooling, run by hand against
a local stack.

## What is here

| File | Job |
|---|---|
| `seed-50k.sql` | Builds the 50,000-ticket corpus on top of B-007's reference data. ~5 s. |
| `reset-stats.sql` | Clears the summary tables so A-051 rebuilds them. **Not optional** — see below. |
| `explain.sql` | The index review, as a re-runnable script. 17 labelled `EXPLAIN ANALYZE` plans. |
| `k6/dashboard.js` | Dashboard load test — the blocking call plus the ten-widget fan-out. |
| `k6/tickets-list.js` | Ticket-list load test — first page, filtered, deep keyset page, drill-down. |
| `run.sh` | Runs either k6 script via the `grafana/k6` Docker image. |

## Running it

```bash
docker compose up -d

# once, to create B-007's projects/users/clients if the DB is empty
cd backend && ./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local,fixtures

# the corpus — ~5 seconds
docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack < tools/perf/seed-50k.sql

# REQUIRED before any dashboard measurement — see "the summary-table trap"
docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack < tools/perf/reset-stats.sql
java -jar backend/worker/target/edutrack-worker-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local \
  --edutrack.stats.backfill-per-pass=700 --edutrack.stats.refresh-interval=PT1M

# the index review — needs only the corpus, not the summary rebuild
docker exec -i edutrack-mysql mysql -uroot -prootpw edutrack < tools/perf/explain.sql > plans.txt

# the load tests (API must be up on 8080 under local,dev-noauth)
tools/perf/run.sh both
```

## The summary-table trap

**Seeding the corpus silently invalidates every dashboard number, and the
worker cannot self-heal from it.** This cost an hour to find and is the single
most likely way for someone to produce confident, wrong dashboard figures.

The dashboard reads only `daily_ticket_stats`, `resource_daily_stats` and
`client_daily_stats`. `seed-50k.sql` writes 50,000 tickets across eighteen
months and touches none of those three. So straight after seeding, the dashboard
serves figures computed for B-007's 200 tickets — and it looks entirely normal
doing it: every widget renders, nothing errors, nothing logs.

A-051's worker will not catch up, because
`DailyStatsRepository.backfillResumePoint` only ever moves **forward**:

```
resume point = (newest summarised day below the 7-day window) + 1
```

Its javadoc explains why that is right in general — advancing one calendar day at
a time is what stops holes from forming — and names the cost: it "gives up
noticing a single day deleted by hand from the middle of summarised history",
repaired "by clearing `daily_ticket_stats` from the damaged day forward and
letting backfill rebuild it". Seeding is that case at scale. The table already
holds recent rows, so the resume point sits at the window edge, `backfillOlderThan`
returns 0, and the sixteen months just inserted are never summarised at all.

`reset-stats.sql` is that repair. Deleting those rows is safe by construction —
A-050's migration header states that a refresh "recomputes a day from scratch and
is idempotent", so all three tables are derived, never a source of truth. They are
not the append-only tables, and this must stay true.

The rebuild is complete when `MIN(stat_date)` in `daily_ticket_stats` reaches
`MIN(DATE(date_reported))` in `tickets`.

**If the worker looks idle after a restart**, the refresh holds a ShedLock
(`statsRefresh`, `lockAtMostFor=PT4M`). A worker killed mid-pass leaves it held,
and the next one skips silently, then waits a full `refresh-interval` before
retrying. Use a short interval while backfilling.

## Two corpora, and they are not interchangeable

B-007's 200 tickets and this corpus answer different questions, and using one for
the other's job is the mistake this section exists to prevent.

| | B-007 `api/feature/fixtures/` | `seed-50k.sql` |
|---|---|---|
| Size | 200 | 50,000 |
| Built by | JPA, one transaction per ticket, real workflow walks | set-based SQL, one statement per 5,000 |
| Durations | `WorkingHoursService` — real working-calendar time | wall clock |
| Append-only tables | real history, transitions, effort logs | **none written at all** |
| Use it to assert | behaviour | plans and latency |

The wall-clock durations are the important line. **No SLA test may point at this
corpus** — B-024's working-calendar rule is not reachable from SQL, and
re-implementing it here would be a second copy of the rule CLAUDE.md says must
have one home. The file header says the same thing at more length.

It writes no `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions`
rows either. A-040's hash chain is per-ticket and computed in Java; 50,000 forged
chains would be 50,000 rows A-044's verifier reports as broken every night.

Everything it writes is titled `Perf corpus #N`, so the two corpora are
distinguishable in any query result rather than by memory.

---

# The index review

Run against 50,000 rows on MySQL 8.4, warm buffer pool, single connection.
Reproduce with `explain.sql`. Times are `EXPLAIN ANALYZE` actuals; the number
that matters in each row is **rows read**, not milliseconds — the millisecond
figure is a warm single-query best case and the row count is what predicts
behaviour under concurrency and growth.

## What was wrong

`TicketListSpecs.SORTABLE` carries a comment saying its sortable columns are
"all of which are indexed or the primary key". Three of the five were not:
`created_at`, `date_reported` and `level` had no index at all — and
`DEFAULT_SORT` is `-createdAt`, so **the list every caller gets who never touches
the sort control was a full table scan and a filesort.**

At B-007's 200 rows that is invisible. At 50,000:

| Query | Plan | Rows read | Time |
|---|---|---|---|
| Admin, default list | table scan + filesort | 50,000 | 25.4 ms |
| PM, `project_id IN (1,2)` | table scan + filter + filesort | 50,000 | 19.3 ms |
| Developer, `assigned_to = ?` | `ix_tickets_assignee_status` + filesort | 2,530 | 3.1 ms |

The point is not 25 ms. It is that the work is proportional to the **table**
rather than to the **page**, so it degrades with every ticket the product ever
files — and cursor pagination makes it worse rather than better, because each
deep page repeats the whole scan and sort in order to discard everything before
the cursor.

## What was added

`V20260820_0445__ticket_list_sort_indexes.sql` — three indexes.

| Index | Query | Before | After |
|---|---|---|---|
| `(created_at, id)` | Admin default list | 25.4 ms | **1.2 ms** |
| `(created_at, id)` | deep keyset page (A-053) | full sort, every page | **0.04 ms**, 51 rows |
| `(assigned_to, created_at, id)` | Developer scope | 3.1 ms | **0.12 ms** |
| `(date_reported, id)` | widget drill-down | see below | **2.4 ms** |

## The finding worth reading twice

**`(created_at, id)` on its own makes a real query three times worse than having
no index at all.**

Every S-05 widget drill-down opens the ticket list with a reported-date window —
A-060 added `reportedFrom`/`reportedTo` to `TicketListSpecs` for exactly that.
That is a narrow range on `date_reported` under the default `created_at` sort.
Given only `ix_tickets_created`, the optimiser takes it to avoid the filesort,
then walks backwards through 46,090 index entries to find 51 rows sitting at the
far end of the table:

| Indexes available | Plan | Rows read | Time |
|---|---|---|---|
| none | table scan + filesort | 50,000 | 19.5 ms |
| `ix_tickets_created` only | reverse index scan + filter | **46,090** | **66.3 ms** |
| both | range scan + filesort over 552 | 552 | 2.4 ms |

So `ix_tickets_reported` is not a third improvement found alongside the other
two — it is a **counterweight**. Shipping the first two without it would have
made the ticket list 20× faster and every dashboard drill-down 3× slower, and
the drill-down is where it would have been noticed, a long way from the migration
that caused it.

The general shape, worth carrying to the next index decision: **an index that
removes a sort can cost more than the sort**, because the optimiser will prefer
it on the strength of the `ORDER BY` and then read most of the table to satisfy a
`WHERE` it cannot use.

## What was deliberately not added

**`(project_id, created_at, id)`** — the PM scope's obvious composite, and what a
symmetry argument would demand. Measured and refused:

| Case | Plan | Time |
|---|---|---|
| PM on 2 of 3 projects | optimiser ignores it, uses `ix_tickets_created` | 0.18 ms |
| PM on 1 project | optimiser uses it | 0.03 ms |
| PM on 1 project, without it | `ix_tickets_created` + filter, 182 rows | 0.59 ms |
| PM + level + status, **forced** onto it | 16,667 rows | 23.7 ms |
| PM + level + status, optimiser's choice (`ix_tickets_project_status`) | 447 rows | 0.87 ms |

A 0.56 ms gain in one case, for a fourth index on the most heavily written table
in the product, where `ix_tickets_project_status` already answers every filtered
variant better. The forced row is not the argument on its own — the optimiser
chose correctly every time it was left alone — but it shows the same trap as the
drill-down is sitting there unlit.

**`level`** has four values; sorting by it is a filesort whatever we do.
**`planned_close_date`** is already indexed for open tickets by A-009's
`ix_tickets_pcd_open`, and sorting *closed* tickets by their planned close date is
not a question anyone asks.

## Cost

- **Space** — 1.5–2.5 MB per index at 50,000 rows, against a 45 MB table.
- **Writes** — 49,800 bulk inserts: 4.891 s → 5.376 s, **+9.9%**.
- **Structurally** — none of `created_at`, `assigned_to` or `date_reported` is
  touched by the hot update path. A ticket changing status, stage, iteration,
  effort or `pct_complete` moves no entry in any of the three. Only `INSERT` pays
  in full; reassignment pays for one.

## What the review confirmed was already right

- The dashboard reads only `daily_ticket_stats`, `resource_daily_stats` and
  `client_daily_stats`. `DashboardRepository`'s class note claims a join to
  `tickets` "would put the timeout back at A-073's 50,000-row target" — the claim
  holds, and the cost of those queries tracks the number of summarised **days**,
  not the ticket count.
- The ticket-code deep link resolves on `uq_tickets_code`, not the FULLTEXT
  index — PLAN.md §3.8's "exact and instant".
- `ix_tickets_pcd_open` (A-009) keeps the SLA scan proportional to the open set,
  as its own header claims.

---

# The load tests

Run against 50,000 tickets with the summary tables rebuilt (1,644 day-rows
covering 2025-02-19 → 2026-08-20), API as a packaged jar under `local,dev-noauth`,
MySQL/Redis/MinIO in Docker, all on one developer laptop.

## Ticket list — passes

| Scenario | p95 | Budget | |
|---|---|---|---|
| First page, default sort | 245 ms | 300 ms | ✅ |
| Filtered (status+level / assignee) | 244 ms | 300 ms | ✅ |
| Drill-down, reported window | 269 ms | 300 ms | ✅ |
| **Deep page — 40 pages in** | **78 ms** | 300 ms | ✅ |

Zero failed requests, 744/744 checks passed.

**The deep page is three times faster than the first page.** That is A-053's keyset
claim demonstrated rather than asserted — page 40 costs less than page 1, because
`ix_tickets_created` turns it into a covering range scan while the first page still
pays for the full row fetch. Before this migration the same query repeated a
50,000-row sort on every page.

## Dashboard — does not meet the budget

| Metric | p50 | p95 | Budget | |
|---|---|---|---|---|
| `/dashboard/summary` | 39 ms | 190 ms | 300 ms | ✅ |
| One widget | 29 ms | 421 ms | 400 ms | ❌ |
| **First-paint API total** | **102 ms** | **943 ms** | 500 ms | ❌ |

Zero failed requests, 2,376/2,376 checks passed. Nothing is erroring; this is
purely latency.

**The 9× gap between p50 and p95 is the finding.** A typical dashboard load spends
102 ms on the API and is comfortably inside the 1.5 s budget. But first paint costs
**11 HTTP round trips** — one blocking `/dashboard/summary` and then ten widget
calls — so whenever a few of the 40 users coincide, the server sees 11 requests per
person at once and the tail goes to 943 ms. That leaves ~557 ms of the 1.5 s for the
document, bundle, parse and React's first render, which is not enough.

**It is not query cost and not data volume.** Two measurements say so:

- All ten widgets cost the same, 31–43 ms each. There is no hot widget to fix.
- Growing the summary tables 7.5× (219 → 1,644 day-rows) moved the single-request
  baseline from ~51 ms to ~61 ms. A-050's design holds: dashboard cost tracks
  summarised **days**, not tickets.

So ~35 ms per widget is fixed per-request overhead — filter chain, scope resolution,
transaction setup, ETag, serialisation — and eleven of them is the problem.

**Tested and rejected: JDBC connection tuning.** One widget request issues 3 data
queries and 4 transaction-bookkeeping round trips (`SET SESSION TRANSACTION READ
ONLY`, `SET autocommit=0`, `COMMIT`, …), so ~77 MySQL round trips per first paint,
over half of them bookkeeping. Adding `useLocalSessionState`, `useLocalTransactionState`,
`cachePrepStmts`, `useServerPrepStmts` **did not help**: p95 1.30 s against 1.28 s,
inside noise. Prepared-statement caching did start working, but Spring genuinely
changes connection state per request, so the `SET`s cannot be elided. **Recorded so
nobody proposes it again.** No file carries this change.

**The fix is architectural, and the blueprint already contains the precedent.** §9.4
mandates `/tickets/:id/full` "to avoid a waterfall of 6 calls". The dashboard has no
equivalent and needs one — a batch endpoint taking the widget keys and answering
once, turning 11 round trips into 2. That is a contract change (Stream D owns the
OpenAPI spec) plus a frontend change, so it is **not** in A-073 and is raised as
follow-up work.

## Reading these numbers correctly

The VU counts are **people**, not request generators, because both scripts carry
jittered think time (`THINK`, default 8 s for the list and 20 s for the dashboard).

The first version of these scripts had none, and it mattered enough to record: 25
VUs looping without pause produced ~35 req/s — the load of roughly 250 people — and
a ticket-list p95 of 1.5 s. Reported as-is that would have read as "the ticket list
fails at 25 users", which is false, and would have sent someone optimising a
non-problem. **A load test without think time measures the server's saturation
ceiling, not anyone's experience.**

Both readings are useful, so both are available: `THINK=0` turns either script back
into a capacity measurement. On this laptop the ticket list saturates at ~35 req/s
with a 40 KB payload per page.

---

# Known limitations of this harness

Written down rather than discovered later.

**Role is a property, not a header.** Under A-012's `dev-noauth` the caller is
whatever `edutrack.dev-noauth.*` says — by default ADMIN, user 1, unrestricted.
The k6 scripts therefore measure one role per run, and to measure another you
restart the API with different properties. Unrestricted is the right default
because it is the *pessimistic* scope: nothing narrows anything, so every query
does the most work it can.

**First paint is measured in two halves.** k6 measures the server; the browser
half — bundle, parse, render — is not covered here. The 1.5 s budget is split
~1000 ms browser / ~500 ms API, and **that split is a decision, not a
measurement**. The API half is what `k6/dashboard.js` asserts.

**The corpus has three projects.** A real org has more, and one project holding
1% of tickets is a scope shape this corpus cannot produce. It is the case where
`(project_id, created_at, id)` would look best, so the decision to omit that index
should be revisited if the project count ever grows substantially.

**`explain.sql` writes the list queries out longhand** rather than capturing them
from Hibernate. Kept readable at the cost of being a copy: if `TicketListSpecs`
changes shape, this file needs updating alongside it.

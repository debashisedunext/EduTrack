---
name: migration
description: Write an EduTrack Flyway migration correctly — timestamp naming, never editing an applied file, Stream A review for the protected tables, and the MySQL 8.4 constraints that reject DDL PostgreSQL would accept. Invoke when adding a table or column, changing schema, or when a migration fails to apply.
---

# Write a migration

Schema is Stream A's (`backend/domain/src/main/resources/db/migration/`). Other
streams write migrations for their own features and get them reviewed; the four
protected tables are never anybody else's to change alone.

## The two rules that break other people's machines

**1. Timestamp versioning only.**

```
V20260813_1030__ticket_status_requests.sql
 └ V<YYYYMMDD>_<HHMM>__<snake_case>.sql
```

Sequential `V14__` guarantees a collision the moment two developers add one on
the same day. The integration gate rejects any other shape.

**2. Never edit an applied migration.**

Flyway checksums them. Editing a file that has already run breaks every other
developer's database with a validation error they did not cause and cannot fix
without dropping data. **A correction is a new migration.** The gate fails the
build on any `M`, `D` or `R` against an existing migration file.

## What needs Stream A's review

Any migration touching **`tickets`, `ticket_history`, `ticket_effort_logs` or
`ticket_stage_transitions`**. Say so in the PR body and request Shivendra — do
not merge it into a batch on the assumption it is fine.

The three history tables are insert-only and hash-chained. A migration must not
add anything that implies mutation: no `updated_at`, no `ON UPDATE CURRENT_TIMESTAMP`,
no trigger that rewrites a row. The single permitted mutation anywhere is
sealing a stage transition (`exited_at` NULL → timestamp), and a DB trigger
rejects everything else.

## MySQL 8.4 will refuse things PostgreSQL accepts

The blueprint's DDL is PostgreSQL and does not apply as written (PLAN.md §2.2,
§3). Two errors cost real debugging time on D-055 and are not guessable from
the message:

**Error 3823 — a column cannot be in a `CHECK` and be the target of an
`ON DELETE SET NULL` foreign key.**

MySQL will not let the FK null a column that a constraint is asserting over.
The fix is to leave that column out of the `CHECK` and keep the columns that
genuinely move together inside it:

```sql
-- answer_message_id is deliberately absent: it is ON DELETE SET NULL, and
-- naming it here is rejected with 3823. answered_at, answered_by_id and
-- response_working_mins are the ones that must agree.
CONSTRAINT ck_answered_together CHECK (
    (answered_at IS NULL     AND answered_by_id IS NULL     AND response_working_mins IS NULL)
 OR (answered_at IS NOT NULL AND answered_by_id IS NOT NULL AND response_working_mins IS NOT NULL))
```

**Error 1215 — a foreign key with a referential action cannot sit on a column
that a generated column's expression reads.**

This one shapes the design, not just the syntax. A partial-unique index in
PostgreSQL becomes a generated column plus a plain unique key in MySQL:

```sql
open_requested_by_id BIGINT AS (IF(answered_at IS NULL, requested_by_id, NULL)) STORED,
UNIQUE KEY uq_open (ticket_id, open_requested_by_id)
```

If the expression reads a column that carries `ON DELETE CASCADE`, MySQL
refuses the FK with 1215. Generate from a column whose FK has no action, or
drop the action. **Probe it against the live container before committing** —
the error arrives at `flyway:migrate`, not at review:

```bash
docker exec -i edutrack-mysql mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-rootpw}" edutrack < /tmp/probe.sql
```

## Other conventions that are not negotiable

- **`DATETIME(6)`, never `TIMESTAMP`.** `TIMESTAMP` has the 2038 limit and does
  silent session-timezone conversion. Time is UTC everywhere in storage; the
  user's timezone is applied in the presentation layer only.
- **Never `COUNT(*)` for ticket ID generation.** Use the `LAST_INSERT_ID(expr)`
  idiom in PLAN.md §3.2 — a count races and reissues an ID under concurrency.
- **Never live `COUNT(*)` for dashboards.** Read the pre-aggregated summary
  tables.
- Charset and collation follow the existing files — read a recent migration
  rather than inventing a header.

## Before you open the PR

```bash
make up                       # MySQL, Redis, MinIO, Mailpit
cd backend && ./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local
```

Apply it for real. A migration that has only been read is not verified — both
MySQL errors above pass review comfortably and fail on contact with the server.

Then check the runtime grants still hold. `edutrack_app` has `SELECT` and
`INSERT` only on the three append-only tables, and a new table needs its grant:

```bash
make grants
```

Finally, `.claude/skills/finish-task/preflight.sh` checks the naming, the
no-edit rule and whether you have touched a protected table.

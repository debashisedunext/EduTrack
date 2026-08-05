---
name: stream-platform
description: Load Stream A (Platform & Security) context for the TaskDesk project — schema, migrations, auth, the scope guard, immutability core, CI, dashboard and reports. Invoke at the start of a session when the developer is working on Stream A, or says they are the platform/security/foundation developer.
---

# Stream A — Platform & Security

You are working as **Stream A** on TaskDesk.

## First, orient

1. Read `docs/streams/STREAM-A-PLATFORM.md` — your task backlog. Find the first unchecked task in the current milestone.
2. Check `git branch --show-current`. If on `develop` or `main`, create `feat/platform/<slug>` before writing anything.
3. If the developer named a task ID (A-034), go straight to it. Otherwise report the next 3 unchecked tasks and ask which to start.

## Your scope

**You own:** `backend/common/`, `backend/domain/db/migration/`, `backend/api/security/`, `backend/api/feature/{auth,dashboard,reports}/`, `docker-compose.yml`, `.github/`, `frontend/src/features/{auth,dashboard,reports}/`

**You do not touch:** `feature/{masters,clients,imports,workflow}` (B) · `feature/{tickets,transitions}`, `components/ui`, `styles/tokens.css` (C) · `worker/`, `feature/{notifications,chat}`, `realtime/` (D)

Needing a change in someone else's path means saying so and coordinating — not editing it quietly.

## What makes Stream A different

**You are the critical path.** Three other developers are blocked on your first two weeks. Before anything else:

- **A-012 `dev-noauth` profile, due day 10.** B, C and D cannot build authenticated endpoints without it. It must reject startup outside `local` and be disabled in CI.
- **The baseline schema (A-003…A-009).** All ~28 tables in one pass. Piecemeal schema growth is how an append-only model gets compromised.

**You are the schema arbiter.** Every migration by any stream touching `tickets`, `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions` needs your review.

## Rules you enforce, not just follow

**The scope guard is the highest-risk component in the system** (blueprint §17). Build it centrally in `ScopeResolver` as a JPA `Specification` composed into every ticket query — never per-controller filtering. Out-of-scope IDs return **404, not 403**.

**The permission test matrix ships with the guard, not after it.** Every role × every route. A new route without a matrix entry fails the build.

**The immutability guarantee is four independent layers** — service (no `update`/`delete` method exists), DB grants (`INSERT, SELECT` only), DB triggers, and absent HTTP routes. Any one alone is insufficient. Prove them with negative tests that attempt mutation and assert the exception.

## MySQL translation

The blueprint's DDL is PostgreSQL. `docs/PLAN.md` §3 is normative and you implement it:

- `BIGSERIAL` → `BIGINT AUTO_INCREMENT` · `TIMESTAMPTZ` → `DATETIME(6)` in UTC · arrays → `JSON`
- Partial indexes → stored generated columns (`pcd_open`, `current_ticket_id`) — §3.3
- `UPDATE … RETURNING` → the `LAST_INSERT_ID(expr)` idiom — §3.2
- `RAISE EXCEPTION` → `SIGNAL SQLSTATE '45000'`, and MySQL needs **two** triggers where PostgreSQL had one — §3.5
- The hash chain is **per-ticket** behind `SELECT … FOR UPDATE`, or concurrent appends fork it — §3.7
- `ONLY_FULL_GROUP_BY` stays enabled; rewrite the roll-up query instead — §3.4

## Timestamp migrations

`V20260812_1430__description.sql`. Never edit an applied migration — Flyway checksums them and editing one breaks every other developer's database.

## When done

Verify against the Definition of Done in `CLAUDE.md`, check the task off in the backlog, commit with a conventional message, rebase on `develop`, push. **Do not merge** — Claude integrates.

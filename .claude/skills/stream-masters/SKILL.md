---
name: stream-masters
description: Load Stream B (Masters & Clients) context for the EduTrack project — resource/role/project masters, client master, Excel import wizard, working calendar, workflow template designer. Invoke at the start of a session when the developer is working on Stream B, or says they are the masters/clients developer.
---

# Stream B — Masters & Clients

You are working as **Stream B** on EduTrack.

## First, orient

1. Read `docs/streams/STREAM-B-MASTERS.md` — your task backlog. Find the first unchecked task in the current milestone.
2. Check `git branch --show-current`. If on `develop` or `main`, create `feat/masters/<slug>` before writing anything.
3. If the developer named a task ID (B-030), go straight to it. Otherwise report the next 3 unchecked tasks and ask which to start.

## Your scope

**You own:** `backend/api/feature/{masters,clients,imports,workflow}/`, `frontend/src/features/{masters,clients}/`

**You do not touch:** `common/`, `db/migration/`, `security/`, `feature/{auth,dashboard,reports}` (A) · `feature/{tickets,transitions}`, `components/ui`, `styles/tokens.css` (C) · `worker/`, `feature/{notifications,chat}`, `realtime/` (D)

You *own* the workflow template master; Stream C *consumes* it. Changing the template or stage contract means telling C.

## Two things the whole team is blocked on

**B-007 — the ticket fixture corpus.** 200 tickets across 3 projects with varied stages, iterations, cycles, breach states and effort logs. This is what lets D test the SLA scanner and C test the ribbon before either feature exists. Ship it in Sprint 0.

**B-024 — the working-hours service.** `workingHoursBetween(start, end)` and `addWorkingHours(start, n)`, honouring weekends, org holidays and resource leave. **Every SLA, duration and utilisation figure in the system routes through this.** Stream D cannot start the SLA engine without it, and blueprint §5 names it the most commonly missed requirement.

## Build the import wizard once

Blueprint §4B.3: "build it once, register two schemas." The Excel engine is a **schema registry** — clients and resources are two registrations, not two implementations. If you find yourself writing a second import flow, stop.

Two things that decide whether it works in practice:

- **Apache POI streaming, not the DOM reader.** The DOM reader loads an entire 5,000-row workbook into memory per concurrent import. Use SXSSF to write, event-driven SAX to read.
- **The dry run writes nothing.** Step 4 previews per-row outcomes — will create / will update / duplicate in file / rejected with reason — before any database write. A silent bulk import that half-succeeds is worse than no import at all.

Upsert on client code, never duplicate. Every import writes an `import_batch` row so a bad import is traceable and reversible as a set.

## Rules that bite in this stream

- **Reporting-manager cycle detection must work at any depth** (A→B→C→A), not just self-reference. The DB `CHECK` only catches the trivial case.
- **Stages in use may be deprecated, never deleted.** Deletion breaks every historical ribbon. Live tickets keep the template version they started on.
- **System roles are non-deletable.**
- **`project_code` is immutable once a ticket exists** — it's the ticket-ID prefix, and changing it orphans every historical code.
- **A client needs at least one primary contact** before it can be selected on a ticket.
- Deactivating a client with open tickets warns and blocks new tickets, but **never hides historical ones**.

## Timestamp migrations

`V20260812_1430__description.sql`. Never edit an applied migration. Anything touching `tickets` or the three append-only tables needs Stream A's review.

## When done

Verify against the Definition of Done in `CLAUDE.md`, check the task off in the backlog, commit with a conventional message, rebase on `develop`, push. **Do not merge** — Claude integrates.

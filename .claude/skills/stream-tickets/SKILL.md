---
name: stream-tickets
description: Load Stream C (Tickets & Ribbon) context for the TaskDesk project — ticket CRUD, detail page, cycles and reopen, comments, attachments, the Workflow Ribbon, handoff and the Journey grid. Invoke at the start of a session when the developer is working on Stream C, or says they are the tickets/ribbon developer.
---

# Stream C — Tickets & Ribbon

You are working as **Stream C** on TaskDesk.

## First, orient

1. Read `docs/streams/STREAM-C-TICKETS.md` — your task backlog. Find the first unchecked task in the current milestone.
2. Check `git branch --show-current`. If on `develop` or `main`, create `feat/tickets/<slug>` before writing anything.
3. If the developer named a task ID (C-051), go straight to it. Otherwise report the next 3 unchecked tasks and ask which to start.

## Your scope

**You own:** `backend/api/feature/{tickets,transitions}/`, `frontend/src/features/tickets/`, `frontend/src/components/{ui,ribbon}/`, `frontend/src/styles/tokens.css`

**You do not touch:** `common/`, `db/migration/`, `security/`, `feature/{auth,dashboard,reports}` (A) · `feature/{masters,clients,imports,workflow}` (B) · `worker/`, `feature/{notifications,chat}`, `realtime/` (D)

**You own the shared component library that all three other streams consume.** Changes there are **additive only** — changing an existing component's props needs a note to the affected streams. Storybook is the contract: if it isn't in Storybook, it isn't shared. Design tokens are frozen after Sprint 0; other streams request tokens rather than adding them.

Stream B joins you from week 10 to take the ribbon's frontend while you hold the service layer.

## What makes this stream hard

You carry ~40% of the product surface, and the ribbon is the hardest UI in it. Five things are easy to get subtly wrong and expensive to fix:

**1. Iterations and cycles are two independent counters.** `iteration_no` increments when a ticket is pushed *backwards* within a cycle (QA fails it). `cycle_no` increments when a ticket is **reopened after closure**. A ticket can read *Cycle 2 · Iteration 3 · currently in QA*. Each cycle has its **own ribbon**; selecting cycle 1 renders that journey read-only. Nothing is ever redrawn or lost.

**2. The Journey roll-up query must join effort logs on `cycle_no`** as well as stage and iteration. The blueprint's own query in §4A.5 omits it, so cycle 1's effort double-counts into cycle 2 after a reopen. `docs/PLAN.md` §3.4 has the corrected query. This is a real defect in the source document, not a MySQL artefact.

**3. The golden rule is server-side.** Only the *current stage owner* (plus PM and Admin) may advance a ticket. A Developer cannot push a ticket into Deployment while it sits with QA. Enforce it in the transition service, not in the UI.

**4. Active vs idle time is the point of the whole feature.** `idle = duration − Σ effort in that stage+iteration`. A stage with 2 days duration and 2 hours of effort is a queue problem, not a capacity problem — blueprint §4A.4 is right that this single insight justifies the ribbon.

**5. Ticket IDs come from `LAST_INSERT_ID(expr)`, never `COUNT(*)`.** See `docs/PLAN.md` §3.2. `COUNT(*)` breaks silently and only under concurrency, which means it passes every test you write and fails in production.

## The append-only rule is yours to protect

`ticket_history`, `ticket_effort_logs` and `ticket_stage_transitions` are insert-only and hash-chained. Your transition service writes to all three.

- **No `update()` or `delete()` method may exist** on these. Only `insert()`.
- The one permitted mutation is sealing a transition: `exited_at` NULL → timestamp plus `duration_mins`. A DB trigger rejects anything else, and the JPA entity is `@Immutable` with an explicit JPQL update so Hibernate never dirty-checks a full-column `UPDATE`.
- The History tab renders **no edit or delete affordance for anyone**, and the API rejects it even if the DOM is manipulated.

If a task seems to need mutation, the design is wrong — raise it rather than working around it.

## Details that decide adoption

- **Clipboard paste on attachments.** A support agent pasting a screenshot from Snipping Tool is the single most common attachment action.
- **Quick Update is two clicks, no reload.** It's the resource's daily driver. Effort logged there auto-stamps the current stage and iteration so it lands in the right journey row with no user action.
- **Comments default to internal, always.** An accidental leak costs far more than an extra click.
- **Ribbon readability at 8 stages on a laptop** — horizontal scroll with the current segment auto-centred, compact dot variant in lists, collapsed grouping for completed stages beyond the first three.

## When done

Verify against the Definition of Done in `CLAUDE.md`, check the task off in the backlog, commit with a conventional message, rebase on `develop`, push. **Do not merge** — Claude integrates.

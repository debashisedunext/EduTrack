---
name: stream-engines
description: Load Stream D (Engines & Realtime) context for the TaskDesk project — OpenAPI contract, SLA and escalation scanners, the mail engine, notification centre, WebSocket infrastructure and chat. Invoke at the start of a session when the developer is working on Stream D, or says they are the engines/realtime/notifications developer.
---

# Stream D — Engines & Realtime

You are working as **Stream D** on TaskDesk.

## First, orient

1. Read `docs/streams/STREAM-D-ENGINES.md` — your task backlog. Find the first unchecked task in the current milestone.
2. Check `git branch --show-current`. If on `develop` or `main`, create `feat/engines/<slug>` before writing anything.
3. If the developer named a task ID (D-023), go straight to it. Otherwise report the next 3 unchecked tasks and ask which to start.

## Your scope

**You own:** `backend/worker/`, `backend/api/feature/{notifications,chat}/`, `backend/api/realtime/`, `frontend/src/features/{chat,notifications}/`

**You do not touch:** `common/`, `db/migration/`, `security/`, `feature/{auth,dashboard,reports}` (A — note A owns the nightly hash verifier despite it living in `worker/`) · `feature/{masters,clients,imports,workflow}` (B) · `feature/{tickets,transitions}`, `components/ui`, `styles/tokens.css` (C)

## Your Sprint 0 unblocks the frontend

**D-001 the OpenAPI contract and D-004 the MSW mock server come before any feature work.** Stream C builds the entire ticket detail page — ribbon, tabs, journey grid — against your mocks, before a single ticket endpoint exists. Ship the contract first.

**D-005, the CI staleness check, is not optional.** Leaving TypeScript on the frontend and Java on the backend means validation can no longer be authored once and run on both sides. Bean Validation annotations on the Java DTOs are the single source of truth; springdoc emits them into the spec; codegen turns them into Zod schemas. The staleness check is the only thing catching drift, and it must fail the build.

## Node libraries the blueprint names that we do not use

`docs/PLAN.md` §2.2 is normative:

- **BullMQ → transactional outbox.** `email_log` **is** the queue — it already has `status`, `retry_count` and a provider message ID. Claim rows with `SELECT … FOR UPDATE SKIP LOCKED`; add `next_attempt_at` for backoff. Enqueue is atomic with the business transaction, so a rolled-back handoff cannot leave a phantom mail queued. `SKIP LOCKED` is also what lets workers scale horizontally.
- **Socket.IO → Spring WebSocket + STOMP** with a Redis relay. Rooms from blueprint §9.3 become STOMP destinations.
- **Quartz** is an alternative to `@Scheduled` + ShedLock if you need cron expressions per tenant; start with ShedLock.

## Two rules that are easy to miss

**1. Authorise WebSocket subscriptions with the same scope rules as REST.** A `ChannelInterceptor` must reject a subscription to `/topic/ticket.{id}` from a user who could not `GET` that ticket. This is the socket-layer equivalent of Stream A's scope guard, and skipping it reopens the exact hole the guard closes.

**2. Never write your own date maths.** Every SLA, duration, breach and utilisation calculation routes through Stream B's working-hours service (B-024), which honours weekends, org holidays and resource leave. A ticket raised Friday 18:00 with a 4-hour SLA must not breach on Saturday morning. Blueprint §5 calls this the most commonly missed requirement in systems of this kind.

## Mail rules that are product decisions, not implementation details

- **"Critical mails cannot be disabled."** Assignment, handoff, escalation and breach mails ignore user preferences. Everything else respects them.
- **Ticket ID first in the subject** — `[CRM-26-00347] Handed to you at QA by Ravi Kumar` — so it threads and searches cleanly.
- **`Message-ID` / `In-Reply-To` keyed on the ticket**, so an entire ticket's mail collapses into one thread in Outlook and Gmail.
- **Every send logged**, three retries with exponential backoff, then an in-app failure notification. Nobody should assume a mail arrived that never did — blueprint §17 wants a missed alert to be *provable rather than deniable*.
- **Rate limit one mail per recipient per ticket per minute**, or a burst of updates spams the assignee into ignoring all of them.
- **Offline notifications are queued and pop on next login.** Nothing is lost.

## Stage SLA is not ticket SLA

Two separate scanners. A ticket can sit comfortably inside its Planned Close Date while rotting four days in the Deployment queue. Per-stage SLAs are what make the ribbon actionable rather than decorative (blueprint §16 item 3b).

## Chat is evidence

Immutable after a 5-minute edit window; deletions leave tombstones. This is what keeps chat admissible when a client disputes what was agreed.

## When done

Verify against the Definition of Done in `CLAUDE.md`, check the task off in the backlog, commit with a conventional message, rebase on `develop`, push. **Do not merge** — Claude integrates.

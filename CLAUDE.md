# TaskDesk — Working Agreement

Auto-loaded in every session. These rules apply to all four streams whether or not a stream skill was invoked.

## What this project is

TaskDesk: an organisation task and client ticketing platform. 34 screens, 6 roles, ~28 tables.

| Document | What it is |
|---|---|
| `docs/Ticketing-System-Blueprint.md` | Product spec — the authority on **behaviour**. Every screen, field and rule. |
| `docs/PLAN.md` | Build spec — the authority on **implementation**. Stack, MySQL translation, milestones M0–M7. |
| `docs/TEAM-PLAN.md` | Streams, timeline, ownership map, git workflow. |
| `docs/streams/STREAM-*.md` | Live task backlogs, one per developer. |

Where the blueprint and PLAN.md disagree, the blueprint wins on behaviour and PLAN.md wins on implementation. PLAN.md §4 lists every intentional deviation — none are silent.

## Stack

Java 21 · Spring Boot 3.3 · MySQL 8.4 · Redis · React 18 + TypeScript + Vite · Tailwind + shadcn/ui.

**The blueprint recommends NestJS + PostgreSQL. We do not use that.** Its DDL, BullMQ, Socket.IO, SheetJS and shared-Zod guidance do not apply as written — PLAN.md §2.2 and §3 carry the substitutions.

## First, identify your stream

Start each session by invoking your stream skill:

| Developer | Command | Owns |
|---|---|---|
| Stream A | `/stream-platform` | Schema, auth, scope guard, immutability, CI, dashboard, reports |
| Stream B | `/stream-masters` | Master screens, clients, Excel import, calendar, workflow designer |
| Stream C | `/stream-tickets` | Ticket CRUD, cycles, comments, attachments, the Workflow Ribbon |
| Stream D | `/stream-engines` | SLA scanners, mail, notifications, WebSocket, chat |

## Rules that apply to everyone

### Code ownership

Work only in your stream's paths (TEAM-PLAN.md §6). Touching another stream's directory needs that owner's sign-off — say so rather than doing it quietly.

Backend uses **feature packaging, not layer packaging**: `api/feature/tickets/` holds its own controller, service, repository and DTOs. Never create shared `controllers/` or `services/` folders — that is what makes four developers collide daily.

### Database migrations

- **Timestamp versioning only:** `V20260812_1430__add_client_contacts.sql`. Sequential `V14__` guarantees collisions.
- **Never edit an applied migration.** Flyway checksums them; editing one breaks every other developer's database. Corrections are new migrations.
- Any migration touching `tickets`, `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions` needs Stream A's review.

### The append-only rule

`ticket_history`, `ticket_effort_logs` and `ticket_stage_transitions` are insert-only and hash-chained.

- **No service method may expose `update()` or `delete()` on them.** Only `insert()`.
- No `PUT`, `PATCH` or `DELETE` route may be registered for `/history` or `/effort-logs`.
- A correction is a new compensating row (`is_correction`, `corrects_entry_id`), like an accounting reversal.
- The one permitted mutation is sealing a stage transition (`exited_at` NULL → timestamp). A DB trigger rejects everything else.

This is the guarantee that erodes first and is hardest to restore. If a task seems to need mutation, the design is wrong — raise it.

### Row scoping

Every ticket query is scoped server-side by `ScopeResolver`, never by a frontend filter. Developer/QA/Deployment see `assigned_to = me`; PM/Support see their projects; Admin sees all.

Out-of-scope IDs return **404, not 403** — no existence leak.

Until Stream A lands the guard (week 7), use the `dev-noauth` profile. **Do not write your own filtering as a workaround** — it becomes a permanent security hole and is the top risk in blueprint §17.

### Git

- Branch from `develop`, never `main`. Name: `<type>/<stream>/<slug>` — `feat/tickets/ribbon-handoff-dialog`.
- Rebase on `develop` daily. Never merge `develop` into your branch.
- Conventional commits: `feat(tickets): add handoff dialog with mandatory effort confirmation`.
- PRs under ~400 changed lines.
- Push daily, even work in progress.
- **Developers never merge.** Claude integrates branches into `develop` and promotes to `main`.

### Never commit

`.env*`, `application-local.yml`, credentials, `target/`, `node_modules/`, `.idea/`, `.DS_Store`.

## Conventions

- **Time is UTC everywhere in storage.** `DATETIME(6)`, never `TIMESTAMP` (2038 limit, silent session-timezone conversion). User timezone is applied in the presentation layer only.
- **All SLA and duration maths use the working calendar** — weekends, org holidays, resource leave. A Friday-18:00 ticket with a 4-hour SLA must not breach on Saturday morning.
- **Never `COUNT(*)` for ticket ID generation.** Use the `LAST_INSERT_ID(expr)` idiom in PLAN.md §3.2.
- **Never live `COUNT(*)` for dashboards.** Read the pre-aggregated summary tables.
- `frontend/src/api/generated/` is generated. Never hand-edit; regenerate.
- Design tokens come from blueprint §12.1. Never introduce a colour that isn't a token.
- Light theme only. Accessibility is not optional: WCAG AA, keyboard navigation, ARIA labels on charts and the ribbon.

## Definition of done

- [ ] Works against the real backend, not only mocks
- [ ] Unit tests for logic; integration tests for new endpoints
- [ ] New routes have permission-matrix entries for **all six roles**
- [ ] Migrations timestamp-versioned; no applied file edited
- [ ] OpenAPI spec updated; client regenerated
- [ ] Storybook entry for any new shared component
- [ ] No new lint or compiler warnings
- [ ] Rebased on current `develop`, CI green
- [ ] Only your stream's paths touched, or sign-off obtained

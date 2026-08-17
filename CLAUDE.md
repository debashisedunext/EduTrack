# EduTrack — Working Agreement

Auto-loaded in every session. These rules apply to all four streams whether or not a stream skill was invoked.

## What this project is

EduTrack: an organisation task and client ticketing platform. 34 screens, 6 roles, ~28 tables.

| Document | What it is |
|---|---|
| `docs/Ticketing-System-Blueprint.md` | Product spec — the authority on **behaviour**. Every screen, field and rule. |
| `docs/PLAN.md` | Build spec — the authority on **implementation**. Stack, MySQL translation, milestones M0–M7. |
| `docs/TEAM-PLAN.md` | Streams, timeline, ownership map, git workflow. |
| `docs/streams/STREAM-*.md` | Live task backlogs, one per developer. |
| `docs/DEPENDENCIES.md` | Ownership index, cross-stream dependency register, critical path, parallel bands. |

Where the blueprint and PLAN.md disagree, the blueprint wins on behaviour and PLAN.md wins on implementation. PLAN.md §4 lists every intentional deviation — none are silent.

## Stack

Java 25 · Spring Boot 3.5 · MySQL 8.4 · Redis · React 18 + TypeScript + Vite · Tailwind + shadcn/ui.

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
- **A task ID in a commit subject means that commit finishes the task.** The plan reads status from git — an ID in a subject that reaches `develop` marks the task done, and there is no way for it to tell "the commit that mentions D-045" from "D-045 is finished". For partial work, name the task in the commit **body** instead, where the parser does not look. This has already gone wrong four times: D-045 and D-053 both read as done on their first commit and had to be corrected in [`overrides.json`](docs/plan/overrides.json), and A-010/A-013 landed under a subject reading `A011` without the hyphen, so git could prove nothing about either. Free to follow, tedious to retrofit.
- PRs under ~400 changed lines.
- Push daily, even work in progress.
- **Open every PR as a draft.** Push to it as often as you like — drafts run no CI, which is what keeps "push daily" affordable. Mark it **Ready for review** when you want it verified.
- **Developers never merge.** Claude integrates branches into `develop` and promotes to `main`.

### Verification before merge — the hard rule

**Every PR is verified before it is merged. No exception, and no PR merges any other way.**

**GitHub Actions is the authority again, as of 17 Aug 2026.** The repository was made public, which restores unlimited Actions minutes *and* branch protection — so `.github/workflows/ci.yml` runs on every push and every PR, and the platform can refuse an unverified merge on its own.

CI runs: migration guard · backend `mvn verify` · frontend lint, test and build · OpenAPI validity, conventions and client staleness · the packaged jar actually starting and serving a request.

**`tools/integration-gate.sh` is retired.** It exists only as insurance if Actions is ever unavailable again; its header says so. Do not run it as a matter of course — a full run is ~50 minutes of a machine that cannot be used for anything else meanwhile, which is now GitHub's time to spend rather than yours.

**What to run locally: unit and smoke tests for what you touched.** Not the whole suite.

```bash
cd frontend && npm run test -- --run src/features/<area>
cd frontend && npm run lint && npx tsc --noEmit -p tsconfig.app.json
cd backend  && ./mvnw -pl api -Dtest=<SomeUnitTest> test     # no container
cd backend  && ./mvnw -pl api -Dit.test=<OneIT> verify       # one smoke IT
```

Push and let CI do the rest. A red check on your PR is cheaper than an hour of your laptop.

### Integration happens in batches, across all four streams

**A batch is every PR that is ready at that moment, whoever wrote it** — not one stream's, and not one PR at a time.

That scope is the whole point. On 12 Aug three verification runs in a row were invalidated by *somebody else's* merge landing while they were in progress — A-076, then B-013, then A-031. Batching one stream's work fixes nothing, because the race is with the other three. One batch, one CI run on the combined result, one push, and the window in which `develop` can move underneath it is a single push wide instead of four.

How it runs:

1. Claude collects every PR marked ready and merges them together into one integration branch.
2. CI runs **once**, on that combined result.
3. Green: all of them merge, and `develop` moves once.
4. Red: the PR that broke it is dropped, the rest are re-run and merged, and the offender goes back to its author. **One bad PR does not hold three good ones hostage.**

Twice a working day, or whenever the queue is worth clearing — the point is to batch what is ready, not to wait for a clock.

**Keep opening PRs while a batch is in flight.** They cost nothing, they are how the other three see and review your work, and holding them back would make work invisible until integration day without helping the race at all — the queue is of *merges*, not of PRs. Open early, open as a draft, mark ready when your own unit tests are green and let CI take it from there.

**Why the rule outlived its workaround.** Between 11 and 17 Aug 2026 the Actions allowance was exhausted and the repository was private, so nothing on the platform could refuse an unverified merge — and A-030 reached `develop` unverified during a two-day outage. `tools/integration-gate.sh` existed to close that window locally. It closed it badly: a run cost ~50 minutes on a machine that could do nothing else, so it was skipped, and on 16–17 Aug seven PRs reached `develop` with it never run. **A verification step nobody can afford is a verification step nobody performs.**

Going public fixed the cause rather than the symptom. Enforce it where it cannot be skipped: **branch protection on `develop` with the CI checks required**. That is stronger than any convention in this file, because it does not depend on anyone remembering.

**The rule itself never lapsed and does not now:** verified, then merged, always.

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
- [ ] Rebased on current `develop`
- [ ] Unit and smoke tests green locally for what you touched — then push and let CI run the full suite. `make verify` runs everything and takes the best part of an hour; it is no longer what stands between a mistake and `develop`, because CI is
- [ ] Only your stream's paths touched, or sign-off obtained

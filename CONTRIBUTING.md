# Contributing to TaskDesk

One page. Read it once, follow it every day.

---

## Before you write any code

```bash
git clone https://github.com/debashisedunext/EduTrack.git && cd EduTrack
git checkout develop
claude
```

Then invoke your stream skill:

| You are | Command | Backlog |
|---|---|---|
| Stream A — Platform & Security | `/stream-platform` | `docs/streams/STREAM-A-PLATFORM.md` |
| Stream B — Masters & Clients | `/stream-masters` | `docs/streams/STREAM-B-MASTERS.md` |
| Stream C — Tickets & Ribbon | `/stream-tickets` | `docs/streams/STREAM-C-TICKETS.md` |
| Stream D — Engines & Realtime | `/stream-engines` | `docs/streams/STREAM-D-ENGINES.md` |

New to the project? Read blueprint **§2, §3, §4 and §4A** first — see `docs/GETTING-STARTED.md` §5.3. They are the shared mental model, and skipping them produces code that looks right and is wrong.

---

## The daily loop

```bash
# start of day — always branch from develop, never from main
git checkout develop && git pull
git checkout -b feat/tickets/quick-update-panel

# ... work ...

git add -A
git commit -m "feat(tickets): add quick update slide-over with optimistic UI"
git pull --rebase origin develop        # daily, without exception
git push -u origin feat/tickets/quick-update-panel
```

Then open a pull request into `develop` and say it is ready. **You do not merge it** — Claude integrates.

---

## Branch and commit naming

**Branches:** `<type>/<stream>/<slug>`

- `<type>` — `feat` · `fix` · `chore` · `refactor` · `docs`
- `<stream>` — `platform` · `masters` · `tickets` · `engines`
- `<slug>` — kebab-case, describes the change

```
feat/tickets/cycle-reopen-transaction
fix/engines/mail-retry-backoff
chore/platform/testcontainers-mysql
```

**Commits:** Conventional Commits.

```
feat(tickets): add handoff dialog with mandatory effort confirmation
fix(engines): honour working calendar in stage-SLA breach check
chore(platform): pin Flyway to 10.17
```

---

## The seven rules

1. **Branch from `develop`**, never from `main`.
2. **Rebase on `develop` daily.** A branch unrebased for a week becomes someone else's problem.
3. **Small pull requests** — target under 400 changed lines. A 3,000-line PR cannot be reviewed or cleanly conflict-resolved.
4. **No merge commits inside a feature branch.** Rebase, so conflicts are resolved once rather than replayed.
5. **Push at least once a day**, even work in progress. Unpushed work is invisible work.
6. **CI must be green** before requesting integration. A red branch is not merged.
7. **Never commit** `.env`, `application-local.yml`, credentials, `target/`, `node_modules/`, `.DS_Store`.

---

## Stay in your lane

Work only in your stream's owned paths — the map is in `docs/TEAM-PLAN.md` §6.

Needing a change in another stream's directory means **saying so and coordinating**, not editing it quietly. `.github/CODEOWNERS` makes GitHub request the right reviewer automatically; that reviewer is who signs off.

Backend uses **feature packaging, not layer packaging**: `api/feature/tickets/` holds its own controller, service, repository and DTOs. Never create shared `controllers/` or `services/` folders — that is precisely what makes four developers collide daily.

---

## The rules that bite

**Migrations**
- Timestamp versioning only: `V20260812_1430__add_client_contacts.sql`. Sequential `V14__` guarantees collisions.
- **Never edit an applied migration.** Flyway checksums them; editing one breaks every other developer's database. Corrections are new migrations.
- Anything touching `tickets`, `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions` needs Stream A's review.

**The append-only rule**
- `ticket_history`, `ticket_effort_logs` and `ticket_stage_transitions` expose **`insert()` only**. No update, no delete, no such route.
- A correction is a new compensating row (`is_correction`, `corrects_entry_id`), like an accounting reversal.
- The one permitted mutation is sealing a stage transition (`exited_at` NULL → timestamp). A database trigger rejects everything else.
- If a task seems to need mutation, the design is wrong — raise it.

**Row scoping**
- Every ticket query is scoped server-side, never by a frontend filter. Out-of-scope IDs return **404, not 403**.
- Until Stream A lands the guard (week 7), use the `dev-noauth` profile. **Do not write your own filtering as a workaround** — it becomes a permanent security hole.

**Conventions**
- All timestamps stored UTC, `DATETIME(6)`. User timezone is presentation-layer only.
- All SLA and duration maths use the working-calendar service — weekends, holidays, leave.
- Never `COUNT(*)` for ticket IDs; never live `COUNT(*)` for dashboards.
- `frontend/src/api/generated/` is generated. Never hand-edit; regenerate.
- Never introduce a colour that isn't a design token.

---

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

---

## Pull request description

```
## What changed


## Stream
platform | masters | tickets | engines

## Task IDs
A-034, C-051 …

## Paths touched
Only my stream's paths?  yes / no — if no, who signed off:

## Migration
none | added V20260812_1430__…

## Tests
unit / integration / permission-matrix entries added

## Checklist
- [ ] Rebased on develop, pipeline green
- [ ] OpenAPI updated and client regenerated (if endpoints changed)
- [ ] No append-only table gained an update or delete path
```

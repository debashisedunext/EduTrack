# EduTrack — Team Structure, Work Division & Git Workflow

**Companion to:** `PLAN.md` (milestones M0–M7) and `Ticketing-System-Blueprint.md` (product spec)
**Team:** 4 developers
**Integration model:** developers push to feature branches → Claude reviews, resolves conflicts and merges → `main`
**Revision:** 1.0 · 2026-08-04

---

## 1. The problem this plan solves

Four developers on one codebase produce three predictable failures. Everything below is designed against them:

1. **The blocking chain.** `PLAN.md` sequences M0 → M1 → M2 → M3/M4 → M5 → M6 → M7 by dependency. Read literally, three developers sit idle for six weeks waiting for the schema and the security spine. §3 and §5 break that chain without breaking the dependency order.
2. **Merge collisions.** A conventional Spring Boot layout (`controllers/`, `services/`, `repositories/`) guarantees that all four developers edit the same four folders every day. §6 replaces it with feature-packaging so ownership is physical, not social.
3. **Silent contract drift.** Four people building against an API that doesn't exist yet will each assume a different shape for it. §4 makes the OpenAPI spec a Sprint 0 deliverable, so the contract is agreed before any of it is implemented.

---

## 2. The four streams

Ownership is **vertical** — each developer owns a slice of the product from database to screen — rather than horizontal (two backend, two frontend). Vertical ownership is what makes parallel work actually parallel: a horizontal split means every feature needs two people to coordinate before it can ship, and the frontend developer is idle whenever the backend developer is behind.

| Stream | Name | Owns | Milestones |
|---|---|---|---|
| **A** | Platform & Security | Schema, migrations, auth, the scope guard, immutability core, CI/CD, then dashboard & reports | M0, M1, M2, M6 |
| **B** | Masters & Clients | All 13 master screens, client master, Excel import, working calendar, workflow template designer | M3, then joins M4 |
| **C** | Tickets & Ribbon | Ticket CRUD, detail page, cycles/reopen, comments, attachments, the Workflow Ribbon, handoff, Journey grid | M4 |
| **D** | Engines & Realtime | SLA/escalation scanners, mail engine, notification centre, WebSocket infrastructure, chat | M5, M7 |

### Assigned owners

| Stream | Owner | Email | GitHub |
|---|---|---|---|
| **A** — Platform & Security | Shivendra | `shivendra.edunext@gmail.com` | `@shivendraedunext-18` |
| **B** — Masters & Clients | Ayush | `ayush.edunext123@gmail.com` | `@Ayushedunext` |
| **C** — Tickets & Ribbon | Divyansh | `divyansh.edunext@gmail.com` | `@Divyanshedunext` |
| **D** — Engines & Realtime | Debashis | `debashis@edunexttechnologies.com` | `@debashisedunext` |

These handles are live in `.github/CODEOWNERS`. Each must have **Write** access to the repository, or GitHub silently ignores the rule and requests nobody.

### Who should take which

- **Stream A** needs the strongest backend and infrastructure person. Everything else depends on their first six weeks, and the scope guard is the single highest-risk component in the system. They are also the natural schema arbiter.
- **Stream C** is the largest slice — roughly 40% of the product surface and the hardest UI in it (the ribbon). Give it the strongest all-rounder, and note that Stream B joins them from week 8.
- **Stream B** is broad but shallow: many screens, few hard problems. Good for the developer who is fastest at CRUD and forms. The Excel import wizard is the one genuinely tricky piece.
- **Stream D** is the most independent — workers, queues and sockets touch little of the request path. Suits someone comfortable with async and scheduling, and it is the stream least damaged by starting slightly later.

---

## 3. Sprint 0 (weeks 1–2) — unblocking all four from day one

Nobody waits. Each stream has two weeks of work that depends on nothing anyone else is writing.

| Stream | Sprint 0 deliverable | Depends on |
|---|---|---|
| **A** | Maven multi-module skeleton · `docker-compose.yml` (MySQL, Redis, MinIO, Mailpit) · **the complete Flyway baseline schema** — all ~28 tables, triggers, generated columns and indexes, translated per `PLAN.md` §3 · CI pipeline · the two DB users and their grants | Nothing |
| **B** | **All seed data** — 6 roles + permission matrix, 11 task types, 4 priorities, statuses and the transition matrix, 3 workflow templates with their stages · JPA entities and repositories for the full model · MapStruct base config | A's schema, from day 3 |
| **C** | React scaffold · design tokens from blueprint §12.1 into `tokens.css` and `tailwind.config.ts` · **the shared component library** — buttons, inputs, table, chips, modal, slide-over, toast, skeleton, empty state · Storybook | Nothing |
| **D** | **The OpenAPI contract for every endpoint in blueprint §13** · the codegen pipeline (springdoc → TypeScript client) · MSW mock server returning realistic fixtures · the CI staleness check | Nothing |

**Why the schema is written by one person.** Twenty-eight interlocking tables authored by four people produce four naming conventions, four opinions on nullability, and a merge conflict on every migration file. A writes it once; B reviews it while building the entities on top, which is the fastest way to find its mistakes.

### Sprint 0 exit criteria

- `docker compose up` yields a migrated database with full seed data
- `mvn verify` green, including the negative tests proving the immutability triggers reject `UPDATE` and `DELETE`
- `npm run storybook` renders the component library in the correct tokens
- `npm run dev` serves the React shell against MSW mocks with no backend running
- The OpenAPI spec is reviewed and agreed by all four

---

## 4. Three decouplers that keep the streams independent

Without these, the dependency chain in `PLAN.md` serialises the team. Each one is cheap to build and is the difference between four people working and one person working while three wait.

**1. The mock server (owned by D, built in Sprint 0).** Every frontend feature is developed against MSW handlers generated from the OpenAPI spec. C can build the entire ticket detail page — ribbon, tabs, journey grid — before a single ticket endpoint exists. When the real endpoint lands, the only change is a flag.

**2. The no-auth dev profile (owned by A, built in Sprint 0).** Stream A does not finish the security spine until week 7, but B, C and D need authenticated endpoints from week 3. A ships a `dev-noauth` Spring profile on day 10 that injects a configurable fake principal — role, project list, reportee list — so the others develop against realistic scope behaviour immediately. **The profile is rejected at startup if the environment is not `local`**, and CI runs the full suite with it disabled.

**3. Seed fixtures as the contract between streams (owned by B, built in Sprint 0).** D's SLA scanner needs tickets that C hasn't built yet. C's ribbon needs workflow templates that B hasn't built the admin UI for yet. Both problems disappear if the seed set includes a realistic corpus — 200 tickets across 3 projects, various stages, iterations, cycles and breach states. Build the fixtures once, in Sprint 0, and every stream develops against real-looking data from week 3.

---

## 5. Phase timeline

Roughly 18 weeks, consistent with the blueprint's own 5-month estimate.

| Weeks | Stream A — Platform | Stream B — Masters | Stream C — Tickets | Stream D — Engines |
|---|---|---|---|---|
| **1–2** | M0 scaffold, schema, CI | Seed data, entities | Design system, Storybook | OpenAPI contract, mocks |
| **3–5** | M1 auth, JWT, refresh rotation | S-07–S-12 resource/role/project/type/priority masters | S-19 create, S-17 list | Outbox + scheduler + STOMP infrastructure |
| **6–7** | M1 scope guard + **permission matrix suite** | S-32–S-34 client master + Excel import | S-20 detail shell, S-21 quick update | M5 SLA + stage-SLA scanners, working-calendar maths |
| **8–9** | M2 immutability core, hash chain | S-13/S-30 workflow master + designer | M4 cycles, reopen, comments, attachments | M5 escalation, mail engine, threading |
| **10–11** | M6 aggregation tables + dashboard | **joins C** — ribbon UI, segment states, compact variant | M4 handoff service, transitions, Journey grid | M5 notification centre, S-26, preference matrix |
| **12–14** | M6 widgets 1–20, drill-down | M6 reports + exports (Excel/CSV/PDF) | M4 S-22/S-23/S-24/S-29/S-31 | M7 chat, Ask Status, S-25 |
| **15–16** | Performance, indexes, load test, S-16 audit viewer | S-28 Resource 360, scheduled reports | E2E walkthroughs A/B/C | M7 finish, live ribbon push |
| **17–18** | Security review, hardening | UAT fixes | UAT fixes | UAT fixes, go-live runbook |

**The one hard dependency to watch:** A's scope guard (week 7). Until it lands, every list endpoint is unscoped. B, C and D must not write their own filtering as a workaround — the `dev-noauth` profile exists precisely so they don't. A workaround here becomes a permanent security hole, and it is the exact failure blueprint §17 ranks as the top risk.

---

## 6. Code ownership map

**Feature packaging, not layer packaging.** The default Spring layout puts every controller in one folder; four developers then edit that folder daily. Instead, each feature package holds its own controller, service, repository and DTOs, so a stream's work lives in directories nobody else touches.

```
backend/
  common/                      → A    shared DTOs, exceptions, hashing, OpenAPI config
  domain/db/migration/         → A    Flyway (schema arbiter — see §7.1)
  api/security/                → A    filter chain, scope resolver, guards
  api/arch/            (test)  → A    ArchUnit rules — layering, scope, append-only
  api/feature/auth/            → A
  api/feature/dashboard/       → A
  api/feature/reports/         → A
  api/feature/masters/         → B    roles, task types, priorities, statuses, calendar
  api/feature/clients/         → B
  api/feature/imports/         → B    Apache POI wizard (shared engine, two schemas)
  api/feature/workflow/        → B    templates, stages  (C consumes, B owns)
  api/feature/fixtures/        → B    B-007 seed corpus  (unowned until A-040)
  api/feature/tickets/         → C    CRUD, detail, cycles, comments, attachments
  api/feature/transitions/     → C    handoff, rework, skip, ribbon, journey
  api/feature/notifications/   → D
  api/feature/chat/            → D
  api/realtime/                → D    STOMP config, channel interceptor, topics
  worker/                      → D    all schedulers
  worker/journal/              → A    A-044's hash-chain verifier
  worker/stats/                → A    A-051's summary refresh  ⚠️ see note below

frontend/src/
  components/ui/               → C    shared design system  (additive changes only)
  components/ribbon/           → C
  styles/tokens.css            → C    frozen after Sprint 0; others request, never add
  api/generated/               → nobody — generated, never hand-edited
  features/auth|dashboard|reports/  → A
  features/masters|clients/    → B
  features/tickets/            → C
  features/chat|notifications/ → D
```

This map is committed as `.github/CODEOWNERS`, so GitHub requests the right reviewer automatically on every pull request. Keep the two in sync — the file is the enforceable version of this table.

> ⚠️ **`worker/` is D's, with two carve-outs — and the second one is new. Needs Debashis's confirmation.**
>
> The row used to read `worker/ → D, all schedulers (A's hash verifier is the exception)`, and that parenthesis has been out of date since A-051. Two directories under `worker/` are Stream A's work, for the same reason: both are *scheduled jobs serving A's own subsystem*, and neither is an engine.
>
> - `worker/journal/` — A-044's hash-chain verifier, which the old parenthesis already meant.
> - `worker/stats/` — A-051's summary refresh. It exists because CLAUDE.md forbids a live `COUNT(*)` behind a dashboard, so A-050's summary tables need something to fill them. **A-056 and A-057 have since edited it three times** — `type_counts`, `assigned_in_progress`, then `sla_closed`/`sla_met` — each flagged in the code and in a pull request, each time against a map that said the file was D's.
>
> Splitting the row is the honest fix. The alternative is that every dashboard widget needing a new aggregate arrives as an unannounced edit to another stream's directory, which is precisely what §6 exists to prevent — and A-058 and A-059 both need aggregates that do not exist yet, so this recurs immediately if left alone.
>
> **This entry records the state of the code, not an agreement.** It was raised by Stream A and is written here so the map stops disagreeing with the repository; it is not D's assent, which is still outstanding. If Debashis would rather own the stats refresh outright, the carve-out comes back out and A-058's aggregates get raised as requests instead — either answer is workable, and the one thing that is not is the map staying wrong. `.github/CODEOWNERS` is deliberately **not** updated until that conversation happens, because that file is the enforceable version and changing who GitHub asks for review is exactly the part that needs both streams to agree.

> ⚠️ **The table above is a package map; CODEOWNERS matches paths.** `api/feature/auth/` here means `backend/api/src/main/java/com/edunext/edutrack/api/feature/auth/` on disk, and until 14 Aug 2026 CODEOWNERS was written the short way — so 17 of its 20 backend rules matched nothing, every pull request requested only the lead, and no stream was ever auto-notified of a change to its own code. Fixed in A-040 by dropping the leading slash so the patterns match at any depth. **If you add a row here, add the path form to CODEOWNERS and prove it resolves** with `git ls-files | grep -E '<pattern>'` — an empty result is a rule that does nothing and looks exactly like one that works.

---

## 7. Conflict hotspots and the rules that defuse them

Seven files will otherwise conflict on most merges. Each has a rule.

### 7.1 Flyway migrations — the worst offender

Sequential `V1__`, `V2__` numbering means two developers who both add a migration on Tuesday both pick `V14__`. Flyway then refuses to start.

> **Rule: timestamp versioning.** `V20260812_1430__add_client_contacts.sql`. Collisions become impossible.
> **Rule: never edit an applied migration.** Corrections are new migrations. Flyway checksums applied files, and editing one breaks every other developer's database.
> **Rule: A is the schema arbiter.** Any migration touching `tickets`, `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions` needs A's review. The append-only guarantees live in those tables and a careless `ALTER` bypasses them.

### 7.2 Generated OpenAPI client

`frontend/src/api/generated/` is a large generated tree that conflicts constantly and is meaningless to merge by hand.

> **Rule: on any conflict, take neither side — regenerate.** `.gitattributes` marks the directory `merge=ours` to stop Git trying. A pre-commit hook regenerates it.

### 7.3 Seed data

> **Rule: one seed file per stream** (`seed_masters.sql`, `seed_tickets.sql`, …), never one shared file. Loaded in a fixed order by a manifest A owns.

### 7.4 Security configuration and route registration

> **Rule: A owns the filter chain.** Streams contribute their own `@Configuration` per feature package rather than editing a central file. New route permissions are requested in the PR description; A applies them.

### 7.5 Design tokens and Tailwind config

> **Rule: frozen after Sprint 0.** Blueprint §12.1 is complete; a stream needing a new token asks A rather than adding one, or the palette drifts across four features.

### 7.6 `pom.xml` and `package.json`

> **Rule: dependency additions are announced before they are committed** — in the team channel, one line. Two developers adding different JSON libraries in the same week is a real and tedious conflict. Lockfile conflicts are resolved by regenerating, never by hand-editing.

### 7.7 The shared component library

C owns `components/ui/`, but all four consume it.

> **Rule: additive only.** Changing an existing component's props needs a note to the affected streams. Storybook is the contract — if it isn't in Storybook, it isn't shared.

---

## 8. Git workflow

### 8.1 Branch model

```
main                      protected · release-only · tagged · Claude promotes, nobody pushes
 └── develop              protected · integration · Claude merges, nobody pushes
      ├── feat/platform/jwt-refresh-rotation
      ├── feat/masters/client-excel-import
      ├── feat/tickets/ribbon-handoff-dialog
      └── feat/engines/sla-scanner
```

Two protected branches rather than one. `develop` is where the four streams actually integrate and where conflicts surface; `main` only ever receives a `develop` that has already been proven green. Merging four streams straight into `main` would mean every conflict is resolved against a branch that is also the release branch.

### 8.2 Branch naming

`<type>/<stream>/<short-slug>`

- `<type>` — `feat` · `fix` · `chore` · `refactor` · `docs`
- `<stream>` — `platform` · `masters` · `tickets` · `engines`
- Slug in kebab-case, describing the change, not the ticket number

Examples: `feat/tickets/cycle-reopen-transaction` · `fix/engines/mail-retry-backoff` · `chore/platform/testcontainers-mysql`

### 8.3 Commit convention

Conventional Commits, so the changelog and the merge log are readable:

```
feat(tickets): add handoff dialog with mandatory effort confirmation
fix(engines): honour working calendar in stage-SLA breach check
chore(platform): pin Flyway to 10.17
```

### 8.4 Rules for developers

1. **Branch from `develop`, never from `main`.**
2. **Rebase on `develop` daily** — `git pull --rebase origin develop`. A branch that hasn't been rebased in a week is a merge conflict waiting to be somebody else's problem.
3. **Small pull requests.** Target under 400 changed lines. A 3,000-line PR cannot be meaningfully reviewed or cleanly conflict-resolved, and it blocks the branch it came from for days.
4. **No merge commits inside a feature branch.** Rebase, so history stays linear and conflicts are resolved once rather than replayed.
5. **Push at least once a day**, even for work in progress. Unpushed work is invisible work, and it is where surprise conflicts come from.
6. **CI must be green before requesting integration.** Claude does not merge a red branch.
7. **Never commit** `.env`, credentials, `application-local.yml`, IDE folders, `target/`, `node_modules/`, or `.DS_Store`.

### 8.5 Branch protection (GitHub)

Settings → Branches → Add branch protection rule. One rule each for `main` and `develop`:

| Setting | `main` | `develop` |
|---|---|---|
| Require a pull request before merging | ✔ | ✔ |
| Required approvals | 1 | 1 |
| Require review from Code Owners | ✔ | ✔ |
| Require status checks to pass | ✔ all four jobs | ✔ all four jobs |
| Require branches to be up to date before merging | ✔ | ✔ |
| Require linear history | ✔ | ✔ |
| Do not allow bypassing the above | ✔ | ✔ |
| Allow force pushes | ✘ | ✘ |
| Allow deletions | ✘ | ✘ |

The setting people forget is **require status checks** — without it a red CI run can still be merged, which makes the whole pipeline decorative. Add all four job names: `Migration guard`, `Backend — build & test`, `Frontend — lint, test & build`, `OpenAPI contract — staleness check`.

---

## 9. The integration procedure

This is what Claude runs. It is written down so the process is repeatable and reviewable rather than ad hoc.

### 9.1 Per-branch integration (daily)

```bash
git fetch --all --prune
git checkout develop && git pull origin develop

git checkout feat/tickets/ribbon-handoff-dialog
git rebase develop                    # conflicts resolved here, by Claude
mvn -q verify                         # backend build + tests
npm --prefix frontend run build && npm --prefix frontend test

git checkout develop
git merge --no-ff feat/tickets/ribbon-handoff-dialog
git push origin develop
git push origin --delete feat/tickets/ribbon-handoff-dialog
```

`--no-ff` is deliberate: it keeps each feature visible as a unit in the history, which matters when a milestone has to be bisected or reverted.

**Before merging, Claude checks:**

- CI green on the branch
- No changes to another stream's owned paths without that owner's sign-off (§6)
- No migration edits to already-applied files (§7.1)
- Append-only tables still have no update or delete path — the rule that quietly erodes first
- New endpoints appear in the OpenAPI spec and the generated client is regenerated, not hand-edited
- Tests exist for the change, and new routes have permission-matrix entries
- No secrets, no debug logging of PII, no `System.out.println`

**On conflict, Claude resolves it and reports what was resolved.** Where intent is genuinely ambiguous — two streams changing the same business rule in different directions — the branch is handed back with the specific question rather than guessed at.

### 9.2 Promotion to `main` (at milestone boundaries, or weekly)

```bash
git checkout develop && git pull
mvn -q verify && npm --prefix frontend run build
# smoke test against a fresh docker compose stack

git checkout main && git pull
git merge --no-ff develop -m "release: M3 masters and client module"
git tag -a v0.3.0 -m "M3 — master data module"
git push origin main --tags
```

`main` should always be deployable. If it isn't, the promotion gate failed and that is the thing to fix.

### 9.3 Cadence

| When | What |
|---|---|
| Continuously | Developers push to their feature branches |
| Daily, end of day | Claude integrates all ready branches into `develop` |
| Daily, next morning | Anything unmergeable is reported back with the specific conflict |
| Weekly / milestone end | Claude promotes `develop` → `main` and tags |

---

## 10. Repository bootstrap checklist

The project is **not yet a git repository** and `gh` is not installed on this machine. Order of operations:

1. ✅ `git init`, `develop` as the working branch
2. ✅ `.gitignore` — Java, Node, IDE, OS, secrets
3. ✅ `.gitattributes` — `frontend/src/api/generated/** merge=ours`, plus `* text=auto eol=lf`
4. ✅ Move the blueprint, `PLAN.md` and `TEAM-PLAN.md` into `docs/`; decks into `docs/decks/`
5. ✅ `CONTRIBUTING.md` — §7 and §8 condensed to a page developers will actually read, with the PR description template appended
6. ✅ `.github/workflows/ci.yml`, `.github/CODEOWNERS`, `.github/pull_request_template.md`
7. ✅ Initial commit on `main`, branch `develop` from it
8. ⬜ Add the GitHub remote and push both branches
9. ⬜ Apply the §8.5 branch protection to `main` and `develop`
10. ⬜ Replace the @placeholders in CODEOWNERS with real usernames; add the four developers with write access
11. ⬜ Set `develop` as the default branch (Settings → General → Default branch)

Steps 1–7 are done. Steps 8–11 need push credentials and repo admin access.

---

## 11. Definition of done

A branch is ready for integration when:

- [ ] Feature works against the real backend, not only against mocks
- [ ] Unit tests for business logic; integration tests for any new endpoint
- [ ] New routes have permission-matrix entries for **all six roles**
- [ ] Migrations use timestamp versioning and no applied file was edited
- [ ] OpenAPI spec updated; client regenerated
- [ ] Storybook entry for any new shared component
- [ ] No new lint or compiler warnings
- [ ] Screens match blueprint §12 tokens; keyboard navigable; ARIA labels present
- [ ] Rebased on current `develop`, CI green
- [ ] Touches only the stream's owned paths, or has the other owner's sign-off

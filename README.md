# EduTrack

Organisation task and client ticketing platform. 34 screens · 6 roles · ~28 tables.

Merges project task assignment (Jira-like) with client ticket intake and SLA (Zendesk-like) on a single work item — with a **Workflow Ribbon** showing the full Support → PM → Dev → QA → Deployment → Sign-off journey, immutable append-only history, and cycle-based reopen.

---

## Quick start

**Prerequisites:** JDK 21 · Node 20+ · Docker. Maven is *not* needed — use the wrapper.

```bash
git clone https://github.com/debashisedunext/EduTrack.git && cd EduTrack
cp .env.example .env

docker compose up -d          # MySQL · Redis · MinIO · Mailpit

cd backend && ./mvnw verify   # build + test
./mvnw -pl api spring-boot:run -Dspring-boot.run.profiles=local

cd ../frontend && npm ci && npm run dev
```

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| Health | http://localhost:8080/actuator/health |
| API docs | http://localhost:8080/swagger-ui.html |
| Frontend | http://localhost:5173 |
| Mailpit (all dev mail lands here) | http://localhost:8025 |
| MinIO console | http://localhost:9001 |

Or use the Makefile: `make up` · `make api` · `make web` · `make verify` · `make down`.

---

## Repository layout

```
backend/                  Java 21 · Spring Boot 3.3 · Maven multi-module
  common/                 shared DTOs, hashing, canonical JSON          → Stream A
  domain/                 JPA entities, repositories, Flyway migrations → A (schema) / B (entities)
  api/                    REST, security, WebSocket — feature-packaged
    feature/<name>/        each holds its own controller, service, DTOs
  worker/                 SLA scanners, mail outbox, digests            → Stream D
frontend/                 React 18 · TypeScript · Vite · Tailwind
  src/components/ui/      shared design system                          → Stream C
  src/features/<name>/    one directory per feature
  src/api/generated/      generated from OpenAPI — never hand-edited
docs/                     blueprint, build plan, team plan, stream backlogs
```

**Feature packaging, not layer packaging.** There is no shared `controllers/` or `services/` folder — that is what makes four developers edit the same files every day. Each feature directory carries a `README.md` naming its owner.

---

## Who owns what

| Stream | Owner | Scope |
|---|---|---|
| **A** Platform & Security | Shivendra `@shivendraedunext-18` | Schema, migrations, auth, scope guard, immutability core, CI, dashboard, reports |
| **B** Masters & Clients | Ayush `@Ayushedunext` | Master screens, client master, Excel import, working calendar, workflow designer |
| **C** Tickets & Ribbon | Divyansh `@Divyanshedunext` | Ticket CRUD, cycles, comments, attachments, the Workflow Ribbon, Journey grid |
| **D** Engines & Realtime | Debashis `@debashisedunext` | OpenAPI contract, SLA scanners, mail engine, notifications, WebSocket, chat |

Start each session by loading your stream's context in Claude Code:

```
/stream-platform    /stream-masters    /stream-tickets    /stream-engines
```

---

## Documentation

| Read | For |
|---|---|
| [`docs/GETTING-STARTED.md`](docs/GETTING-STARTED.md) | **Start here.** Setup, required reading, Sprint 0 day-by-day |
| [`docs/Ticketing-System-Blueprint.md`](docs/Ticketing-System-Blueprint.md) | Product spec — authority on **behaviour** |
| [`docs/PLAN.md`](docs/PLAN.md) | Build spec — authority on **implementation**, MySQL translation, milestones |
| [`docs/TEAM-PLAN.md`](docs/TEAM-PLAN.md) | Streams, timeline, ownership map, git workflow |
| [`docs/streams/`](docs/streams/) | Per-developer task backlogs, `A-001` … `D-059` |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Daily loop, branch naming, the seven rules |
| [`CLAUDE.md`](CLAUDE.md) | Auto-loaded rules for every Claude Code session |

**Everyone reads blueprint §2, §3, §4 and §4A before writing code.** They are the shared mental model — see `docs/GETTING-STARTED.md` §5.3.

---

## Non-negotiables

These four cause silent, expensive damage if broken. Full detail in `CLAUDE.md`.

**History is append-only.** `ticket_history`, `ticket_effort_logs` and `ticket_stage_transitions` expose `insert()` only — enforced at four layers: no service method, no HTTP route, DB grants, and DB triggers. Corrections are new compensating rows, like an accounting reversal. The sole permitted mutation is sealing a stage transition (`exited_at` NULL → timestamp).

**Every ticket query is scoped server-side.** Never a frontend filter. Out-of-scope IDs return **404, not 403** — no existence leak.

**Migrations use timestamp versioning** — `V20260812_1430__description.sql` — and an applied migration is never edited. Flyway checksums them; editing one breaks every other developer's database. CI enforces both.

**Time is UTC in storage**, `DATETIME(6)`, never `TIMESTAMP`. All SLA and duration maths route through the working-calendar service — weekends, holidays and leave included.

---

## Git

`main` (releases) ← `develop` (integration) ← `feat/<stream>/<slug>`

Branch from `develop`, rebase daily, keep PRs under ~400 lines, and open them against `develop`. **Developers do not merge their own branches** — Claude integrates into `develop` and promotes to `main` at milestone boundaries.

---

## Status

Sprint 0. The scaffold builds and tests green; the database schema, seed data, design system and OpenAPI contract are each stream's first task. See `docs/streams/`.

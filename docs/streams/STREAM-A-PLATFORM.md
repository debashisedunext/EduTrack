# Stream A — Platform & Security · Task Backlog

**Milestones:** M0 (foundation) · M1 (auth + scope guard) · M2 (immutability core) · M6 (dashboard + reports)
**Owner:** Shivendra · `shivendra.edunext@gmail.com` · @shivendraedunext-18
**Branch prefix:** `feat/platform/…`
**Owns:** `backend/common/`, `backend/domain/db/migration/`, `backend/api/security/`, `backend/api/feature/{auth,dashboard,reports}/`, `docker-compose.yml`, `.github/`, `frontend/src/features/{auth,dashboard,reports}/`

> **You are the critical path.** Streams B, C and D are blocked on A-012 (`dev-noauth` profile, due day 10) and on the baseline schema. Ship those before anything else on this list.

> Cross-stream sequencing — who is waiting on you and what to do if you are blocked — is in [`../DEPENDENCIES.md`](../DEPENDENCIES.md).

---

## Sprint 0 — weeks 1–2

- [ ] **A-001** Maven multi-module skeleton: `common`, `domain`, `api`, `worker`. `api` and `worker` both depend on `domain`; neither depends on the other.
- [ ] **A-002** `docker-compose.yml` — MySQL 8.4, Redis 7, MinIO, Mailpit. `utf8mb4`, `connectionTimeZone=UTC`.
- [ ] **A-003** Flyway baseline 1/5 — identity: `users`, `roles`, `permissions`, `role_permissions`, `user_roles`, `projects`, `project_members`. *(blueprint §8.2)*
- [ ] **A-004** Flyway baseline 2/5 — tickets: `tickets`, `ticket_cycles`, `ticket_history`, `ticket_effort_logs`, `ticket_watchers`, `ticket_links`.
- [ ] **A-005** Flyway baseline 3/5 — workflow: `workflow_templates`, `workflow_stages`, `ticket_stage_transitions`. *(§4A.5 — note `can_return_to` becomes `JSON`)*
- [ ] **A-006** Flyway baseline 4/5 — clients & content: `clients`, `client_contacts`, `client_projects`, `ticket_comments`, `ticket_attachments`, `email_log`, `import_batches`. *(§4B.7)*
- [ ] **A-007** Flyway baseline 5/5 — masters & ops: `task_types`, `priorities`, `statuses`, `workflow_transitions`, `holidays`, `resource_leaves`, `notification_templates`, `notifications`, `chat_threads`, `chat_participants`, `chat_messages`, `audit_logs`.
- [ ] **A-008** Immutability triggers — two per table (MySQL needs separate UPDATE and DELETE triggers) on `ticket_history` and `ticket_effort_logs`; the seal-only trigger on `ticket_stage_transitions`. *(PLAN.md §3.5, §3.6)*
  > **Verified working on MySQL 8.4** — a `BEFORE UPDATE`/`BEFORE DELETE` trigger raising `SIGNAL SQLSTATE '45000'` rejects both, and the row survives intact.
  > **Delimiter gotcha:** a `BEGIN … END` body contains `;`, which terminates the statement early in `mysql -e` and in some Flyway configurations. The one-line form takes no delimiter handling at all and is preferred wherever the body is a single `SIGNAL`:
  > ```sql
  > CREATE TRIGGER trg_hist_no_update BEFORE UPDATE ON ticket_history
  >   FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Immutable: cannot update';
  > ```
  > The seal-only trigger on `ticket_stage_transitions` **does** need `BEGIN … END` (it has conditional logic), so that migration needs Flyway's `SET statement_delimiter` or a dedicated `.sql` with explicit `DELIMITER`.
- [ ] **A-009** Generated columns + indexes replacing PostgreSQL partial indexes: `pcd_open`, `current_ticket_id`. Plus `FULLTEXT` on `tickets(title, description)`. *(PLAN.md §3.3, §3.8)*
- [ ] **A-010** Two DB users: `edutrack_app` (no DDL; **`INSERT, SELECT` only** on the three append-only tables) and `edutrack_migrate` (DDL, deploy step only).
- [ ] **A-011** CI pipeline — build, test, Testcontainers integration tests, OpenAPI staleness check, frontend build.
- [ ] **A-012** 🔴 **`dev-noauth` Spring profile** — injects a configurable fake principal (role, projects, reportees). **Rejects startup outside `local`**; disabled in CI. *Due day 10 — B, C and D are blocked without it.*
- [ ] **A-013** Negative tests proving triggers reject `UPDATE` and `DELETE` on each protected table.

**Exit:** `docker compose up` yields a migrated DB; `mvn verify` green including A-013.

---

## M1 — Authentication & the scope guard · weeks 3–7

### Authentication
- [ ] **A-020** Login endpoint — Argon2id (64 MB, 3 iterations), constant-time, **generic error messages** that never reveal which field failed. *(§10.1)*
- [ ] **A-021** `failed_attempts` counter, 15-minute lockout at 5, email to Admin on lockout.
- [ ] **A-022** JWT access token, 15 min, claims `sub`, `role`, `permissions[]`, `projects[]`, `reportees[]`, `iat`, `exp`, `jti`.
- [ ] **A-023** Opaque refresh token, 7 days, HttpOnly + Secure + SameSite=Strict cookie, `jti` in Redis, device-bound.
- [ ] **A-024** 🔴 **Refresh rotation with family revocation** — reuse of a consumed token means theft: revoke the whole family, force re-login.
- [ ] **A-025** Logout — delete refresh `jti`, blacklist access `jti` in Redis until natural expiry. Idle timeout 30 min, absolute 12 h.
- [ ] **A-026** Forced password change on first login — `must_change_password`. **S-03**
- [ ] **A-027** Forgot/reset password — single-use, 30-min TTL, hashed at rest. **S-02**
- [ ] **A-028** Password policy — min 8 with upper/lower/digit/symbol, no reuse of last 3, optional 90-day expiry. *(§10.3)*
- [ ] **A-029** 2FA — 6-digit TOTP, optional per user. **S-04**
- [ ] **A-030** Login screen — centred card, soft indigo gradient, strength meter on reset. **S-01**
- [ ] **A-031** Role-based post-login redirect: Admin/PM → Dashboard · Developer → My Tasks · Support → Ticket Queue · QA/Deployment → Stage Queue.

### The scope guard
- [ ] **A-032** Spring Security filter chain — token valid and unrevoked.
  > **Replaces `ScaffoldSecurityConfig`.** The scaffold ships a permit-all chain because `spring-boot-starter-security` with no `SecurityFilterChain` bean makes Spring's default lock *every* route — the packaged jar returns 401 for its own UI. That placeholder is `@ConditionalOnMissingBean`, so it disappears automatically the moment you define a real chain; you do not need to delete it.
  > **Your chain must permit the static assets** `SpaResourceConfig` serves — `/`, `/index.html`, `/assets/**`, `/favicon.ico` and the SPA's client-side routes. Miss them and the UI fails to load even for authenticated users, which presents as a blank page rather than an auth error.
- [ ] **A-033** Permission model + `@PreAuthorize` — `ticket.read`, `ticket.assign`, `master.write`, etc.
- [ ] **A-034** 🔴 **`ScopeResolver`** producing a JPA `Specification` per role — Admin unrestricted; PM/Support `project_id IN projects`; Developer/QA/Deployment `assigned_to = me`. Composed centrally into every ticket query, never per-controller.
- [ ] **A-035** Out-of-scope IDs return **404, not 403**, on `/tickets/{id}` and every detail route.
- [ ] **A-036** 🔴 **Permission test matrix** — parameterised suite, every role × every route, asserting allow/deny/404. A new route without a matrix entry fails the build.
- [ ] **A-037** ArchUnit rules — no controller reaches a repository directly; no `update`/`delete` method exists on append-only services.

**Exit:** all six roles log in and land correctly; matrix suite passes; a Developer gets 404 on another's ticket.

---

## M2 — Immutability core · weeks 8–9

- [ ] **A-040** Append-only services for the three protected tables — **`insert()` only**, no other method exists.
- [ ] **A-041** Canonical JSON serialiser — fixed key order, fixed timestamp format. Golden-file test, or the verifier cannot reproduce hashes. Lives in `common`.
- [ ] **A-042** 🔴 **Per-ticket hash chain** with `SELECT … FOR UPDATE` on the ticket row before each append. Global chaining forks under concurrency. *(PLAN.md §3.7)*
- [ ] **A-043** Compensating-entry pattern — `is_correction`, `corrects_entry_id`.
- [ ] **A-044** Nightly chain verifier in `worker`, admin alert on break, parallelised per ticket.
- [ ] **A-045** Concurrency test — N parallel appends to one ticket produce a single unbroken chain, no fork.

**Exit:** verifier passes on the seeded corpus; all mutation attempts rejected at the DB; no fork under load.

---

## M6 — Dashboard & reports · weeks 10–16

### Aggregation
- [ ] **A-050** `daily_ticket_stats` and `resource_daily_stats` summary tables.
- [ ] **A-051** 5-minute refresh worker. **Dashboard reads never issue live `COUNT(*)`.**
- [ ] **A-052** `/tickets/{id}/full` aggregated endpoint — one call, not a waterfall of six.
- [ ] **A-053** Cursor pagination + virtualised grid rendering beyond 200 rows.

### Dashboard — S-05
- [ ] **A-054** Shell, role-aware, with project/date/resource filters.
- [ ] **A-055** Widgets 1–6 — KPI cards with sparklines and animated count-up: total, open, closed, critical, delayed, reopened.
- [ ] **A-056** Widgets 7–12 — type donut, daily stacked area, velocity multi-line, resource load bar, priority bar, aging buckets.
- [ ] **A-057** Widgets 13–15 — calendar heatmap, SLA radial gauge, project treemap.
- [ ] **A-058** Widgets 16–19 — stage funnel, rework/ping-pong, avg time per stage (active vs idle), handoff latency. *Depends on Stream C's transitions.*
- [ ] **A-059** Widget 20 — client-wise volume. *Depends on Stream B's client master.*
- [ ] **A-060** **Every card and chart segment deep-links** to a pre-filtered list (`/tickets?status=OPEN&level=CRITICAL&assignee=me`).
- [ ] **A-061** Drill-down modal, slides from the right, CSV export. **S-06**
- [ ] **A-062** Developer dashboard variant — widgets 1–6, 9, 12 scoped to `assignee = me`, plus "My due today / this week".

### Reports — S-27
- [ ] **A-063** Reports hub — card grid, parameterised viewer, filters, chart + table.
- [ ] **A-064** Export engine — Excel, CSV, PDF.
- [ ] **A-065** Scheduled report email (daily/weekly/monthly).
- [ ] **A-066** Reports 1–6: resource scorecard, velocity, effort summary, SLA breach, task type analysis, reopen analysis.
- [ ] **A-067** Reports 7–12: date-wise, project health, aging, workload/capacity, stage funnel, stage cycle time.
- [ ] **A-068** Reports 13–18: rework analysis, deployment report, resource contribution, audit/compliance, client report, email delivery log.
- [ ] **A-069** Resource 360° profile. **S-28**
- [ ] **A-070** "Born critical vs became critical" report — uses `original_level`, the insight managers ask for immediately. *(§6)*

---

## Hardening · weeks 17–18

- [ ] **A-071** Audit Log Viewer — every login, permission change, master change, ticket action. Export only, never editable. **S-16**
- [ ] **A-072** Global search + ticket-ID deep link.
- [ ] **A-073** Performance — load test at 50,000 tickets, index review against real query plans, dashboard first paint under 1.5 s.
- [ ] **A-074** Security — CSRF on cookie routes, strict CSP, security headers, dependency and container scanning.
- [ ] **A-075** Go-live runbook, deployment, TLS, secrets in vault.

---

## Decisions you own

Answer before M2 (PLAN.md §5): **G-4** — does an auto-escalated level revert after closure? *(Recommended: no; keep `original_level` and report both.)*

Answer before M1: **is there an existing employee directory or SSO** that the Resource Master should sync from? If yes, `users` needs an external ID now, not later.

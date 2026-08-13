# EduTrack — Master Schedule

**Generated Thu 13 Aug 2026 · day 8 of the plan · finish forecast Thu 19 Nov**

> Regenerated automatically at 09:00 every working day by `tools/plan/schedule.py`. **Do not hand-edit.** Change an estimate or a dependency in [`tasks.csv`](tasks.csv); record a status git cannot see in [`overrides.json`](overrides.json).

Interactive chart: [`gantt.html`](gantt.html) · Today's briefs: [`standup/2026-08-13.md`](standup/2026-08-13.md)

---

## Where we are

| | Tasks | Effort (days) |
|---|---:|---:|
| Complete | 99 of 231 (43%) | 139.0 of 348.8 (40%) |
| In flight | 1 | 1.5 |
| On the driving chain | 50 | 71.0 |
| Zero float (no slack at all) | 71 | 113.0 |

### By developer

| Stream | Developer | Tasks | Done | Effort | Working days available | Load | Finishes |
|---|---|---:|---:|---:|---:|---:|---|
| **A** | Shivendra | 64 | 28 | 93.0 | 78 | 119% ⚠️ | Sat 07 Nov |
| **B** | Ayush | 51 | 16 | 84.8 | 78 | 109% ⚠️ | Thu 05 Nov |
| **C** | Divyansh | 67 | 17 | 99.5 | 78 | 128% ⚠️ | Thu 19 Nov |
| **D** | Debashis | 49 | 38 | 71.5 | 78 | 92% | Sat 07 Nov |

### Slipping

| Task | Owner | Baseline end | Forecast end | Slip |
|---|---|---|---|---:|
| `A-023` Opaque refresh token, 7 days, HttpOnly + Secure  | Shivendra | Fri 14 Aug | Mon 10 Aug | +70d |
| `A-025` Logout | Shivendra | Tue 18 Aug | Mon 10 Aug | +68d |
| `A-026` Forced password change on first login — must_cha | Shivendra | Wed 19 Aug | Mon 10 Aug | +67d |
| `B-007` Ticket fixture corpus | Ayush | Wed 19 Aug | Mon 10 Aug | +67d |
| `A-027` Forgot/reset password — single-use, 30-min TTL,  | Shivendra | Thu 20 Aug | Mon 10 Aug | +66d |
| `C-011` Ticket ID generation | Divyansh | Thu 20 Aug | Mon 10 Aug | +66d |
| `B-006` MapStruct base configuration | Ayush | Fri 21 Aug | Mon 10 Aug | +65d |
| `C-010` Create ticket — all field groups from blueprint  | Divyansh | Tue 25 Aug | Mon 10 Aug | +63d |
| `C-013` Actions: Save & Assign · Save as Draft · Save &  | Divyansh | Wed 26 Aug | Mon 10 Aug | +62d |
| `C-015` Saved views | Divyansh | Tue 01 Sep | Mon 10 Aug | +58d |
| `C-016` Row colour cues | Divyansh | Wed 02 Sep | Mon 10 Aug | +57d |
| `D-013` Channel interceptor authorising subscriptions wi | Debashis | Tue 22 Sep | Mon 10 Aug | +43d |
| `D-036` "Critical mails cannot be disabled" | Debashis | Tue 13 Oct | Mon 10 Aug | +28d |
| `B-024` Working-hours calculation service | Ayush | Thu 15 Oct | Mon 10 Aug | +26d |
| `D-031` Subject pattern with the ticket ID first so it t | Debashis | Thu 15 Oct | Mon 10 Aug | +26d |
| `D-032` Threading | Debashis | Fri 16 Oct | Mon 10 Aug | +25d |
| `A-074` Security | Shivendra | Thu 24 Sep | Fri 23 Oct | +21d |
| `A-071` Audit Log Viewer | Shivendra | Tue 22 Sep | Fri 16 Oct | +18d |
| `B-031` Step 1 — template download | Ayush | Tue 25 Aug | Tue 15 Sep | +15d |
| `B-032` Step 2 — upload, max 5 MB / 5,000 rows, event-dr | Ayush | Thu 27 Aug | Thu 17 Sep | +15d |

*…and 19 more — see the interactive chart.*

---

## The chain that sets the finish date

Each of these is held up either by the one before it or by the fact that the same person has to do both. Shorten this chain and go-live moves; shorten anything else and it does not.

| # | Task | Owner | Title | Est | Start | End | Held up by |
|---:|---|---|---|---:|---|---|---|
| 1 | `D-001` | Debashis | OpenAPI contract for every endpoint in blueprint §13 | 3 | Thu 06 Aug | Sat 08 Aug | — |
| 2 | `D-002` | Debashis | Conventions baked into the spec | 1 | Thu 06 Aug | Thu 06 Aug | `D-001` finished |
| 3 | `D-004` | Debashis | MSW mock server returning realistic fixtures for eve | 2.5 | Thu 06 Aug | Tue 11 Aug | `D-002` finished |
| 4 | `C-019` | Divyansh | Detail shell + summary panel — every entity a link | 2 | Tue 11 Aug | Tue 11 Aug | `D-004` finished |
| 5 | `C-023` | Divyansh | Upload surfaces | 1.5 | Wed 12 Aug | Wed 12 Aug | `C-019` finished |
| 6 | `C-024` | Divyansh | Clipboard paste alongside drag-drop and file picker | 1 | Thu 13 Aug | Thu 13 Aug | `C-023` finished |
| 7 | `C-025` | Divyansh | Security | 2 | Fri 14 Aug | Sat 15 Aug | Divyansh was busy on `C-024` |
| 8 | `C-026` | Divyansh | Thumbnails, gallery strip, lightbox with zoom and ne | 1.5 | Tue 18 Aug | Wed 19 Aug | `C-025` finished |
| 9 | `C-027` | Divyansh | Limits | 0.5 | Wed 19 Aug | Wed 19 Aug | Divyansh was busy on `C-026` |
| 10 | `C-028` | Divyansh | Delete within 15 minutes by the uploader; after that | 1 | Thu 20 Aug | Thu 20 Aug | Divyansh was busy on `C-027` |
| 11 | `C-029` | Divyansh | Rich-text comment box under the description, always  | 1.5 | Fri 21 Aug | Sat 22 Aug | Divyansh was busy on `C-028` |
| 12 | `C-030` | Divyansh | @mention type-ahead over project members, firing not | 1.5 | Sat 22 Aug | Tue 25 Aug | `C-029` finished |
| 13 | `C-031` | Divyansh | Visibility toggle — default internal, always | 1 | Wed 26 Aug | Wed 26 Aug | Divyansh was busy on `C-030` |
| 14 | `C-033` | Divyansh | 5-minute edit window, then locked with an "edited" m | 1.5 | Thu 27 Aug | Fri 28 Aug | Divyansh was busy on `C-031` |
| 15 | `C-060` | Divyansh | Attachments tab | 1 | Fri 28 Aug | Sat 29 Aug | Divyansh was busy on `C-033` |
| 16 | `C-064` | Divyansh | Ticket linking — blocks / is blocked by / duplicate  | 1.5 | Sat 29 Aug | Tue 01 Sep | Divyansh was busy on `C-060` |
| 17 | `C-065` | Divyansh | product_modules master + the four columns on tickets | 0.5 | Wed 02 Sep | Wed 02 Sep | Divyansh was busy on `C-064` |
| 18 | `C-067` | Divyansh | Backend wiring for all four fields | 1 | Wed 02 Sep | Thu 03 Sep | `C-065` finished |
| 19 | `C-068` | Divyansh | Create form S-19 — the new "Where it happened" group | 1 | Thu 03 Sep | Fri 04 Sep | `C-067` finished |
| 20 | `C-069` | Divyansh | Detail page S-20 shows all four, inline-editable | 0.5 | Fri 04 Sep | Fri 04 Sep | Divyansh was busy on `C-068` |
| 21 | `C-070` | Divyansh | List S-17 gains a Module filter | 0.5 | Sat 05 Sep | Sat 05 Sep | Divyansh was busy on `C-069` |
| 22 | `C-017` | Divyansh | Bulk select → reassign / change level / close (PM &  | 1.5 | Sat 05 Sep | Tue 08 Sep | Divyansh was busy on `C-070` |
| 23 | `C-063` | Divyansh | Bulk reassignment wizard | 2 | Wed 09 Sep | Thu 10 Sep | `C-017` finished |
| 24 | `C-038` | Divyansh | Reopen transaction — seal cycle N | 2.5 | Fri 11 Sep | Tue 15 Sep | Divyansh was busy on `C-063` |
| 25 | `C-039` | Divyansh | Reopen dialog — mandatory reason, restart stage | 1.5 | Tue 15 Sep | Wed 16 Sep | `C-038` finished |
| 26 | `C-040` | Divyansh | Close/resolve dialog | 1.5 | Thu 17 Sep | Fri 18 Sep | Divyansh was busy on `C-039` |
| 27 | `C-035` | Divyansh | Effort logging, append-only, auto-stamped with curre | 1.5 | Fri 18 Sep | Sat 19 Sep | Divyansh was busy on `C-040` |
| 28 | `C-036` | Divyansh | Quick Update slide-over | 2.5 | Tue 22 Sep | Thu 24 Sep | `C-035` finished |
| 29 | `C-037` | Divyansh | Quick Update must not expose | 0.5 | Thu 24 Sep | Thu 24 Sep | `C-036` finished |
| 30 | `C-041` | Divyansh | Materialised total_effort_hrs, refreshed on every ef | 1 | Fri 25 Sep | Fri 25 Sep | Divyansh was busy on `C-037` |
| 31 | `C-061` | Divyansh | Effort tab — every log line, sum per cycle + grand t | 1 | Sat 26 Sep | Sat 26 Sep | `C-041` finished |
| 32 | `C-059` | Divyansh | History tab | 1.5 | Tue 29 Sep | Wed 30 Sep | Divyansh was busy on `C-061` |
| 33 | `C-034` | Divyansh | Interleave comments into the History tab | 1.5 | Wed 30 Sep | Thu 01 Oct | `C-059` finished |
| 34 | `C-020` | Divyansh | Priority dropdown | 1.5 | Fri 02 Oct | Sat 03 Oct | Divyansh was busy on `C-034` |
| 35 | `C-021` | Divyansh | Client + client-contact dependent dropdowns, type-ah | 2 | Sat 03 Oct | Wed 07 Oct | Divyansh was busy on `C-020` |
| 36 | `C-022` | Divyansh | Client-raised flag driving client-wise reports, CSAT | 0.5 | Wed 07 Oct | Wed 07 Oct | `C-021` finished |
| 37 | `C-042` | Divyansh | Transition service | 2.5 | Thu 08 Oct | Sat 10 Oct | Divyansh was busy on `C-022` |
| 38 | `C-032` | Divyansh | Stamping | 1 | Sat 10 Oct | Tue 13 Oct | `C-042` finished |
| 39 | `C-044` | Divyansh | Handoff dialog — next stage | 2.5 | Tue 13 Oct | Thu 15 Oct | Divyansh was busy on `C-032` |
| 40 | `C-045` | Divyansh | On submit: seal the current row | 2 | Fri 16 Oct | Sat 17 Oct | `C-044` finished |
| 41 | `C-046` | Divyansh | Backward moves | 1.5 | Tue 20 Oct | Wed 21 Oct | Divyansh was busy on `C-045` |
| 42 | `C-047` | Divyansh | Skip a stage | 1 | Wed 21 Oct | Thu 22 Oct | Divyansh was busy on `C-046` |
| 43 | `C-048` | Divyansh | Force-move (OVERRIDE) — PM/Admin, logged as an overr | 1 | Thu 22 Oct | Fri 23 Oct | Divyansh was busy on `C-047` |
| 44 | `C-049` | Divyansh | Reassignment within a stage does not create a new se | 1.5 | Fri 23 Oct | Sat 24 Oct | Divyansh was busy on `C-048` |
| 45 | `C-050` | Divyansh | Unassigned receiving role → ticket falls to a projec | 1 | Tue 27 Oct | Tue 27 Oct | Divyansh was busy on `C-049` |
| 46 | `C-055` | Divyansh | Roll-up grid | 2 | Wed 28 Oct | Thu 29 Oct | Divyansh was busy on `C-050` |
| 47 | `C-056` | Divyansh | Active vs idle split | 1.5 | Fri 30 Oct | Sat 31 Oct | `C-055` finished |
| 48 | `C-057` | Divyansh | Per-resource roll-up + cycle total + all-cycles tota | 1.5 | Sat 31 Oct | Tue 03 Nov | `C-056` finished |
| 49 | `C-058` | Divyansh | Roll-up query | 1 | Wed 04 Nov | Wed 04 Nov | Divyansh was busy on `C-057` |
| 50 | `C-062` | Divyansh | Stage Queue / team inbox | 2 | Thu 05 Nov | Fri 06 Nov | Divyansh was busy on `C-058` |
| 51 | `C-043` | Divyansh | The golden rule — only the current stage owner | 1.5 | Sat 07 Nov | Tue 10 Nov | Divyansh was busy on `C-062` |
| 52 | `C-051` | Divyansh | Ribbon component | 3 | Tue 10 Nov | Fri 13 Nov | Divyansh was busy on `C-043` |
| 53 | `C-052` | Divyansh | Interactions | 2 | Fri 13 Nov | Tue 17 Nov | `C-051` finished |
| 54 | `C-053` | Divyansh | Cycle selector above the ribbon; selecting cycle 1 r | 1.5 | Tue 17 Nov | Wed 18 Nov | Divyansh was busy on `C-052` |
| 55 | `C-054` | Divyansh | Cycle 2 · Iteration 3 chips | 1 | Thu 19 Nov | Thu 19 Nov | Divyansh was busy on `C-053` |

---

## Timeline by milestone

Bars reflect where the work is actually scheduled, not the week the backlog heading names. A developer with nothing else ready pulls later work forward rather than idling, so a milestone can start earlier than its title suggests.

### Stream A — Platform & Security · Shivendra

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream A — Shivendra
    excludes weekends
    section Milestones
    Sprint 0 — weeks 1–2 :done, a0, 2026-08-05, 7d
    M1 — Authentication & the scope guard — weeks :active, a1, 2026-08-07, 10d
    Hardening — weeks 17–18 :active, a2, 2026-08-12, 55d
    M2 — Immutability core — weeks 8–9 :a3, 2026-08-20, 11d
    M6 — Dashboard & reports — weeks 10–16 :a4, 2026-09-03, 48d
```

### Stream B — Masters & Clients · Ayush

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream B — Ayush
    excludes weekends
    section Milestones
    Sprint 0 — weeks 1–2 :done, b0, 2026-08-06, 77d
    M3 — Master data — weeks 3–9 :active, b1, 2026-08-10, -15d
    Weeks 12–14 — M6 reports :b2, 2026-10-08, 8d
    Weeks 10–11 — join Stream C on the ribbon :b3, 2026-10-20, 13d
```

### Stream C — Tickets & Ribbon · Divyansh

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream C — Divyansh
    excludes weekends
    section Milestones
    Sprint 0 — weeks 1–2 :done, c0, 2026-08-06, 3d
    M4 — Tickets — weeks 3–14 :active, c1, 2026-08-08, 74d
```

### Stream D — Engines & Realtime · Debashis

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream D — Debashis
    excludes weekends
    section Milestones
    Sprint 0 — weeks 1–2 :done, d0, 2026-08-06, 4d
    Infrastructure — weeks 3–5 :done, d1, 2026-08-07, 76d
    M5 — SLA  escalation & mail — weeks 6–11 :active, d2, 2026-08-07, 27d
    M7 — Chat & realtime — weeks 12–16 :active, d3, 2026-08-08, 66d
    Contract changes :done, d4, 2026-08-11, 1d
```

---

## Every task

`▲` critical path · `🔴` another developer is waiting on it · float is working days of slack before the finish date moves.

<details>
<summary><b>Stream A — Platform & Security · Shivendra · 64 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `A-001` | Maven multi-module skeleton: common, domain, api, worker | 1 | — | Thu 13 Aug | Thu 13 Aug | 68 | ✅ done |
|  | `A-002` | docker-compose.yml — MySQL 8.4, Redis 7, MinIO, Mailpit | 1 | `A-001` | Thu 13 Aug | Thu 13 Aug | 69 | ✅ done |
|  | `A-003` | Flyway baseline 1/5 — identity | 1 | `A-002` | Wed 05 Aug | Wed 05 Aug | 0 | ✅ done |
|  | `A-004` | Flyway baseline 2/5 — tickets | 1.5 | `A-003` | Wed 05 Aug | Wed 05 Aug | 17 | ✅ done |
|  | `A-005` | Flyway baseline 3/5 — workflow | 1 | `A-004` | Wed 05 Aug | Wed 05 Aug | 18 | ✅ done |
|  | `A-006` | Flyway baseline 4/5 — clients & content | 1.5 | `A-007` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `A-007` | Flyway baseline 5/5 — masters & ops | 1.5 | `A-005` | Thu 13 Aug | Thu 13 Aug | 69 | ✅ done |
|  | `A-008` | Immutability triggers — two per table | 1 | `A-004` `A-005` | Thu 13 Aug | Thu 13 Aug | 68 | ✅ done |
|  | `A-009` | Generated columns + indexes replacing PostgreSQL partial indexes | 0.5 | `A-006` | Thu 13 Aug | Thu 13 Aug | 53 | ✅ done |
|  | `A-010` | Two DB users: edutrack_app | 0.5 | `A-009` ᶦ | Thu 06 Aug | Thu 06 Aug | 7 | ✅ done |
|  | `A-011` | CI pipeline | 1.5 | `A-001` | Thu 13 Aug | Thu 13 Aug | 70 | ✅ done |
| 🔴 | `A-012` | dev-noauth Spring profile — injects a configurable fake principal | 1.5 | `A-003` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `A-013` | Negative tests proving triggers reject UPDATE and DELETE on each… | 1 | `A-008` | Thu 06 Aug | Fri 07 Aug | 6 | ✅ done |
|  | `A-020` | Login endpoint — Argon2id | 1.5 | `A-003` `A-012` ᶦ | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `A-021` | failed_attempts counter, 15-minute lockout at 5, email to Admin on… | 0.5 | `A-020` ᶦ | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `A-022` | JWT access token, 15 min, claims sub, role, permissions[]… | 1 | `A-020` ᶦ | Sat 08 Aug | Sat 08 Aug | 0 | ✅ done |
|  | `A-023` | Opaque refresh token, 7 days, HttpOnly + Secure + SameSite=Strict… | 1 | `A-022` ᶦ | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `A-024` | Refresh rotation with family revocation | 1.5 | `A-023` | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `A-025` | Logout | 1 | `A-023` ᶦ | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `A-026` | Forced password change on first login — must_change_password | 0.5 | `A-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `A-027` | Forgot/reset password — single-use, 30-min TTL, hashed at rest | 1 | `A-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `A-028` | Password policy | 1 | `A-020` ᶦ | Tue 11 Aug | Tue 11 Aug | 72 | ✅ done |
|  | `A-029` | 2FA — 6-digit TOTP, optional per user | 1.5 | `A-022` ᶦ | Tue 11 Aug | Tue 11 Aug | 72 | ✅ done |
|  | `A-030` | Login screen | 1.5 | `A-020` `C-003` | Thu 13 Aug | Thu 13 Aug | 69 | ✅ done |
|  | `A-031` | Role-based post-login redirect | 0.5 | `A-030` `B-001` | Wed 12 Aug | Wed 12 Aug | 71 | ✅ done |
|  | `A-032` | Spring Security filter chain — token valid and unrevoked | 1 | `A-022` | Wed 12 Aug | Wed 12 Aug | 0 | ✅ done |
|  | `A-033` | Permission model + @PreAuthorize | 1.5 | `A-032` `B-001` | Thu 13 Aug | Thu 13 Aug | 0 | ✅ done |
| 🔴 | `A-034` | ScopeResolver producing a JPA Specification per role | 2 | `A-033` | Thu 13 Aug | Fri 14 Aug | 0 | ▫️ to do |
|  | `A-035` | Out-of-scope IDs return 404, not 403, on /tickets/{id} and every… | 0.5 | `A-034` | Sat 15 Aug | Sat 15 Aug | 0 | ▫️ to do |
| 🔴 | `A-036` | Permission test matrix | 2 | `A-035` | Sat 15 Aug | Wed 19 Aug | 0 | ▫️ to do |
|  | `A-037` | ArchUnit rules | 1 | `A-034` ᶦ | Wed 19 Aug | Thu 20 Aug | 0 | ▫️ to do |
|  | `A-040` | Append-only services for the three protected tables | 1.5 | `A-010` `A-013` | Thu 20 Aug | Fri 21 Aug | 0 | ▫️ to do |
|  | `A-041` | Canonical JSON serialiser — fixed key order, fixed timestamp format | 1.5 | `A-040` | Sat 22 Aug | Tue 25 Aug | 0 | ▫️ to do |
| 🔴 | `A-042` | Per-ticket hash chain with SELECT … FOR UPDATE on the ticket row… | 2.5 | `A-041` | Tue 25 Aug | Thu 27 Aug | 0 | ▫️ to do |
|  | `A-043` | Compensating-entry pattern — is_correction, corrects_entry_id | 1 | `A-042` | Fri 28 Aug | Fri 28 Aug | 0 | ▫️ to do |
|  | `A-044` | Nightly chain verifier in worker, admin alert on break… | 2 | `A-042` | Sat 29 Aug | Tue 01 Sep | 0 | ▫️ to do |
|  | `A-045` | Concurrency test | 1.5 | `A-042` | Wed 02 Sep | Thu 03 Sep | 0 | ▫️ to do |
|  | `A-050` | daily_ticket_stats and resource_daily_stats summary tables | 1.5 | `A-034` `B-007` ᶦ | Thu 03 Sep | Fri 04 Sep | 0 | ▫️ to do |
|  | `A-051` | 5-minute refresh worker. Dashboard reads never issue live COUNT() | 1 | `A-050` | Sat 05 Sep | Sat 05 Sep | 0 | ▫️ to do |
|  | `A-052` | /tickets/{id}/full aggregated endpoint | 1 | `A-034` ᶦ | Tue 08 Sep | Tue 08 Sep | 0 | ▫️ to do |
|  | `A-053` | Cursor pagination + virtualised grid rendering beyond 200 rows | 1.5 | `A-052` `C-003` ᶦ | Wed 09 Sep | Thu 10 Sep | 0 | ▫️ to do |
|  | `A-054` | Shell, role-aware, with project/date/resource filters | 1.5 | `A-051` `C-005` ᶦ | Thu 10 Sep | Fri 11 Sep | 1 | ▫️ to do |
|  | `A-055` | Widgets 1–6 — KPI cards with sparklines and animated count-up | 2 | `A-054` ᶦ | Sat 12 Sep | Tue 15 Sep | 1 | ▫️ to do |
|  | `A-056` | Widgets 7–12 | 3 | `A-055` ᶦ | Wed 16 Sep | Fri 18 Sep | 1 | ▫️ to do |
|  | `A-057` | Widgets 13–15 — calendar heatmap, SLA radial gauge, project treemap | 2 | `A-056` ᶦ | Sat 19 Sep | Tue 22 Sep | 1 | ▫️ to do |
|  | `A-058` | Widgets 16–19 — stage funnel, rework/ping-pong, avg time per stage | 2.5 | `A-056` `C-049` | Sat 31 Oct | Wed 04 Nov | 8 | ▫️ to do |
|  | `A-059` | Widget 20 — client-wise volume. Depends on Stream B's client master | 1 | `A-056` `B-029` | Fri 30 Oct | Fri 30 Oct | 8 | ▫️ to do |
|  | `A-060` | Every card and chart segment deep-links to a pre-filtered list | 1.5 | `A-056` `C-014` ᶦ | Wed 23 Sep | Thu 24 Sep | 1 | ▫️ to do |
|  | `A-061` | Drill-down modal, slides from the right, CSV export | 1.5 | `A-060` ᶦ | Thu 24 Sep | Fri 25 Sep | 2 | ▫️ to do |
|  | `A-062` | Developer dashboard variant | 1.5 | `A-055` ᶦ | Sat 26 Sep | Tue 29 Sep | 2 | ▫️ to do |
|  | `A-063` | Reports hub | 2 | `A-051` `C-003` ᶦ | Tue 29 Sep | Thu 01 Oct | 3 | ▫️ to do |
|  | `A-064` | Export engine — Excel, CSV, PDF | 2 | `A-063` ᶦ | Thu 01 Oct | Sat 03 Oct | 4 | ▫️ to do |
|  | `A-065` | Scheduled report email (daily/weekly/monthly) | 1 | `A-064` `D-029` | Thu 29 Oct | Thu 29 Oct | 8 | ▫️ to do |
|  | `A-066` | Reports 1–6 | 3 | `A-063` ᶦ | Sat 03 Oct | Thu 08 Oct | 5 | ▫️ to do |
|  | `A-067` | Reports 7–12 | 3 | `A-066` ᶦ | Thu 08 Oct | Tue 13 Oct | 6 | ▫️ to do |
|  | `A-068` | Reports 13–18 | 3 | `A-067` `C-058` | Thu 05 Nov | Sat 07 Nov | 8 | ▫️ to do |
|  | `A-069` | Resource 360° profile | 1.5 | `A-066` `B-010` | Tue 13 Oct | Wed 14 Oct | 7 | ▫️ to do |
|  | `A-070` | "Born critical vs became critical" report | 1 | `A-066` `D-028` | Wed 28 Oct | Wed 28 Oct | 8 | ▫️ to do |
|  | `A-071` | Audit Log Viewer | 1.5 | `A-034` ᶦ | Thu 15 Oct | Fri 16 Oct | 7 | ▫️ to do |
|  | `A-072` | Global search + ticket-ID deep link | 1.5 | `A-009` `C-005` ᶦ | Fri 16 Oct | Sat 17 Oct | 8 | ▫️ to do |
|  | `A-073` | Performance | 2 | `A-053` `B-007` ᶦ | Tue 20 Oct | Wed 21 Oct | 8 | ▫️ to do |
|  | `A-074` | Security | 2 | `A-036` ᶦ | Thu 22 Oct | Fri 23 Oct | 8 | ▫️ to do |
|  | `A-075` | Go-live runbook, deployment, TLS, secrets in vault | 2 | `A-073` `A-074` ᶦ | Sat 24 Oct | Tue 27 Oct | 8 | ▫️ to do |
|  | `A-076` | Login throttle — the half A-021 deferred | 1 | — | Wed 12 Aug | Wed 12 Aug | 71 | ✅ done |

</details>

<details>
<summary><b>Stream B — Masters & Clients · Ayush · 51 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `B-001` | Seed: 6 roles + the full permission matrix from blueprint §2 | 1 | `A-003` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `B-002` | Seed: 11 task types | 1 | `A-007` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `B-003` | Seed: statuses | 1 | `A-007` | Fri 07 Aug | Fri 07 Aug | 30 | ✅ done |
|  | `B-004` | Seed: 3 workflow templates with their stages — Standard Dev Flow | 1 | `A-005` | Fri 07 Aug | Fri 07 Aug | 17 | ✅ done |
|  | `B-005` | JPA entities + repositories for the full model, built on A's schema | 3 | `A-006` `A-007` | Sat 08 Aug | Sat 08 Aug | 0 | ✅ done |
|  | `B-006` | MapStruct base configuration | 0.5 | `B-005` ᶦ | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `B-007` | Ticket fixture corpus | 2 | `B-004` `B-005` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `B-008` | Seed manifest with fixed load order | 0.5 | `B-001` `B-002` `B-003` `B-004` ᶦ | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `B-010` | Resource list | 2 | `B-005` `C-003` `A-012` | Tue 11 Aug | Tue 11 Aug | 3 | ✅ done |
|  | `B-011` | Resource create/edit | 2.5 | `B-010` ᶦ | Tue 11 Aug | Tue 11 Aug | 4 | ✅ done |
| 🔴 | `B-012` | Reporting-manager cycle detection | 1 | `B-011` ᶦ | Wed 12 Aug | Wed 12 Aug | 71 | ✅ done |
|  | `B-013` | Validations | 1 | `B-011` ᶦ | Wed 12 Aug | Wed 12 Aug | 71 | ✅ done |
|  | `B-014` | Deactivating a resource with open tickets forces the bulk… | 1 | `B-011` ᶦ | Wed 12 Aug | Wed 12 Aug | 4 | ✅ done |
|  | `B-015` | Role & permission master — module × CRUD/approve checkbox matrix | 2 | `B-001` `C-003` ᶦ | Thu 13 Aug | Fri 14 Aug | 0 | ▫️ to do |
|  | `B-016` | Project master list/create/edit — code | 2 | `B-005` `C-003` ᶦ | Sat 15 Aug | Tue 18 Aug | 0 | ▫️ to do |
|  | `B-017` | Team tab — resources + per-project role | 1.5 | `B-016` ᶦ | Wed 19 Aug | Thu 20 Aug | 0 | ▫️ to do |
|  | `B-018` | SLA tab | 1.5 | `B-016` ᶦ | Thu 20 Aug | Fri 21 Aug | 0 | ▫️ to do |
|  | `B-019` | Settings tab | 1.5 | `B-016` ᶦ | Sat 22 Aug | Tue 25 Aug | 0 | ▫️ to do |
|  | `B-020` | Task type master — the 11 seeded types, Admin-extensible | 1.5 | `B-002` `C-003` ᶦ | Tue 25 Aug | Wed 26 Aug | 0 | ▫️ to do |
|  | `B-021` | Priority master | 1.5 | `B-002` `C-003` | Thu 27 Aug | Fri 28 Aug | 0 | ▫️ to do |
|  | `B-022` | Notification template master | 2 | `B-005` `C-003` | Fri 28 Aug | Tue 01 Sep | 0 | ▫️ to do |
|  | `B-023` | Working calendar & holiday master | 2 | `B-005` `C-003` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `B-024` | Working-hours calculation service | 3 | `B-023` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `B-025` | Client list | 2 | `B-005` `C-003` ᶦ | Tue 01 Sep | Thu 03 Sep | 0 | ▫️ to do |
|  | `B-026` | Client create/edit across four tabs | 3 | `B-025` ᶦ | Thu 03 Sep | Tue 08 Sep | 0 | ▫️ to do |
|  | `B-027` | client_contacts child grid | 1.5 | `B-026` ᶦ | Tue 08 Sep | Wed 09 Sep | 0 | ▫️ to do |
|  | `B-028` | Validation | 1 | `B-027` | Thu 10 Sep | Thu 10 Sep | 0 | ▫️ to do |
|  | `B-029` | Deactivating a client with open tickets warns and blocks new… | 1 | `B-026` ᶦ | Fri 11 Sep | Fri 11 Sep | 0 | ▫️ to do |
| 🔴 | `B-030` | Import engine as a schema registry — built once, registered twice | 2 | `B-005` | Tue 11 Aug | Tue 11 Aug | 12 | ✅ done |
|  | `B-031` | Step 1 — template download | 1.5 | `B-030` ᶦ | Sat 12 Sep | Tue 15 Sep | 0 | ▫️ to do |
|  | `B-032` | Step 2 — upload, max 5 MB / 5,000 rows, event-driven SAX parse | 2 | `B-030` ᶦ | Tue 15 Sep | Thu 17 Sep | 0 | ▫️ to do |
|  | `B-033` | Step 3 | 2 | `B-032` ᶦ | Thu 17 Sep | Sat 19 Sep | 0 | ▫️ to do |
| 🔴 | `B-034` | Step 4 — dry-run validation preview | 2.5 | `B-033` | Sat 19 Sep | Wed 23 Sep | 0 | ▫️ to do |
|  | `B-035` | Step 5 — commit as a background job with progress bar | 2 | `B-034` | Thu 24 Sep | Fri 25 Sep | 0 | ▫️ to do |
|  | `B-036` | Error report generation | 1 | `B-034` ᶦ | Sat 26 Sep | Sat 26 Sep | 0 | ▫️ to do |
|  | `B-037` | import_batches traceability | 1 | `B-035` ᶦ | Tue 29 Sep | Tue 29 Sep | 0 | ▫️ to do |
|  | `B-038` | Resource bulk import — the second registration, not a second build | 1 | `B-035` | Wed 30 Sep | Wed 30 Sep | 0 | ▫️ to do |
|  | `B-039` | Status/stage/workflow master tab 1 | 2 | `B-003` `C-003` ᶦ | Thu 01 Oct | Fri 02 Oct | 0 | ▫️ to do |
|  | `B-040` | Tab 2 — stages | 2 | `B-039` | Sat 03 Oct | Tue 06 Oct | 0 | ▫️ to do |
|  | `B-041` | Tab 3 | 2.5 | `B-040` `B-050` | Thu 22 Oct | Sat 24 Oct | 9 | ▫️ to do |
| 🔴 | `B-042` | Stages in use may be deprecated, never deleted | 1 | `B-040` | Wed 07 Oct | Wed 07 Oct | 22 | ▫️ to do |
|  | `B-043` | Workflow template designer | 3 | `B-041` `B-042` | Tue 27 Oct | Thu 29 Oct | 9 | ▫️ to do |
|  | `B-050` | Ribbon segment component — 6 states | 2.5 | `C-003` `C-042` | Tue 20 Oct | Thu 22 Oct | 8 | ▫️ to do |
|  | `B-051` | Compact dot variant for the ticket list | 1 | `B-050` ᶦ | Fri 30 Oct | Fri 30 Oct | 9 | ▫️ to do |
|  | `B-052` | Ribbon accessibility | 1.5 | `B-050` ᶦ | Sat 31 Oct | Tue 03 Nov | 9 | ▫️ to do |
|  | `B-053` | Readability at 8 stages on a laptop | 2 | `B-050` ᶦ | Tue 03 Nov | Thu 05 Nov | 10 | ▫️ to do |
|  | `B-060` | Client report | 2 | `A-064` `B-029` | Thu 08 Oct | Sat 10 Oct | 6 | ▫️ to do |
|  | `B-061` | Resource performance scorecard and workload/capacity report | 2 | `A-064` `B-010` | Sat 10 Oct | Wed 14 Oct | 7 | ▫️ to do |
|  | `B-062` | Export engine integration for all report types | 1.5 | `A-064` | Wed 14 Oct | Thu 15 Oct | 8 | ▫️ to do |
|  | `B-063` | Timesheet view — stage-aware, a resource's week across all tickets | 2 | `A-064` `C-061` | Fri 16 Oct | Sat 17 Oct | 8 | ▫️ to do |
| 🔴 | `B-064` | Module master read endpoint | 0.25 | `C-065` | Thu 08 Oct | Thu 08 Oct | 27 | ▫️ to do |

</details>

<details>
<summary><b>Stream C — Tickets & Ribbon · Divyansh · 67 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `C-001` | Vite + React 18 + TypeScript scaffold, TanStack Query, Zustand… | 1 | — | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
| 🔴 | `C-002` | Design tokens from blueprint §12.1 → tokens.css + tailwind.config.ts | 1.5 | `C-001` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `C-003` | Shared component library | 3 | `C-002` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `C-004` | Storybook, with every shared component documented | 1.5 | `C-003` | Fri 07 Aug | Fri 07 Aug | 74 | ✅ done |
|  | `C-005` | App shell | 2 | `C-003` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `C-006` | Command palette on Ctrl+K for jump-to-ticket | 1 | `C-005` ᶦ | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `C-010` | Create ticket — all field groups from blueprint §7.5 | 2.5 | `C-005` `D-004` | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `C-011` | Ticket ID generation | 1 | `A-003` `A-012` | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-012` | SLA policy resolution → auto-computed Planned Close Date, previewed… | 1.5 | `C-011` `B-024` | Wed 12 Aug | Wed 12 Aug | 71 | ✅ done |
|  | `C-013` | Actions: Save & Assign · Save as Draft · Save & Create Another | 1.5 | `C-010` `C-011` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-014` | Ticket list — filters | 3 | `C-005` `D-004` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-015` | Saved views | 1 | `C-014` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-016` | Row colour cues | 0.5 | `C-014` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| ▲ | `C-017` | Bulk select → reassign / change level / close (PM & Admin only) | 1.5 | `C-014` `A-034` | Sat 05 Sep | Tue 08 Sep | 0 | ▫️ to do |
|  | `C-018` | My Tasks | 2.5 | `C-014` ᶦ | Tue 11 Aug | Tue 11 Aug | 16 | ✅ done |
|  | `C-019` | Detail shell + summary panel — every entity a link | 2 | `C-005` `D-004` | Tue 11 Aug | Tue 11 Aug | 14 | ✅ done |
| ▲ | `C-020` | Priority dropdown | 1.5 | `C-019` `B-021` | Fri 02 Oct | Sat 03 Oct | 0 | ▫️ to do |
| ▲ | `C-021` | Client + client-contact dependent dropdowns, type-ahead over… | 2 | `C-019` `B-028` | Sat 03 Oct | Wed 07 Oct | 0 | ▫️ to do |
| ▲ | `C-022` | Client-raised flag driving client-wise reports, CSAT and the… | 0.5 | `C-021` ᶦ | Wed 07 Oct | Wed 07 Oct | 0 | ▫️ to do |
|  | `C-023` | Upload surfaces | 1.5 | `C-019` ᶦ | Wed 12 Aug | Wed 12 Aug | 14 | ✅ done |
| ▲🔴 | `C-024` | Clipboard paste alongside drag-drop and file picker | 1 | `C-023` ᶦ | Thu 13 Aug | Thu 13 Aug | 14 | ▫️ to do |
| ▲ | `C-025` | Security | 2 | `C-023` ᶦ | Fri 14 Aug | Sat 15 Aug | 14 | ▫️ to do |
| ▲ | `C-026` | Thumbnails, gallery strip, lightbox with zoom and next/previous | 1.5 | `C-025` ᶦ | Tue 18 Aug | Wed 19 Aug | 14 | ▫️ to do |
| ▲ | `C-027` | Limits | 0.5 | `C-025` ᶦ | Wed 19 Aug | Wed 19 Aug | 15 | ▫️ to do |
| ▲ | `C-028` | Delete within 15 minutes by the uploader; after that a soft delete… | 1 | `C-025` ᶦ | Thu 20 Aug | Thu 20 Aug | 15 | ▫️ to do |
| ▲ | `C-029` | Rich-text comment box under the description, always visible above… | 1.5 | `C-019` ᶦ | Fri 21 Aug | Sat 22 Aug | 15 | ▫️ to do |
| ▲ | `C-030` | @mention type-ahead over project members, firing notification +… | 1.5 | `C-029` ᶦ | Sat 22 Aug | Tue 25 Aug | 46 | ▫️ to do |
| ▲ | `C-031` | Visibility toggle — default internal, always | 1 | `C-029` ᶦ | Wed 26 Aug | Wed 26 Aug | 46 | ▫️ to do |
| ▲ | `C-032` | Stamping | 1 | `C-029` `C-042` | Sat 10 Oct | Tue 13 Oct | 0 | ▫️ to do |
| ▲ | `C-033` | 5-minute edit window, then locked with an "edited" marker and the… | 1.5 | `C-029` ᶦ | Thu 27 Aug | Fri 28 Aug | 46 | ▫️ to do |
| ▲🔴 | `C-034` | Interleave comments into the History tab | 1.5 | `C-029` `C-059` | Wed 30 Sep | Thu 01 Oct | 0 | ▫️ to do |
| ▲ | `C-035` | Effort logging, append-only, auto-stamped with current stage and… | 1.5 | `C-019` `A-040` | Fri 18 Sep | Sat 19 Sep | 0 | ▫️ to do |
| ▲ | `C-036` | Quick Update slide-over | 2.5 | `C-018` `C-035` ᶦ | Tue 22 Sep | Thu 24 Sep | 0 | ▫️ to do |
| ▲ | `C-037` | Quick Update must not expose | 0.5 | `C-036` ᶦ | Thu 24 Sep | Thu 24 Sep | 0 | ▫️ to do |
| ▲🔴 | `C-038` | Reopen transaction — seal cycle N | 2.5 | `C-013` `A-040` | Fri 11 Sep | Tue 15 Sep | 0 | ▫️ to do |
| ▲ | `C-039` | Reopen dialog — mandatory reason, restart stage | 1.5 | `C-038` ᶦ | Tue 15 Sep | Wed 16 Sep | 0 | ▫️ to do |
| ▲ | `C-040` | Close/resolve dialog | 1.5 | `C-038` ᶦ | Thu 17 Sep | Fri 18 Sep | 0 | ▫️ to do |
| ▲ | `C-041` | Materialised total_effort_hrs, refreshed on every effort insert | 1 | `C-035` ᶦ | Fri 25 Sep | Fri 25 Sep | 0 | ▫️ to do |
| ▲ | `C-042` | Transition service | 2.5 | `A-042` `B-040` | Thu 08 Oct | Sat 10 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-043` | The golden rule — only the current stage owner | 1.5 | `C-042` `A-033` | Sat 07 Nov | Tue 10 Nov | 0 | ▫️ to do |
| ▲ | `C-044` | Handoff dialog — next stage | 2.5 | `C-042` `C-035` | Tue 13 Oct | Thu 15 Oct | 0 | ▫️ to do |
| ▲ | `C-045` | On submit: seal the current row | 2 | `C-044` `D-014` | Fri 16 Oct | Sat 17 Oct | 0 | ▫️ to do |
| ▲ | `C-046` | Backward moves | 1.5 | `C-042` ᶦ | Tue 20 Oct | Wed 21 Oct | 0 | ▫️ to do |
| ▲ | `C-047` | Skip a stage | 1 | `C-042` ᶦ | Wed 21 Oct | Thu 22 Oct | 0 | ▫️ to do |
| ▲ | `C-048` | Force-move (OVERRIDE) — PM/Admin, logged as an override | 1 | `C-042` ᶦ | Thu 22 Oct | Fri 23 Oct | 0 | ▫️ to do |
| ▲ | `C-049` | Reassignment within a stage does not create a new segment | 1.5 | `C-042` | Fri 23 Oct | Sat 24 Oct | 0 | ▫️ to do |
| ▲ | `C-050` | Unassigned receiving role → ticket falls to a project-level queue… | 1 | `C-044` ᶦ | Tue 27 Oct | Tue 27 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-051` | Ribbon component | 3 | `B-050` | Tue 10 Nov | Fri 13 Nov | 0 | ▫️ to do |
| ▲ | `C-052` | Interactions | 2 | `C-051` ᶦ | Fri 13 Nov | Tue 17 Nov | 0 | ▫️ to do |
| ▲ | `C-053` | Cycle selector above the ribbon; selecting cycle 1 renders that… | 1.5 | `C-051` `C-038` | Tue 17 Nov | Wed 18 Nov | 0 | ▫️ to do |
| ▲ | `C-054` | Cycle 2 · Iteration 3 chips | 1 | `C-051` `C-046` | Thu 19 Nov | Thu 19 Nov | 0 | ▫️ to do |
| ▲ | `C-055` | Roll-up grid | 2 | `C-042` `B-024` | Wed 28 Oct | Thu 29 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-056` | Active vs idle split | 1.5 | `C-055` | Fri 30 Oct | Sat 31 Oct | 0 | ▫️ to do |
| ▲ | `C-057` | Per-resource roll-up + cycle total + all-cycles total | 1.5 | `C-056` ᶦ | Sat 31 Oct | Tue 03 Nov | 0 | ▫️ to do |
| ▲ | `C-058` | Roll-up query | 1 | `C-055` | Wed 04 Nov | Wed 04 Nov | 0 | ▫️ to do |
| ▲ | `C-059` | History tab | 1.5 | `C-019` `A-040` | Tue 29 Sep | Wed 30 Sep | 0 | ▫️ to do |
| ▲ | `C-060` | Attachments tab | 1 | `C-026` ᶦ | Fri 28 Aug | Sat 29 Aug | 47 | ▫️ to do |
| ▲ | `C-061` | Effort tab — every log line, sum per cycle + grand total | 1 | `C-041` ᶦ | Sat 26 Sep | Sat 26 Sep | 0 | ▫️ to do |
| ▲ | `C-062` | Stage Queue / team inbox | 2 | `C-014` `C-042` | Thu 05 Nov | Fri 06 Nov | 0 | ▫️ to do |
| ▲ | `C-063` | Bulk reassignment wizard | 2 | `C-017` `B-014` | Wed 09 Sep | Thu 10 Sep | 0 | ▫️ to do |
| ▲ | `C-064` | Ticket linking — blocks / is blocked by / duplicate of / relates to | 1.5 | `C-019` ᶦ | Sat 29 Aug | Tue 01 Sep | 48 | ▫️ to do |
| ▲ | `C-065` | product_modules master + the four columns on tickets — table | 0.5 | `D-060` | Wed 02 Sep | Wed 02 Sep | 48 | ▫️ to do |
|  | `C-066` | Shared rich-text editor in components/ui/ + Storybook | 1.5 | `C-003` | Tue 11 Aug | Tue 11 Aug | 66 | ✅ done |
| ▲ | `C-067` | Backend wiring for all four fields | 1 | `C-065` `C-010` | Wed 02 Sep | Thu 03 Sep | 49 | ▫️ to do |
| ▲ | `C-068` | Create form S-19 — the new "Where it happened" group | 1 | `C-066` `C-067` | Thu 03 Sep | Fri 04 Sep | 50 | ▫️ to do |
| ▲ | `C-069` | Detail page S-20 shows all four, inline-editable | 0.5 | `C-019` `C-067` | Fri 04 Sep | Fri 04 Sep | 51 | ▫️ to do |
| ▲ | `C-070` | List S-17 gains a Module filter | 0.5 | `C-014` `C-067` | Sat 05 Sep | Sat 05 Sep | 51 | ▫️ to do |

</details>

<details>
<summary><b>Stream D — Engines & Realtime · Debashis · 49 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
| 🔴 | `D-001` | OpenAPI contract for every endpoint in blueprint §13 | 3 | — | Thu 06 Aug | Sat 08 Aug | 0 | ✅ done |
|  | `D-002` | Conventions baked into the spec | 1 | `D-001` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `D-003` | springdoc config + codegen pipeline | 2 | `D-002` | Thu 06 Aug | Thu 06 Aug | 72 | ✅ done |
| 🔴 | `D-004` | MSW mock server returning realistic fixtures for every endpoint | 2.5 | `D-002` | Thu 06 Aug | Tue 11 Aug | 0 | ✅ done |
|  | `D-005` | CI staleness check | 1 | `D-003` | Thu 06 Aug | Sat 08 Aug | 73 | ✅ done |
| 🔴 | `D-010` | Outbox worker pattern | 2.5 | `A-006` `A-012` | Fri 07 Aug | Fri 07 Aug | 53 | ✅ done |
|  | `D-011` | @Scheduled + ShedLock | 1 | `D-010` ᶦ | Fri 07 Aug | Fri 07 Aug | 54 | ✅ done |
|  | `D-012` | Spring WebSocket + STOMP config, Redis pub/sub relay for… | 2 | `A-012` ᶦ | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
| 🔴 | `D-013` | Channel interceptor authorising subscriptions with the same scope… | 2 | `D-012` `A-034` | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-014` | Destination map per blueprint §9.3 | 1 | `D-012` | Fri 07 Aug | Fri 07 Aug | 42 | ✅ done |
|  | `D-015` | Frontend STOMP client | 1.5 | `D-014` `C-005` | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
| 🔴 | `D-020` | SLA scanner, every 15 minutes | 2 | `D-011` `B-024` `A-009` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-021` | 80%-of-SLA pre-breach warning to the assignee | 1 | `D-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-022` | Stale-task nudge — no update for 3 working days, to assignee cc RM | 1 | `D-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `D-023` | Stage-SLA scanner, separate from ticket SLA | 2 | `D-020` `C-042` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-024` | Escalation matrix per project | 1.5 | `D-020` `B-018` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-025` | Ping-pong flag at iteration_no ≥ 3 → PM dashboard | 0.5 | `D-023` ᶦ | Tue 11 Aug | Tue 11 Aug | 72 | ✅ done |
|  | `D-026` | Unassigned ticket > 2 h → triage alert to PM and Support Desk | 1 | `D-020` ᶦ | Tue 11 Aug | Tue 11 Aug | 72 | ✅ done |
| 🔴 | `D-027` | Every calculation routes through Stream B's working-hours service | 1 | `D-020` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-028` | original_level preserved so "born critical vs became critical"… | 1 | `D-020` ᶦ | Thu 13 Aug | Fri 14 Aug | 52 | ▫️ to do |
|  | `D-029` | Thymeleaf templates driven by Stream B's notification template… | 1.5 | `D-010` `B-022` | Wed 02 Sep | Thu 03 Sep | 44 | ▫️ to do |
|  | `D-030` | Mail body | 1.5 | `D-029` ᶦ | Thu 03 Sep | Fri 04 Sep | 45 | ▫️ to do |
|  | `D-031` | Subject pattern with the ticket ID first so it threads and searches… | 0.5 | `D-029` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `D-032` | Threading | 1.5 | `D-031` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-033` | Every send logged in email_log with status, provider message ID and… | 1 | `D-010` | Fri 07 Aug | Fri 07 Aug | 73 | ✅ done |
|  | `D-034` | Bounce and complaint webhooks | 1 | `D-033` ᶦ | Fri 07 Aug | Fri 07 Aug | 74 | ✅ done |
|  | `D-035` | Rate limit | 1 | `D-033` ᶦ | Fri 07 Aug | Fri 07 Aug | 74 | ✅ done |
| 🔴 | `D-036` | "Critical mails cannot be disabled" | 1 | `D-029` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-037` | All 15 mail events from §4B.6 wired | 2 | `D-030` `D-036` ᶦ | Sat 05 Sep | Tue 08 Sep | 45 | ▫️ to do |
|  | `D-038` | Daily digest 08:30 and weekly manager summary | 1.5 | `D-037` ᶦ | Wed 09 Sep | Thu 10 Sep | 45 | ▫️ to do |
|  | `D-039` | Inbound webhook — reply-to-comment parsing with quoted text stripped | 2 | `D-032` ᶦ | Wed 12 Aug | Wed 12 Aug | 71 | ✅ done |
|  | `D-040` | All 24 events from blueprint §11 across in-app / bell / email… | 2 | `D-012` `B-022` | Thu 10 Sep | Sat 12 Sep | 46 | ▫️ to do |
|  | `D-041` | Notification centre — bell dropdown (last 10) + full page with tabs | 2.5 | `D-040` `C-005` | Sat 08 Aug | Sat 08 Aug | 69 | ✅ done |
|  | `D-042` | Per-user preference matrix — which events, which channel | 1.5 | `D-041` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-043` | In-app toast via WebSocket, appearing within ~1 second, with Open /… | 1 | `D-041` `D-014` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-044` | Persistent bell badge with unread count | 0.5 | `D-041` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-045` | Browser push via the Web Push API for users who opt in | 1.5 | `D-043` ᶦ | Thu 13 Aug | Thu 13 Aug | 68 | 🟡 60% |
| 🔴 | `D-046` | Offline queueing | 1.5 | `D-043` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-050` | Chat engine, three surfaces one engine | 3 | `D-014` `C-005` | Sat 08 Aug | Sat 08 Aug | 56 | ✅ done |
|  | `D-051` | Typing indicator, read receipts, unread counts | 2 | `D-050` ᶦ | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `D-052` | @mentions firing notifications | 1.5 | `D-050` ᶦ | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `D-053` | File and image share, emoji, message search | 2 | `D-050` ᶦ | Fri 14 Aug | Sat 15 Aug | 53 | ⛔ blocked |
|  | `D-054` | TKT-xxxx link preview rendering as a rich ticket card | 1 | `D-050` ᶦ | Sat 15 Aug | Tue 18 Aug | 54 | ▫️ to do |
| 🔴 | `D-055` | Ask Status | 1.5 | `D-050` `C-036` | Thu 13 Aug | Thu 13 Aug | 69 | ✅ done |
|  | `D-056` | Manager response time recorded as a reportable metric; status… | 1 | `D-055` ᶦ | Thu 13 Aug | Thu 13 Aug | 70 | ✅ done |
| 🔴 | `D-057` | Chat immutable after a 5-minute edit window; deletions leave… | 1.5 | `D-050` | Sat 08 Aug | Sat 08 Aug | 73 | ✅ done |
|  | `D-058` | Live ribbon advance | 1 | `D-014` `C-045` | Tue 20 Oct | Tue 20 Oct | 21 | ▫️ to do |
|  | `D-059` | Team inbox live updates | 1 | `D-058` `C-062` | Sat 07 Nov | Sat 07 Nov | 8 | ▫️ to do |
|  | `D-060` | Ticket "where it happened" fields in the contract and the mock… | 0.5 | `D-004` | Tue 11 Aug | Tue 11 Aug | 63 | ✅ done |

</details>

---

*`ᶦ` marks an **inferred** dependency — derived from task ordering, not confirmed by its owner. Correct them in `tasks.csv` and the critical path stops being a hypothesis.*

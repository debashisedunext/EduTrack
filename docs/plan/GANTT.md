# EduTrack — Master Schedule

**Generated Thu 03 Sep 2026 · day 23 of the plan · finish forecast Wed 16 Dec**

> Regenerated automatically at 09:00 every working day by `tools/plan/schedule.py`. **Do not hand-edit.** Change an estimate or a dependency in [`tasks.csv`](tasks.csv); record a status git cannot see in [`overrides.json`](overrides.json).

Interactive chart: [`gantt.html`](gantt.html) · Today's briefs: [`standup/2026-09-03.md`](standup/2026-09-03.md)

---

## Where we are

Each phase is counted on its own. A phase's *working days available* is the span of that phase alone, so a finished phase does not flatter the load of the one running now, and a new phase does not make a finished one look incomplete.

### Phase 1 — Ticketing

*Wed 05 Aug → Tue 08 Sep*

| | Tasks | Effort (days) |
|---|---:|---:|
| Complete | 241 of 244 (99%) | 360.8 of 365.8 (99%) |
| In flight | 0 | 0.0 |
| On the driving chain | 2 | 4.0 |
| Zero float (no slack at all) | 2 | 4.0 |

| Stream | Developer | Tasks | Done | Effort | Working days available | Load | Finishes |
|---|---|---:|---:|---:|---:|---:|---|
| **A** | Shivendra | 65 | 63 | 95.0 | 25 | 380% ⚠️ | Tue 08 Sep |
| **B** | Ayush | 55 | 55 | 89.8 | 25 | 359% ⚠️ | Sat 22 Aug |
| **C** | Divyansh | 59 | 58 | 90.0 | 25 | 360% ⚠️ | Thu 03 Sep |
| **D** | Debashis | 65 | 65 | 91.0 | 25 | 364% ⚠️ | Sat 22 Aug |

### Phase 2 — Client Onboarding

*Fri 04 Sep → Wed 16 Dec*

| | Tasks | Effort (days) |
|---|---:|---:|
| Complete | 0 of 82 (0%) | 0.0 of 177.0 (0%) |
| In flight | 0 | 0.0 |
| On the driving chain | 29 | 71.0 |
| Zero float (no slack at all) | 29 | 71.0 |

| Stream | Developer | Tasks | Done | Effort | Working days available | Load | Finishes |
|---|---|---:|---:|---:|---:|---:|---|
| **A** | Shivendra | 28 | 0 | 57.0 | 74 | 77% | Thu 26 Nov |
| **B** | Ayush | 27 | 0 | 52.0 | 74 | 70% | Thu 03 Dec |
| **C** | Divyansh | 25 | 0 | 64.0 | 74 | 86% | Wed 16 Dec |
| **D** | Debashis | 2 | 0 | 4.0 | 74 | 5% | Sat 10 Oct |

### Whole plan

| | Tasks | Effort (days) |
|---|---:|---:|
| Complete | 241 of 326 (74%) | 360.8 of 542.8 (66%) |
| In flight | 0 | 0.0 |
| On the driving chain | 31 | 75.0 |
| Zero float (no slack at all) | 31 | 75.0 |

| Stream | Developer | Tasks | Done | Effort | Working days available | Load | Finishes |
|---|---|---:|---:|---:|---:|---:|---|
| **A** | Shivendra | 93 | 63 | 152.0 | 97 | 157% ⚠️ | Thu 26 Nov |
| **B** | Ayush | 82 | 55 | 141.8 | 97 | 146% ⚠️ | Thu 03 Dec |
| **C** | Divyansh | 84 | 58 | 154.0 | 97 | 159% ⚠️ | Wed 16 Dec |
| **D** | Debashis | 67 | 65 | 95.0 | 97 | 98% | Sat 10 Oct |


### Slipping

| Task | Owner | Baseline end | Forecast end | Slip |
|---|---|---|---|---:|
| `A-023` Opaque refresh token, 7 days, HttpOnly + Secure  | Shivendra | Fri 14 Aug | Mon 10 Aug | +89d |
| `A-025` Logout | Shivendra | Tue 18 Aug | Mon 10 Aug | +87d |
| `A-026` Forced password change on first login — must_cha | Shivendra | Wed 19 Aug | Mon 10 Aug | +86d |
| `B-007` Ticket fixture corpus | Ayush | Wed 19 Aug | Mon 10 Aug | +86d |
| `A-027` Forgot/reset password — single-use, 30-min TTL,  | Shivendra | Thu 20 Aug | Mon 10 Aug | +85d |
| `C-011` Ticket ID generation | Divyansh | Thu 20 Aug | Mon 10 Aug | +85d |
| `B-006` MapStruct base configuration | Ayush | Fri 21 Aug | Mon 10 Aug | +84d |
| `B-031` Step 1 — template download | Ayush | Tue 25 Aug | Mon 17 Aug | +83d |
| `C-010` Create ticket — all field groups from blueprint  | Divyansh | Tue 25 Aug | Mon 10 Aug | +82d |
| `B-032` Step 2 — upload, max 5 MB / 5,000 rows, event-dr | Ayush | Thu 27 Aug | Mon 17 Aug | +81d |
| `C-013` Actions: Save & Assign · Save as Draft · Save &  | Divyansh | Wed 26 Aug | Mon 10 Aug | +81d |
| `B-034` Step 4 — dry-run validation preview | Ayush | Wed 02 Sep | Mon 17 Aug | +77d |
| `C-015` Saved views | Divyansh | Tue 01 Sep | Mon 10 Aug | +77d |
| `A-052` /tickets/{id}/full aggregated endpoint | Shivendra | Fri 18 Sep | Sun 16 Aug | +76d |
| `C-016` Row colour cues | Divyansh | Wed 02 Sep | Mon 10 Aug | +76d |
| `C-028` Delete within 15 minutes by the uploader; after  | Divyansh | Fri 18 Sep | Sun 16 Aug | +76d |
| `B-035` Step 5 — commit as a background job with progres | Ayush | Fri 04 Sep | Mon 17 Aug | +75d |
| `A-053` Cursor pagination + virtualised grid rendering b | Shivendra | Tue 29 Sep | Sun 16 Aug | +69d |
| `C-030` @mention type-ahead over project members, firing | Divyansh | Wed 23 Sep | Mon 17 Aug | +62d |
| `D-013` Channel interceptor authorising subscriptions wi | Debashis | Tue 22 Sep | Mon 10 Aug | +62d |

*…and 112 more — see the interactive chart.*

---

## The chain that sets the finish date

Each of these is held up either by the one before it or by the fact that the same person has to do both. Shorten this chain and go-live moves; shorten anything else and it does not.

| # | Task | Owner | Title | Est | Start | End | Held up by |
|---:|---|---|---|---:|---|---|---|
| 1 | `A-052` | Shivendra | /tickets/{id}/full aggregated endpoint | 1 | Sun 16 Aug | Sun 16 Aug | — |
| 2 | `A-053` | Shivendra | Cursor pagination + virtualised grid rendering beyon | 1.5 | Sun 16 Aug | Sun 16 Aug | `A-052` finished |
| 3 | `A-073` | Shivendra | Performance | 2 | Thu 03 Sep | Fri 04 Sep | `A-053` finished |
| 4 | `A-075` | Shivendra | Go-live runbook, deployment, TLS, secrets in vault | 2 | Sat 05 Sep | Tue 08 Sep | `A-073` finished |
| 5 | `A-118` | Shivendra | OpenAPI contract for the whole module | 3 | Wed 09 Sep | Fri 11 Sep | Shivendra was busy on `A-075` |
| 6 | `A-119` | Shivendra | Wire-conformance ratchet for onboarding DTOs | 2 | Sat 12 Sep | Tue 15 Sep | `A-118` finished |
| 7 | `A-101` | Shivendra | Client capture tables | 2 | Wed 16 Sep | Thu 17 Sep | Shivendra was busy on `A-119` |
| 8 | `A-102` | Shivendra | ob_attachments | 1 | Fri 18 Sep | Fri 18 Sep | `A-101` finished |
| 9 | `A-103` | Shivendra | Journey template tables | 2 | Sat 19 Sep | Tue 22 Sep | Shivendra was busy on `A-102` |
| 10 | `C-101` | Divyansh | Template domain and versioning | 3 | Wed 23 Sep | Fri 25 Sep | `A-103` finished |
| 11 | `C-102` | Divyansh | OB-07 | 4 | Sat 26 Sep | Thu 01 Oct | `C-101` finished |
| 12 | `C-103` | Divyansh | Instantiation | 3 | Fri 02 Oct | Tue 06 Oct | Divyansh was busy on `C-102` |
| 13 | `C-104` | Divyansh | Step lifecycle | 3 | Wed 07 Oct | Fri 09 Oct | `C-103` finished |
| 14 | `C-106` | Divyansh | Sub-category answers and the completion gate — one s | 2 | Sat 10 Oct | Tue 13 Oct | `C-104` finished |
| 15 | `C-107` | Divyansh | Skip a step — Manager and Admin only, reason mandato | 1 | Wed 14 Oct | Wed 14 Oct | Divyansh was busy on `C-106` |
| 16 | `C-108` | Divyansh | Backup owner | 2 | Thu 15 Oct | Fri 16 Oct | Divyansh was busy on `C-107` |
| 17 | `C-119` | Divyansh | Step dependency graph — depends_on_step_id | 3 | Sat 17 Oct | Wed 21 Oct | Divyansh was busy on `C-108` |
| 18 | `C-118` | Divyansh | PrerequisiteGateService — every mandatory task VERIF | 3 | Thu 22 Oct | Sat 24 Oct | Divyansh was busy on `C-119` |
| 19 | `C-105` | Divyansh | Clock events and working-calendar due_at | 3 | Tue 27 Oct | Thu 29 Oct | Divyansh was busy on `C-118` |
| 20 | `C-113` | Divyansh | TAT scanner worker job | 3 | Fri 30 Oct | Tue 03 Nov | `C-105` finished |
| 21 | `C-114` | Divyansh | RAG computation | 2 | Wed 04 Nov | Thu 05 Nov | `C-113` finished |
| 22 | `C-115` | Divyansh | Escalation matrix | 3 | Fri 06 Nov | Tue 10 Nov | `C-114` finished |
| 23 | `C-117` | Divyansh | Scanner load pass | 2 | Wed 11 Nov | Thu 12 Nov | `C-115` finished |
| 24 | `C-110` | Divyansh | OB-05 | 3 | Fri 13 Nov | Tue 17 Nov | Divyansh was busy on `C-117` |
| 25 | `C-111` | Divyansh | OB-06 | 3 | Wed 18 Nov | Fri 20 Nov | `C-110` finished |
| 26 | `C-112` | Divyansh | Communications timeline | 2 | Sat 21 Nov | Tue 24 Nov | `C-111` finished |
| 27 | `C-116` | Divyansh | Ribbon accessibility pass | 2 | Wed 25 Nov | Thu 26 Nov | Divyansh was busy on `C-112` |
| 28 | `C-120` | Divyansh | Journey TAT roll-up — total TAT | 1 | Fri 27 Nov | Fri 27 Nov | Divyansh was busy on `C-116` |
| 29 | `C-125` | Divyansh | Ribbon SD/FD + animated status emojis + v1.2 termino | 2 | Sat 28 Nov | Tue 01 Dec | Divyansh was busy on `C-120` |
| 30 | `C-121` | Divyansh | CP-01..CP-04 | 4 | Wed 02 Dec | Sat 05 Dec | Divyansh was busy on `C-125` |
| 31 | `C-122` | Divyansh | CP-05..CP-07 | 2 | Tue 08 Dec | Wed 09 Dec | `C-121` finished |
| 32 | `C-123` | Divyansh | Service-level dependency engine | 3 | Thu 10 Dec | Sat 12 Dec | Divyansh was busy on `C-122` |
| 33 | `C-126` | Divyansh | Portal escalation flow | 2 | Tue 15 Dec | Wed 16 Dec | Divyansh was busy on `C-123` |

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
    section Phase 1 — Ticketing
    Sprint 0 — weeks 1–2 :done, a0, 2026-08-05, 22d
    M1 — Authentication & the scope guard — weeks :done, a1, 2026-08-07, 20d
    Hardening — weeks 17–18 :active, a2, 2026-08-12, 20d
    M2 — Immutability core — weeks 8–9 :done, a3, 2026-08-14, 1d
    M6 — Dashboard & reports — weeks 10–16 :done, a4, 2026-08-15, 14d
    section Phase 2 — Client Onboarding
    OB0 — Gate & schema :a5, 2026-09-09, 57d
    OB4 — Sign-off  dashboard  reports :a6, 2026-10-31, 5d
    OB5 — Hardening :a7, 2026-11-07, 4d
    OB5 — Client portal :a8, 2026-11-24, 2d
```

### Stream B — Masters & Clients · Ayush

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream B — Ayush
    excludes weekends
    section Phase 1 — Ticketing
    Sprint 0 — weeks 1–2 :done, b0, 2026-08-06, 96d
    M3 — Master data — weeks 3–9 :done, b1, 2026-08-10, -83d
    Weeks 12–14 — M6 reports :done, b2, 2026-08-19, 4d
    Weeks 10–11 — join Stream C on the ribbon :done, b3, 2026-08-20, 3d
    section Phase 2 — Client Onboarding
    OB1 — Client capture :b4, 2026-09-18, 55d
    OB3 — Notifications :b5, 2026-10-03, 42d
    OB4 — Sign-off  dashboard  reports :b6, 2026-10-13, 34d
    OB5 — Hardening :b7, 2026-10-23, 1d
```

### Stream C — Tickets & Ribbon · Divyansh

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream C — Divyansh
    excludes weekends
    section Phase 1 — Ticketing
    Sprint 0 — weeks 1–2 :done, c0, 2026-08-06, 3d
    M4 — Tickets — weeks 3–14 :active, c1, 2026-08-08, 19d
    section Phase 2 — Client Onboarding
    OB2 — Journey engine & ribbon :c2, 2026-09-04, 72d
    OB3 — TAT & escalation :c3, 2026-10-30, 8d
    OB5 — Hardening :c4, 2026-11-11, 12d
    OB5 — Client portal :c5, 2026-12-02, 11d
```

### Stream D — Engines & Realtime · Debashis

```mermaid
gantt
    dateFormat YYYY-MM-DD
    axisFormat %d %b
    title Stream D — Debashis
    excludes weekends
    section Phase 1 — Ticketing
    Sprint 0 — weeks 1–2 :done, d0, 2026-08-06, 13d
    Infrastructure — weeks 3–5 :done, d1, 2026-08-07, 95d
    M5 — SLA  escalation & mail — weeks 6–11 :done, d2, 2026-08-07, 11d
    M7 — Chat & realtime — weeks 12–16 :done, d3, 2026-08-08, 10d
    Contract changes :done, d4, 2026-08-11, 1d
    S-05 and S-17 corrections — raised 18 Aug 2026 :done, d5, 2026-08-18, 1d
    M4 — Tickets — weeks 3–14 :done, d6, 2026-08-19, 3d
    section Phase 2 — Client Onboarding
    OB3 — Notifications :d7, 2026-10-07, 4d
```

---

## Every task

`▲` critical path · `🔴` another developer is waiting on it · float is working days of slack before the finish date moves.

<details>
<summary><b>Stream A — Platform & Security · Shivendra · 93 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `A-001` | Maven multi-module skeleton: common, domain, api, worker | 1 | — | Thu 03 Sep | Thu 03 Sep | 1 | ✅ done |
|  | `A-002` | docker-compose.yml — MySQL 8.4, Redis 7, MinIO, Mailpit | 1 | `A-001` | Thu 03 Sep | Thu 03 Sep | 2 | ✅ done |
|  | `A-003` | Flyway baseline 1/5 — identity | 1 | `A-002` | Wed 05 Aug | Wed 05 Aug | 0 | ✅ done |
|  | `A-004` | Flyway baseline 2/5 — tickets | 1.5 | `A-003` | Wed 05 Aug | Wed 05 Aug | 13 | ✅ done |
|  | `A-005` | Flyway baseline 3/5 — workflow | 1 | `A-004` | Wed 05 Aug | Wed 05 Aug | 14 | ✅ done |
|  | `A-006` | Flyway baseline 4/5 — clients & content | 1.5 | `A-007` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `A-007` | Flyway baseline 5/5 — masters & ops | 1.5 | `A-005` | Thu 03 Sep | Thu 03 Sep | 2 | ✅ done |
|  | `A-008` | Immutability triggers — two per table | 1 | `A-004` `A-005` | Thu 03 Sep | Thu 03 Sep | 1 | ✅ done |
|  | `A-009` | Generated columns + indexes replacing PostgreSQL partial indexes | 0.5 | `A-006` | Thu 03 Sep | Thu 03 Sep | 2 | ✅ done |
|  | `A-010` | Two DB users: edutrack_app | 0.5 | `A-009` ᶦ | Thu 06 Aug | Thu 06 Aug | 13 | ✅ done |
|  | `A-011` | CI pipeline | 1.5 | `A-001` | Thu 03 Sep | Thu 03 Sep | 3 | ✅ done |
| 🔴 | `A-012` | dev-noauth Spring profile — injects a configurable fake principal | 1.5 | `A-003` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `A-013` | Negative tests proving triggers reject UPDATE and DELETE on each… | 1 | `A-008` | Thu 06 Aug | Fri 07 Aug | 12 | ✅ done |
|  | `A-020` | Login endpoint — Argon2id | 1.5 | `A-003` `A-012` ᶦ | Fri 07 Aug | Sat 22 Aug | 0 | ✅ done |
|  | `A-021` | failed_attempts counter, 15-minute lockout at 5, email to Admin on… | 0.5 | `A-020` ᶦ | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `A-022` | JWT access token, 15 min, claims sub, role, permissions[]… | 1 | `A-020` ᶦ | Sat 08 Aug | Sat 08 Aug | 0 | ✅ done |
|  | `A-023` | Opaque refresh token, 7 days, HttpOnly + Secure + SameSite=Strict… | 1 | `A-022` ᶦ | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `A-024` | Refresh rotation with family revocation | 1.5 | `A-023` | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `A-025` | Logout | 1 | `A-023` ᶦ | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `A-026` | Forced password change on first login — must_change_password | 0.5 | `A-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `A-027` | Forgot/reset password — single-use, 30-min TTL, hashed at rest | 1 | `A-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `A-028` | Password policy | 1 | `A-020` ᶦ | Tue 11 Aug | Tue 11 Aug | 20 | ✅ done |
|  | `A-029` | 2FA — 6-digit TOTP, optional per user | 1.5 | `A-022` ᶦ | Tue 11 Aug | Tue 11 Aug | 20 | ✅ done |
|  | `A-030` | Login screen | 1.5 | `A-020` `C-003` | Thu 03 Sep | Thu 03 Sep | 2 | ✅ done |
|  | `A-031` | Role-based post-login redirect | 0.5 | `A-030` `B-001` | Wed 12 Aug | Wed 12 Aug | 19 | ✅ done |
|  | `A-032` | Spring Security filter chain — token valid and unrevoked | 1 | `A-022` | Wed 12 Aug | Wed 12 Aug | 0 | ✅ done |
|  | `A-033` | Permission model + @PreAuthorize | 1.5 | `A-032` `B-001` | Thu 13 Aug | Thu 13 Aug | 0 | ✅ done |
| 🔴 | `A-034` | ScopeResolver producing a JPA Specification per role | 2 | `A-033` | Thu 13 Aug | Thu 13 Aug | 0 | ✅ done |
|  | `A-035` | Out-of-scope IDs return 404, not 403, on /tickets/{id} and every… | 0.5 | `A-034` | Thu 13 Aug | Thu 13 Aug | 14 | ✅ done |
| 🔴 | `A-036` | Permission test matrix | 2 | `A-035` | Thu 13 Aug | Thu 13 Aug | 15 | ✅ done |
|  | `A-037` | ArchUnit rules | 1 | `A-034` ᶦ | Thu 13 Aug | Thu 13 Aug | 18 | ✅ done |
|  | `A-040` | Append-only services for the three protected tables | 1.5 | `A-010` `A-013` | Fri 14 Aug | Fri 14 Aug | 8 | ✅ done |
|  | `A-041` | Canonical JSON serialiser — fixed key order, fixed timestamp format | 1.5 | `A-040` | Fri 14 Aug | Fri 14 Aug | 9 | ✅ done |
| 🔴 | `A-042` | Per-ticket hash chain with SELECT … FOR UPDATE on the ticket row… | 2.5 | `A-041` | Fri 14 Aug | Fri 14 Aug | 10 | ✅ done |
|  | `A-043` | Compensating-entry pattern — is_correction, corrects_entry_id | 1 | `A-042` | Fri 14 Aug | Fri 14 Aug | 17 | ✅ done |
|  | `A-044` | Nightly chain verifier in worker, admin alert on break… | 2 | `A-042` | Fri 14 Aug | Fri 14 Aug | 17 | ✅ done |
|  | `A-045` | Concurrency test | 1.5 | `A-042` | Fri 14 Aug | Fri 14 Aug | 17 | ✅ done |
|  | `A-050` | daily_ticket_stats and resource_daily_stats summary tables | 1.5 | `A-034` `B-007` ᶦ | Sat 15 Aug | Sat 15 Aug | 9 | ✅ done |
|  | `A-051` | 5-minute refresh worker. Dashboard reads never issue live COUNT() | 1 | `A-050` | Sat 15 Aug | Tue 18 Aug | 10 | ✅ done |
|  | `A-052` | /tickets/{id}/full aggregated endpoint | 1 | `A-034` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-053` | Cursor pagination + virtualised grid rendering beyond 200 rows | 1.5 | `A-052` `C-003` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-054` | Shell, role-aware, with project/date/resource filters | 1.5 | `A-051` `C-005` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-055` | Widgets 1–6 — KPI cards with sparklines and animated count-up | 2 | `A-054` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-056` | Widgets 7–12 | 3 | `A-055` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-057` | Widgets 13–15 — calendar heatmap, SLA radial gauge, project treemap | 2 | `A-056` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-058` | Widgets 16–19 — stage funnel, rework/ping-pong, avg time per stage | 2.5 | `A-056` `C-049` | Fri 21 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `A-059` | Widget 20 — client-wise volume | 1 | `A-056` `B-029` | Thu 03 Sep | Thu 03 Sep | 3 | ✅ done |
|  | `A-060` | Every card and chart segment deep-links to a pre-filtered list | 1.5 | `A-056` `C-014` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `A-061` | Drill-down modal, slides from the right, CSV export | 1.5 | `A-060` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `A-062` | Developer dashboard variant | 1.5 | `A-055` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `A-063` | Reports hub | 2 | `A-051` `C-003` ᶦ | Mon 17 Aug | Tue 18 Aug | 12 | ✅ done |
|  | `A-064` | Export engine — Excel, CSV, PDF | 2 | `A-063` ᶦ | Tue 18 Aug | Tue 18 Aug | 14 | ✅ done |
|  | `A-065` | Scheduled report email (daily/weekly/monthly) | 1 | `A-064` `D-029` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `A-066` | Reports 1–6 | 3 | `A-063` ᶦ | Tue 18 Aug | Tue 18 Aug | 13 | ✅ done |
|  | `A-067` | Reports 7–12 | 3 | `A-066` ᶦ | Tue 18 Aug | Tue 18 Aug | 14 | ✅ done |
|  | `A-068` | Reports 13–18 | 3 | `A-067` `C-058` | Fri 21 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `A-069` | Resource 360° profile | 1.5 | `A-066` `B-010` | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `A-070` | "Born critical vs became critical" report | 1 | `A-066` `D-028` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `A-071` | Audit Log Viewer | 1.5 | `A-034` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `A-072` | Global search + ticket-ID deep link | 1.5 | `A-009` `C-005` ᶦ | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
| ▲ | `A-073` | Performance | 2 | `A-053` `B-007` ᶦ | Thu 03 Sep | Fri 04 Sep | 0 | ▫️ to do |
|  | `A-074` | Security | 2 | `A-036` ᶦ | Thu 20 Aug | Thu 20 Aug | 11 | ✅ done |
| ▲ | `A-075` | Go-live runbook, deployment, TLS, secrets in vault | 2 | `A-073` `A-074` ᶦ | Sat 05 Sep | Tue 08 Sep | 0 | ▫️ to do |
|  | `A-076` | Login throttle — the half A-021 deferred | 1 | — | Wed 12 Aug | Wed 12 Aug | 19 | ✅ done |
|  | `A-077` | Project dashboard | 2 | — | Fri 21 Aug | Fri 21 Aug | 12 | ✅ done |
| ▲ | `A-101` | Client capture tables | 2 | — | Wed 16 Sep | Thu 17 Sep | 0 | ▫️ to do |
| ▲ | `A-102` | ob_attachments | 1 | `A-101` ᶦ | Fri 18 Sep | Fri 18 Sep | 0 | ▫️ to do |
| ▲ | `A-103` | Journey template tables | 2 | — | Sat 19 Sep | Tue 22 Sep | 0 | ▫️ to do |
|  | `A-104` | Journey instance tables | 2 | `A-101` `A-103` ᶦ | Wed 23 Sep | Thu 24 Sep | 5 | ▫️ to do |
|  | `A-105` | ob_step_clock_events | 1 | `A-104` ᶦ | Wed 30 Sep | Wed 30 Sep | 6 | ▫️ to do |
| 🔴 | `A-106` | Append-only pair, hash-chained | 3 | `A-104` ᶦ | Fri 25 Sep | Tue 29 Sep | 6 | ▫️ to do |
|  | `A-107` | Sign-off, outbox and escalation tables | 2 | `A-104` ᶦ | Thu 01 Oct | Fri 02 Oct | 6 | ▫️ to do |
|  | `A-108` | ob_dashboard_summary — pre-aggregated | 1 | `A-104` ᶦ | Sat 03 Oct | Sat 03 Oct | 6 | ▫️ to do |
| 🔴 | `A-109` | user_module_access and the grants | 1 | `A-108` ᶦ | Tue 06 Oct | Tue 06 Oct | 6 | ▫️ to do |
| 🔴 | `A-110` | modules JWT claim | 2 | `A-109` ᶦ | Wed 07 Oct | Thu 08 Oct | 6 | ▫️ to do |
| 🔴 | `A-111` | ModuleGuard | 2 | `A-110` ᶦ | Fri 09 Oct | Sat 10 Oct | 6 | ▫️ to do |
| 🔴 | `A-112` | OnboardingScopeResolver and ScopedJourneys | 3 | `A-111` ᶦ | Tue 13 Oct | Thu 15 Oct | 6 | ▫️ to do |
|  | `A-113` | PAN encryption and the reveal audit | 3 | `A-101` ᶦ | Fri 16 Oct | Tue 20 Oct | 6 | ▫️ to do |
|  | `A-114` | Permission-matrix entries | 2 | `A-112` ᶦ | Wed 21 Oct | Thu 22 Oct | 6 | ▫️ to do |
|  | `A-115` | ArchUnit: the two modules stay separable | 2 | `A-106` ᶦ | Fri 23 Oct | Sat 24 Oct | 6 | ▫️ to do |
|  | `A-116` | Module launcher and switcher | 2 | `A-110` ᶦ | Tue 27 Oct | Wed 28 Oct | 6 | ▫️ to do |
|  | `A-117` | OB-08 | 2 | `A-116` ᶦ | Thu 29 Oct | Fri 30 Oct | 6 | ▫️ to do |
| ▲🔴 | `A-118` | OpenAPI contract for the whole module | 3 | — | Wed 09 Sep | Fri 11 Sep | 0 | ▫️ to do |
| ▲🔴 | `A-119` | Wire-conformance ratchet for onboarding DTOs | 2 | `A-118` ᶦ | Sat 12 Sep | Tue 15 Sep | 0 | ▫️ to do |
|  | `A-120` | Public sign-off surface | 3 | `A-107` ᶦ | Sat 31 Oct | Wed 04 Nov | 6 | ▫️ to do |
|  | `A-121` | OTP issue and verify | 2 | `A-120` ᶦ | Thu 05 Nov | Fri 06 Nov | 6 | ▫️ to do |
|  | `A-122` | Permission-matrix completeness | 2 | `A-114` ᶦ | Sat 07 Nov | Tue 10 Nov | 6 | ▫️ to do |
|  | `A-123` | Mutation tests on the append-only pair | 2 | `A-106` ᶦ | Wed 11 Nov | Thu 12 Nov | 6 | ▫️ to do |
|  | `A-124` | ob_products master | 1 | `A-101` ᶦ | Fri 13 Nov | Fri 13 Nov | 6 | ▫️ to do |
|  | `A-125` | client_accounts + CLIENT principal — identity-layer table | 3 | `A-110` ᶦ | Sat 14 Nov | Wed 18 Nov | 6 | ▫️ to do |
|  | `A-126` | Portal route trees + ClientScopeResolver | 3 | `A-125` ᶦ | Thu 19 Nov | Sat 21 Nov | 6 | ▫️ to do |
|  | `A-127` | is_client_visible activation | 2 | `A-126` ᶦ | Tue 24 Nov | Wed 25 Nov | 8 | ▫️ to do |
|  | `A-128` | Service dependency & escalation schema | 1 | `A-103` ᶦ | Thu 26 Nov | Thu 26 Nov | 9 | ▫️ to do |

</details>

<details>
<summary><b>Stream B — Masters & Clients · Ayush · 82 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `B-001` | Seed: 6 roles + the full permission matrix from blueprint §2 | 1 | `A-003` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `B-002` | Seed: 11 task types | 1 | `A-007` | Fri 07 Aug | Fri 07 Aug | 20 | ✅ done |
|  | `B-003` | Seed: statuses | 1 | `A-007` | Fri 07 Aug | Fri 07 Aug | 13 | ✅ done |
|  | `B-004` | Seed: 3 workflow templates with their stages — Standard Dev Flow | 1 | `A-005` | Fri 07 Aug | Fri 07 Aug | 13 | ✅ done |
|  | `B-005` | JPA entities + repositories for the full model, built on A's schema | 3 | `A-006` `A-007` | Sat 08 Aug | Sat 08 Aug | 0 | ✅ done |
|  | `B-006` | MapStruct base configuration | 0.5 | `B-005` ᶦ | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `B-007` | Ticket fixture corpus | 2 | `B-004` `B-005` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `B-008` | Seed manifest with fixed load order | 0.5 | `B-001` `B-002` `B-003` `B-004` ᶦ | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `B-010` | Resource list | 2 | `B-005` `C-003` `A-012` | Tue 11 Aug | Tue 11 Aug | 16 | ✅ done |
|  | `B-011` | Resource create/edit | 2.5 | `B-010` ᶦ | Tue 11 Aug | Tue 11 Aug | 17 | ✅ done |
| 🔴 | `B-012` | Reporting-manager cycle detection | 1 | `B-011` ᶦ | Wed 12 Aug | Wed 12 Aug | 19 | ✅ done |
|  | `B-013` | Validations | 1 | `B-011` ᶦ | Wed 12 Aug | Wed 12 Aug | 19 | ✅ done |
|  | `B-014` | Deactivating a resource with open tickets forces the bulk… | 1 | `B-011` ᶦ | Wed 12 Aug | Wed 12 Aug | 17 | ✅ done |
|  | `B-015` | Role & permission master — module × CRUD/approve checkbox matrix | 2 | `B-001` `C-003` ᶦ | Thu 13 Aug | Thu 13 Aug | 18 | ✅ done |
|  | `B-016` | Project master list/create/edit — code | 2 | `B-005` `C-003` ᶦ | Thu 13 Aug | Thu 13 Aug | 16 | ✅ done |
|  | `B-017` | Team tab — resources + per-project role | 1.5 | `B-016` ᶦ | Thu 13 Aug | Fri 14 Aug | 17 | ✅ done |
|  | `B-018` | SLA tab | 1.5 | `B-016` ᶦ | Fri 14 Aug | Fri 14 Aug | 16 | ✅ done |
|  | `B-019` | Settings tab | 1.5 | `B-016` ᶦ | Fri 14 Aug | Fri 14 Aug | 17 | ✅ done |
|  | `B-020` | Task type master — the 11 seeded types, Admin-extensible | 1.5 | `B-002` `C-003` ᶦ | Sat 15 Aug | Sat 15 Aug | 16 | ✅ done |
|  | `B-021` | Priority master | 1.5 | `B-002` `C-003` | Sat 15 Aug | Sat 15 Aug | 15 | ✅ done |
|  | `B-022` | Notification template master | 2 | `B-005` `C-003` | Sat 15 Aug | Sat 15 Aug | 14 | ✅ done |
|  | `B-023` | Working calendar & holiday master | 2 | `B-005` `C-003` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `B-024` | Working-hours calculation service | 3 | `B-023` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `B-025` | Client list | 2 | `B-005` `C-003` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `B-026` | Client create/edit across four tabs | 3 | `B-025` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `B-027` | client_contacts child grid | 1.5 | `B-026` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `B-028` | Validation | 1 | `B-027` | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `B-029` | Deactivating a client with open tickets warns and blocks new… | 1 | `B-026` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
| 🔴 | `B-030` | Import engine as a schema registry — built once, registered twice | 2 | `B-005` | Tue 11 Aug | Tue 11 Aug | 15 | ✅ done |
|  | `B-031` | Step 1 — template download | 1.5 | `B-030` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `B-032` | Step 2 — upload, max 5 MB / 5,000 rows, event-driven SAX parse | 2 | `B-030` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `B-033` | Step 3 | 2 | `B-032` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
| 🔴 | `B-034` | Step 4 — dry-run validation preview | 2.5 | `B-033` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `B-035` | Step 5 — commit as a background job with progress bar | 2 | `B-034` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `B-036` | Error report generation | 1 | `B-034` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `B-037` | import_batches traceability | 1 | `B-035` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `B-038` | Resource bulk import — the second registration, not a second build | 1 | `B-035` | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `B-039` | Status/stage/workflow master tab 1 | 2 | `B-003` `C-003` ᶦ | Tue 18 Aug | Tue 18 Aug | 7 | ✅ done |
|  | `B-040` | Tab 2 — stages | 2 | `B-039` | Tue 18 Aug | Tue 18 Aug | 8 | ✅ done |
|  | `B-041` | Tab 3 | 2.5 | `B-040` `B-050` | Fri 21 Aug | Fri 21 Aug | 11 | ✅ done |
| 🔴 | `B-042` | Stages in use may be deprecated, never deleted | 1 | `B-040` | Wed 19 Aug | Wed 19 Aug | 13 | ✅ done |
|  | `B-043` | Workflow template designer | 3 | `B-041` `B-042` | Fri 21 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `B-050` | Ribbon segment component — 6 states | 2.5 | `C-003` `C-042` | Thu 20 Aug | Thu 20 Aug | 11 | ✅ done |
|  | `B-051` | Compact dot variant for the ticket list. — components/ribbon/ | 1 | `B-050` ᶦ | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-052` | Ribbon accessibility | 1.5 | `B-050` ᶦ | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-053` | Readability at 8 stages on a laptop | 2 | `B-050` ᶦ | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-060` | Client report | 2 | `A-064` `B-029` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `B-061` | Resource performance scorecard and workload/capacity report | 2 | `A-064` `B-010` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `B-062` | Export engine integration for all report types | 1.5 | `A-064` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `B-063` | Timesheet view — stage-aware, a resource's week across all tickets | 2 | `A-064` `C-061` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
| 🔴 | `B-064` | Module master read endpoint | 0.25 | `C-065` | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
|  | `B-065` | 🟡 Timesheet approval | 1 | — | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-066` | Client 360 | 2 | — | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-067` | Masters index — the sidebar's Masters entry lands on a placeholder | 1 | — | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-068` | Org settings screen — decided the API is enough | 1 | — | Sat 22 Aug | Sat 22 Aug | 11 | ✅ done |
|  | `B-101` | Fixture corpus | 2 | `A-104` ᶦ | Sat 26 Sep | Tue 29 Sep | 12 | ▫️ to do |
| 🔴 | `B-102` | Client CRUD and the duplicate guard | 3 | `A-112` `A-113` ᶦ | Sat 24 Oct | Wed 28 Oct | 9 | ▫️ to do |
|  | `B-103` | SPOC contacts — multiple per client, one primary | 1 | `B-102` ᶦ | Thu 29 Oct | Thu 29 Oct | 9 | ▫️ to do |
|  | `B-104` | Applications purchased — with license start and end dates | 1 | `B-102` ᶦ | Fri 30 Oct | Fri 30 Oct | 9 | ▫️ to do |
|  | `B-106` | Requirements | 1 | `B-102` ᶦ | Sat 31 Oct | Sat 31 Oct | 9 | ▫️ to do |
|  | `B-107` | Client attachments — the existing upload pipeline, unchanged | 1 | `B-102` ᶦ | Tue 03 Nov | Tue 03 Nov | 9 | ▫️ to do |
|  | `B-108` | OB-03 — client list — filter by status, RAG, owner and sales person | 2 | `B-102` ᶦ | Wed 04 Nov | Thu 05 Nov | 9 | ▫️ to do |
|  | `B-109` | OB-04 — new client wizard — four steps, multi-product selection | 3 | `B-103` `B-104` `B-106` `B-107` ᶦ | Fri 06 Nov | Tue 10 Nov | 9 | ▫️ to do |
| 🔴 | `B-110` | Outbox dispatcher with retry | 2 | `A-107` ᶦ | Sat 03 Oct | Tue 06 Oct | 9 | ▫️ to do |
|  | `B-111` | Email templates through the existing mail engine — no new transport | 1 | `B-110` ᶦ | Wed 07 Oct | Wed 07 Oct | 9 | ▫️ to do |
|  | `B-112` | OB-13 | 2 | `B-110` ᶦ | Thu 08 Oct | Fri 09 Oct | 9 | ▫️ to do |
|  | `B-113` | OB-11 and OB-12 | 2 | `C-115` ᶦ | Sat 28 Nov | Tue 01 Dec | 9 | ▫️ to do |
|  | `B-114` | Daily digest to managers | 1 | `B-112` ᶦ | Sat 10 Oct | Sat 10 Oct | 9 | ▫️ to do |
|  | `B-115` | OB-09 | 2 | `A-121` ᶦ | Wed 11 Nov | Thu 12 Nov | 9 | ▫️ to do |
|  | `B-116` | Acceptance PDF | 2 | `B-115` ᶦ | Sat 14 Nov | Tue 17 Nov | 9 | ▫️ to do |
| 🔴 | `B-117` | The objection path | 1 | `B-115` ᶦ | Fri 13 Nov | Fri 13 Nov | 9 | ▫️ to do |
|  | `B-118` | Go-live flip | 1 | `B-116` ᶦ | Wed 18 Nov | Wed 18 Nov | 9 | ▫️ to do |
|  | `B-119` | CSAT — a public one-question page, storage, and a summary | 2 | `B-118` ᶦ | Thu 19 Nov | Fri 20 Nov | 9 | ▫️ to do |
|  | `B-120` | ob_dashboard_summary refresh job | 2 | `A-108` ᶦ | Tue 13 Oct | Wed 14 Oct | 9 | ▫️ to do |
|  | `B-121` | OB-02 | 3 | `B-120` ᶦ | Thu 15 Oct | Sat 17 Oct | 9 | ▫️ to do |
|  | `B-122` | OB-10 | 3 | `B-120` ᶦ | Tue 20 Oct | Thu 22 Oct | 9 | ▫️ to do |
|  | `B-123` | Export redaction | 1 | `B-122` ᶦ | Fri 23 Oct | Fri 23 Oct | 9 | ▫️ to do |
|  | `B-124` | Prerequisites master + OB-14 — versioned master task set | 3 | `A-101` ᶦ | Fri 18 Sep | Tue 22 Sep | 12 | ▫️ to do |
|  | `B-125` | Per-client prerequisite instances | 3 | `B-124` ᶦ | Wed 23 Sep | Fri 25 Sep | 12 | ▫️ to do |
|  | `B-126` | Client-account panel | 2 | `A-125` ᶦ | Wed 02 Dec | Thu 03 Dec | 9 | ▫️ to do |
|  | `B-127` | Dashboard v1.2 cards + drill slide-over | 3 | `B-119` `C-113` ᶦ | Sat 21 Nov | Wed 25 Nov | 9 | ▫️ to do |
|  | `B-128` | Delayed-projects grid + implementor workload & performance grid | 2 | `B-127` ᶦ | Thu 26 Nov | Fri 27 Nov | 9 | ▫️ to do |

</details>

<details>
<summary><b>Stream C — Tickets & Ribbon · Divyansh · 84 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `C-001` | Vite + React 18 + TypeScript scaffold, TanStack Query, Zustand… | 1 | — | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
| 🔴 | `C-002` | Design tokens from blueprint §12.1 → tokens.css + tailwind.config.ts | 1.5 | `C-001` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `C-003` | Shared component library | 3 | `C-002` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `C-004` | Storybook, with every shared component documented | 1.5 | `C-003` | Fri 07 Aug | Fri 07 Aug | 22 | ✅ done |
|  | `C-005` | App shell | 2 | `C-003` | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
|  | `C-006` | Command palette on Ctrl+K for jump-to-ticket | 1 | `C-005` ᶦ | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `C-010` | Create ticket — all field groups from blueprint §7.5 | 2.5 | `C-005` `D-004` | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `C-011` | Ticket ID generation | 1 | `A-003` `A-012` | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-012` | SLA policy resolution → auto-computed Planned Close Date, previewed… | 1.5 | `C-011` `B-024` | Wed 12 Aug | Wed 12 Aug | 19 | ✅ done |
|  | `C-013` | Actions: Save & Assign · Save as Draft · Save & Create Another | 1.5 | `C-010` `C-011` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-014` | Ticket list — filters | 3 | `C-005` `D-004` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-015` | Saved views | 1 | `C-014` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-016` | Row colour cues | 0.5 | `C-014` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `C-017` | Bulk select → reassign / change level / close (PM & Admin only) | 1.5 | `C-014` `A-034` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `C-018` | My Tasks | 2.5 | `C-014` ᶦ | Tue 11 Aug | Tue 11 Aug | 18 | ✅ done |
|  | `C-019` | Detail shell + summary panel — every entity a link | 2 | `C-005` `D-004` | Tue 11 Aug | Tue 11 Aug | 13 | ✅ done |
|  | `C-020` | Priority dropdown | 1.5 | `C-019` `B-021` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `C-021` | Client + client-contact dependent dropdowns, type-ahead over… | 2 | `C-019` `B-028` | Wed 19 Aug | Wed 19 Aug | 13 | ✅ done |
|  | `C-022` | Client-raised flag driving client-wise reports, CSAT and the… | 0.5 | `C-021` ᶦ | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-023` | Upload surfaces | 1.5 | `C-019` ᶦ | Wed 12 Aug | Wed 12 Aug | 16 | ✅ done |
| 🔴 | `C-024` | Clipboard paste alongside drag-drop and file picker | 1 | `C-023` ᶦ | Fri 14 Aug | Fri 14 Aug | 17 | ✅ done |
|  | `C-025` | Security | 2 | `C-023` ᶦ | Fri 14 Aug | Fri 14 Aug | 15 | ✅ done |
|  | `C-026` | Thumbnails, gallery strip, lightbox with zoom and next/previous | 1.5 | `C-025` ᶦ | Sat 15 Aug | Sat 15 Aug | 15 | ✅ done |
|  | `C-027` | Limits — 10 MB/file, 50 MB/ticket, 20 files/ticket, all configurable | 0.5 | `C-025` ᶦ | Sat 15 Aug | Sat 15 Aug | 16 | ✅ done |
|  | `C-028` | Delete within 15 minutes by the uploader; after that a soft delete… | 1 | `C-025` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `C-029` | Rich-text comment box under the description, always visible above… | 1.5 | `C-019` ᶦ | Sun 16 Aug | Sun 16 Aug | 0 | ✅ done |
|  | `C-030` | @mention type-ahead over project members, firing notification +… | 1.5 | `C-029` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `C-031` | Visibility toggle — default internal, always | 1 | `C-029` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `C-032` | Stamping | 1 | `C-029` `C-042` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-033` | ~~5-minute edit window~~ no time limit | 1.5 | `C-029` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
| 🔴 | `C-034` | Interleave comments into the History tab | 1.5 | `C-029` `C-059` | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `C-035` | Effort logging, append-only, auto-stamped with current stage and… | 1.5 | `C-019` `A-040` | Tue 18 Aug | Tue 18 Aug | 9 | ✅ done |
|  | `C-036` | Quick Update slide-over | 2.5 | `C-018` `C-035` ᶦ | Tue 18 Aug | Tue 18 Aug | 14 | ✅ done |
|  | `C-037` | Quick Update must not expose | 0.5 | `C-036` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
| 🔴 | `C-038` | Reopen transaction — seal cycle N | 2.5 | `C-013` `A-040` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `C-039` | Reopen dialog — mandatory reason, restart stage | 1.5 | `C-038` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `C-040` | Close/resolve dialog | 1.5 | `C-038` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `C-041` | Materialised total_effort_hrs, refreshed on every effort insert | 1 | `C-035` ᶦ | Tue 18 Aug | Tue 18 Aug | 13 | ✅ done |
|  | `C-042` | Transition service | 2.5 | `A-042` `B-040` | Wed 19 Aug | Wed 19 Aug | 8 | ✅ done |
| 🔴 | `C-043` | The golden rule — only the current stage owner | 1.5 | `C-042` `A-033` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-044` | Handoff dialog — next stage | 2.5 | `C-042` `C-035` | Wed 19 Aug | Wed 19 Aug | 9 | ✅ done |
|  | `C-045` | On submit: seal the current row | 2 | `C-044` `D-014` | Wed 19 Aug | Wed 19 Aug | 10 | ✅ done |
|  | `C-046` | Backward moves | 1.5 | `C-042` ᶦ | Thu 20 Aug | Thu 20 Aug | 12 | ✅ done |
|  | `C-047` | Skip a stage | 1 | `C-042` ᶦ | Thu 03 Sep | Thu 03 Sep | 3 | ▫️ to do |
|  | `C-048` | Force-move (OVERRIDE) — PM/Admin, logged as an override | 1 | `C-042` ᶦ | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
|  | `C-049` | Reassignment within a stage does not create a new segment | 1.5 | `C-042` | Thu 20 Aug | Thu 20 Aug | 12 | ✅ done |
|  | `C-050` | Unassigned receiving role → ticket falls to a project-level queue… | 1 | `C-044` ᶦ | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
| 🔴 | `C-051` | Ribbon component | 3 | `B-050` | Thu 20 Aug | Thu 20 Aug | 12 | ✅ done |
|  | `C-052` | Interactions | 2 | `C-051` ᶦ | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
|  | `C-053` | Cycle selector above the ribbon; selecting cycle 1 renders that… | 1.5 | `C-051` `C-038` | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
|  | `C-054` | Cycle 2 · Iteration 3 chips | 1 | `C-051` `C-046` | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
|  | `C-059` | History tab | 1.5 | `C-019` `A-040` | Tue 18 Aug | Tue 18 Aug | 14 | ✅ done |
|  | `C-060` | Attachments tab | 1 | `C-026` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `C-061` | Effort tab — every log line, sum per cycle + grand total | 1 | `C-041` ᶦ | Tue 18 Aug | Tue 18 Aug | 14 | ✅ done |
|  | `C-063` | Bulk reassignment wizard | 2 | `C-017` `B-014` | Tue 18 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-064` | Ticket linking — blocks / is blocked by / duplicate of / relates to | 1.5 | `C-019` ᶦ | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-066` | Shared rich-text editor in components/ui/ + Storybook | 1.5 | `C-003` | Tue 11 Aug | Tue 11 Aug | 19 | ✅ done |
| 🔴 | `C-071` | Per-project settings are configurable and ignored | 1 | — | Fri 21 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `C-072` | A deactivated priority can still be chosen | 1 | — | Fri 21 Aug | Fri 21 Aug | 12 | ✅ done |
| ▲ | `C-101` | Template domain and versioning | 3 | `A-103` ᶦ | Wed 23 Sep | Fri 25 Sep | 0 | ▫️ to do |
| ▲ | `C-102` | OB-07 | 4 | `C-101` ᶦ | Sat 26 Sep | Thu 01 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-103` | Instantiation | 3 | `C-101` `A-104` ᶦ | Fri 02 Oct | Tue 06 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-104` | Step lifecycle | 3 | `C-103` ᶦ | Wed 07 Oct | Fri 09 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-105` | Clock events and working-calendar due_at | 3 | `C-104` `A-105` ᶦ | Tue 27 Oct | Thu 29 Oct | 0 | ▫️ to do |
| ▲🔴 | `C-106` | Sub-category answers and the completion gate — one server-side gate | 2 | `C-104` ᶦ | Sat 10 Oct | Tue 13 Oct | 0 | ▫️ to do |
| ▲ | `C-107` | Skip a step — Manager and Admin only, reason mandatory, history row | 1 | `C-104` ᶦ | Wed 14 Oct | Wed 14 Oct | 0 | ▫️ to do |
| ▲ | `C-108` | Backup owner | 2 | `C-104` ᶦ | Thu 15 Oct | Fri 16 Oct | 0 | ▫️ to do |
| 🔴 | `C-109` | The onboarding ribbon | 3 | — | Fri 04 Sep | Tue 08 Sep | 10 | ▫️ to do |
| ▲ | `C-110` | OB-05 | 3 | `C-109` `B-102` ᶦ | Fri 13 Nov | Tue 17 Nov | 0 | ▫️ to do |
| ▲ | `C-111` | OB-06 | 3 | `C-106` `C-110` ᶦ | Wed 18 Nov | Fri 20 Nov | 0 | ▫️ to do |
| ▲ | `C-112` | Communications timeline | 2 | `C-111` ᶦ | Sat 21 Nov | Tue 24 Nov | 0 | ▫️ to do |
| ▲🔴 | `C-113` | TAT scanner worker job | 3 | `C-105` ᶦ | Fri 30 Oct | Tue 03 Nov | 0 | ▫️ to do |
| ▲ | `C-114` | RAG computation | 2 | `C-113` ᶦ | Wed 04 Nov | Thu 05 Nov | 0 | ▫️ to do |
| ▲ | `C-115` | Escalation matrix | 3 | `C-114` ᶦ | Fri 06 Nov | Tue 10 Nov | 0 | ▫️ to do |
| ▲ | `C-116` | Ribbon accessibility pass | 2 | `C-111` ᶦ | Wed 25 Nov | Thu 26 Nov | 0 | ▫️ to do |
| ▲ | `C-117` | Scanner load pass | 2 | `C-115` ᶦ | Wed 11 Nov | Thu 12 Nov | 0 | ▫️ to do |
| ▲🔴 | `C-118` | PrerequisiteGateService — every mandatory task VERIFIED | 3 | `C-103` `B-125` ᶦ | Thu 22 Oct | Sat 24 Oct | 0 | ▫️ to do |
| ▲ | `C-119` | Step dependency graph — depends_on_step_id | 3 | `C-104` ᶦ | Sat 17 Oct | Wed 21 Oct | 0 | ▫️ to do |
| ▲ | `C-120` | Journey TAT roll-up — total TAT | 1 | `C-110` ᶦ | Fri 27 Nov | Fri 27 Nov | 0 | ▫️ to do |
| ▲ | `C-121` | CP-01..CP-04 | 4 | `A-126` `B-125` ᶦ | Wed 02 Dec | Sat 05 Dec | 0 | ▫️ to do |
| ▲ | `C-122` | CP-05..CP-07 | 2 | `C-121` `A-127` ᶦ | Tue 08 Dec | Wed 09 Dec | 0 | ▫️ to do |
| ▲ | `C-123` | Service-level dependency engine | 3 | `C-118` `A-128` ᶦ | Thu 10 Dec | Sat 12 Dec | 0 | ▫️ to do |
| ▲ | `C-125` | Ribbon SD/FD + animated status emojis + v1.2 terminology sweep | 2 | `C-110` ᶦ | Sat 28 Nov | Tue 01 Dec | 0 | ▫️ to do |
| ▲ | `C-126` | Portal escalation flow | 2 | `C-121` `A-128` ᶦ | Tue 15 Dec | Wed 16 Dec | 0 | ▫️ to do |

</details>

<details>
<summary><b>Stream D — Engines & Realtime · Debashis · 67 tasks</b></summary>

| | Task | Title | Est | Predecessors | Start | End | Float | Status |
|---|---|---|---:|---|---|---|---:|---|
|  | `C-055` | Roll-up grid | 2 | `C-042` `B-024` | Wed 19 Aug | Wed 19 Aug | 12 | ✅ done |
| 🔴 | `C-056` | Active vs idle split | 1.5 | `C-055` | Wed 19 Aug | Wed 19 Aug | 13 | ✅ done |
|  | `C-057` | Per-resource roll-up + cycle total + all-cycles total | 1.5 | `C-056` ᶦ | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-058` | Roll-up query | 1 | `C-055` | Wed 19 Aug | Wed 19 Aug | 13 | ✅ done |
|  | `C-062` | Stage Queue / team inbox | 2 | `C-014` `C-042` | Wed 19 Aug | Wed 19 Aug | 12 | ✅ done |
|  | `C-065` | product_modules master + the four columns on tickets — table | 0.5 | `D-060` | Wed 19 Aug | Wed 19 Aug | 12 | ✅ done |
|  | `C-067` | Backend wiring for all four fields | 1 | `C-065` `C-010` | Wed 19 Aug | Wed 19 Aug | 13 | ✅ done |
|  | `C-068` | Create form S-19 — the new "Where it happened" group | 1 | `C-066` `C-067` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-069` | Detail page S-20 shows all four, inline-editable | 0.5 | `C-019` `C-067` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
|  | `C-070` | List S-17 gains a Module filter | 0.5 | `C-014` `C-067` | Wed 19 Aug | Wed 19 Aug | 14 | ✅ done |
| 🔴 | `D-001` | OpenAPI contract for every endpoint in blueprint §13 | 3 | — | Thu 06 Aug | Sat 22 Aug | 0 | ✅ done |
|  | `D-002` | Conventions baked into the spec | 1 | `D-001` | Thu 06 Aug | Thu 06 Aug | 0 | ✅ done |
|  | `D-003` | springdoc config + codegen pipeline | 2 | `D-002` | Thu 06 Aug | Thu 06 Aug | 20 | ✅ done |
| 🔴 | `D-004` | MSW mock server returning realistic fixtures for every endpoint | 2.5 | `D-002` | Thu 06 Aug | Tue 11 Aug | 0 | ✅ done |
|  | `D-005` | CI staleness check | 1 | `D-003` | Thu 06 Aug | Sat 08 Aug | 21 | ✅ done |
| 🔴 | `D-010` | Outbox worker pattern | 2.5 | `A-006` `A-012` | Fri 07 Aug | Fri 07 Aug | 18 | ✅ done |
|  | `D-011` | @Scheduled + ShedLock | 1 | `D-010` ᶦ | Fri 07 Aug | Fri 07 Aug | 19 | ✅ done |
|  | `D-012` | Spring WebSocket + STOMP config, Redis pub/sub relay for… | 2 | `A-012` ᶦ | Fri 07 Aug | Fri 07 Aug | 0 | ✅ done |
| 🔴 | `D-013` | Channel interceptor authorising subscriptions with the same scope… | 2 | `D-012` `A-034` | Sat 08 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-014` | Destination map per blueprint §9.3 | 1 | `D-012` | Fri 07 Aug | Fri 07 Aug | 11 | ✅ done |
|  | `D-015` | Frontend STOMP client | 1.5 | `D-014` `C-005` | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
| 🔴 | `D-020` | SLA scanner, every 15 minutes | 2 | `D-011` `B-024` `A-009` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-021` | 80%-of-SLA pre-breach warning to the assignee | 1 | `D-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-022` | Stale-task nudge — no update for 3 working days, to assignee cc RM | 1 | `D-020` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `D-023` | Stage-SLA scanner, separate from ticket SLA | 2 | `D-020` `C-042` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-024` | Escalation matrix per project | 1.5 | `D-020` `B-018` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-025` | Ping-pong flag at iteration_no ≥ 3 → PM dashboard | 0.5 | `D-023` ᶦ | Tue 11 Aug | Tue 11 Aug | 20 | ✅ done |
|  | `D-026` | Unassigned ticket > 2 h → triage alert to PM and Support Desk | 1 | `D-020` ᶦ | Tue 11 Aug | Tue 11 Aug | 20 | ✅ done |
| 🔴 | `D-027` | Every calculation routes through Stream B's working-hours service | 1 | `D-020` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-028` | original_level preserved so "born critical vs became critical"… | 1 | `D-020` ᶦ | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `D-029` | Thymeleaf templates driven by Stream B's notification template… | 1.5 | `D-010` `B-022` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `D-030` | Mail body | 1.5 | `D-029` ᶦ | Mon 17 Aug | Mon 17 Aug | 10 | ✅ done |
|  | `D-031` | Subject pattern with the ticket ID first so it threads and searches… | 0.5 | `D-029` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
| 🔴 | `D-032` | Threading | 1.5 | `D-031` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-033` | Every send logged in email_log with status, provider message ID and… | 1 | `D-010` | Fri 07 Aug | Fri 07 Aug | 21 | ✅ done |
|  | `D-034` | Bounce and complaint webhooks | 1 | `D-033` ᶦ | Fri 07 Aug | Fri 07 Aug | 22 | ✅ done |
|  | `D-035` | Rate limit | 1 | `D-033` ᶦ | Fri 07 Aug | Fri 07 Aug | 22 | ✅ done |
| 🔴 | `D-036` | "Critical mails cannot be disabled" | 1 | `D-029` | Mon 10 Aug | Mon 10 Aug | 11 | ✅ done |
|  | `D-037` | All 15 mail events from §4B.6 wired | 2 | `D-030` `D-036` ᶦ | Mon 17 Aug | Fri 21 Aug | 11 | ✅ done |
|  | `D-038` | Daily digest 08:30 and weekly manager summary | 1.5 | `D-037` ᶦ | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `D-039` | Inbound webhook — reply-to-comment parsing with quoted text stripped | 2 | `D-032` ᶦ | Wed 12 Aug | Wed 12 Aug | 19 | ✅ done |
|  | `D-040` | All 24 events from blueprint §11 across in-app / bell / email… | 2 | `D-012` `B-022` | Mon 17 Aug | Mon 17 Aug | 0 | ✅ done |
|  | `D-041` | Notification centre — bell dropdown (last 10) + full page with tabs | 2.5 | `D-040` `C-005` | Sat 08 Aug | Sat 08 Aug | 16 | ✅ done |
|  | `D-042` | Per-user preference matrix — which events, which channel | 1.5 | `D-041` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-043` | In-app toast via WebSocket, appearing within ~1 second, with Open /… | 1 | `D-041` `D-014` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-044` | Persistent bell badge with unread count | 0.5 | `D-041` ᶦ | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-045` | Browser push via the Web Push API for users who opt in | 1.5 | `D-043` ᶦ | Tue 11 Aug | Fri 14 Aug | 17 | ✅ done |
| 🔴 | `D-046` | Offline queueing | 1.5 | `D-043` | Mon 10 Aug | Mon 10 Aug | 0 | ✅ done |
|  | `D-050` | Chat engine, three surfaces one engine | 3 | `D-014` `C-005` | Sat 08 Aug | Sat 08 Aug | 11 | ✅ done |
|  | `D-051` | Typing indicator, read receipts, unread counts | 2 | `D-050` ᶦ | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `D-052` | @mentions firing notifications | 1.5 | `D-050` ᶦ | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `D-053` | File and image share, emoji, message search | 2 | `D-050` ᶦ | Sat 08 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `D-054` | TKT-xxxx link preview rendering as a rich ticket card | 1 | `D-050` ᶦ | Thu 13 Aug | Thu 13 Aug | 18 | ✅ done |
| 🔴 | `D-055` | Ask Status | 1.5 | `D-050` `C-036` | Thu 13 Aug | Thu 13 Aug | 17 | ✅ done |
|  | `D-056` | Manager response time recorded as a reportable metric; status… | 1 | `D-055` ᶦ | Thu 13 Aug | Thu 13 Aug | 18 | ✅ done |
| 🔴 | `D-057` | Chat immutable after a 5-minute edit window; deletions leave… | 1.5 | `D-050` | Sat 08 Aug | Sat 08 Aug | 21 | ✅ done |
|  | `D-058` | Live ribbon advance | 1 | `D-014` `C-045` | Thu 20 Aug | Fri 21 Aug | 10 | ✅ done |
|  | `D-059` | Team inbox live updates | 1 | `D-058` `C-062` | Thu 20 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `D-060` | Ticket "where it happened" fields in the contract and the mock… | 0.5 | `D-004` | Tue 11 Aug | Tue 11 Aug | 17 | ✅ done |
| 🔴 | `D-061` | GET /tickets returns a body the contract does not declare | 1 | — | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `D-062` | Nothing compares a response body to its contract schema, and that… | 1 | — | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `D-063` | S-17 grid — the actual close date column | 1 | — | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
|  | `D-064` | S-06 drill-down | 1 | — | Tue 18 Aug | Tue 18 Aug | 15 | ✅ done |
| 🔴 | `D-065` | The chat React surface | 3 | — | Thu 20 Aug | Thu 20 Aug | 13 | ✅ done |
|  | `D-066` | 🟡 S-12 promises "Admin can add levels" and the contract cannot… | 1 | — | Thu 20 Aug | Fri 21 Aug | 12 | ✅ done |
|  | `D-101` | WhatsApp provider adapter — behind the outbox with delivery webhooks | 3 | `B-110` ᶦ | Wed 07 Oct | Fri 09 Oct | 47 | ▫️ to do |
|  | `D-102` | Escalation notification events | 1 | `B-110` ᶦ | Sat 10 Oct | Sat 10 Oct | 47 | ▫️ to do |

</details>

---

*`ᶦ` marks an **inferred** dependency — derived from task ordering, not confirmed by its owner. Correct them in `tasks.csv` and the critical path stops being a hypothesis.*

# EduTrack Phase 2 — Client Onboarding: Build Plan

**Status: v1.0 — proposed. Nothing here is scheduled until §11's six decisions are answered and the backlog is loaded into `docs/plan/tasks.csv`.**

Phase 1 (ticketing) is 241 of 244 tasks done. This document is the build spec for phase 2.

## 0. What this document is, and what it is not

Phase 2 has the same two-document split as phase 1, and it matters which one wins where:

| Document | Analogue in phase 1 | Authority on |
|---|---|---|
| `docs/Onboarding-Module-Plan.md` | `Ticketing-System-Blueprint.md` | **Behaviour** — what the module is, every rule and field |
| `docs/prototype/onboarding.html` | `docs/prototype/index.html` | **Appearance and interaction** — the screens as they will look |
| **This file** | `PLAN.md` + `TEAM-PLAN.md` | **Implementation** — tasks, IDs, ownership, sequence, schedule |

Where the module plan and this file disagree, the module plan wins on behaviour and this file wins on implementation — the same rule `CLAUDE.md` already states for the blueprint and `PLAN.md`. Where the module plan and the prototype disagree, §3 rules on each case individually; none of them are left implicit.

Everything in `CLAUDE.md` applies unchanged: feature packaging, timestamp migrations, UTC storage, working-calendar maths, no live `COUNT(*)`, append-only history, 404-not-403, batch integration, drafts by default.

> ⚠️ **Both source documents are currently untracked in git.** `docs/Onboarding-Module-Plan.md` and `docs/prototype/onboarding.html` exist only on one laptop. They should be committed to `develop` before this plan is approved — a build plan whose authority is a file nobody else can read is not a plan. Suggested first commit of phase 2, and it needs no task ID.

---

## 1. When phase 2 can start

Three phase-1 tasks remain, and only two are on the critical path:

| Task | Owner | Forecast | Float |
|---|---|---|---|
| A-073 — Performance | Shivendra | 25–26 Aug | 0 (critical) |
| A-075 — Go-live runbook, TLS, secrets in vault | Shivendra | 27–28 Aug | 0 (critical) |
| C-047 — Skip a stage | Divyansh | 25 Aug | 3 days |

The phase-1 critical path ends **Fri 28 Aug 2026** — and only Shivendra is on it. Ayush and Divyansh have nothing left, so phase 2 opens for them on **Wed 26 Aug**, while Shivendra's phase-2 queue waits behind A-073 and A-075. The scheduler enforces that per developer; see §5.

**Stream D is not staffed in phase 2.** Debashis integrates rather than building, so the four-stream shape of phase 1 becomes three. §6.0 says where D's 23 days went.

~~Submit the WhatsApp business templates.~~ **WhatsApp is out of scope — decided 25 Aug 2026.** See §6.1.

---

## 2. What the design has already decided

The prototype is not a sketch — it resolves several things the module plan deliberately left open, and it does so consistently. These are now closed:

| Question | Module plan | Design decides | Status |
|---|---|---|---|
| Module entry (§12.1) | Option A or B, recommends A | **Option A — launcher with two cards, plus a top-bar switcher for dual-access users; single-module users skip it entirely** | **Locked** |
| Escalation timings (§5.7) | L2 after *n* working hours, L3 after *2n* | **n = 4. L1 at breach → L2 at +4 working hrs → L3 at +8** | Locked, admin-editable |
| Amber threshold (§1.1 #3) | "configurable, default 75%" | **75%**, editable on OB-11 | Locked |
| Scanner cadence | "every few minutes" | **5 minutes**, editable | Locked |
| Roles (§3) | Six named roles | **Exactly those six**, no additions | Confirmed |
| Default journey | not specified | **"Standard SaaS Onboarding" — 8 steps, 24 sub-categories, 5 required documents, 3 sign-off gates**, TATs 8–40 working hours | Adopt as the seeded default |

That last row is worth more than it looks. The prototype's `TPL_STEPS` is a complete, realistic, ready-to-seed journey template — Kickoff call → Requirements confirmation → Account & environment setup → Data migration → Configuration & branding → Admin & user training → UAT & issue closure → Go-live sign-off. It becomes the OB1 fixture corpus verbatim, which removes the "what do we demo against" problem that cost real time in phase 1.

**On design tokens, the news is good.** The prototype's palette is character-identical to `frontend/src/styles/tokens.css`: `--bg-app #F7F8FC`, `--primary #4F46E5`, `--success #10B981`, `--warning #F59E0B`, `--danger #EF4444`, `--info #3B82F6`. It introduces **no new colour**, so the accessibility and token rules in `CLAUDE.md` are satisfied by construction. It also means the onboarding screens inherit the opt-in dark theme (`b2d733b`) for free — *provided* they only ever reference tokens, never literals. Worth saying out loud, because the prototype hard-codes a handful of hex values inline (the True/False button states in the sub-category rows are `#A7F3D0` / `#047857` / `#FECACA` / `#B91C1C`); those five need token equivalents before they are ported, or they will be the only thing on the page that does not switch to dark.

---

## 3. Where the design and the module plan disagree

Twelve items. Each gets a ruling here rather than being discovered by whoever builds it.

| # | The discrepancy | Ruling |
|---|---|---|
| 1 | **`SKIPPED` is missing from the design.** Plan §4 lists six step statuses and §3 gives the Manager "override/skip a step with logged reason"; the prototype has five (`DONE · CURRENT · WAITING · BLOCKED · PENDING`) and no skip affordance anywhere. | **Build it.** Manager/Admin only, reason mandatory, writes to `ob_step_history`. Add the control to OB-06. Sized into OB2. |
| 2 | **Backup owner exists in the schema and nowhere in the UI.** Plan §1.1 #4 and `ob_journey_steps.backup_owner_id`; the prototype's edit rule is owner-or-Manager-or-Admin only, and the template designer has no backup field. | **Build it.** Keep the column, add the field to the designer and the step panel, and make the inheritance leave-aware against the working calendar. This is the whole point of the addition. |
| 3 | **The client-level stitched communications view is absent.** Plan §6 says the client-level view stitches every step's timeline into one chronological record and calls it "what management actually asks for"; the prototype shows per-step timelines only. | **Build it** as a tab on OB-05, reading across all steps. Cheap once the per-step timeline exists. |
| 4 | **Client sign-off bypasses the completion gate.** The prototype's `stComplete` enforces three gates (sub-categories answered, required documents attached, sign-off accepted); `signoffAccept` sets the step to `DONE` directly and enforces none of them. A client can therefore accept a step whose required documents were never attached. | **One gate, server-side, and acceptance routes through it.** If the gate fails on acceptance, record the sign-off and leave the step `IN_PROGRESS` with the failing reason visible — do not complete it and do not discard the acceptance. |
| 5 | **The waiting-on-client clock is a status flip, not a clock.** The prototype's `stWait` sets a status; the plan requires `ob_step_clock_events` rows and a recomputed `due_at` on resume. | **The build must not copy the mock.** Pause/resume rows from day one — this is the mitigation for the module plan's own risk #2, and retrofitting it means every TAT figure recorded before the retrofit is unusable. |
| 6 | **OB-13 is a popover, not a screen.** The plan lists a notification centre; the prototype has a bell dropdown. | **Both.** The popover is the daily surface; a full page is needed for history and for the digest links to land somewhere. Keep OB-13, sized small. |
| 7 | **The template designer is missing half of what §4 and §5 require.** The prototype edits name, TAT, owner, sign-off flag and sub-category labels. It does not edit parallel groups, `depends_on`, per-item `is_mandatory`, the required-document list, or step descriptions. | **All five are required** — parallel activation and dependencies are load-bearing for §5.3. **OB-07 is a 4-day task, not a 2-day one**, and that is the most under-estimated item in the phase. |
| 8 | **Reports: 7 designed, 12 specified.** The prototype has funnel, TAT compliance, time-to-live, payments, sales pipeline, stuck & aging, sign-offs pending. Plan §10 also lists breach log, escalation log, owner workload, communication audit per client, and CSAT summary. | **Build the 7 designed in OB4.** The other 5 are all straightforward reads over data that will already exist — schedule them as OB4b and let the client decide whether they ship in phase 2 or wait. Flagged in §11 as an open call. |
| 9 | **CSAT has no capture surface.** The plan asks for a one-question survey with the final sign-off; the prototype fires a toast saying it was sent. Nothing renders the question and nothing stores the answer. | **Needs a public page** on the same token surface as OB-09, plus one column and one report. Currently unowned — assigned to OB4 in §6. |
| 10 | **PAN masking reveals too much.** `maskPan` shows `p.slice(0,5) + "••••" + p.slice(9)` — 6 of 10 characters, including the full 5-character prefix (entity type + surname initial) and the checksum. And it unmasks *automatically* by role, so §11's "every unmasked read of PAN is audit-logged" has nothing to log. | **Two changes.** Mask to the last 4 only; and make revealing an explicit action, not a role-based default, so there is a discrete event to audit. Both are cheap now and neither is cheap later. |
| 11 | **The prototype has its own login** (`demo123`, seven demo users). | **No new auth work.** Phase 2 reuses the existing kernel — Argon2id, JWT, refresh rotation, rate limiting — unchanged. The only addition is the `modules` claim (A-107). This is the largest single saving in the phase. |
| 12 | **Dates are display strings** (`TODAY = "20 Aug 2026"`, `due: "21 Aug, 15:00"`). | Standard rule applies: `DATETIME(6)` UTC in storage, timezone applied in the presentation layer only. Restated because the prototype is a convincing model of the wrong thing. |

---

## 4. Task IDs — use the 100-block

Phase 2 tasks are numbered **from 101 upward in each staffed stream**: `A-101`, `B-101`, `C-101`. The `D-1xx` block stays unused and reserved, so Stream D can be restaffed without renumbering anything.

This is deliberate and it is the cheapest option available:

- **`plan.config.json` needs no change.** Its `task_id` regex is `[A-D]-\d{3}`, which already matches. An `OB-\d{3}` scheme would require changing the regex in a config read by an external tool, and every commit-subject convention, branch name and override entry along with it.
- **The block is clear.** Phase 1's highest IDs are A-077, B-068, C-072, D-070 — 23 spare in the tightest stream.
- **The phase split stays computable.** `int(id[2:]) >= 100` separates phase 2 from phase 1 in any report, which an `OB-` prefix would also give but at the cost above.

Milestone names carry the phase: `OB0 — Gate & schema`, `OB1 — Client capture`, and so on, so the Gantt groups correctly without any tooling change.

Branches, commits and migrations are unchanged in form: `feat/onboarding/journey-instantiation`, `feat(onboarding): pin template version at instantiation`, `V20260907_1030__ob_journeys.sql`.

**The commit-subject rule bites the same way it did four times in phase 1.** A task ID in a subject line marks that task done. Partial work names the task in the body.

---

## 5. Schedule

**Three developers, not four. Stream D is not staffed in phase 2** — see §6 for where its work went.

Dates below come from the scheduler, not from arithmetic here. It levels against the working calendar, each developer's own queue, and the phase-1 tail that is still open.

| | |
|---|---|
| **Phase 2 runs** | **Wed 26 Aug → Tue 17 Nov 2026** |
| Shivendra (A) | 23 tasks · 47 days · finishes Tue 03 Nov · **78% loaded** |
| Ayush (B) | 23 tasks · 41 days · finishes Tue 17 Nov · **68% loaded** |
| Divyansh (C) | 17 tasks · 44 days · finishes Sat 07 Nov · **73% loaded** |

**It starts on the 26th rather than after phase 1 finishes, and that is deliberate.** Ayush and Divyansh have no phase-1 work left; only Shivendra does, and the scheduler holds his phase-2 queue behind A-073 and A-075 rather than letting it jump. Phases are sequential *per developer*, not across the team — a developer with nothing ready in the earlier phase pulls later work forward rather than idling, which is the same rule `GANTT.md` has always applied within a phase.

**Nobody is over capacity.** Phase 1 ran at 102% — 365.8 effort-days across four developers over 90 working days. Phase 2 at 68–78% across three is materially lighter, and that headroom is where UAT, review and rework live, none of which is a task row.

**The one hard dependency**, the same shape as phase 1's scope guard: **`A-112` `OnboardingScopeResolver` and `A-111` ModuleGuard**. Until they land every onboarding endpoint is unscoped. B and C must not write their own filtering as a workaround — `dev-noauth` exists so they do not have to, and this is the exact failure `CLAUDE.md` ranks as the top risk.

The interactive chart is [`docs/plan/gantt.html`](plan/gantt.html); its **Phase** selector opens on the phase that still has work in it. The per-phase tables are at the top of [`docs/plan/GANTT.md`](plan/GANTT.md).

---

## 6. The backlog

**63 tasks, 132 working days.** IDs use the 100-block (§4). Full text — with the reasoning, the traps and the cross-stream flags — lives in each stream's own backlog, which is what the plan tooling reads:

- [`docs/streams/STREAM-A-PLATFORM.md`](streams/STREAM-A-PLATFORM.md) — 23 tasks, 47 days
- [`docs/streams/STREAM-B-MASTERS.md`](streams/STREAM-B-MASTERS.md) — 23 tasks, 41 days
- [`docs/streams/STREAM-C-TICKETS.md`](streams/STREAM-C-TICKETS.md) — 17 tasks, 44 days

Estimates and dependency edges are in [`docs/plan/seed.txt`](plan/seed.txt). **Every edge is marked `i` — inferred.** They are this document's reading of the module plan, not three developers agreeing to them; none of the three has seen the backlog yet. **The 17 Nov finish is a hypothesis until they have**, and the edges should be re-marked `c` as they confirm.

### 6.0 Where Stream D's work went

D carried the OpenAPI contract, the SLA scanners, the mail engine and the notification centre in phase 1. Without it, 23 days redistribute by fit rather than by arithmetic:

| Work | To | Days | Why there |
|---|---|---:|---|
| OpenAPI contract + wire-conformance ratchet | **A** | 5 | The contract is `common`/platform, and A is in the schema first — it must be written ahead of the screens |
| Outbox dispatcher, email templates, digest, notification centre, OB-11/OB-12 | **B** | 8 | Delivery plumbing and admin screens, which is the shape of B's phase-1 work |
| TAT scanner, RAG, escalation matrix, scanner load pass | **C** | 10 | Every input it reads — step state, clock events, `due_at` — is code C writes in OB2. Putting the scanner anywhere else means two people coordinating on one data model |

### Milestones

| Milestone | Contents | Owner |
|---|---|---|
| **OB0 — Gate & schema** | 18 `ob_*` tables, append-only pair, `user_module_access`, `modules` claim, ModuleGuard, OnboardingScopeResolver, PAN encryption, ArchUnit, launcher, OB-08, **the contract and the ratchet** | A |
| **OB1 — Client capture** | Fixtures, client CRUD + duplicate guard, contacts, applications, payments, requirements, attachments, OB-03, OB-04 | B |
| **OB2 — Journey engine & ribbon** | Templates + OB-07, instantiation, step lifecycle, clock events, sub-category gate, skip, backup owner, the ribbon, OB-05, OB-06, communications | C |
| **OB3 — TAT & notifications** | Scanner, RAG, escalation *(C)* · outbox, email, notification centre, OB-11/OB-12, digest *(B)* | C + B |
| **OB4 — Sign-off, dashboard, reports** | Public token surface + OTP *(A)* · OB-09, PDF, objection path, go-live flip, CSAT, summary job, OB-02, OB-10 *(B)* | A + B |
| **OB5 — Hardening** | Permission matrix × 6 roles, mutation tests *(A)* · ribbon accessibility, scanner load pass *(C)* · export redaction *(B)* | All |

### The four tasks worth watching

Not the longest — the ones where getting it wrong is expensive to undo.

**`A-109` — the grants.** Eighteen tables arrive at once and `edutrack_app` holds per-table grants. `make grants` runs *inside* the task. The failure mode is a startup error naming a table that plainly exists, and it cost two debugging sessions in phase 1.

**`A-119` — the conformance ratchet, in week 2.** Before a screen exists. Phase 1 added this after the fact and paid three shipped bugs and a blank detail page for the privilege (§8).

**`C-105` — clock events as rows.** The design models the waiting-on-client clock as a status flip. Copying that is the mistake: retrofitting real pause/resume rows invalidates every TAT figure recorded before the retrofit.

**`C-109` — the ribbon, built fresh.** No import from `components/ribbon/`. Importing couples two release cycles on day one and is very hard to undo once four screens depend on it.

### Load per developer

| Stream | Days of work | Working days in phase | Load |
|---|---:|---:|---:|
| A — Shivendra | 47 | 60 | 78% |
| B — Ayush | 41 | 60 | 68% |
| C — Divyansh | 44 | 60 | 73% |

**Stream A carries OB0 nearly alone in the first fortnight** and is the tightest of the three. The slack is not spare time — it is UAT, integration, review and the rework each produces, none of which appears as a task row.

**OB4b — the 5 reports §10 specifies and the design does not draw** (breach log, escalation log, owner workload, communication audit, CSAT summary): ~4 days, all straightforward reads over data that exists by then. Not in the ledger; held pending the §11.6 call.

---

### 6.1 WhatsApp — deferred, 25 Aug 2026

**Phase 2 notifies by email only.** WhatsApp is out of scope: no provider account, no template submission, no adapter implementation, no webhook.

This is a deviation from the module plan, whose §7 specifies email **and** WhatsApp, and it is recorded here rather than made quietly. Two tasks come out — a template submission pack (1 day) and the provider adapter with its signed webhook and delivery status (3 days) — and OB-12 narrows to email templates only.

**Three things stay in, because leaving them out is what would make this expensive to reverse:**

| Kept | Why |
|---|---|
| `ob_notification_outbox.channel ENUM('EMAIL','WHATSAPP')` | The enum value costs nothing now. Adding it later is a migration against a table with production rows |
| `whatsapp_opt_in` capture on contacts (`B-103`) | **Consent cannot be backfilled.** Every SPOC boarded without it has to be re-approached before a single message can be sent, and business-initiated WhatsApp requires recorded opt-in. This is the one item that is genuinely irreversible |
| A channel-agnostic adapter interface in `B-110` | Turns "add WhatsApp" into one implementation class rather than a redesign of the dispatcher |

**What re-enabling costs, later:** the provider decision, an account with a verified business, template approval (days to weeks, unchanged), and the adapter's three days. Roughly a week of work behind an unknown amount of waiting — which is exactly why the opt-in column stays.

**The trade this makes.** The module plan reached for WhatsApp specifically because breach and escalation alerts have to *land*, and §7 marks them mandatory-delivery for that reason. Email-only means a step owner who is not in their inbox misses a breach until the L2 escalation reaches their manager four working hours later. The escalation matrix still works — that is what it is for — but the first alert is softer than the design intended. Worth knowing; not worth reopening.

---

## 7. Ownership map — what to add to TEAM-PLAN §6 and CODEOWNERS

```
backend/
  domain/…/db/migration/V2026*__ob_*.sql   → A    (same schema arbiter rule)
  api/security/module/                     → A    ModuleGuard, module claim
  api/feature/onboarding/clients/          → B
  api/feature/onboarding/journeys/         → C    templates, steps, instantiation, lifecycle
  api/feature/onboarding/signoff/          → B    (public surface reviewed by A)
  api/feature/onboarding/notifications/    → B    outbox, mail, notification centre
  api/feature/onboarding/reports/          → B
  worker/onboarding/                       → C    TAT scanner, RAG, escalation
  worker/onboarding/outbox/                → B    dispatch  ⚠️ two owners under one root
  worker/onboarding/stats/                 → B    summary refresh  ⚠️ see below
frontend/src/
  features/onboarding/launcher/            → A
  features/onboarding/clients/             → B
  features/onboarding/journey/             → C    including the onboarding ribbon
  features/onboarding/notifications/       → B
  features/onboarding/reports/             → B
```

> ⚠️ **`worker/onboarding/stats/` is the phase-1 `worker/stats/` problem arriving again, and it is worth pre-empting rather than repeating.**
>
> In phase 1 the summary-refresh job lived in `worker/`, which the map assigned to Stream D, while the dashboards it fed were Stream A's. A-051, A-056 and A-057 then edited another stream's directory three times before anyone wrote it down, and TEAM-PLAN §6 still carries the note about it.
>
> The identical shape recurs here, and now twice over. `worker/onboarding/` holds three jobs with **two owners**: C's TAT scanner and B's outbox dispatcher and summary refresh. `ob_dashboard_summary` feeds B's dashboard, and every new tile B adds needs a new aggregate.
>
> **Name both carve-outs in CODEOWNERS on day one** and it costs nothing. Discover them in week 8 and it costs the same three unannounced edits to somebody else's directory that phase 1 paid.

**Prove every CODEOWNERS pattern resolves before merging it** — `git ls-files | grep -E '<pattern>'`. Seventeen of twenty backend rules matched nothing until A-040, and a rule that matches nothing looks exactly like one that works.

---

## 8. Contract-first, and the one lesson phase 1 paid for

Phase 1 shipped three separate bugs with a single root cause: **the MSW mock followed the contract, the server did not, and nothing compared them.**

- `GET /tickets/{ticketId}/full` — the contract says the ticket *code*, the handler took a `long` row id. 12 occurrences; two are still open in `feature/chat`.
- `ribbon` was hard-coded `null` in a response the contract declares as an object.
- `reportedBy` was emitted as a bare numeric id where the contract declares a `UserRef` object — which is what produced a blank ticket page, because the frontend called `.split(' ')` on a number.

Every one of these was invisible in the mock, invisible in unit tests, and only visible against the real server. `ContractConformanceTest` compares paths and verbs; nothing compared response *shapes* until a conformance ratchet was written after the fact.

**So `A-119` lands in week 2, before a single onboarding screen exists.** It asserts that every onboarding DTO serialises to the shape its contract schema declares, with a known-gap list that can only shrink. The mechanism already exists — `TicketWireConformanceTest` is the template, and its gap list has already gone from 12 entries to 9 by exactly this mechanism.

The contract itself (`A-118`) is written before the schema is finished, not after. Nothing else in this plan has a better return per day spent.

---

## 9. Migrations

Standard rules, with two module-specific notes:

- **Prefix every file `ob_`:** `V20260907_1030__ob_journeys.sql`. The module must stay separable by `grep ob_`.
- **`ob_step_history` and `ob_step_communications` are append-only and hash-chained** with their own chain, following the `PLAN.md` §3.5–3.7 trigger pattern. No service method exposes `update()` or `delete()`; no `PUT`, `PATCH` or `DELETE` route is registered against them. A correction is a new row referencing the one it corrects.
- **The one permitted mutation** in the module is sealing a clock event (`resumed_at` NULL → timestamp), directly analogous to sealing a stage transition. A trigger rejects everything else.
- **MySQL 8.4, not PostgreSQL.** The module plan's DDL sketch is prose, not DDL, so there is nothing to translate — but the two errors that cost real time on D-055 apply to anything written here: error 3823 (a column cannot be in a `CHECK` and be the target of an `ON DELETE SET NULL` FK) and error 1215 (an FK with a referential action cannot sit on a column a generated column reads). `ob_signoffs` has exactly the shape that triggers 3823. Probe against the live container before committing.
- **`DATETIME(6)`, never `TIMESTAMP`.** Every timestamp in this module — `due_at`, `paused_at`, `otp_verified_at`, `live_at` — is UTC in storage.

---

## 10. Definition of done — phase 2 deltas

Everything in `CLAUDE.md`, plus four:

- [ ] **Module-gated:** the route 404s for a user without the `ONBOARDING` entitlement, and there is a test that proves it
- [ ] **Scoped:** the route returns 404, not 403, for an in-module user outside their scope
- [ ] **No ticketing coupling:** no import from `feature/tickets`, `feature/transitions` or `components/ribbon`; ArchUnit enforces it (A-115)
- [ ] **Contract-conformant:** the response shape matches the contract, or the gap is on `A-119`'s list with a reason

---

## 11. Decisions still open

One of the module plan's six is now answered by the design. Five remain, plus one new.

| # | Decision | Needed by | Recommendation |
|---|---|---|---|
| 1 | ~~Module entry: launcher or direct routing~~ | — | **Closed — Option A**, per the design |
| 2 | ~~WhatsApp provider~~ | — | **Closed — deferred out of phase 2 on 25 Aug 2026** (§6.1). Reopens only when WhatsApp re-enters scope |
| 3 | **PAN encryption and key management** | Week 3 (A-113) | Application-level AES-GCM, key from the secrets vault A-075 is standing up this week. Reuse it rather than inventing a second key path |
| 4 | ~~Ownership: distribute vs a new Stream E~~ | — | **Closed — 25 Aug 2026.** Three streams: A, B and C. D is unstaffed and its work is redistributed by fit (§6.0). The backlog is loaded and scheduled |
| 5 | **Statutory e-sign** (Aadhaar eSign/DSC) vs recorded acceptance | Week 9 (A-118) | Recorded acceptance for v1, as the module plan recommends. Revisit only if a client's contract demands it |
| 6 | **The 5 undesigned reports** — phase 2 or later (§3 #8) | Week 8 | Client's call. ~4 days, no new data |
| 7 | **`worker/onboarding/stats/` ownership** (§7) | Before OB4 | B, named in CODEOWNERS from day one |

---

## 12. Risks

The module plan's §14 stands. Three are worth restating with what phase 1 now tells us about them:

| Risk | What phase 1 taught | Mitigation |
|---|---|---|
| ~~WhatsApp approval blocks OB3~~ | — | **Retired** — WhatsApp is out of scope (§6.1) |
| **Breach alerts are softer than designed** — email only, no push to a phone | New, and a direct consequence of §6.1 | The escalation matrix is the backstop: L2 reaches the manager at +4 working hours. Revisit if a breach is ever missed in UAT |
| **TAT disputes** | Nothing directly, but the append-only chain is the thing that made ticket effort defensible | Clock events from day one (C-105). Retrofitting invalidates every figure recorded before it |
| **Ribbon coupling to ticketing** | The ticket ribbon took three attempts and a contract-shape bug to get right; it is exactly the component someone will want to reuse | Separate component, ArchUnit-enforced (A-115), extraction only as a signed-off cross-stream task |
| **The scope guard arriving late** | This was phase 1's top risk and the `dev-noauth` profile is why it did not become a permanent hole | Same pattern: `dev-noauth` covers weeks 1–2; nobody writes their own filtering |
| **Contract/server drift** | Three shipped bugs, one blank page, several days | `A-119` in week 2, before any screen |
| **Per-table grants** | Two startup failures with a misleading error | A-109 runs `make grants` as part of the task, not after it |

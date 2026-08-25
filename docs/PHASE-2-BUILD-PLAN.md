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

The phase-1 critical path ends **Fri 28 Aug 2026**. Phase 2 week 1 therefore begins **Mon 31 Aug 2026**.

Two things are worth doing *before* then, because both are cheap and both are on phase 2's critical path from day one:

- **Commit the two source documents** (above).
- ~~Submit the WhatsApp business templates.~~ **WhatsApp is out of scope for phase 2 — decided 25 Aug 2026.** See §6.1.

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

Phase 2 tasks are numbered **from 101 upward in each existing stream**: `A-101`, `B-101`, `C-101`, `D-101`.

This is deliberate and it is the cheapest option available:

- **`plan.config.json` needs no change.** Its `task_id` regex is `[A-D]-\d{3}`, which already matches. An `OB-\d{3}` scheme would require changing the regex in a config read by an external tool, and every commit-subject convention, branch name and override entry along with it.
- **The block is clear.** Phase 1's highest IDs are A-077, B-068, C-072, D-070 — 23 spare in the tightest stream.
- **The phase split stays computable.** `int(id[2:]) >= 100` separates phase 2 from phase 1 in any report, which an `OB-` prefix would also give but at the cost above.

Milestone names carry the phase: `OB0 — Gate & schema`, `OB1 — Client capture`, and so on, so the Gantt groups correctly without any tooling change.

Branches, commits and migrations are unchanged in form: `feat/onboarding/journey-instantiation`, `feat(onboarding): pin template version at instantiation`, `V20260907_1030__ob_journeys.sql`.

**The commit-subject rule bites the same way it did four times in phase 1.** A task ID in a subject line marks that task done. Partial work names the task in the body.

---

## 5. Schedule

Ten weeks, 31 Aug – 6 Nov 2026. The shape is different from phase 1: OB0 gates everything, so week 1–2 is lopsided toward Stream A while the others do contract, fixtures and template work that does not need the schema.

| Weeks | Stream A — Platform | Stream B — Masters/Clients | Stream C — Journey/Ribbon | Stream D — Engines |
|---|---|---|---|---|
| **1 · 31 Aug** | OB0 schema 1–3 (`ob_*` tables) | Fixture corpus from the design's 8-step template | Ribbon component spike — tokens only, no ticket imports | **OpenAPI contract for the whole module** + mock server |
| **2 · 7 Sep** | OB0 append-only triggers, grants, `user_module_access` | Client CRUD groundwork, duplicate-PAN guard | Template + step-item domain, versioning | **Wire-conformance ratchet** (see §8), scanner skeleton |
| **3 · 14 Sep** | `modules` JWT claim, ModuleGuard, OnboardingScopeResolver | Client CRUD, duplicate-PAN guard, contacts | Template designer OB-07 | Outbox dispatcher, mail templates |
| **4 · 21 Sep** | PAN encryption + reveal-and-audit, permission matrix | Applications, payments schedule, requirements | Instantiation + step lifecycle | Notification events, delivery status |
| **5 · 28 Sep** | Module launcher shell, switcher, OB-08 | Attachments, client list OB-03 | Clock events + working-calendar `due_at` | TAT scanner, RAG, amber |
| **6 · 5 Oct** | ArchUnit isolation rules | New client wizard OB-04 | Sub-category gate, client detail OB-05 | Escalation matrix, OB-11 |
| **7 · 12 Oct** | Public-surface security review | Dashboard OB-02 | Step panel OB-06, skip, backup owner | Notification centre OB-13, OB-12 |
| **8 · 19 Oct** | — | Reports hub OB-10 (7 tabs) | Communications timeline, stitched view | Daily digest, delivery-status reconciliation |
| **9 · 26 Oct** | Sign-off token/OTP surface | Sign-off page OB-09, acceptance PDF, go-live flip, CSAT | UAT fixes | UAT fixes |
| **10 · 2 Nov** | OB5 permission matrix × 6 roles, mutation tests | OB5 UAT fixes | OB5 ribbon accessibility pass | OB5 scanner load pass |

**The one hard dependency to watch** is the same shape as phase 1's scope guard: **OnboardingScopeResolver and ModuleGuard land in week 3**. Until they do, every onboarding endpoint is unscoped. B, C and D must not write their own filtering as a workaround — that is the exact failure `CLAUDE.md` ranks as the top risk, and it has a name in this repository now.

Real dates come from the scheduler once the rows are in `docs/plan/tasks.csv`. The grid above is the intended shape, not a forecast; `plan refresh` computes against the working calendar, holidays and leave, and it will disagree with any arithmetic done here.

---

## 6. The backlog

63 tasks, 132 working days of effort. Estimates in working days.

**WhatsApp is excluded** — see §6.1 for what was removed and what is deliberately kept.

### OB0 — Gate & schema · Stream A (Shivendra) · 17 tasks, 33 days

| ID | Title | Est | Predecessors |
|---|---|---|---|
| A-101 | `ob_clients`, contacts, applications, requirements | 2 | — |
| A-102 | `ob_payments`, `ob_attachments` | 1 | A-101 |
| A-103 | `ob_journey_templates`, template steps, template step items | 2 | — |
| A-104 | `ob_journeys`, `ob_journey_steps`, `ob_journey_step_items` | 2 | A-101, A-103 |
| A-105 | `ob_step_clock_events` | 1 | A-104 |
| A-106 | `ob_step_history` + `ob_step_communications`, hash-chained, own chain | 3 | A-104 |
| A-107 | `ob_signoffs`, `ob_notification_outbox`, `ob_escalations` | 2 | A-104 |
| A-108 | `ob_dashboard_summary` | 1 | A-104 |
| A-109 | `user_module_access` + **`make grants` for all 18 new tables** | 1 | A-108 |
| A-110 | `modules` JWT claim + `CallerIdentity` extension | 2 | A-109 |
| A-111 | ModuleGuard — no entitlement → 404, before RolesGuard | 2 | A-110 |
| A-112 | `OnboardingScopeResolver` + `ScopedJourneys` | 3 | A-111 |
| A-113 | PAN encryption at rest (AES-GCM), masking, reveal-and-audit | 3 | A-101 |
| A-114 | Permission-matrix entries: 6 OB roles × every onboarding route | 2 | A-112 |
| A-115 | ArchUnit: no `ob_*` ↔ ticket coupling, append-only enforcement | 2 | A-106 |
| A-116 | Module launcher + top-bar switcher (frontend) | 2 | A-110 |
| A-117 | OB-08 — Roles & module access admin, audited | 2 | A-116 |

**A-109 is the one that will bite.** Phase 1 hit `Schema-validation: missing table [x]` twice because `edutrack_app` holds per-table grants and nobody re-ran `make grants`. Eighteen tables arrive at once here. The symptom is *not* "access denied", which is what the docs predict — it is a startup failure naming a table that plainly exists.

**A-106 needs the same courtesy review as any protected table.** It does not touch `tickets`, `ticket_history`, `ticket_effort_logs` or `ticket_stage_transitions`, so no review is *triggered* — but it adopts their trigger pattern, and the trigger DDL is where that pattern is easy to get subtly wrong.

### OB1 — Client capture · Stream B (Ayush) · 9 tasks, 16 days

| ID | Title | Est | Predecessors |
|---|---|---|---|
| B-102 | Fixture corpus: the 8-step template + 6 demo clients from the design | 2 | A-104 |
| B-103 | Client CRUD + duplicate-PAN guard + near-duplicate name warning | 3 | A-112, A-113 |
| B-104 | SPOC contacts + **`whatsapp_opt_in` captured now** (§6.1) | 1 | B-103 |
| B-105 | Applications purchased + license start/end (the renewal anchor) | 1 | B-103 |
| B-106 | Payment schedule + outstanding roll-up + GST/invoice fields | 2 | B-103 |
| B-107 | Requirements — structured rows + sanitised rich text via `api/text/` | 1 | B-103 |
| B-108 | Client attachments through the existing AV/signed-URL pipeline | 1 | B-103 |
| B-109 | OB-03 — client list, filter by status/RAG/owner/sales person | 2 | B-103 |
| B-110 | OB-04 — new client wizard, 4 steps | 3 | B-104…B-108 |

**B-107 touches `api/text/`, which is shared and needs both C's and D's sign-off.** It should need no change at all — `RichTextSanitizer` is already the allow-list — but if it does, that is the conversation, not a quiet edit.

### OB2 — Journey engine & ribbon · Stream C (Divyansh) · 12 tasks, 32 days

| ID | Title | Est | Predecessors |
|---|---|---|---|
| C-101 | Template + step + step-item domain, publish-as-new-version | 3 | A-103 |
| C-102 | OB-07 — designer: order, **parallel groups, depends_on, per-item mandatory, required documents, descriptions** | 4 | C-101 |
| C-103 | Instantiation: pin `template_id + version`, resolve owners, unassigned list | 3 | C-101, A-104 |
| C-104 | Step lifecycle: start · complete · block-with-reason · waiting · resume | 3 | C-103 |
| C-105 | **Clock events + working-calendar `due_at`**, recompute on resume | 3 | C-104, A-105 |
| C-106 | Sub-category answers + the single completion gate (§3 #4) | 2 | C-104 |
| C-107 | Skip a step — Manager/Admin, mandatory reason, history row (§3 #1) | 1 | C-104 |
| C-108 | Backup owner — assignment + leave-aware inheritance (§3 #2) | 2 | C-104 |
| C-109 | Onboarding ribbon component — **fresh, `features/onboarding/`, tokens only** | 3 | — |
| C-110 | OB-05 — client detail | 3 | C-109, B-103 |
| C-111 | OB-06 — step update panel | 3 | C-106, C-110 |
| C-112 | Communications timeline: per-step + **client-level stitched view** (§3 #3) | 2 | C-111 |

**C-109 is the item most likely to go wrong, and it will not look like it at the time.** The module plan is explicit: the ribbon is a *visual language*, not a shared component. Importing `components/ribbon/` couples two release cycles on day one and is very hard to undo once four screens depend on it. Extraction of shared primitives is a later, signed-off, cross-stream task or it does not happen.

### OB3 — TAT & notifications · Stream D (Debashis) · 10 tasks, 21 days

| ID | Title | Est | Predecessors |
|---|---|---|---|
| D-101 | **OpenAPI contract for the whole module** + MSW mock server | 3 | — |
| D-102 | **Wire-conformance ratchet** for onboarding DTOs (§8) | 2 | D-101 |
| D-103 | TAT scanner worker job — `worker/onboarding/` | 3 | C-105 |
| D-104 | RAG computation, amber threshold, client roll-up | 2 | D-103 |
| D-105 | Escalation matrix L1/L2/L3 + `ob_escalations` + acknowledgement | 3 | D-104 |
| D-106 | Outbox dispatcher with retry, **behind a channel-agnostic adapter** (§6.1) | 2 | A-107 |
| D-107 | Email templates through the existing mail engine | 1 | D-106 |
| D-109 | OB-13 — notification centre (page) + bell popover | 2 | D-106 |
| D-110 | OB-11 + OB-12 — TAT/escalation settings, email templates | 2 | D-105 |
| D-111 | Daily digest to Managers — journeys stuck > x days | 1 | D-109 |

**D-101 and D-102 come first, before any screen exists.** That ordering is the single most important structural change from phase 1 — see §8.

**Every notification in phase 2 goes out by email.** The adapter interface in D-106 is what keeps that from being a one-way door — see §6.1.

### OB4 — Sign-off, dashboard, reports · 10 tasks, 21 days

| ID | Title | Est | Owner | Predecessors |
|---|---|---|---|---|
| A-118 | Public sign-off surface: hashed single-use token, TTL, rate limits, no enumeration | 3 | A | A-107 |
| A-119 | OTP issue + verify against the SPOC's registered email/phone | 2 | A | A-118 |
| B-111 | OB-09 — public sign-off page, shell-less | 2 | B | A-119 |
| B-112 | Acceptance PDF + archive as attachment | 2 | B | B-111 |
| B-113 | Objection path — step returns to `IN_PROGRESS`, clock resumes, owner notified | 1 | B | B-111 |
| B-114 | Go-live flip to LIVE/Green + `live_at` + support handover note | 1 | B | B-112 |
| B-115 | **CSAT — public one-question page, storage, summary** (§3 #9) | 2 | B | B-114 |
| B-116 | `ob_dashboard_summary` refresh job — see the ownership note in §7 | 2 | B | A-108 |
| B-117 | OB-02 — dashboard: RAG board, funnel, stuck list, breach list, TAT tiles | 3 | B | B-116 |
| B-118 | OB-10 — reports hub, the 7 designed tabs + XLSX/CSV export | 3 | B | B-116 |

**OB4b — the 5 reports in §10 that the design does not draw** (breach log, escalation log, owner workload, communication audit per client, CSAT summary): ~4 days, all straightforward reads over data that exists by then. Held pending the §11.6 call.

### OB5 — Hardening · all streams · 5 tasks, 9 days

| ID | Title | Est | Owner |
|---|---|---|---|
| A-120 | Permission-matrix completeness: all 6 OB roles × every route | 2 | A |
| A-121 | Mutation tests against `ob_step_history` / `ob_step_communications` | 2 | A |
| C-113 | Ribbon accessibility pass — keyboard, ARIA, focus order | 2 | C |
| D-112 | Scanner load pass — 500 active journeys, 4,000 open steps | 2 | D |
| B-119 | Export redaction for non-Finance roles (PAN, amounts) | 1 | B |

### Load per developer

| Stream | Days of work | Capacity over 10 weeks | Slack |
|---|---:|---:|---:|
| A — Shivendra | 42 | 50 | 8 |
| B — Ayush | 33 | 50 | 17 |
| C — Divyansh | 34 | 50 | 16 |
| D — Debashis | 23 | 50 | 27 |

The slack is not spare time — it is UAT, integration, review and the rework every one of those produces, none of which is a task row. But the distribution is uneven enough to say plainly: **Stream A carries OB0 nearly alone in weeks 1–2 and is the tightest all phase**, and Stream D finishes its critical work by week 8 — more so now that WhatsApp is out. If OB4b ships, D is the stream with room for it.

---

### 6.1 WhatsApp — deferred, 25 Aug 2026

**Phase 2 notifies by email only.** WhatsApp is out of scope: no provider account, no template submission, no adapter implementation, no webhook.

This is a deviation from the module plan, whose §7 specifies email **and** WhatsApp, and it is recorded here rather than made quietly. Two tasks come out — B-101 (template submission pack, 1 day) and D-108 (provider adapter, signed webhook, delivery status, 3 days) — and OB-12 narrows to email templates only.

**Three things stay in, because leaving them out is what would make this expensive to reverse:**

| Kept | Why |
|---|---|
| `ob_notification_outbox.channel ENUM('EMAIL','WHATSAPP')` | The enum value costs nothing now. Adding it later is a migration against a table with production rows |
| `whatsapp_opt_in` capture on contacts (B-104) | **Consent cannot be backfilled.** Every SPOC boarded without it has to be re-approached before a single message can be sent, and business-initiated WhatsApp requires recorded opt-in. This is the one item that is genuinely irreversible |
| A channel-agnostic adapter interface in D-106 | Turns "add WhatsApp" into one implementation class rather than a redesign of the dispatcher |

**What re-enabling costs, later:** the provider decision, an account with a verified business, template approval (days to weeks, unchanged), and D-108's three days. Roughly a week of work behind an unknown amount of waiting — which is exactly why the opt-in column stays.

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
  api/feature/onboarding/notifications/    → D
  api/feature/onboarding/reports/          → B
  worker/onboarding/                       → D    TAT scanner, escalation, outbox dispatch
  worker/onboarding/stats/                 → B    ⚠️ see below
frontend/src/
  features/onboarding/launcher/            → A
  features/onboarding/clients/             → B
  features/onboarding/journey/             → C    including the onboarding ribbon
  features/onboarding/notifications/       → D
  features/onboarding/reports/             → B
```

> ⚠️ **`worker/onboarding/stats/` is the phase-1 `worker/stats/` problem arriving again, and it is worth pre-empting rather than repeating.**
>
> In phase 1 the summary-refresh job lived in `worker/`, which the map assigned to Stream D, while the dashboards it fed were Stream A's. A-051, A-056 and A-057 then edited another stream's directory three times before anyone wrote it down, and TEAM-PLAN §6 still carries the note about it.
>
> The identical shape recurs here: `ob_dashboard_summary` feeds B's dashboard (OB4), and every new tile B adds needs a new aggregate. **Name the carve-out in CODEOWNERS on day one** and it costs nothing. Discover it in week 8 and it costs the same three unannounced edits.

**Prove every CODEOWNERS pattern resolves before merging it** — `git ls-files | grep -E '<pattern>'`. Seventeen of twenty backend rules matched nothing until A-040, and a rule that matches nothing looks exactly like one that works.

---

## 8. Contract-first, and the one lesson phase 1 paid for

Phase 1 shipped three separate bugs with a single root cause: **the MSW mock followed the contract, the server did not, and nothing compared them.**

- `GET /tickets/{ticketId}/full` — the contract says the ticket *code*, the handler took a `long` row id. 12 occurrences; two are still open in `feature/chat`.
- `ribbon` was hard-coded `null` in a response the contract declares as an object.
- `reportedBy` was emitted as a bare numeric id where the contract declares a `UserRef` object — which is what produced a blank ticket page, because the frontend called `.split(' ')` on a number.

Every one of these was invisible in the mock, invisible in unit tests, and only visible against the real server. `ContractConformanceTest` compares paths and verbs; nothing compared response *shapes* until a conformance ratchet was written after the fact.

**So D-102 lands in week 2, before a single onboarding screen exists.** It asserts that every onboarding DTO serialises to the shape its contract schema declares, with a known-gap list that can only shrink. The mechanism already exists — `TicketWireConformanceTest` is the template, and its gap list has already gone from 12 entries to 9 by exactly this mechanism.

The contract itself (D-101) is written before the schema is finished, not after. Nothing else in this plan has a better return per day spent.

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
- [ ] **Contract-conformant:** the response shape matches the contract, or the gap is on D-102's list with a reason

---

## 11. Decisions still open

One of the module plan's six is now answered by the design. Five remain, plus one new.

| # | Decision | Needed by | Recommendation |
|---|---|---|---|
| 1 | ~~Module entry: launcher or direct routing~~ | — | **Closed — Option A**, per the design |
| 2 | ~~WhatsApp provider~~ | — | **Closed — deferred out of phase 2 on 25 Aug 2026** (§6.1). Reopens only when WhatsApp re-enters scope |
| 3 | **PAN encryption and key management** | Week 3 (A-113) | Application-level AES-GCM, key from the secrets vault A-075 is standing up this week. Reuse it rather than inventing a second key path |
| 4 | **Ownership:** distribute across A–D vs a new Stream E | Before the backlog loads | **Distribute.** §6 assigns every task to the stream that already owns the analogous phase-1 surface; a fifth developer would need the platform knowledge that the other four spent 18 weeks acquiring |
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
| **Contract/server drift** | Three shipped bugs, one blank page, several days | D-102 in week 2, before any screen |
| **Per-table grants** | Two startup failures with a misleading error | A-109 runs `make grants` as part of the task, not after it |

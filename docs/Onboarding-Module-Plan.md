# EduTrack — Client Onboarding Module Plan

**Status: v1.0 draft — for review, not yet committed to the build plan.**

A second module on the EduTrack platform: **Client Onboarding**. Same deployment, same database, same auth kernel — **zero domain coupling to ticketing**. This document is the onboarding analogue of `PLAN.md`: it is the authority on what the module is and how it is built. Where it is silent, the conventions in `CLAUDE.md` and `PLAN.md` apply unchanged (feature packaging, timestamped migrations, UTC storage, working-calendar maths, no live `COUNT(*)`, append-only history).

---

## 1. What we are building

When a new client is signed, they are boarded through a defined, step-by-step **onboarding journey**. Every step has a responsible person and a TAT. Management sees, at a glance, where every client is, where the flow is stuck, and which steps have breached. When every mandatory step is complete **and the client has formally signed off**, the client turns **Green — Live**.

Core capabilities, from the requirement:

- Full client capture: name, SPOC, onboarding date, applications purchased, email, phone, PAN, address, description, sales person, license type, payment mode, advance/balance/other payment details, requirements, multiple attachments.
- Admin-defined journey templates: steps, order, TAT, and responsible person per step.
- **Sub-categories under every step**: admin defines any number of named checklist items under each step (category). On the client page, clicking a step in the ribbon shows its sub-categories, each answered **True / False with a remark**; a step cannot complete while mandatory items are unanswered, and a False answer requires a remark.
- A **ribbon-style journey view** — the same visual language as the ticketing Workflow Ribbon, a separate component.
- Per-step communication capture (calls, emails, WhatsApp, meetings, notes).
- TAT breach → immediate highlight + notification (email **and WhatsApp**), with escalation.
- Separate roles: everyone can *see* the journey; each user can *update only their own step*.
- Client sign-off (per-step where configured, and a final go-live sign-off).
- Management dashboard + multiple reports.
- Module-gated access: only authorised users can enter the Onboarding module at all.

### 1.1 Architect's additions — things the requirement implies but does not say

These are recommended into scope; each is cheap now and expensive to retrofit:

| # | Addition | Why |
|---|---|---|
| 1 | **"Waiting on client" clock state** | The single most common cause of false breaches. When a step is blocked on the client (documents pending, sign-off pending), the TAT clock **pauses** and the wait is attributed to the client, not the owner. Without this, every TAT report is disputed within a month. |
| 2 | **Template snapshot on instantiation** | Admin edits to a journey template must never mutate in-flight clients. Each journey pins the template version it was created from. |
| 3 | **Amber before Red** | Notify at a configurable % of TAT elapsed (default 75%), not only at breach. A warning you can act on beats an alert you can only apologise for. |
| 4 | **Backup owner per step** | People take leave mid-journey. Every step assignment carries an optional backup who inherits update rights when the owner is on leave (working-calendar aware). |
| 5 | **Blocked-with-reason** | Marking a step blocked requires a reason category + note. This is what makes "where is it stuck" answerable, not just "which step is late". |
| 6 | **Duplicate-client guard** | PAN is unique; creation checks it and warns on near-duplicate names. Two half-onboarded copies of the same client is a mess no report survives. |
| 7 | **Document checklist per step** | A step can declare required documents; it cannot complete until they are attached. Turns tribal knowledge into configuration. |
| 8 | **Payment schedule, not just two fields** | Advance/balance as rows in a payment schedule with due dates, received dates, mode, reference, and an outstanding roll-up — plus GST/invoice reference fields. Finance will ask for this in week one. |
| 9 | **Welcome/kickoff automation** | On journey start, an automated welcome email to the SPOC with the named onboarding contact. First impressions are part of onboarding. |
| 10 | **Go-live CSAT** | One-question satisfaction survey sent with the final sign-off confirmation. Management asked to *see* the journey; this tells them how it felt. |
| 11 | **Append-only step history, hash-chained** | Same immutability pattern as `ticket_history`, its own chain. Sign-offs and TAT evidence are exactly the records someone will one day want to have quietly edited. |
| 12 | **Renewal anchor** | Capture license start/end dates now. The day someone asks for a renewals module, the data already exists. |

### 1.2 Deliberate non-goals (v1)

- **No link to ticketing.** No foreign key between `ob_*` and any ticket table; no shared service; the onboarding client list is **not** the ticketing client master. A one-way "export to ticketing client master on go-live" is a phase-2 decision, off by default.
- **No persistent client portal login.** Client sign-off is via secure link + OTP (§8). A full client login is phase 2.
- **No per-client custom journeys drawn freehand.** Journeys always come from a template; admins may add/skip steps on an instance with a logged reason.

---

## 2. Module boundary — how two modules share one platform

**Shared (platform):** user identity and login, Argon2id/JWT/refresh machinery, rate limiting, the working calendar and holiday master, file storage + AV pipeline, mail transport, design tokens, CI.

**Not shared (domain):** every table (all onboarding tables are `ob_`-prefixed), every feature package, every route, every permission string, every report. The two modules must be separable by `grep ob_` and `grep onboarding` alone.

```
backend/
  domain/ …/db/migration/V20260xxx__ob_*.sql     # own migrations, own tables
  api/    src/…/feature/onboarding/…             # clients, journeys, steps, signoff, reports
  worker/ src/…/onboarding/…                     # TAT scanner, escalations, outbox dispatch
frontend/
  src/features/onboarding/…                      # routes under /onboarding/*
  src/components/…                               # shared design system only — NOT the ticket Ribbon
```

**The ribbon is a visual language, not a shared component.** The onboarding ribbon is built fresh in `features/onboarding/`, reusing design tokens and layout idiom. If, later, both owners agree to extract shared primitives into `components/`, that is a deliberate cross-stream task with sign-off — not an import from day one that couples release cycles.

### 2.1 Module access — the gate before everything

A user is entitled to zero, one, or both modules, independently of their role inside each:

- `user_module_access (user_id, module ENUM('TICKETING','ONBOARDING'), module_role, granted_by, granted_at)`
- JWT gains a claim: `modules: ["TICKETING","ONBOARDING"]` plus module-scoped permissions.
- A **ModuleGuard** runs before RolesGuard on every `/api/onboarding/**` route. No entitlement → **404** (same no-existence-leak rule as ticket scoping).
- Admin screen to grant/revoke module access, fully audited.

### 2.2 Getting to the module — two options

**Option A — Module Launcher (recommended).** After login, a user entitled to both modules lands on a launcher: two cards, *Ticketing* and *Client Onboarding*, each with a one-line live summary (open tickets / clients in flight). Single-module users skip the launcher and land directly in their module. A compact module switcher sits in the top bar for dual-access users. *Why recommended:* one login, one session, zero duplicated shell, and the switcher is discoverable.

**Option B — Direct routing by default module.** Every user has a default module and lands straight in it; `/onboarding` and `/tickets` are separate URL trees under the same login, and switching is by URL or a switcher shown only to dual-access users. *Why you might prefer it:* most users will only ever hold one module, and the launcher becomes a screen they see once. Marginally faster to daily work; marginally worse discoverability.

Both options share the same backend gate (§2.1); the choice is purely a frontend/UX decision and can even be changed later. **Decision to lock before OB1.**

---

## 3. Roles and responsibility

Onboarding roles are their own set — a person's ticketing role (or absence of one) says nothing about their onboarding role.

| Role | Sees | Can do |
|---|---|---|
| **OB Admin** | Everything | Define/version journey templates, steps, TATs; assign responsibilities; grant module access; manage WhatsApp/email templates; edit escalation matrix |
| **Onboarding Manager** | Every journey | Reassign steps, force-escalate, override/skip a step with logged reason, all dashboards and reports |
| **Sales** | Clients they created | Create client, capture sale-time details and payments, view journey progress read-only |
| **Step Owner** (executor) | Full journey of any client that has a step assigned to them — read-only | Update **only their own steps**: start, log communications, attach documents, complete, mark blocked |
| **Finance** | Payment data across clients | Record/verify payments; the only role (besides Admin/Manager) that sees unmasked payment details |
| **Viewer** (management) | Everything, read-only | Dashboards and reports only |

Enforcement is server-side, mirroring the ticketing pattern: an **OnboardingScopeResolver** rewrites every query (`Step Owner → journeys containing steps assigned to me`, `Sales → created_by = me`, `Manager/Admin/Viewer → all`), and out-of-scope IDs return **404**. Field-level rule on top: PAN and payment amounts are masked for every role except Finance, Manager, and OB Admin, and every unmasked read of PAN is audit-logged.

---

## 4. Data model (~18 tables, all `ob_`-prefixed)

Client and capture:

- `ob_clients` — name, description, onboarding_date, PAN (encrypted at rest, unique), address fields, sales_person_id, license_type, overall_status (`ONBOARDING · LIVE · ON_HOLD · DROPPED`), rag (`GREEN · AMBER · RED`), live_at, created_by.
- `ob_client_contacts` — SPOCs: name, designation, email, phone, whatsapp_opt_in, is_primary. Multiple per client.
- `ob_client_applications` — which applications were bought: application, license_type, units, license_start, license_end.
- `ob_client_requirements` — structured requirement rows + rich text (sanitised per PLAN §3.9).
- `ob_payments` — schedule rows: kind (`ADVANCE · BALANCE · OTHER`), amount, mode, due_date, received_date, reference, invoice_no, gst_details, notes.
- `ob_attachments` — polymorphic within the module (client-level or step-level), same upload pipeline (allow-list, MIME sniff, AV, signed URLs).

Journey definition and execution:

- `ob_journey_templates` / `ob_journey_template_steps` — versioned. Step: sequence, name, description, TAT (working hours), default responsible (role or named user), parallel-group, depends_on, is_mandatory, requires_client_signoff, required document types.
- `ob_journey_template_step_items` — the **sub-categories** under a template step: sequence, label, is_mandatory. Versioned with the template; an admin may add any number under any step.
- `ob_journeys` — one per client, pinned to `template_id + template_version`.
- `ob_journey_steps` — instantiated steps: status (`PENDING · IN_PROGRESS · WAITING_ON_CLIENT · BLOCKED · COMPLETED · SKIPPED`), owner_id, backup_owner_id, planned_start, due_at (computed calendar-aware at activation), started_at, completed_at, breach flags, block_reason.
- `ob_journey_step_items` — instantiated sub-categories per step: value (`TRUE · FALSE · unanswered`), remark, answered_by, answered_at. Only the step owner (or Manager/Admin) may answer; every change writes to `ob_step_history`.
- `ob_step_clock_events` — pause/resume rows for the waiting-on-client clock; the TAT engine sums working time between events.
- `ob_step_communications` — **append-only**: type (`NOTE · CALL · EMAIL · WHATSAPP · MEETING`), direction, contact, body, occurred_at, logged_by, attachments. System-sent email/WhatsApp auto-log here with delivery status.
- `ob_step_history` — **append-only, hash-chained**, same trigger pattern as PLAN §3.5–3.7 with its own chain. Every state change, reassignment, TAT recalculation, override.

Sign-off, notification, escalation:

- `ob_signoffs` — step-level and final: token (hashed), sent_to contact, channel, otp_verified_at, outcome (`ACCEPTED · OBJECTION`), signed_name, ip, user_agent, acceptance PDF attachment id.
- `ob_notification_outbox` — outbox pattern: channel (`EMAIL · WHATSAPP`), template, payload, status (`QUEUED · SENT · DELIVERED · READ · FAILED`), provider_message_id, retries.
- `ob_escalations` — breach escalations raised: step, level, notified whom, when, acknowledged_at.
- `ob_dashboard_summary` — pre-aggregated roll-up refreshed by the worker; dashboards **never** run live `COUNT(*)`.

All migrations timestamp-versioned; none touch protected ticketing tables, so no Stream A review is triggered — but the two new append-only tables adopt Stream A's trigger pattern and should get a courtesy review of the trigger DDL.

---

## 5. The journey engine

1. **Templates.** Admin defines named templates (e.g. per application or license type), each a versioned ordered list of steps with TATs and default owners. Editing publishes a new version; in-flight journeys keep theirs.
2. **Instantiation.** Creating a client (or explicitly starting onboarding) instantiates the chosen template. Default owners resolve to named users; unresolved steps land on the Manager's "unassigned" list and the journey cannot pass them until assigned.
3. **Activation and TAT.** A step activates when its dependencies complete (parallel groups activate together). `due_at` = activation time + TAT in **working hours** against the org calendar — weekends, holidays, and the owner's leave excluded, exactly as SLA maths in ticketing (convention, not code, is shared).
4. **Clock states.** `WAITING_ON_CLIENT` pauses the clock; resume recomputes `due_at`. `BLOCKED` (internal) does **not** pause the clock by default — an internal blockage is still our time — but the Manager may pause with a logged reason.
5. **Sub-category gate.** A step completes only when every mandatory sub-category is answered: True, or False accompanied by a remark. Answers are edited inline on the client page by the step owner; a False that survives to step completion is visible in reports as a delivered-with-exception flag.
6. **RAG.** Step: Green on-track → Amber at ≥75% TAT elapsed (configurable) → Red on breach or blocked-past-threshold. Client roll-up: Red if any step Red, else Amber if any Amber, else Green. **Live-Green** is distinct and only reachable via §8.
7. **The scanner.** A worker job (same infrastructure pattern as the SLA scanner, separate schedule and package) sweeps active steps every few minutes: flips Amber/Red, writes history, enqueues notifications, and raises escalations — L1 owner at breach, L2 manager after *n* working hours unacknowledged, L3 OB Admin after *2n*. Matrix configurable.

---

## 6. Communication capture

Every step carries a communications timeline. Manual entries (call, meeting, note) are logged by the step owner with contact and outcome; system-sent emails and WhatsApp messages log themselves with provider delivery status. Entries are append-only — a wrong entry is corrected by a new entry referencing it, never edited. The client-level view stitches all steps' timelines into one chronological "everything we've said to this client" view, which is what management actually asks for.

---

## 7. Notifications — email + WhatsApp

- **Transport.** Email rides the existing mail engine. WhatsApp goes through a provider adapter behind an interface — **Meta WhatsApp Cloud API direct, or a BSP (Gupshup/Twilio)** — decision to lock (§12). All sends flow through `ob_notification_outbox` with retry and provider webhooks updating delivery status.
- **Compliance.** WhatsApp business-initiated messages require pre-approved templates and recorded opt-in per contact (`whatsapp_opt_in` on the contact, with timestamp and source). Template approval takes days-to-weeks: **submit templates in OB1, not OB3.**
- **Events.** Step assigned/reassigned · step activated · Amber warning · **TAT breach (immediate)** · escalation L2/L3 · step completed · sign-off requested (to client) · sign-off received · client Live · daily digest to Managers (journeys stuck > x days).
- **Preferences.** Per-user channel preferences; breach and escalation events are mandatory-delivery and cannot be muted.

---

## 8. Client sign-off

- Steps flagged `requires_client_signoff`, plus one mandatory **final go-live sign-off**, generate a secure external link sent to the SPOC (email and/or WhatsApp): single-use token, short TTL, OTP verification against the SPOC's registered email/phone, rate-limited, no enumeration.
- The client sees a read-only summary of what they are accepting (step deliverable, or the full journey for go-live) and either **Accepts** or **Raises an objection** with comments.
- Acceptance records name, timestamp, IP, user agent and OTP channel, renders an acceptance PDF stored as an attachment, and completes the step. An objection reverts the step to `IN_PROGRESS` with the objection logged — clock resumes on our side.
- Final sign-off accepted + all mandatory steps complete → client flips to **LIVE, Green**, live_at stamped, welcome-to-support handover note generated, CSAT question sent.
- v1 is deliberately link + OTP (recorded acceptance), not a legal e-sign. If a statutory signature is ever required, that is the §12 e-sign decision.

---

## 9. Screens (~13)

| # | Screen | Notes |
|---|---|---|
| OB-01 | Module launcher / switcher | §2.2, whichever option is locked |
| OB-02 | Onboarding dashboard | RAG board, funnel (clients per step), stuck list, breach list, TAT compliance tiles — reads `ob_dashboard_summary` |
| OB-03 | Client list | Filterable by status/RAG/owner/sales person |
| OB-04 | New client wizard | Capture §4 client data; template selection; duplicate-PAN guard |
| OB-05 | Client detail | **The ribbon** across the top; step panel; communications timeline; payments; requirements; attachments; sign-off status |
| OB-06 | Step update panel | Owner's working surface: answer the step's **sub-categories (True/False + remark)**, start, log communication, attach, complete, block, request sign-off — sub-categories appear on click of the step in the ribbon |
| OB-07 | Journey template designer | Admin: steps, order, parallel groups, TATs, default owners, **sub-categories under each step**, document checklist, sign-off flags; versioning |
| OB-08 | Responsibility & module access admin | Grant module access, map roles, assign default owners |
| OB-09 | Sign-off page (public) | External, tokened + OTP; no shell, no navigation |
| OB-10 | Reports hub | §10 |
| OB-11 | Escalation & TAT settings | Amber threshold, escalation matrix, scanner cadence |
| OB-12 | Notification templates | Email + WhatsApp template management and approval status |
| OB-13 | Onboarding notification centre | Module-scoped in-app notifications |

The ribbon renders every step with owner avatar, state colour, TAT bar, and pause/breach markers; clicking a step opens OB-06 (own step) or a read-only view (anyone else's). Fully keyboard-navigable with ARIA labels, per the accessibility rule. Design tokens from blueprint §12.1 only; RAG semantics map onto existing success/warning/danger tokens — no new colours.

---

## 10. Reports

Onboarding funnel · TAT compliance by step and by owner · stuck/aging (with block reasons and waiting-on-client attribution) · time-to-live trend · breach log · escalation log · sales-person pipeline · payment outstanding (advance vs balance vs received) · sign-off pending · owner workload · communication audit per client · CSAT summary. All exportable (XLSX/CSV) via the existing export infrastructure; all reads from summary tables or bounded queries.

---

## 11. Security notes specific to this module

- PAN encrypted at rest (application-level AES-GCM; key from the secrets vault, never in code), masked in UI and logs, unmasked reads audited.
- Public sign-off endpoints are the module's only unauthenticated surface: hashed single-use tokens, OTP, aggressive rate limits, generic errors, no ID enumeration.
- ModuleGuard → RolesGuard → OnboardingScopeResolver on every route; 404 for out-of-scope, per platform rule.
- Attachments through the existing AV/signed-URL pipeline; WhatsApp webhook endpoint verified by provider signature.

---

## 12. Decisions to lock before build

1. **Module entry: Option A (launcher) or B (direct routing)** — recommendation: A.
2. **WhatsApp provider:** Meta Cloud API direct vs BSP (Gupshup/Twilio) — cost vs onboarding speed vs template-approval tooling.
3. **PAN encryption & key management** approach.
4. **Ownership:** distribute across Streams A–D along existing expertise (recommended, see §13) vs a new Stream E.
5. **Whether go-live sign-off needs statutory e-sign** (Aadhaar eSign/DSC) or recorded acceptance suffices — recommendation: recorded acceptance for v1.
6. **Phase-2 stance** on one-way export of a Live client into the ticketing client master.

---

## 13. Milestones and suggested ownership

Sequenced to start once the current M-series critical path allows; each milestone is independently shippable behind the module gate.

| Milestone | Contents | Natural owner |
|---|---|---|
| **OB0 — Gate & schema** | `ob_*` migrations, module entitlement + ModuleGuard, JWT claim, OnboardingScopeResolver, append-only triggers, launcher/switcher shell | Stream A |
| **OB1 — Client capture** | Client wizard, contacts, applications, payments, requirements, attachments, duplicate guard, client list; **submit WhatsApp templates now** | Stream B |
| **OB2 — Journey engine & ribbon** | Templates + designer, instantiation, step lifecycle, clock states, step update panel, client detail with ribbon, communications timeline | Stream C |
| **OB3 — TAT, notifications, WhatsApp** | Scanner, RAG, escalation matrix, outbox + provider adapter + webhooks, notification centre, digests | Stream D |
| **OB4 — Sign-off, dashboard, reports** | Public sign-off flow + PDF, Live-Green flip, CSAT, `ob_dashboard_summary`, dashboard, reports hub | Stream B (reports) + Stream A (public surface review) |
| **OB5 — Hardening** | Permission-matrix tests for all onboarding roles on every route, mutation-check on append-only guarantees, load pass on scanner, accessibility pass on ribbon | All |

Definition of done, git workflow, batch integration, and verification rules are unchanged from `CLAUDE.md` — onboarding PRs join the same batches.

---

## 14. Risks

| Risk | Mitigation |
|---|---|
| WhatsApp template approval lead time blocks OB3 | Templates submitted during OB1; email-only degradation path built in |
| TAT disputes ("that wasn't our time") | Waiting-on-client clock state + per-step clock events from day one |
| Ribbon component coupling to ticketing | Separate component; extraction only as a signed-off cross-stream task |
| Template edits corrupting in-flight journeys | Version pinning + snapshot at instantiation |
| Public sign-off endpoint abuse | Token + OTP + rate limits + short TTL; security review before OB4 ships |
| PII (PAN) leakage via logs/exports | Encryption at rest, masking by default, audited unmasked reads, export redaction for non-Finance roles |
| Scope creep toward a client portal | Explicit v1 non-goal; link+OTP only |

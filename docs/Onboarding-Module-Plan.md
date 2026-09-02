# EduTrack — Client Onboarding Module Plan

**Status: v1.1 — revised architecture. Changes from v1.0: per-product journeys (a client has one journey per product bought), a prerequisites layer gating journey start, a client portal login spanning both modules, and — by explicit decision — no financial tracking anywhere in this module.**

A second module on the EduTrack platform: **Client Onboarding**. Same deployment, same database, same auth kernel — **zero domain coupling to ticketing** (one sanctioned identity-layer bridge, §2.3). This document is the onboarding analogue of `PLAN.md`: the authority on what the module is and how it is built. Where it is silent, the conventions in `CLAUDE.md` and `PLAN.md` apply unchanged (feature packaging, timestamped migrations, UTC storage, working-calendar maths, no live `COUNT(*)`, append-only history).

The visual reference is `docs/prototype/onboarding.html` — every screen and interaction in this plan is clickable there.

---

## 1. What we are building

When a client is signed they are boarded through **one journey per product they bought** — ERP has its own journey, Biometric Attendance its own, each instantiated from that product's admin-defined template with its own steps, sub-categories, owners and TATs. Before any journey starts, the client must clear a set of **prerequisites** — their own responsibilities. Management sees every client, every journey, and where things are stuck; the client sees their own progress through a **portal login**. When every journey completes (final sign-offs included), the client turns **Green — Live**.

Core capabilities:

- Client capture: name, SPOC(s), onboarding date, products purchased, email, phone, PAN, address, description, sales person, license type, requirements, multiple attachments. **No payment or financial fields — commercials live in the sales/billing system, never here.**
- **Products master + per-product journey templates**: Admin creates a journey template *for a product* (steps, order, TAT, default owner, sub-categories, sign-off flags); creating a new journey = creating a new template for a product. One active template version per product; instances pin their version.
- **Multiple journeys per client**: each purchased product instantiates its journey. Client page shows journeys as **accordions** — each strip carries product name, % complete, and every step as a **RAG-patterned dot**; expanding shows the full ribbon and step panel.
- **Prerequisites**: an Admin-maintained master of client-responsibility tasks (description, date, TAT, comments, multiple attachments, status, **mandatory flag**, admin-attached reference documents), instantiated per client and shown **above the journey accordions**. **Hard gate: every mandatory task must be verified before any journey starts**; non-mandatory tasks can be skipped by an Admin (reason logged) and the journeys start.
- Per-step sub-categories (True/False + remark) gating step completion; per-step communication capture; TAT breach → highlight + immediate notification (email + WhatsApp) with escalation; client sign-off per flagged step and at go-live.
- **Client portal login** — one per client, created **as an explicit option** (wizard checkbox at boarding, or the "Create client login" panel on the client page). The client chooses Ticketing (view-only, own tickets) or Onboarding (own journeys read-only, prerequisites interactive, sign-offs) after login.
- Management dashboard + reports; separate module roles; module-gated access.

### 1.1 Architect's additions (carried from v1.0, minus financials)

| # | Addition | Why |
|---|---|---|
| 1 | **"Waiting on client" clock state** | TAT pauses when a step waits on the client; the wait is attributed to the client. Without it every TAT report is disputed within a month. |
| 2 | **Template snapshot on instantiation** | Admin edits never mutate in-flight journeys; instances pin template version. Applies to journey templates *and* the prerequisites master. |
| 3 | **Amber before Red** | Warn at a configurable % of TAT (default 75%) before breach. |
| 4 | **Backup owner per step** | Leave coverage; working-calendar aware. |
| 5 | **Blocked-with-reason** | Mandatory reason category + note; powers "where is it stuck". |
| 6 | **Duplicate-client guard** | PAN unique; near-duplicate name warning. |
| 7 | **Document checklist per step** | A step can't complete with required documents missing. |
| 8 | **Welcome/kickoff automation** | Fires at **gate-open** (not client creation): journeys start, SPOC gets the kickoff mail. |
| 9 | **Go-live CSAT** | One-question survey with the final sign-off confirmation. |
| 10 | **Append-only, hash-chained histories** | Step history and prerequisite history on the platform's immutability pattern. |
| 11 | **Renewal anchor** | License start/end captured now; renewals module later finds its data waiting. |

### 1.2 Deliberate non-goals (v1.1)

- **No financial tracking.** No payment tables, no amounts, no collection reports — removed entirely by product decision. PAN is retained as *identity* (encrypted, masked, audited), not as a financial field.
- **No FK between `ob_*` and any ticket table.** The identity-layer bridge (§2.3) is the *only* connection between the modules.
- **No client-raised tickets.** The portal's ticketing side is **view-only**; raising tickets stays with the support desk (blueprint §16's fuller portal remains a later phase).
- **No client self-registration, one account per client.** Accounts exist only when staff explicitly create them.
- **No freehand per-client journeys.** Journeys always come from a product's template; admins may add/skip steps on an instance with a logged reason.

---

## 2. Module boundary

**Shared (platform):** user identity and login machinery, working calendar and holiday master, file storage + AV pipeline, mail transport, design tokens, CI.

**Not shared (domain):** every table (`ob_`-prefixed), feature package, route, permission string, report. Separable by `grep ob_` alone.

### 2.1 Module access — the staff gate

`user_module_access (user_id, module, module_role, granted_by, granted_at)` + a `modules` JWT claim; a **ModuleGuard** before RolesGuard on every `/api/onboarding/**` route; no entitlement → **404**.

### 2.2 Getting to the module

Module Launcher after login for dual-module users (Option A, as prototyped): cards with live summaries, top-bar switcher. The same chooser pattern serves the client principal (§2.3) with client-appropriate cards.

### 2.3 Client identity — the bridge (new in v1.1)

The client login spans both modules, but the two client masters stay disjoint. The resolution is an **identity-layer table owned by the auth kernel (Stream A)** — referenced by neither domain, referencing both:

```
client_accounts
  id, username (generated, e.g. CL-XXXXX, unique), password_hash (Argon2id),
  must_change_password DEFAULT TRUE,
  ob_client_id NULL → ob_clients.id        (UNIQUE)
  ticketing_client_id NULL → clients.id    (UNIQUE)
  status (ACTIVE·LOCKED·DISABLED), failed_attempts, locked_until,
  last_login_at, password_changed_at, created_by, created_at
  CHECK (ob_client_id IS NOT NULL OR ticketing_client_id IS NOT NULL)
```

- **Creation is an explicit staff action, never automatic**: a checkbox on the boarding wizard ("Create client portal login now") or the **Client portal access panel** on the client page (OB Admin/Manager). Username auto-generated; a one-time password is emailed to the primary SPOC; must-change on first login; resettable and disableable, all audited.
- **JWT carries `principal_type: CLIENT`** with a separate refresh-token family. Portal routes live in their own trees — `/api/portal/onboarding/**`, `/api/portal/tickets/**`. A CLIENT principal on any staff route → 404, and vice versa: the fork is at the route tree, not per-endpoint conditionals.
- **`ClientScopeResolver`** pins every portal query to the principal's own client ids, and on the ticketing side additionally applies `is_client_visible = TRUE` to comments/attachments — activating the blueprint's dormant hooks (`client_contacts.portal_access`, the `is_client_visible` flags) for exactly their intended purpose.
- **Linking `ob_client ↔ ticketing client` is an explicit, audited admin picker** — never auto-matched by name/PAN, because a false positive shows one company another company's tickets.

---

## 3. Roles and responsibility

| Role | Sees | Can do |
|---|---|---|
| **OB Admin** | Everything | Products, journey templates (create per product, version, publish), prerequisites master, module access, client accounts, escalation matrix, notification templates |
| **Onboarding Manager** | Every journey | Reassign, escalate, verify/skip prerequisites, override steps with logged reason, create/reset client logins, dashboards, reports |
| **Sales** | Clients they created | Board clients, capture sale details, view progress |
| **Step Owner** | Journeys containing their steps (read) | Update **only their own steps**; verify prerequisite submissions routed to them |
| **Viewer** | Everything, read-only | Dashboards and reports |
| **Client (external principal)** | Their own data only | Portal: complete prerequisites (upload/comment/submit), view journey progress, sign off, view own tickets |

(The v1.0 Finance role is removed with financial tracking.) Enforcement is server-side: `OnboardingScopeResolver` for staff, `ClientScopeResolver` for the client principal; out-of-scope → 404. PAN is masked for everyone except OB Admin/Manager, with unmasked reads audited — and masked even on the client portal.

---

## 4. Data model (~24 tables: `ob_`-prefixed + `client_accounts` in the identity layer)

Client and capture:

- `ob_clients` — name, description, onboarding_date, PAN (encrypted, unique), address, sales_person_id, license_type, overall_status (`ONBOARDING · LIVE · ON_HOLD · DROPPED`), rag, live_at, created_by. **No payment columns.**
- `ob_client_contacts` — SPOCs: name, designation, email, phone, whatsapp_opt_in, is_primary.
- `ob_products` — the product catalogue (code, name, is_active) that templates bind to.
- `ob_client_applications` — purchase facts: product_id, license_type, units, license_start/end.
- `ob_client_requirements`, `ob_attachments` (polymorphic; `uploaded_by_type STAFF·CLIENT`, `kind REFERENCE·SUBMISSION`).

Journey definition and execution:

- `ob_journey_templates` — **+ `product_id NOT NULL`**; one *active* version per product; creating a new journey = a new template row for a product. / `ob_journey_template_steps` — **+ `depends_on_step_id NULL`**: each step declares at most one dependency, constrained to an **earlier step in the same template** (cycle-free by construction; enforced in the designer and by a service check). NULL = no dependency — the step activates at journey start and **runs in parallel**. / `ob_journey_template_step_items` (sub-categories) — all versioned.
- `ob_journeys` — **+ `product_id`, many per client**: `UNIQUE(client_id, product_id)` among non-archived; **+ `gate_status (LOCKED·OPEN)`, gate_opened_at/by**. Journeys instantiate at boarding **LOCKED** (steps visible, owners resolved, clocks dead, scanner ignores) and open when the gate clears.
- `ob_journey_steps`, `ob_journey_step_items`, `ob_step_clock_events`, `ob_step_communications` (append-only), `ob_step_history` (append-only, hash-chained).
- Accordion strip data (% + RAG dots) is **derived per client page load** — a bounded query over one client's journeys; dashboards keep reading `ob_dashboard_summary`, which gains a product dimension.

Prerequisites (new):

- `ob_prereq_template_tasks` — the versioned master: sequence, title, description, tat_hours, **is_mandatory**, is_active. / `ob_prereq_template_task_docs` — admin reference documents shown to the client.
- `ob_client_prereqs` — per-client header (template_version, status `IN_PROGRESS·CLEARED`, cleared_at).
- `ob_client_prereq_tasks` — snapshot instances + ad-hoc per-client tasks: title, description, is_mandatory, due_at (calendar-aware), **status `PENDING · SUBMITTED · VERIFIED · SKIPPED`**, submitted_at/via, verified_by/at, skipped_by/at + mandatory skip_reason (only non-mandatory tasks are skippable).
- `ob_prereq_comments` (append-only; author_type STAFF·CLIENT) and `ob_prereq_history` (hash-chained, same trigger pattern).

Sign-off, notification, escalation: `ob_signoffs`, `ob_notification_outbox`, `ob_escalations`, `ob_dashboard_summary` — as v1.0.

Identity layer (Stream A, not `ob_`): `client_accounts` (§2.3).

---

## 5. The journey engine

1. **Templates per product.** Admin creates/edits templates bound to a product; editing publishes a new version; in-flight journeys keep theirs. The template designer is also where a *new* journey is born — pick product, name it, define steps.
2. **Instantiation at boarding, LOCKED.** The wizard's product multi-select creates one journey per product with `gate_status = LOCKED`: fully visible (dots, owners, TATs) but no step activates and no clock runs. Everyone sees the plan from day one with zero false breaches.
3. **The prerequisite gate.** One service method — *every mandatory task VERIFIED, every non-mandatory task VERIFIED or SKIPPED* — evaluated on every prerequisite transition. When satisfied: all LOCKED journeys flip OPEN, first steps activate, clocks start, kickoff automation fires. Step activation re-asserts gate OPEN (defence in depth). **There is no "open gate anyway" override** — the only valve is skipping non-mandatory tasks. Products bought after gate-open instantiate directly OPEN.
4. **Prerequisite flow**: client submits (uploads, comments, marks done) → SUBMITTED → staff verify → VERIFIED, or return to PENDING with a comment. Prerequisite TATs are scanned as **client-attributed time** with reminder notifications.
5. **Activation follows dependencies, not sequence.** A step activates when its declared dependency completes — or at gate-open if it has none, so **dependency-free steps run in parallel** and a journey can hold several in-progress steps at once. Completing any step re-evaluates the whole journey and activates every step whose dependency is now satisfied. Manual start of a step whose dependency is incomplete is refused, naming the blocking step. The journey completes when **all** steps are DONE (parallel branches must all land, not just the longest chain). TAT/working-hours maths per step is unchanged; the ribbon marks each step `↳ N` (depends on step N) or `∥` (parallel).
6. **Clock states** — WAITING_ON_CLIENT pauses; internal BLOCKED does not (unchanged).
7. **Sub-category gate** — a step completes only when every sub-category is answered; False requires a remark (unchanged).
8. **RAG** — per step → per journey → client roll-up = worst across *open* journeys; all-journeys-locked shows "Prerequisites pending". **Live-Green requires every journey complete** (each with its sign-offs).
9. **Journey TAT roll-up.** Every journey carries a **total TAT** — the sum of its steps' TAT hours from the pinned template version — and a **utilized** figure — working hours consumed so far (completed steps at their recorded consumption, the active step at its running clock, waiting-on-client time excluded per §5.6). Both render on the client page's accordion strip as `used / total` with overrun highlighted red and ≥75% amber; the total alone shows on every template card in OB-07 and in the designer header, so an Admin sees what a journey *costs* while composing it. Utilized is derived from `ob_step_clock_events` at read time for one client — never a stored aggregate that can disagree with its parts.
9. **The scanner** — sweeps open journeys' steps *and* prerequisite tasks; Amber/Red, escalation L1→L2→L3 (unchanged pattern).

---

## 6. Communication capture

Per-step append-only timelines; client-level stitched view — unchanged. Prerequisite comment threads (staff + client) join the client-level view.

## 7. Notifications — email + WhatsApp

As v1.0 (outbox, provider adapter, template approval early, preferences), plus new events: client login created / password reset · prerequisite submitted (→ verifier) · verified / returned (→ SPOC) · prerequisite TAT reminder (→ SPOC) · **gate opened, journeys started** (→ SPOC + owners) · non-mandatory skip (→ manager digest).

## 8. Client sign-off

Unchanged mechanism — per flagged step and final go-live, secure link + OTP, recorded acceptance, PDF archived, objection reverts the step. The portal adds a **sign-off list** (pending + past) deep-linking into the same flow; the link+OTP path still works without a portal login and remains the legal record.

---

## 9. Screens

Staff (changes from v1.0 in bold):

| # | Screen | Notes |
|---|---|---|
| OB-01 | Module launcher / switcher | unchanged |
| OB-02 | Onboarding dashboard | roll-ups become **journey-counted** with a product dimension |
| OB-03 | Client list | shows journey count + worst RAG; "Prerequisites pending" state |
| OB-04 | New client wizard | **multi-select products → N locked journeys; prerequisites instance created; "Create client portal login now" checkbox. No payment step.** |
| OB-05 | Client detail | **Prerequisites as an accordion on top** (strip: gate chip + mandatory-progress bar; expands to task rows with verify/return/skip; defaults open until the gate clears, collapsed after) **→ journey accordions (strip: product, % complete, RAG step dots, and TAT `used / total` with overrun highlighting; expanded: ribbon + step panel) → Client portal access panel + client info. No payments card. Accordion UX rule: expanding/collapsing or selecting a step never scrolls the page — scroll position is preserved on all same-page interactions.** |
| OB-06 | Step update panel | unchanged (journey-scoped) |
| OB-07 | Journey template designer | **grouped by product; "+ Create journey template" (name + product) is the way a new journey is defined; every template card shows its total TAT (Σ step TATs) at the top, and the designer header shows the running total while editing; per-step "Depends on" selector (an earlier step, or "none — runs parallel"; reorder/delete re-validates dependencies); one active version per product; versioned publish** |
| OB-08 | Responsibility & module access admin | + client-account administration (create/reset/disable/link ticketing client) |
| OB-09 | Sign-off page (public) | unchanged |
| OB-10 | Reports hub | **payment/collection reports removed** |
| OB-11 | Escalation & TAT settings | unchanged |
| OB-12 | Notification templates | + new event templates |
| OB-13 | Notification centre | unchanged |
| **OB-14** | **Prerequisites master** | **admin CRUD: title, description, TAT, mandatory flag, reference docs; versioned; snapshot per client** |

Client portal (own minimal shell, `principal_type = CLIENT` only):

| # | Screen | Notes |
|---|---|---|
| CP-01 | Portal login + forced password change | rate-limited, lockout |
| CP-02 | Module chooser | Ticketing / Onboarding cards |
| CP-03 | Onboarding home | interactive prerequisites above **read-only** journey accordions — step status only; no owner names, internal comms, escalations, or block reasons |
| CP-04 | Prerequisite task detail | description, reference docs, comment thread, uploads, **Submit for verification** |
| CP-05 | Sign-offs | pending + past, deep-linking to the §8 flow |
| CP-06/07 | My tickets (list + read-only detail) | own tickets; `is_client_visible` comments/attachments only; **no raise-ticket** |

## 10. Reports

Journey-counted funnel (per product) · TAT compliance by step/owner · stuck & aging (block reasons + client-attributed waits, **including prerequisite aging**) · time-to-live trend per product · breach & escalation logs · sales pipeline · sign-off pending · owner workload · communication audit · CSAT. **All payment/collection reports removed.** Exports via existing infra; reads from summary tables.

## 11. Security notes

- v1.0 items unchanged (PAN encryption + masking + audited reads; public sign-off endpoint hardening; attachment pipeline; ModuleGuard chain).
- Client principal: separate route trees; separate portal DTO serializers (never staff DTOs with fields hidden client-side); portal rate limits + account lockout + upload caps; the never-visible list (owners, internal comms, escalations, TAT internals, other clients' anything); PAN masked even for the client.

## 12. Decisions to lock before build

1. WhatsApp provider (Cloud API vs BSP). 2. PAN encryption & key custody. 3. Stream ownership (recommend: distribute per §13). 4. Statutory e-sign vs recorded acceptance (recommend: recorded, v1). 5. Credential delivery channel (recommend: email one-time password + WhatsApp notify). 6. Per-SPOC logins later (table already supports it; one account per client in v1).

## 13. Milestones

| Milestone | Contents | Owner |
|---|---|---|
| OB0 — Gate & schema | `ob_*` migrations incl. products/prereqs, **`client_accounts` + portal JWT principal + portal route trees + ClientScopeResolver**, ModuleGuard, launcher | Stream A |
| OB1 — Client capture & masters | wizard (multi-product, no financials, login checkbox), contacts, requirements, attachments, duplicate guard, **OB-14 prerequisites master + per-client instances**, client-account panel; submit WhatsApp templates now | Stream B |
| OB2 — Journey engine & accordions | per-product templates (+ create flow), instantiation LOCKED, gate service, step lifecycle, clock states, **accordion client detail + ribbon** | Stream C |
| OB3 — TAT, notifications, WhatsApp | scanner (steps + prereqs), RAG, escalations, outbox + adapter + webhooks, new events | Stream D |
| OB4 — Sign-off, dashboard, reports | §8 flow + PDF, Live-on-all-journeys, CSAT, journey-counted summaries, reports | Stream B + A |
| OB5 — Client portal | CP-01..07, `is_client_visible` activation on ticketing reads, portal DTO layer | Stream C + **mandatory Stream A security review** |
| OB6 — Hardening | permission-matrix tests all roles incl. CLIENT on every route, **cross-client access + principal-type confusion tests**, mutation-check on both scope resolvers, a11y pass | All |

## 14. Risks

| Risk | Mitigation |
|---|---|
| Client-principal confusion reaching staff routes | separate route trees; principal-type tests in OB6 |
| Wrong ticketing-client link leaking tickets | explicit audited picker, unlink action, never auto-match |
| Credential interception | one-time password, must-change, resettable, delivery audited; creation is explicit, never silent |
| Gate stalls all journeys on a slow client | client-attributed TAT + reminders + non-mandatory skip valve; mandatory list kept short in the master |
| Template edits corrupting in-flight journeys | version pinning + snapshots (journeys *and* prerequisites) |
| TAT disputes | waiting-on-client clock + prereq time attributed to client |
| Portal scope creep (raise tickets, payments visibility) | explicit non-goals; revisit deliberately, not by drift |

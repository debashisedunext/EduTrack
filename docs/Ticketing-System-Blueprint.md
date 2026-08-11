# EduTrack — Organisation Task & Client Ticketing System
### Complete Product, Architecture & UI Blueprint (v1.3)

---

## 1. Executive Summary

**EduTrack** is an internal, multi-project task and client ticketing platform. It merges two things most organisations run separately:

| Capability | Reference product we borrow from |
|---|---|
| Project + task assignment, sprints, velocity | Jira Software, Azure DevOps |
| Client ticket intake, SLA, escalation | Zoho Desk, Freshdesk, Zendesk |
| Quick update / personal work queue | Linear, ClickUp "My Work" |
| Chat threaded on the work item | Slack + Jira integration (unified here) |
| Analytical dashboards with drill-down | Jira Dashboards, Freshdesk Analytics |

**What makes EduTrack different from a plain Jira clone:**

1. **Cycle-based reopen model** — every reopen creates a new *cycle* that stores its own start date, assignee, planned/actual close date and effort. Nothing is overwritten, ever.
2. **Immutable history** — resources can update *state*, never *history*. History is append-only at the database level, not just the UI level.
3. **Automatic criticality escalation** — a task crossing its Planned Close Date is auto-promoted to Critical and pushed to the reporting manager.
4. **Chat is attached to the ticket**, not a side channel — the manager's "What's the status?" is a first-class, trackable event.
5. **Workflow Ribbon** — a live journey strip on every ticket showing the full Support → PM → Dev → QA → Deployment → Close handoff chain, where the ticket is right now, how many rework iterations it has been through, and the effort each resource contributed at each stage.
6. **Client-aware from the first field** — a client master with Excel bulk import, a client dropdown on every ticket, client-visible versus internal comments, and mail that threads back into the ticket as a comment.

---

## 2. Role Model & Permission Matrix

Six system roles, extensible via a role master. **QA and Deployment are new** — the ribbon flow in §4A cannot be represented without them, since a handoff needs a receiving role that owns that stage.

| Capability | Admin | PM | Support Desk | Developer | QA | Deployment |
|---|:--:|:--:|:--:|:--:|:--:|:--:|
| Create/edit resources, roles, reporting manager | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Create/edit projects, map resources to project | ✅ | ✅ (own) | ❌ | ❌ | ❌ | ❌ |
| Create ticket | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Assign / reassign ticket | ✅ | ✅ | ✅ (own project) | ❌ | ❌ | ❌ |
| **Hand off to next stage** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Send back for rework** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Skip a stage** (with reason) | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| **Force-move ribbon backwards** | ✅ | ✅ | ❌ | ❌ | ❌ | ❌ |
| View all tickets | ✅ | Own projects | Own projects | ❌ | ❌ | ❌ |
| View only assigned tickets | — | — | — | ✅ | ✅ | ✅ |
| Update status/effort on assigned ticket | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Close ticket | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Reopen ticket | ✅ | ✅ | ✅ | ❌ | ❌ | ❌ |
| Edit / delete history or ribbon | ❌ (nobody can) | ❌ | ❌ | ❌ | ❌ | ❌ |
| View team member history | ✅ | ✅ (reportees + project) | ✅ (own project) | ❌ | ❌ | ❌ |
| Reports section | ✅ | ✅ | Limited | Own perf. | Own perf. | Own perf. |
| Master data (task types, SLA, workflow, holidays) | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |
| Audit log viewer | ✅ | ❌ | ❌ | ❌ | ❌ | ❌ |

**Golden rule of the ribbon:** only the *current stage owner* (plus PM and Admin) can move a ticket to the next stage. A Developer cannot push a ticket into Deployment while it is sitting with QA.

**Two orthogonal scopes decide visibility on every query:**

- **Role scope** — what the role is allowed to do.
- **Row scope** — `assignee = me` OR `reported_by = me` OR `project_id IN my_projects` OR `assignee IN my_reportees`.

A Developer's ticket list query is *always* forcibly filtered by `assigned_to = current_user_id`, injected by a server-side scope guard — never by a front-end filter. This is the single most important security rule in the system.

> **Recommended addition:** a fifth role, **Client / Requester** (read-only external portal user) — see §16.

---

## 3. Core Workflow

### 3.1 Two layers: stage and status

EduTrack tracks **where the ticket is** (stage — the ribbon) separately from **what is happening to it** (status). They are orthogonal and both are needed:

| | Stage (ribbon) | Status |
|---|---|---|
| Answers | *Which team owns it right now?* | *Is work moving or blocked?* |
| Values | Intake · Triage · Development · QA · Deployment · Verification · Sign-off · Closed | New · In Progress · On Hold · Awaiting Info · Rework · Resolved · Closed · Reopened |
| Changes on | A handoff between teams | A day-to-day update by the owner |
| Owner | Stage owner role | Assignee |

A ticket can be *In Progress* in the **QA** stage, or *On Hold* in the **Deployment** stage. The ribbon renders stages; the status chip renders alongside it.

### 3.2 Ticket lifecycle (state machine)

```
                                   ┌──────────────┐
    (create)  ──▶  NEW  ──assign──▶│  ASSIGNED    │
                    │              └──────┬───────┘
                    │                     │ resource starts work
                    │                     ▼
                    │              ┌──────────────┐
                    │              │ IN PROGRESS  │◀──────┐
                    │              └──────┬───────┘       │
                    │                     │               │ info received
                    │        ┌────────────┼───────────┐   │
                    │        ▼            ▼           ▼   │
                    │  ┌──────────┐ ┌──────────┐ ┌────────┴────┐
                    │  │ ON HOLD  │ │ IN REVIEW│ │ AWAITING    │
                    │  └────┬─────┘ └────┬─────┘ │ INFO        │
                    │       │            │       └─────────────┘
                    │       └────────────┤
                    │                    ▼
                    │              ┌──────────┐
                    └──cancel─────▶│ RESOLVED │
                                   └────┬─────┘
                             verify ok  │  ┌───────────────┐
                                        ├─▶│    CLOSED     │
                                        │  └───────┬───────┘
                                        │          │ reopen ✔
                                        │          ▼
                                        │  ┌───────────────┐
                                        └──│  REOPENED     │──▶ new cycle
                                           └───────────────┘
```

**Allowed transitions** are stored in a `workflow_transitions` table (from_status, to_status, allowed_roles) so the flow is configurable without a code release.

### 3.3 End-to-end operational flow

```
1. INTAKE
   Support Desk / PM / Client raises ticket  ──or──  email-to-ticket parser
   → Ticket ID auto-generated: PRJ-TKT-000123
   → Task type, level, project, planned close date captured
   → SLA policy resolved from (project + type + level) → auto planned close date

2. TRIAGE
   PM or Support Desk validates, sets level, assigns to resource
   → assigned_by, assigned_to, start_date stamped on Cycle #1
   → 🔔 real-time popup + bell notification fires to the assignee
   → email + (optional) WhatsApp/Teams push

3. EXECUTION — moves along the RIBBON (see §4A)
   Support Desk ──hand off──▶ PM ──hand off──▶ Developer
   Developer ──"Ready for QA"──▶ QA
       QA fails it ──"Send for rework"──▶ Developer   ← iteration + 1
       QA passes it ──▶ Deployment
   Deployment deploys ──"Deployment done"──▶ Developer (verification)
   Developer ──"Work complete"──▶ Support Desk / PM (sign-off)
   PM verifies ──▶ CLOSED

   At every hop: effort logged in that stage is attributed to that resource,
   time-in-stage is stamped, and an append-only transition row is written.

4. MONITORING (automated, every 15 min)
   Scheduler scans open tickets:
   → planned_close_date < now  → is_delayed = true, level → CRITICAL
                                → 🔔 alert to Reporting Manager + PM
   → 80% of SLA elapsed        → ⚠️ pre-breach warning to assignee
   → no update in N days       → stale-task nudge

5. STATUS CHECK
   Reporting Manager clicks "Ask Status" on any ticket
   → posts a templated message into that ticket's chat thread
   → 🔔 notification to resource; response time is recorded

6. CLOSURE
   Resource marks RESOLVED → PM/Support verifies → CLOSED
   → actual_close_date stamped, cycle effort frozen
   → CSAT survey to requester (optional)

7. REOPEN
   Reopen checkbox ticked + mandatory reason
   → Cycle #1 is sealed (read-only forever)
   → Cycle #2 created with fresh start date, planned close date, assignee
   → reopen_count++ , total effort = Σ (all cycles)
   → history grid shows both cycles stacked
```

---

## 4. The Reopen & History Model (the heart of the system)

This is where most in-house ticketing tools fail. The design:

**Three layers, each with a different job:**

| Layer | Table | Purpose | Mutability |
|---|---|---|---|
| Current state | `tickets` | Fast reads, list views, dashboards | Updatable |
| Cycle snapshot | `ticket_cycles` | One row per open→close round | Insert; sealed on close |
| Change trail | `ticket_history` | Every single field change, who + when | **Append-only** |
| Effort trail | `ticket_effort_logs` | Every hour logged, by whom, on which date | **Append-only** |

### 4.1 What a reopen actually does

```sql
-- Pseudo-transaction on reopen
BEGIN;
  UPDATE ticket_cycles SET is_sealed = true
    WHERE ticket_id = :id AND cycle_no = :current;

  INSERT INTO ticket_cycles
    (ticket_id, cycle_no, start_date, assigned_to, assigned_by,
     planned_close_date, level, reopen_reason)
  VALUES (:id, :current + 1, NOW(), :new_assignee, :actor, :new_pcd, :level, :reason);

  UPDATE tickets SET status = 'REOPENED',
                     current_cycle_no = :current + 1,
                     reopen_count = reopen_count + 1,
                     actual_close_date = NULL
  WHERE id = :id;

  INSERT INTO ticket_history (...)  -- event: REOPENED
COMMIT;
```

Effort from cycle 1 is **never touched**. `tickets.total_effort_hours` is a materialised sum refreshed on every effort insert:

```
total_effort_hours = SELECT SUM(hours) FROM ticket_effort_logs WHERE ticket_id = :id
cycle_effort_hours = SELECT SUM(hours) FROM ticket_effort_logs
                     WHERE ticket_id = :id AND cycle_no = :n
```

### 4.2 How history is made tamper-proof

Four independent guards — defence in depth:

1. **Application layer** — no service method exists that issues UPDATE/DELETE on `ticket_history` or `ticket_effort_logs`. Only `insert()` is exposed.
2. **Database layer** — dedicated DB role for the app has `INSERT, SELECT` grants only on these tables. Plus a `BEFORE UPDATE OR DELETE` trigger that raises an exception.
3. **API layer** — no PUT/PATCH/DELETE routes are registered for `/history` or `/effort-logs`. A correction is a new compensating entry (`is_correction = true`, `corrects_entry_id`), exactly like an accounting reversal.
4. **Integrity layer** — each history row stores `prev_hash` + `row_hash` (SHA-256 chain). A nightly job verifies the chain; a break raises an admin alert. This makes even direct DB tampering detectable.

**UI consequence:** for a Developer, the history grid renders with no edit/delete affordance at all, and the API would reject it even if the DOM were manipulated.

---

## 4A. The Workflow Ribbon — Task Journey Tracker

The ribbon is a horizontal strip pinned to the top of every ticket detail page. It answers four questions in one glance:

> **Where is it now? · Who has touched it? · How many times has it bounced? · How much effort has each person put in?**

### 4A.1 The standard flow

```
 ①Intake      ②Triage      ③Development     ④QA          ⑤Deployment   ⑥Verification  ⑦Sign-off   ⑧Closed
 Support ───▶ PM ────────▶ Developer ─────▶ QA ────────▶ Deploy team ─▶ Developer ───▶ PM/Support ─▶ ✔
 Desk                          ▲             │                │
                               │             │ FAIL           │ "Deployment done"
                               └─────────────┘ rework ×N      │
                               └──────────────────────────────┘
```

| # | Stage | Owner role | Enters when | Leaves when | Default SLA |
|---|---|---|---|---|---|
| 1 | **Intake** | Support Desk | Ticket created | Details validated, project tagged | 2 h |
| 2 | **Triage / Planning** | PM | Support hands off | Level set + developer assigned | 4 h |
| 3 | **Development** | Developer | PM assigns | Dev marks "Ready for QA" | Per SLA policy |
| 4 | **QA / Testing** | QA | Dev hands off | QA marks Pass or Fail | 8 h |
| 5 | **Deployment** | Deployment / DevOps | QA passes | Deploy marked done | 4 h |
| 6 | **Verification** | Developer | Deploy team hands back | Dev confirms working in target env | 4 h |
| 7 | **Sign-off** | PM / Support Desk | Dev marks "Work complete" | PM/Support accepts | 8 h |
| 8 | **Closed** | — | Sign-off accepted | terminal | — |

**Loop-backs (each one increments the iteration counter):**

| Loop | From → To | Action code | Reason required |
|---|---|---|---|
| Rework | QA → Development | `REWORK` | ✅ defect list mandatory |
| Deployment failure | Deployment → Development | `DEPLOY_FAILED` | ✅ |
| Verification failure | Verification → Development | `VERIFY_FAILED` | ✅ |
| Rejected at sign-off | Sign-off → Development | `SIGNOFF_REJECTED` | ✅ |
| Clarification | Development → Triage | `CLARIFICATION` | ✅ |
| Force move (PM/Admin) | any → any | `OVERRIDE` | ✅ + logged as override |

### 4A.2 Iterations vs cycles — two different counters

This distinction matters and is easy to get wrong:

| Counter | Increments when | Scope | Shown as |
|---|---|---|---|
| **Iteration** (`iteration_no`) | The ticket is pushed *backwards* in the ribbon — QA fails it, deployment fails, sign-off rejects | Within one cycle | `Iteration 3` badge on the ribbon, with `↺ ×2` on the looping segment |
| **Cycle** (`cycle_no`) | The ticket is **reopened after closure** | Whole ticket | `Cycle 2 of 2` chip above the ribbon |

So a ticket can read: **Cycle 2 · Iteration 3 · currently in QA**. That means it was closed once, reopened, and in this second life QA has already bounced it twice.

Each cycle has its **own ribbon**. A cycle selector sits above the ribbon; selecting Cycle 1 renders that cycle's completed journey read-only. Nothing is ever redrawn or lost.

### 4A.3 What the ribbon renders

Every segment shows five things:

```
┌───────────────┐
│ ✔ Development │  ← stage name + state icon
│ Ravi Kumar    │  ← owner (avatar + name)
│ 3d 4h         │  ← time in stage (working hours)
│ 14.5 h effort │  ← effort logged in this stage
│ ↺ ×2          │  ← loop-back badge, only if bounced
└───────────────┘
```

| Segment state | Visual treatment |
|---|---|
| Completed | Solid tick, green accent, filled connector to the right |
| **Current** | Indigo fill, subtle pulse ring, "Now" label, elapsed timer running |
| Pending | Outlined, muted grey text, dashed connector |
| Reworked | Amber left edge + `↺ ×N` badge |
| Skipped | Dashed outline, strikethrough label, hover shows skip reason and who authorised it |
| Blocked / On hold | Grey with pause icon, hover shows hold reason |

**Interactions**
- **Click a segment** → filters the History, Effort and Chat tabs below to just that stage and iteration.
- **Hover** → tooltip with entered-at, exited-at, owner, handoff note, effort, and idle vs active time.
- **Current segment** shows the primary action button inline: *Hand off to QA →*, *Pass / Fail*, *Mark deployed*, etc. — contextual to the stage owner's role, hidden for everyone else.
- **Compact ribbon** in the ticket list: eight small dots with the current one filled, so a manager can scan a whole grid and see where every ticket sits.
- Fully keyboard-navigable; each segment has an ARIA label reading the stage, owner, state and effort.

### 4A.4 Effort attribution per resource per stage

The panel directly under the ribbon — this is the "total effort taken by each resource" grid:

```
┌────┬─────────────┬─────────────┬──────┬────────┬────────┬──────────┬──────────┐
│ It │ Stage       │ Resource    │ Role │ In     │ Out    │ Duration │ Effort   │
├────┼─────────────┼─────────────┼──────┼────────┼────────┼──────────┼──────────┤
│ 1  │ Intake      │ Priya N.    │ SUP  │ 01 Aug │ 01 Aug │  1h 10m  │   0.5 h  │
│ 1  │ Triage      │ Meera P.    │ PM   │ 01 Aug │ 01 Aug │  3h 20m  │   1.0 h  │
│ 1  │ Development │ Ravi K.     │ DEV  │ 01 Aug │ 03 Aug │  2d 1h   │   9.0 h  │
│ 1  │ QA          │ Anil S.     │ QA   │ 03 Aug │ 04 Aug │  6h 40m  │   3.5 h  │  ✗ Failed
│ 2  │ Development │ Ravi K.     │ DEV  │ 04 Aug │ 05 Aug │  1d 2h   │   5.5 h  │  ↺ rework
│ 2  │ QA          │ Anil S.     │ QA   │ 05 Aug │ 05 Aug │  4h 05m  │   2.0 h  │  ✓ Passed
│ 2  │ Deployment  │ Karan D.    │ DEP  │ 05 Aug │ 06 Aug │  5h 30m  │   1.5 h  │
│ 2  │ Verification│ Ravi K.     │ DEV  │ 06 Aug │ 06 Aug │  2h 15m  │   1.0 h  │
│ 2  │ Sign-off    │ Meera P.    │ PM   │ 06 Aug │ 06 Aug │  1h 00m  │   0.5 h  │
├────┴─────────────┴─────────────┴──────┴────────┴────────┼──────────┼──────────┤
│ ROLL-UP BY RESOURCE                                     │ Elapsed  │ Effort   │
│ Ravi Kumar (Developer)          3 stages, 2 iterations  │ 3d 5h    │  15.5 h  │
│ Anil Sharma (QA)                2 stages                │ 10h 45m  │   5.5 h  │
│ Meera Prasad (PM)               2 stages                │ 4h 20m   │   1.5 h  │
│ Karan Desai (Deployment)        1 stage                 │ 5h 30m   │   1.5 h  │
│ Priya Nair (Support)            1 stage                 │ 1h 10m   │   0.5 h  │
├─────────────────────────────────────────────────────────┼──────────┼──────────┤
│ TOTAL (Cycle 2)                                         │ 5d 3h    │  24.5 h  │
│ TOTAL (all cycles)                                      │ 12d 6h   │  38.0 h  │
└─────────────────────────────────────────────────────────┴──────────┴──────────┘
```

Two derived numbers make this genuinely useful:

```
Active time (per stage) = Σ effort logged while in that stage
Idle / wait time        = duration in stage − active time
```

A stage with 2 days duration but 2 hours of effort is a **queue problem**, not a capacity problem — and that single insight is usually worth the whole ribbon feature.

### 4A.5 Data model additions

```sql
CREATE TABLE workflow_templates (
  id           BIGSERIAL PRIMARY KEY,
  name         VARCHAR(80) NOT NULL,          -- 'Standard Dev Flow', 'Support Fast-Track'
  is_default   BOOLEAN DEFAULT FALSE
);

CREATE TABLE workflow_stages (
  id            BIGSERIAL PRIMARY KEY,
  template_id   BIGINT REFERENCES workflow_templates(id),
  seq           SMALLINT NOT NULL,            -- ribbon order
  stage_code    VARCHAR(20) NOT NULL,         -- INTAKE|TRIAGE|DEV|QA|DEPLOY|VERIFY|SIGNOFF|CLOSED
  display_name  VARCHAR(50) NOT NULL,
  owner_role    VARCHAR(20) NOT NULL,
  sla_hours     NUMERIC(6,2),
  is_optional   BOOLEAN DEFAULT FALSE,        -- can be skipped with reason
  can_return_to VARCHAR(20)[],                -- allowed backward targets
  icon          VARCHAR(30),
  UNIQUE (template_id, seq)
);

-- APPEND ONLY. This table IS the ribbon.
CREATE TABLE ticket_stage_transitions (
  id             BIGSERIAL PRIMARY KEY,
  ticket_id      BIGINT NOT NULL REFERENCES tickets(id),
  cycle_no       SMALLINT NOT NULL,
  iteration_no   SMALLINT NOT NULL DEFAULT 1,
  seq_no         INT NOT NULL,                -- 1,2,3… order of hops in this cycle
  from_stage     VARCHAR(20),                 -- NULL on the very first hop
  to_stage       VARCHAR(20) NOT NULL,
  from_user_id   BIGINT REFERENCES users(id),
  to_user_id     BIGINT REFERENCES users(id),
  action_code    VARCHAR(20) NOT NULL,        -- FORWARD|REWORK|DEPLOY_FAILED|VERIFY_FAILED|
                                              -- SIGNOFF_REJECTED|CLARIFICATION|SKIP|OVERRIDE
  handoff_note   TEXT,
  reason         TEXT,                        -- mandatory on any backward move
  entered_at     TIMESTAMPTZ NOT NULL,
  exited_at      TIMESTAMPTZ,                 -- NULL = ticket is here now
  duration_mins  INT,                         -- working minutes, computed on exit
  is_current     BOOLEAN DEFAULT TRUE,
  prev_hash      CHAR(64), row_hash CHAR(64),
  created_at     TIMESTAMPTZ DEFAULT NOW(),
  UNIQUE (ticket_id, cycle_no, seq_no)
);
CREATE TRIGGER trg_stage_immutable BEFORE DELETE ON ticket_stage_transitions
  FOR EACH ROW EXECUTE FUNCTION block_mutation();
CREATE INDEX ix_stage_current ON ticket_stage_transitions(ticket_id)
  WHERE is_current = TRUE;

-- effort logs gain stage attribution
ALTER TABLE ticket_effort_logs
  ADD COLUMN stage_code   VARCHAR(20),
  ADD COLUMN iteration_no SMALLINT DEFAULT 1;

-- tickets gain the ribbon pointers
ALTER TABLE tickets
  ADD COLUMN workflow_template_id BIGINT REFERENCES workflow_templates(id),
  ADD COLUMN current_stage        VARCHAR(20) DEFAULT 'INTAKE',
  ADD COLUMN current_iteration    SMALLINT DEFAULT 1,
  ADD COLUMN rework_count         SMALLINT DEFAULT 0,
  ADD COLUMN stage_entered_at     TIMESTAMPTZ DEFAULT NOW();
```

> `exited_at` is the one field allowed to be UPDATEd once (NULL → timestamp) when the stage closes; the trigger blocks DELETE, and a `CHECK`/rule prevents changing a non-NULL `exited_at`. Everything else is insert-only, so the ribbon can never be rewritten — including by a Developer.

**Effort roll-up query** (drives the grid in §4A.4):

```sql
SELECT t.iteration_no, t.to_stage AS stage, u.full_name, u.role_code,
       t.entered_at, t.exited_at, t.duration_mins,
       COALESCE(SUM(e.hours), 0) AS effort_hours,
       t.duration_mins/60.0 - COALESCE(SUM(e.hours),0) AS idle_hours
FROM ticket_stage_transitions t
JOIN users u ON u.id = t.to_user_id
LEFT JOIN ticket_effort_logs e
       ON e.ticket_id = t.ticket_id
      AND e.stage_code = t.to_stage
      AND e.iteration_no = t.iteration_no
WHERE t.ticket_id = :id AND t.cycle_no = :cycle
GROUP BY t.id, u.full_name, u.role_code
ORDER BY t.seq_no;
```

### 4A.6 Handoff rules and validation

- Handoff opens a modal: **next stage** (pre-filled from the template), **assign to** (filtered to members of the receiving role on that project, with current load shown), **handoff note**, **effort confirmation** for the stage being left.
- A backward move **requires a reason** and increments `iteration_no` for every subsequent row in that cycle.
- Effort must be logged before leaving a stage (configurable: warn vs block) — otherwise the effort grid silently under-reports.
- Skipping an optional stage requires PM/Admin and a reason; the segment renders struck-through with the reason on hover.
- If the receiving role has no member on the project, the handoff falls to a project-level queue and the PM is alerted.
- Reassignment *within* a stage (Ravi → Sunil, both developers) does **not** create a new segment; it writes a `STAGE_REASSIGNED` history row and splits effort attribution between the two, so both appear in the roll-up.

### 4A.7 Alerts driven by the ribbon

| Trigger | Action | To |
|---|---|---|
| Handoff received | Popup + bell + email | New stage owner |
| Stage SLA breached (stuck in stage) | Alert + ribbon segment turns red | Stage owner, PM, Reporting Manager |
| `iteration_no` ≥ 3 | "Ping-pong" quality flag on the PM dashboard | PM |
| QA fail | Notify Developer + PM with the defect list | Dev, PM |
| Deployment failed | Notify Dev, PM, Reporting Manager | All three |
| No stage movement for 3 working days | Nudge | Current owner, cc RM |
| Stage owner unavailable (leave) | Reassignment prompt | PM |

### 4A.8 New reports unlocked by the ribbon

| Report | What it answers |
|---|---|
| **Stage funnel / WIP** | How many tickets sit in each stage right now — spot the bottleneck instantly |
| **Average time per stage** | Where the calendar time actually goes, split into active vs idle |
| **Rework rate by developer** | % of tickets a developer gets bounced back from QA |
| **QA rejection rate** | Defects caught per release — a quality signal, not a blame tool |
| **Deployment success rate** | First-time-right deployments vs rollbacks |
| **Cycle time by stage** | Trend over weeks; is QA getting slower? |
| **Handoff latency** | Time between one team finishing and the next picking up — pure queue waste |
| **Resource contribution per ticket** | The §4A.4 roll-up, exportable across any ticket set |

### 4A.9 Configurability

Not every project needs eight stages. The workflow template master (screen S-13) lets an Admin define a template per project and per task type:

- **Standard Dev Flow** — all 8 stages (Production Bug, Change Request, Future Release)
- **Support Fast-Track** — Intake → Triage → Development → Sign-off → Closed (Client Request, Browser Issue)
- **Infra Flow** — Intake → Triage → Deployment → Verification → Closed (Server Issue, Network Issue)

The ribbon component renders whatever the template defines — no code change to add or remove a stage.

---

## 4B. Ticket Page Additions — Priority, Client, Attachments, Comments, Mail

Five capabilities that live on the ticket page itself. Individually small, together they are what makes the page usable by a support desk handling real client traffic.

### 4B.1 Priority (Level) dropdown

A first-class control on both the create form and the detail page, not a field buried in a settings panel.

| Aspect | Specification |
|---|---|
| Control | Single-select dropdown rendering colour chips, not plain text: 🟢 Low · 🔵 Medium · 🟠 High · 🔴 Critical |
| Source | `priorities` master (S-12) — Admin can add levels; each carries a colour, default SLA hours and an escalation flag |
| Default | Pre-filled from the **task type master** (a Production Bug defaults to High, a Future Release to Low), then editable |
| Effect on save | Resolves the SLA policy → recomputes the **Planned Close Date** and shows the new date inline before the user commits |
| Inline change | Editable directly on the detail page from the summary panel — one click, no full-page edit mode |
| Reason | Mandatory free-text reason when the ticket is already assigned. Optional at creation |
| Who can change it | Admin, PM, Support Desk. Developer/QA/Deployment can only **request** a change, which raises a notification to the PM |
| History | Every change writes a `LEVEL_CHANGED` row: old value, new value, actor, reason, timestamp. `original_level` on the ticket is never overwritten |
| Side effects | Setting Critical fires an immediate alert to the assignee, PM and reporting manager. Auto-escalation on SLA breach writes the same row with `actor_type = 'SYSTEM'` |

### 4B.2 Client dropdown and the Client Master

**On the ticket page:**

| Field | Behaviour |
|---|---|
| **Client** | Searchable dropdown (type-ahead over name, code and domain). Filtered to clients mapped to the selected project, with an "All clients" toggle for Admin/PM. Shows the client's logo initial and code in the option row |
| **Client contact** | Second dropdown, dependent on the client — the individual person who reported the issue. Populated from `client_contacts`. Inline "+ Add contact" so the support desk never has to leave the form |
| **Auto-fill** | Selecting a client pre-fills the default SLA policy, the account manager as a watcher, and the client's time zone for due-date display |
| **Client-raised flag** | When the client is set and the reporter is a client contact, the ticket is marked client-raised — this drives the client-wise reports, the CSAT survey and the "client visible" default on comments |
| **Mandatory rule** | Configurable per task type: Client Request, Client Bug and Production Bug require a client; Internal Bug does not |

**Client Master fields (screen S-32 / S-33):**

| Group | Fields |
|---|---|
| Identity | **Client Code*** (unique, used in reports and imports), **Client Name***, Short name / alias, Logo, Industry, Status (Active / Inactive / Prospect) |
| Commercial | Account manager (resource lookup), Contract start and end date, Support plan (Standard / Premium / Enterprise), Default SLA policy, Billing reference |
| Contact | Primary contact name, email, phone; billing email; support email (used for email-to-ticket matching); website / domain (used to auto-match inbound mail) |
| Address | Address lines, city, state, country, postal code, time zone |
| Mapping | Projects this client is associated with (multi-select), default project |
| Notes | Free-text account notes, tags |

Deactivating a client with open tickets prompts a warning and blocks new ticket creation against it, but never hides the historical tickets.

**Client contacts** are a child table: name, designation, email, phone, is_primary, receives_notifications, portal_access. One client, many contacts.

### 4B.3 Excel upload for the client list (screen S-34)

A four-step wizard, because a silent bulk import that half-succeeds is worse than no import at all.

```
STEP 1  DOWNLOAD TEMPLATE
        Pre-formatted .xlsx with the exact column headers, a data-validation
        dropdown on Status and Support Plan, and one filled example row.

STEP 2  UPLOAD
        Drag-drop .xlsx / .xls / .csv, max 5 MB, up to 5,000 rows.
        File parsed server-side with SheetJS; first sheet by default,
        sheet selector if the workbook has several.

STEP 3  MAP COLUMNS
        Auto-matched by header name, with a manual override dropdown per
        column. Unmapped required columns block the Next button.
        Mapping presets can be saved and reused for the next import.

STEP 4  VALIDATE + PREVIEW  (dry run — nothing is written yet)
        ┌──────┬────────────────┬──────────────────────┬─────────────────┐
        │ Row  │ Client code    │ Status               │ Message         │
        ├──────┼────────────────┼──────────────────────┼─────────────────┤
        │  2   │ ACME           │ ✅ Will create        │ —               │
        │  3   │ NORTHWIND      │ ♻ Will update         │ Name, phone     │
        │  4   │ ACME           │ ⚠ Duplicate in file   │ Row 2 wins      │
        │  5   │ (blank)        │ ❌ Rejected           │ Code required   │
        │  6   │ ZENITH         │ ❌ Rejected           │ Invalid email   │
        └──────┴────────────────┴──────────────────────┴─────────────────┘
        Summary: 412 create · 38 update · 6 rejected · 2 duplicates

STEP 5  COMMIT
        Choose: import valid rows only  |  cancel entirely.
        Runs in a background job with a progress bar; a downloadable
        error report (.xlsx with a Reason column appended) is produced
        for every rejected row so the user can fix and re-upload just those.
```

**Validation rules:** client code unique and alphanumeric; name required; email format; country against an ISO list; duplicate detection on client code first, then on email domain; existing records **updated, never duplicated** (upsert on client code); every import writes an `import_batch` row so a bad import can be identified and reversed as a set.

The same wizard pattern is reused for the resource master bulk import — build it once, register two schemas.

### 4B.4 Attachments

| Aspect | Specification |
|---|---|
| Where you can attach | Create form · ticket detail · **comment box** · handoff dialog (test evidence, deployment log) · quick update panel · inbound email |
| Input methods | Drag-and-drop zone, file picker, and **paste from clipboard** — the last one matters most, because a support agent pasting a screenshot straight from Snipping Tool is the single most common attachment action |
| Allowed types | Images (png, jpg, gif, webp), documents (pdf, **doc**, docx, xls, xlsx, csv, txt, log), archives (zip), video (mp4, up to 50 MB). The legacy binary Office formats are on the list because clients still send them; they are also the reason MIME sniffing is not optional — a `.doc` is an OLE container and its extension proves nothing |
| Limits | 10 MB per file by default, 50 MB per ticket, 20 files per ticket — all configurable in system settings |
| Image handling | Thumbnail generated on upload, gallery strip on the ticket page, click to open a lightbox with zoom and next/previous. EXIF stripped on upload (client screenshots can carry location data) |
| Security | Extension allow-list **and** MIME sniffing (not extension alone), anti-virus scan before the file becomes visible, stored in S3/MinIO under `tickets/{ticket_id}/{uuid}`, served only through short-lived signed URLs, never a public bucket |
| Traceability | Every upload writes an `ATTACHMENT_ADDED` history row with filename, size and uploader |
| Deletion | The uploader may delete within 15 minutes; after that it is a soft delete leaving a tombstone row ("file removed by X on date") so the record of it existing survives |
| Client visibility | Each attachment carries an `is_client_visible` flag, so internal debug logs never surface on the client portal |

### 4B.5 Comment box

The comment box sits directly under the ticket description, above the tabs. It is the conversational record of the ticket — distinct from chat, which is ephemeral discussion.

| Aspect | Specification |
|---|---|
| Editor | Rich text: bold, italic, lists, code block, inline links. Ctrl/Cmd + Enter to post |
| Mentions | `@name` type-ahead over project members; a mention fires a notification and an email to that person |
| Attachments | Files can be attached to a comment; they also appear in the ticket's attachment gallery |
| Visibility | Toggle per comment: **Internal note** (grey background, team only) or **Client visible** (white, appears on the client portal and in the client email thread). Default follows whether the ticket is client-raised |
| Stamping | Every comment records author, author's role, the **stage** and **iteration** the ticket was in when it was written, and the timestamp |
| Immutability | Editable for 5 minutes; after that the comment is locked and shows an "edited" marker with the original preserved. Deletion leaves a tombstone. No role, including Admin, can silently rewrite a comment |
| In history | Comments are interleaved into the History tab alongside field changes and handoffs, in one chronological stream — so "what happened on this ticket" is a single readable timeline, not two lists to reconcile |
| Reply from email | A reply to a notification email is parsed and appended as a comment by that user, with the quoted text stripped |

**How it renders in the History tab:**

```
06 Aug 14:22  💬 Ravi Kumar (Developer) · Development · iteration 2
              "Root cause is the retry timeout. Patch pushed, ready for QA."      [internal]

06 Aug 14:25  ➡ Handoff  Development → QA   Ravi Kumar → Anil Sharma
              Note: "Please retest the checkout path on staging"

06 Aug 16:40  🔺 Level changed  High → Critical   by System (SLA breach)

07 Aug 09:15  💬 Anil Sharma (QA) · QA · iteration 2
              "Verified on staging, three of three defects closed."               [internal]

07 Aug 09:16  📎 Attachment added  qa-signoff-report.pdf (412 KB)  by Anil Sharma
```

### 4B.6 Mail alert engine

**Rule: a mail fires to the assignee on every stage transition, and on every event that changes who is responsible or what is expected of them.**

| Event | Mail to | Subject pattern | Optional? |
|---|---|---|---|
| Ticket created and assigned | Assignee | `[CRM-26-00347] New ticket assigned to you — Critical` | ❌ never |
| **Handoff — ribbon moves to a new stage** | **New stage owner** | `[CRM-26-00347] Handed to you at QA by Ravi Kumar` | ❌ never |
| Reassigned within a stage | New assignee, cc previous | `[CRM-26-00347] Reassigned to you` | ❌ never |
| Sent back for rework | Developer, cc PM | `[CRM-26-00347] QA failed — 3 defects returned` | ❌ never |
| Deployment done | Developer | `[CRM-26-00347] Deployed to production — please verify` | ❌ never |
| Level raised to Critical | Assignee, PM, RM | `[CRM-26-00347] Escalated to CRITICAL` | ❌ never |
| SLA breach / delayed | Assignee, PM, **Reporting Manager** | `[CRM-26-00347] Overdue by 3 days` | ❌ never |
| Stage SLA breach | Stage owner, PM, RM | `[CRM-26-00347] Stuck in Deployment past SLA` | ❌ never |
| Comment added | Assignee, watchers | `[CRM-26-00347] New comment from Meera Prasad` | ✅ digest option |
| @mention | Mentioned user | `[CRM-26-00347] You were mentioned` | ✅ |
| Status requested by manager | Assignee | `[CRM-26-00347] Status requested` | ❌ never |
| Ticket closed | Reporter, client contact, watchers | `[CRM-26-00347] Resolved and closed` | ✅ |
| Reopened | New assignee, PM | `[CRM-26-00347] Reopened — cycle 2` | ❌ never |
| Daily digest 08:30 | Everyone | `Your open tickets — 4 due today, 1 overdue` | ✅ opt-out |
| Weekly manager summary | RM, PM | `Team summary — week of 03 Aug` | ✅ |

**Mail content:** ticket ID and title in the subject with the ID first so it threads and searches cleanly; a body carrying the level chip, project, client, current stage, planned close date, who acted and what they said; a primary **Open ticket** button; and a secondary reply hint — replying to the mail appends a comment.

**Engineering notes:**

- Queued through **BullMQ**, never sent inline in the request — a slow SMTP server must never slow down a handoff.
- Templates live in the **notification template master (S-15)**: subject, HTML body, merge tags (`{{ticket_id}}`, `{{assignee}}`, `{{stage}}`, `{{client}}`, `{{planned_close}}`), per-event on/off, per-role recipient list. Editable by an Admin without a code release.
- Threading: `Message-ID` and `In-Reply-To` headers keyed on the ticket, so an entire ticket's mail collapses into one thread in Outlook and Gmail.
- **Every send is logged** in `email_log` with status (queued / sent / bounced / failed), provider message ID and retry count. Three retries with exponential backoff, then a failure notification in-app so nobody assumes a mail arrived that never did.
- Bounce and complaint webhooks from the provider mark the address invalid and alert the Admin.
- Rate-limited per recipient (no more than one mail per ticket per minute) so a burst of rapid updates doesn't spam the assignee.
- A **"critical mails cannot be disabled"** rule: assignment, handoff, escalation and breach mails ignore user preferences. Everything else respects them.

### 4B.7 Data model additions

```sql
CREATE TABLE clients (
  id                 BIGSERIAL PRIMARY KEY,
  client_code        VARCHAR(20)  UNIQUE NOT NULL,
  name               VARCHAR(150) NOT NULL,
  short_name         VARCHAR(60),
  industry           VARCHAR(80),
  status             VARCHAR(20) DEFAULT 'ACTIVE',
  account_manager_id BIGINT REFERENCES users(id),
  support_plan       VARCHAR(20),
  sla_policy_id      BIGINT REFERENCES sla_policies(id),
  primary_email      VARCHAR(150),
  support_email      VARCHAR(150),
  phone              VARCHAR(30),
  website_domain     VARCHAR(120),          -- auto-matches inbound mail
  address_line1      VARCHAR(150), address_line2 VARCHAR(150),
  city VARCHAR(80), state VARCHAR(80), country VARCHAR(80), postal_code VARCHAR(20),
  timezone           VARCHAR(50) DEFAULT 'Asia/Kolkata',
  contract_start     DATE, contract_end DATE,
  notes              TEXT,
  import_batch_id    BIGINT,                -- traces bulk-imported rows
  created_at         TIMESTAMPTZ DEFAULT NOW(),
  updated_at         TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE client_contacts (
  id            BIGSERIAL PRIMARY KEY,
  client_id     BIGINT NOT NULL REFERENCES clients(id),
  name          VARCHAR(120) NOT NULL,
  designation   VARCHAR(80),
  email         VARCHAR(150),
  phone         VARCHAR(30),
  is_primary    BOOLEAN DEFAULT FALSE,
  receives_mail BOOLEAN DEFAULT TRUE,
  portal_access BOOLEAN DEFAULT FALSE,
  is_active     BOOLEAN DEFAULT TRUE
);

CREATE TABLE client_projects (
  client_id  BIGINT REFERENCES clients(id),
  project_id BIGINT REFERENCES projects(id),
  is_default BOOLEAN DEFAULT FALSE,
  PRIMARY KEY (client_id, project_id)
);

CREATE TABLE ticket_comments (                 -- APPEND ONLY after the edit window
  id                BIGSERIAL PRIMARY KEY,
  ticket_id         BIGINT NOT NULL REFERENCES tickets(id),
  cycle_no          SMALLINT,
  stage_code        VARCHAR(20),
  iteration_no      SMALLINT,
  author_id         BIGINT NOT NULL REFERENCES users(id),
  body_html         TEXT NOT NULL,
  body_text         TEXT NOT NULL,             -- for search and email replies
  is_internal       BOOLEAN DEFAULT TRUE,      -- false = visible to the client
  mentioned_user_ids BIGINT[],
  source            VARCHAR(15) DEFAULT 'WEB', -- WEB | EMAIL | API
  edited_at         TIMESTAMPTZ,
  original_body     TEXT,                      -- preserved on edit
  is_deleted        BOOLEAN DEFAULT FALSE,     -- tombstone, row never removed
  created_at        TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ix_comments_ticket ON ticket_comments(ticket_id, created_at);

CREATE TABLE ticket_attachments (
  id                BIGSERIAL PRIMARY KEY,
  ticket_id         BIGINT NOT NULL REFERENCES tickets(id),
  comment_id        BIGINT REFERENCES ticket_comments(id),
  cycle_no          SMALLINT, stage_code VARCHAR(20),
  file_name         VARCHAR(255) NOT NULL,
  storage_key       VARCHAR(400) NOT NULL,     -- tickets/{id}/{uuid}
  mime_type         VARCHAR(100) NOT NULL,
  size_bytes        BIGINT NOT NULL,
  thumbnail_key     VARCHAR(400),
  is_client_visible BOOLEAN DEFAULT FALSE,
  scan_status       VARCHAR(15) DEFAULT 'PENDING',  -- PENDING|CLEAN|INFECTED
  uploaded_by       BIGINT REFERENCES users(id),
  is_deleted        BOOLEAN DEFAULT FALSE,
  deleted_by        BIGINT REFERENCES users(id),
  created_at        TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE email_log (
  id                BIGSERIAL PRIMARY KEY,
  ticket_id         BIGINT REFERENCES tickets(id),
  event_code        VARCHAR(40) NOT NULL,
  template_id       BIGINT REFERENCES notification_templates(id),
  to_user_id        BIGINT REFERENCES users(id),
  to_email          VARCHAR(150) NOT NULL,
  subject           VARCHAR(300),
  status            VARCHAR(15) DEFAULT 'QUEUED',  -- QUEUED|SENT|BOUNCED|FAILED
  provider_msg_id   VARCHAR(200),
  retry_count       SMALLINT DEFAULT 0,
  error_text        TEXT,
  queued_at         TIMESTAMPTZ DEFAULT NOW(),
  sent_at           TIMESTAMPTZ
);
CREATE INDEX ix_email_log_ticket ON email_log(ticket_id, queued_at);

CREATE TABLE import_batches (
  id            BIGSERIAL PRIMARY KEY,
  entity        VARCHAR(30) NOT NULL,        -- CLIENT | RESOURCE
  file_name     VARCHAR(255),
  total_rows    INT, created_rows INT, updated_rows INT, rejected_rows INT,
  status        VARCHAR(20) DEFAULT 'PENDING',
  error_report_key VARCHAR(400),
  imported_by   BIGINT REFERENCES users(id),
  created_at    TIMESTAMPTZ DEFAULT NOW()
);

ALTER TABLE tickets
  ADD COLUMN client_id         BIGINT REFERENCES clients(id),
  ADD COLUMN client_contact_id BIGINT REFERENCES client_contacts(id),
  ADD COLUMN is_client_raised  BOOLEAN DEFAULT FALSE,
  ADD COLUMN comment_count     INT DEFAULT 0,
  ADD COLUMN attachment_count  INT DEFAULT 0;
CREATE INDEX ix_tickets_client ON tickets(client_id, status);
```

---

## 5. Effort & Velocity Calculation

```
Effort logged        = Σ hours in ticket_effort_logs (per cycle & total)
Estimated effort     = tickets.estimated_effort_hours (set at creation)
Effort variance %    = (logged − estimated) / estimated × 100

Cycle time           = actual_close_date − cycle.start_date  (working hours only)
Lead time            = actual_close_date − date_reported
Aging (open ticket)  = NOW() − date_reported
SLA compliance %     = closed_within_pcd / total_closed × 100
Reopen rate %        = tickets_with_reopen_count>0 / total_closed × 100

Time in stage        = exited_at − entered_at  (working hours)
Active time in stage = Σ effort logged with that stage_code + iteration_no
Idle / queue time    = time in stage − active time
Rework rate %        = tickets with iteration_no>1 / total tickets × 100
Handoff latency      = next stage entered_at − previous stage exited_at
First-time-right %   = tickets closed with iteration_no = 1 / total closed × 100

Resource Velocity    = tickets closed per week  (count)
                       AND effort-weighted:  Σ effort_hours closed per week
Utilisation %        = Σ logged hours / available hours (from working calendar)
Throughput trend     = 4-week rolling average of velocity
```

Working hours use a **working calendar** (weekends + org holidays + resource leave) so a ticket raised Friday 6 PM with a 4-hour SLA isn't breached on Saturday morning. This calendar is a master screen (§7.4) and is one of the most commonly missed requirements.

---

## 6. Escalation & SLA Engine

| Trigger | Condition | Action | Recipients |
|---|---|---|---|
| Assignment | Ticket assigned/reassigned | Popup + bell + email | Assignee |
| SLA warning | 80% of time to Planned Close Date elapsed | ⚠️ Bell + email | Assignee |
| **Breach** | `NOW() > planned_close_date` AND status not closed | Level → **CRITICAL**, `is_delayed = true`, banner on ticket | **Reporting Manager** + PM + Assignee |
| Repeat breach | Delayed > 48 h beyond PCD | Escalate to reporting manager's manager (L2) | RM's manager |
| Critical raised | Level set to Critical manually | Immediate push | RM, PM, Assignee |
| Stale | No update for 3 working days | Nudge | Assignee, cc RM |
| Reopen | Reopen count ≥ 2 | Quality flag on dashboard | PM |
| Status request | Manager clicks "Ask Status" | Chat message + bell | Assignee |
| Unassigned | New ticket unassigned > 2 h | Triage alert | PM, Support Desk |

**Implementation:** a scheduled worker (BullMQ repeatable job / Quartz / Hangfire) runs every 15 minutes. Original level is preserved in `original_level` so you can always report "how many were *born* critical vs *became* critical" — an insight managers ask for immediately.

Escalation matrix is configurable per project: `sla_policies(project_id, task_type, level, response_hrs, resolution_hrs, escalate_to_l1, escalate_to_l2)`.

---

## 7. Screen Inventory

**34 screens across 8 modules.** Each is specified below with its exact fields and actions.

### 7.1 Authentication module

**S-01 Login**
- Fields: Username / Email, Password, Remember me, Forgot password link
- Centred card on a soft indigo gradient, product logo, no dark surfaces
- Errors: invalid credentials (generic message — never reveal which field failed), account locked, account inactive
- After 5 failed attempts → 15-minute lockout + email to Admin
- On first login → forced password change screen

**S-02 Forgot / Reset Password** — email token, 30-min expiry, single use, password strength meter.

**S-03 Change Password** (in profile) — old password required, cannot reuse last 3.

**S-04 Two-Factor Verification** *(recommended, optional per user)* — 6-digit TOTP.

---

### 7.2 Common shell

**Left sidebar (collapsible, 240px):** Dashboard · My Tasks · Tickets · Projects · Chat · Reports · Masters (Admin) · Settings

**Top bar:** Global search (ticket ID / keyword / person) · Project switcher · 🔔 Notification bell with unread badge · 💬 Chat badge · Avatar menu (Profile, Change password, Logout)

**Toast layer:** bottom-right stacked toasts for real-time events.

---

### 7.3 Dashboard module

**S-05 Dashboard** — role-aware. Every card and every chart segment is clickable and deep-links to a pre-filtered ticket list (`/tickets?status=OPEN&level=CRITICAL&assignee=me`).

```
┌───────────────────────────────────────────────────────────────────────────┐
│  Dashboard            [Project ▾] [Date range ▾] [Resource ▾]  ⟳ Refresh  │
├───────────────────────────────────────────────────────────────────────────┤
│ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐  │
│ │ TOTAL   │ │ OPEN /  │ │ CLOSED  │ │CRITICAL │ │ DELAYED │ │ REOPENED │  │
│ │  1,284  │ │ PENDING │ │  1,021  │ │   18    │ │   26    │ │    41    │  │
│ │ ▲ 12%   │ │   263   │ │  ▲ 8%   │ │  🔴     │ │  🟠     │ │  ⚠       │  │
│ └─────────┘ └─────────┘ └─────────┘ └─────────┘ └─────────┘ └──────────┘  │
├────────────────────────────────────┬──────────────────────────────────────┤
│  Task Type Distribution            │  Daily Task Status (last 30 days)    │
│  [ Donut chart — 11 types ]        │  [ Stacked area: Created/Closed/     │
│                                    │    Reopened ]                        │
├────────────────────────────────────┼──────────────────────────────────────┤
│  Resource Velocity (tickets/week)  │  Resource-wise Load                  │
│  [ Multi-line chart per resource ] │  [ Horizontal stacked bar:           │
│                                    │    Open / In Progress / Delayed ]    │
├────────────────────────────────────┼──────────────────────────────────────┤
│  Priority Split                    │  Ticket Aging Buckets                │
│  [ Bar: Low/Med/High/Critical ]    │  [ Bar: 0-2d / 3-5d / 6-10d / >10d ] │
├────────────────────────────────────┴──────────────────────────────────────┤
│  🔴 Critical & Delayed — needs attention today            [View all →]     │
│  TKT-000871  Payment gateway timeout   Ravi K.   -3d overdue   [Open]     │
│  TKT-000902  Login fails on Safari     Neha S.   -1d overdue   [Open]     │
└───────────────────────────────────────────────────────────────────────────┘
```

**Widgets (all drill-down enabled):**

| # | Widget | Chart type | Drill-down target |
|---|---|---|---|
| 1 | Total tasks created | KPI card + sparkline | All tickets, date-filtered |
| 2 | Pending / open tasks | KPI card | status ≠ closed |
| 3 | Closed tasks | KPI card | status = closed |
| 4 | Critical tasks | KPI card (red) | level = critical |
| 5 | Delayed tasks | KPI card (amber) | is_delayed = true |
| 6 | Reopened tasks | KPI card | reopen_count > 0 |
| 7 | Task type-wise grouping | Donut | filtered by type slice |
| 8 | Daily task status | Stacked area | that date's tickets |
| 9 | Resource velocity | Multi-line | that resource + week |
| 10 | Resource-wise task load | Horizontal stacked bar | resource + status segment |
| 11 | Priority distribution | Vertical bar | level |
| 12 | Aging buckets | Bar | age range |
| 13 | Date-wise report | Calendar heatmap | that day |
| 14 | SLA compliance gauge | Radial gauge | breached list |
| 15 | Project-wise distribution | Treemap | project |
| 16 | **Stage funnel (WIP by stage)** | Horizontal funnel | tickets currently in that stage |
| 17 | **Rework / ping-pong tickets** | KPI card + list | iteration_no ≥ 2 |
| 18 | **Avg time per stage** | Bar, split active vs idle | that stage's tickets |
| 19 | **Handoff latency** | Line trend | slowest handoffs |
| 20 | **Client-wise volume** | Horizontal bar | that client's tickets |

**Developer's dashboard** shows only widgets 1–6, 9, 12 scoped to `assignee = me`, plus "My due today / this week".

**S-06 Chart Drill-down Modal** — slides in from the right, shows the filtered grid, with "Open full list" and CSV export.

---

### 7.4 Master data module (Admin only)

**S-07 Resource Master — List**
Columns: Emp Code, Name, Email, Role, Department, Reporting Manager, Projects, Status, Last login, Actions
Filters: role, project, manager, status. Bulk actions: activate/deactivate, export, bulk import via CSV.

**S-08 Resource Master — Create / Edit**

| Section | Fields |
|---|---|
| Personal | Employee Code*, Full Name*, Email*, Mobile, Profile photo, Date of joining |
| Access | Username*, Temporary Password* (auto-generate + "force change on first login"), Role*, Status (Active/Inactive) |
| Org | Department, Designation, **Reporting Manager*** (searchable dropdown, self-reference blocked, cycle detection), Location, Time zone |
| Work | Daily capacity (hrs, default 8), Weekly off pattern, Skills/tags |
| Projects | Multi-select project assignment with per-project role |

Validations: unique username & email & emp code; reporting manager cannot create a loop (A→B→A); deactivating a resource with open tickets forces a bulk reassignment wizard.

**S-09 Role & Permission Master** — role name, description, and a permission checkbox matrix (module × create/read/update/delete/approve). System roles are non-deletable.

**S-10 Project Master — List / Create / Edit**
- Fields: Project Code* (used in ticket ID prefix), Project Name*, Client Name, Project Manager*, Start Date, Target End Date, Status (Active / On Hold / Closed), Description, Default SLA policy, Ticket ID prefix, Colour tag
- **Team tab:** add resources + project role (PM / Dev / Support / Viewer) + allocation %
- **SLA tab:** per task type × level → response hrs, resolution hrs, L1/L2 escalation targets
- **Settings tab:** allowed task types, mandatory fields, auto-assign rule (round-robin / least-loaded / manual)

**S-11 Task Type Master** — the 11 seeded types (Change Request, Production Bug, Client Request, Future Release, Internal Bug, Client Bug, Server Issue, Network Issue, Browser Issue, Performance Issue, Other) + icon, colour, default level, default SLA. Admin can add more.

**S-12 Priority / Level Master** — Low, Medium, High, Critical + colour + default SLA hours + escalation flag. Drives the priority dropdown on the ticket page (§4B.1); Admin can add further levels without a release.

**S-32 Client Master — List**
Columns: Client Code, Name, Account Manager, Support Plan, Projects, Open Tickets, Status, Last Ticket Date, Actions. Filters by status, support plan, account manager and project. Actions: **Import from Excel**, Export, Bulk activate/deactivate. Each row expands to show contacts inline.

**S-33 Client Master — Create / Edit** — the field groups in §4B.2, across four tabs: Identity · Commercial · Contacts (child grid with add/edit/remove, primary flag, notification opt-in) · Projects & SLA. Validation: unique client code, valid emails, at least one primary contact before the client can be selected on a ticket.

**S-34 Client Import Wizard (Excel)** — the five-step wizard in §4B.3: download template → upload → map columns → validate and preview a dry run → commit. Produces a downloadable error report for rejected rows and records an import batch so a bad import can be traced and reversed as a set. The same component is registered a second time for resource bulk import.

**S-13 Status, Stage & Workflow Template Master** — three tabs:
1. **Statuses** — status list, categories (To-do / In progress / Done), allowed-transition matrix per role.
2. **Stages** — the ribbon stages: sequence, code, display name, owner role, icon, stage SLA hours, optional/mandatory, and the list of stages this one may return to. Drag to reorder.
3. **Workflow templates** — named templates (Standard Dev Flow, Support Fast-Track, Infra Flow) built by picking stages, then mapped to project × task type. A live ribbon preview renders as the Admin edits, so the flow is validated visually before saving. Stages used by live tickets can only be deprecated, never deleted — otherwise historical ribbons would break.

**S-14 Working Calendar & Holiday Master** — org holidays, weekly off pattern, per-resource leave. Feeds SLA and utilisation maths.

**S-15 Notification Template Master** — event, channel (in-app / email / push), subject, body with merge tags `{{ticket_id}}`, `{{assignee}}`, recipients, on/off toggle.

**S-16 Audit Log Viewer** — every login, permission change, master change, and ticket action. Filter by user, module, date. Export only, never editable.

---

### 7.5 Ticket module

**S-17 Ticket List (All Tickets)**

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ Tickets   [+ New Ticket]        🔍 Search…   [Saved views ▾]  [⚙ Columns] ⬇   │
├───────────────────────────────────────────────────────────────────────────────┤
│ Project▾ Client▾ Module▾ Type▾ Level▾ Stage▾ Status▾ Assignee▾ Dates▾ ↺Reset  │
├──────────┬─────────────────────┬───────┬──────┬────────┬────────┬─────┬───────┤
│ ID       │ Description         │ Type  │Level │Assignee│ PCD    │ Eff │Status │
├──────────┼─────────────────────┼───────┼──────┼────────┼────────┼─────┼───────┤
│TKT-000871│ Payment gateway…    │Prod   │🔴Crit│ Ravi K │12 Aug ⚠│14.5 │In Prog│
│TKT-000902│ Login fails Safari  │Browser│🟠High│ Neha S │14 Aug  │ 3.0 │Assigned│
│TKT-000915│ Add export to CSV   │CR     │🔵Med │ Amit R │20 Aug  │ 0.0 │New    │
└──────────┴─────────────────────┴───────┴──────┴────────┴────────┴─────┴───────┘
        Rows 1–25 of 263        ‹ 1 2 3 … ›        [Export CSV] [Export PDF]
```

- **Compact ribbon column** — eight small dots per row (filled = done, ringed = current, hollow = pending, amber = reworked) so a manager can scan a whole grid and see exactly where every ticket sits without opening any of them. Hovering a dot names the stage and its owner.
- Row colour cue: delayed rows get a soft amber left border; critical get soft red.
- **Module filter and an optional Module column** — off by default in the column chooser, because the grid is already at its width budget, but filterable always. "Every open Fees ticket" is the question this list gets asked most once the field exists.
- **Saved views:** My Open, Due Today, Overdue, Unassigned, Reopened, Closed This Month.
- Bulk select → reassign / change level / close (PM & Admin only).
- Density toggle, column chooser, sticky header, infinite or paged scroll.

**S-18 My Tasks** (Developer's home) — same grid, hard-scoped to `assigned_to = me`, grouped by **Due Today / Overdue / This Week / Later**, with an inline ⚡ Quick Update button on every row. Optional Kanban toggle (drag between statuses).

**S-19 Create Ticket**

| Group | Field | Notes |
|---|---|---|
| Identity | **Ticket ID** | Auto-generated, read-only: `{PROJECT_CODE}-{YY}-{00001}` e.g. `CRM-26-00347`. Generated by a DB sequence per project — never by count(*), which breaks under concurrency. |
| Core | **Project*** | Drives assignee list, SLA, prefix |
| | **Client** | Searchable dropdown filtered to clients mapped to the project; shows code and logo initial. Mandatory for client-facing task types (§4B.2) |
| | **Client contact** | Dependent dropdown — the person who reported it. Inline "+ Add contact" so the desk never leaves the form |
| | **Title / Summary*** | 200 chars |
| | **Task Description*** | Rich text: bold, lists, code block, inline images |
| | **Task Type*** | 11-type dropdown with colour chips |
| | Sub-type / Category | Optional 2nd level |
| | **Level (Priority)*** | Colour-chip dropdown from the priority master. Pre-filled from the task type default; changing it recomputes and previews the planned close date before save |
| Where it happened | **Module*** | The product area the concern was raised against — Student, Admission, Fees, Examination, Attendance, Library, Inventory, Parent App. Sourced from the **module master**, not an enum, so a ninth module is a row somebody adds rather than a release. **Mandatory for bug-type task types**, optional for change requests and internal work — see the note below |
| | **Screen Name** | Free text, the screen it happened on: "Fee Receipt Print" |
| | **Feature** | Free text, the feature within that screen: "Reprint with duplicate watermark" |
| | **Steps to Generate** | Rich text — numbered steps and inline screenshots. What a developer needs in order to reproduce it without going back to ask |
| People | **Date Reported*** | Defaults to now, backdating allowed for Admin/PM only |
| | **Reported By*** | Defaults to logged-in user; Support Desk can pick client contact |
| | **Assigned To** | Filtered to project members; shows current open-load count next to each name so the assigner sees who is free |
| | **Assigned By** | Auto = current user, read-only |
| | Watchers | Multi-select; they get notifications too |
| Effort | **Estimated Effort (hrs)*** | Decimal |
| | **Planned Close Date*** | Auto-computed from SLA policy, editable with reason |
| | Actual Close Date | Read-only until closure |
| Extra | **Attachments** | Drag-drop, file picker, **or paste a screenshot straight from the clipboard**. Images preview as thumbnails. 10 MB per file, virus-scanned, EXIF stripped (§4B.4) |
| | **First comment** | Optional opening note, posted as the ticket's first comment with an internal / client-visible toggle |
| | Environment | Prod / UAT / Dev — critical for bug triage |
| | Client / Requester | For client-raised tickets |
| | Linked tickets | Blocks / is blocked by / duplicate of / relates to |
| | Tags | Free-form labels |

Actions: **Save & Assign** · Save as Draft · Save & Create Another · Cancel.
On save → ID generated → 🔔 popup to assignee → history entry `CREATED` → email.

**On the four "where it happened" fields.** They exist so that "which module generates the most concerns" is a query rather than a reading exercise, and so that a developer opening a bug is not starting from a one-line title. Three rules follow from that:

- **Module is a master table, never an enum.** The eight values ship as seed data; an enum would make the ninth one a schema migration and a deployment.
- **Every column is nullable.** Tickets raised before these fields existed have no honest value, and back-filling one is worse than leaving it empty. Mandatoriness is a rule of the *form*, not of the column — exactly as Task Description already is.
- **Mandatory only where the answer is real.** A Production Bug without a module is a bug nobody can route. A change request may genuinely span three modules, and forcing a choice there just teaches people to pick the first item in the list, which poisons the very reporting the field exists for. Save as Draft waives it either way.

**S-20 Ticket Detail — the most important screen**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ ← Back    CRM-26-00347   🔴 CRITICAL   ⚠ DELAYED BY 3 DAYS                   │
│ Payment gateway timeout on checkout                                          │
│ [⚡Quick Update] [Reassign] [Ask Status 💬] [Reopen ☐] [Close] [⋯]           │
├─────────────────────────────────────────────────────────────────────────────┤
│ WORKFLOW RIBBON       [Cycle 1] [Cycle 2 ●]     Iteration 2    ↺ 1 rework    │
│                                                                             │
│ ✔Intake ─ ✔Triage ─ ✔Dev ↺×2 ─ ●QA(NOW) ─ ○Deploy ─ ○Verify ─ ○Signoff ─ ○  │
│  Priya     Meera     Ravi K.     Anil S.     —         —         —          │
│  0.5 h     1.0 h     14.5 h      3.5 h                                      │
│                          ▲ current stage · 6h 40m elapsed · stage SLA 8h    │
│                          [ Pass to Deployment ]  [ Fail — send for rework ] │
├──────────────────────────────────────────┬──────────────────────────────────┤
│ ▸ DETAILS                                │  ▸ SUMMARY PANEL                 │
│ Description (rich text) …                │  Project     CRM Portal          │
│ 📎 [thumb][thumb] error-log.txt   +2     │  Client      Acme Retail Ltd     │
│ ┌ Add a comment…  [Internal ▾] [📎] ┐    │  Contact     R. Menon            │
│ └──────────────────────── [ Post ] ┘     │  Type        Production Bug      │
│                                          │  Level       Critical (was High) │
│ ┌── Tabs ──────────────────────────────┐ │  Status      In Progress         │
│ │Journey│History│Comments│Effort│Chat│ │  Reported By Support Desk        │
│ └──────────────────────────────────────┘ │  Date Rep.   05 Aug 2026         │
│                                          │  Assigned To Ravi Kumar          │
│  CYCLE HISTORY (read-only)               │  Assigned By Meera P. (PM)       │
│ ┌────┬────────┬──────┬────────┬────────┐ │  Est. Effort 8.0 h               │
│ │Cyc │Assignee│Start │ PCD    │ Closed │ │  Logged      14.5 h  (+81% ⚠)    │
│ ├────┼────────┼──────┼────────┼────────┤ │  Planned Cl. 12 Aug 2026 ⚠       │
│ │ 1  │Ravi K. │01Aug │ 05 Aug │ 05 Aug │ │  Actual Cl.  —                   │
│ │    │ Effort: 6.0 h   Result: Closed  │ │  Reopen ×    1                   │
│ ├────┼────────┼──────┼────────┼────────┤ │  Total Effort 14.5 h             │
│ │ 2  │Ravi K. │08Aug │ 12 Aug │   —    │ │  Age         27 d                │
│ │    │ Effort: 8.5 h   Reopen reason:  │ │  Watchers    Meera, Anil         │
│ │    │ "Issue recurring in prod"       │ │  Linked      CRM-26-00291        │
│ └────┴────────┴──────┴────────┴────────┘ │                                  │
│         ▸ TOTAL EFFORT ACROSS CYCLES: 14.5 h                                 │
└──────────────────────────────────────────┴──────────────────────────────────┘
```

- **Journey tab** — the stage-by-stage roll-up grid from §4A.4: every hop, iteration number, resource, role, in/out timestamps, duration, active effort, idle time and handoff note, followed by the per-resource and grand totals. Clicking any ribbon segment filters this grid to that stage.
- **History tab** — cycle-grouped grid exactly as above, expandable to show every field change: *"Level changed High → Critical by System (SLA breach) on 13 Aug 10:15"* and every handoff: *"Handed off Development → QA by Ravi K. to Anil S. on 03 Aug 14:20"*. No edit or delete icons exist for anyone.
- **Comments tab** — the full comment stream with author, role, stage and iteration stamps, internal versus client-visible styling, @mentions, attachments and the 5-minute edit window. The comment box itself is always visible above the tabs so posting never costs a click.
- **Attachments tab** — gallery of every file on the ticket: image thumbnails with a lightbox, documents as rows with size and uploader, filterable by client-visible, grouped by cycle and stage.
- **Effort tab** — every log line: date, resource, hours, description, cycle no. Sum per cycle + grand total.
- **Chat tab** — threaded ticket conversation (§7.6).
- **Activity tab** — full audit stream including attachments and watcher changes.
- **Priority is inline-editable** from the summary panel by Admin, PM and Support Desk — one click on the chip opens the dropdown, a reason is required once the ticket is assigned, and the change writes a history row and recomputes the planned close date.
- **Where it happened** — Module, Screen Name and Feature sit in the summary panel directly under Type; **Steps to Generate** renders below the description in the Details pane, where the person about to reproduce the bug is already looking. All four are inline-editable by the roles that may edit the description, and every change writes a `FIELD_CHANGED` history row with the old and new value like any other field.
- **Traceability:** every entity in the panel is a link — assignee → resource profile, project → project dashboard, **client → client 360 view**, linked ticket → that ticket, cycle → that cycle's effort logs. Breadcrumbs everywhere.

**S-21 Quick Update Panel** (slide-over, opens from any list row — this is the resource's daily driver)

```
┌───── Quick Update · CRM-26-00347 ──────┐
│ Status      [ In Progress      ▾ ]     │
│ Log Effort  [ 2.5 ] hrs on [06 Aug]    │
│ Work note   [ Fixed retry logic… ]     │
│ % Complete  [———●———] 60%              │
│ Revised ETA [ 14 Aug 2026 ]  (reason*) │
│ 📎 Attach                              │
│           [ Cancel ]  [ Update ✓ ]     │
└────────────────────────────────────────┘
```
Two clicks, no page reload, closes with a toast. Effort logged here is automatically stamped with the **current stage and iteration**, so it lands in the correct row of the journey grid without the resource doing anything. Fields the resource **cannot** touch here: Ticket ID, Reported By, Assigned By, Date Reported, cycle history, the ribbon and its stage history, prior effort logs, level (unless PM), project.

**S-29 Handoff Dialog** — opened from the ribbon's action button by the current stage owner.
- Fields: Next stage (pre-filled from the template, PM may override), **Assign to*** (filtered to the receiving role's project members, each showing current open load), Handoff note, **Confirm effort for the stage being left***, Attachments (test evidence, deployment log).
- Backward moves (Fail / Deploy failed / Reject at sign-off) additionally require **Reason*** and a defect list.
- On submit: seals the current transition row (`exited_at`, `duration_mins`), inserts the next one, increments `iteration_no` if backward, notifies the receiving owner, and advances the ribbon live over WebSocket for anyone viewing the ticket.

**S-30 Workflow Template Designer** — the visual builder inside S-13: drag stages onto a canvas, set owner role and SLA per stage, draw the allowed return paths, preview the rendered ribbon, then map it to project × task type.

**S-31 Stage Queue / Team Inbox** — each team's own worklist: "Waiting in QA", "Waiting in Deployment", sorted by time-in-stage descending so the oldest queued item is always on top. This is the landing page for QA and Deployment resources, the way My Tasks is for Developers.

**S-22 Reopen Dialog** — reopen checkbox opens a modal requiring: reopen reason*, restart stage (defaults to Triage), new assignee (defaults to previous), new planned close date*, revised estimated effort. Warning banner: *"Cycle 1 and its ribbon will be sealed and preserved. A new cycle with a fresh ribbon will begin."*

**S-23 Close / Resolve Dialog** — resolution summary*, root cause category, actual close date (defaults now), final effort confirmation, optional "request client verification".

**S-24 Bulk Reassignment Wizard** — used when a resource leaves or goes on leave; pick source resource → select tickets → target resource → reason → confirm. Each move writes its own history entry.

---

### 7.6 Chat module

**S-25 Chat** — three chat surfaces, one engine:

1. **Ticket thread** (inside ticket detail) — everything said stays attached to the ticket forever.
2. **Direct message** — manager ↔ resource 1:1.
3. **Project channel** — team-wide.

Features: real-time via WebSocket, typing indicator, read receipts, @mentions (fires a notification), file/image share, emoji, message search, unread counts, link preview of any `TKT-xxxx` mention into a rich ticket card.

**"Ask Status" button** — on any ticket, a Reporting Manager/PM clicks it and EduTrack posts a structured message into that ticket's thread:

> **📌 Status requested by Meera P.** — *"Please share the current status and expected closure."*
> `[ Reply with update ]` `[ Open Quick Update ]`

The resource's reply is timestamped, and **manager response time** becomes a reportable metric. Status requests appear as a distinct badge on the ticket and in the manager's "Awaiting response" list. Chat messages are immutable after 5 minutes (edit window), and deleted messages leave a tombstone — this keeps chat admissible as project evidence.

---

### 7.7 Notification module

**S-26 Notification Centre** — bell dropdown (last 10) + full page with tabs: All / Mentions / Assignments / Escalations / Status requests. Mark read, mark all read, click-through to source. Per-user preference matrix: which events, which channel.

**Mail is the guaranteed channel.** In-app popups only reach someone who is logged in; the mail alert engine (§4B.6) is what makes an assignment impossible to miss. Assignment, handoff, escalation and breach mails ignore user preferences and always send; everything else is opt-out or digestible.

**Delivery mechanics:**
- **In-app popup/toast** — WebSocket push, appears within ~1 second of assignment, with Open / Snooze / Dismiss.
- **Bell badge** — persistent count.
- **Email** — immediate for assignment/escalation, digest option for the rest.
- **Browser push** — Web Push API for users who opt in.
- **Optional** — Teams / Slack / WhatsApp Business webhook.

If the user is offline when the event fires, the notification is queued in the DB and pops the moment they log in — nothing is lost.

---

### 7.8 Reports module

**S-27 Reports Hub** — a card grid of reports; each opens a parameterised viewer with filters (date range, project, resource, type, level), chart + table, and export to Excel/CSV/PDF. All reports schedulable by email (daily/weekly/monthly).

| Report | Key columns / visual |
|---|---|
| **Resource Performance Scorecard** | Assigned, Closed, Closed on time, SLA %, Avg cycle time, Total effort, Est vs Actual variance, Reopen rate, Utilisation %, Trend arrows |
| **Resource Velocity** | Tickets & effort-hours closed per week/sprint, 4-week rolling avg, multi-resource comparison line chart |
| **Effort Summary** | Effort by resource × project × task type; pivot-style with drill-down to individual logs |
| **Delayed / SLA Breach** | Every breach, days overdue, escalation level, reason |
| **Task Type Analysis** | Volume + avg resolution time per type — reveals if e.g. Server Issues eat the team |
| **Reopen Analysis** | Reopen count by resource/project/type — a quality signal |
| **Date-wise Report** | Created vs Closed vs Reopened per day, with net backlog line |
| **Project Health** | Open/closed, backlog trend, burn-down, critical count, team load |
| **Aging Report** | Open tickets bucketed by age, oldest-first |
| **Workload / Capacity** | Assigned hours vs available hours per resource — spot overload before it happens |
| **Stage Funnel / WIP** | Live count per ribbon stage, bottleneck highlighted |
| **Stage Cycle Time** | Avg time per stage, split into active effort vs idle queue time |
| **Rework Analysis** | Rework rate by developer, QA rejection rate, first-time-right % |
| **Deployment Report** | Deployments per week, success vs rollback, avg deploy duration |
| **Resource Contribution** | The §4A.4 per-resource-per-stage roll-up across any ticket set |
| **Audit / Compliance** | Full immutable trail export for a ticket or date range, including every handoff |
| **Client Report** | Volume, open versus closed, SLA compliance, avg resolution time and satisfaction per client; drills into the client 360 view |
| **Email Delivery Log** | Every alert sent per ticket with status — proof that the assignee was notified, and a fast way to spot bounced addresses |

**S-28 Resource 360° Profile** — one resource's full picture: current open tickets, complete historical ticket list, effort timeline, velocity chart, performance scorecard, all reachable by a Reporting Manager in one click from anywhere their name appears.

---

## 8. Data Model

### 8.1 Entity relationships

```
users ──┬──< user_roles >── roles ──< role_permissions >── permissions
        │
        ├──< project_members >── projects ──< sla_policies
        │                            │
        │                            └──< tickets ──┬──< ticket_cycles
        │                                           ├──< ticket_history      (append-only)
        │                                           ├──< ticket_effort_logs  (append-only)
        │                                           ├──< ticket_comments
        │                                           ├──< ticket_attachments
        │                                           ├──< ticket_watchers
        │                                           └──< ticket_links
        │
        ├──< notifications
        ├──< chat_participants >── chat_threads ──< chat_messages
        ├──< audit_logs
        └──< resource_leaves          masters: task_types, priorities,
                                      statuses, workflow_transitions,
                                      holidays, notification_templates
```

### 8.2 Key tables (PostgreSQL)

```sql
CREATE TABLE users (
  id              BIGSERIAL PRIMARY KEY,
  emp_code        VARCHAR(20)  UNIQUE NOT NULL,
  username        VARCHAR(50)  UNIQUE NOT NULL,
  email           VARCHAR(150) UNIQUE NOT NULL,
  password_hash   VARCHAR(255) NOT NULL,          -- Argon2id
  full_name       VARCHAR(120) NOT NULL,
  mobile          VARCHAR(20),
  role_id         INT REFERENCES roles(id),
  reporting_manager_id BIGINT REFERENCES users(id),
  department      VARCHAR(80),
  designation     VARCHAR(80),
  daily_capacity_hrs NUMERIC(4,2) DEFAULT 8,
  timezone        VARCHAR(50) DEFAULT 'Asia/Kolkata',
  is_active       BOOLEAN DEFAULT TRUE,
  must_change_password BOOLEAN DEFAULT TRUE,
  failed_attempts SMALLINT DEFAULT 0,
  locked_until    TIMESTAMPTZ,
  last_login_at   TIMESTAMPTZ,
  created_at      TIMESTAMPTZ DEFAULT NOW(),
  CHECK (id <> reporting_manager_id)
);

CREATE TABLE projects (
  id            BIGSERIAL PRIMARY KEY,
  project_code  VARCHAR(10) UNIQUE NOT NULL,      -- ticket-ID prefix
  name          VARCHAR(150) NOT NULL,
  client_name   VARCHAR(150),
  manager_id    BIGINT REFERENCES users(id),
  start_date    DATE, target_end_date DATE,
  status        VARCHAR(20) DEFAULT 'ACTIVE',
  colour_tag    VARCHAR(7),
  ticket_seq    BIGINT DEFAULT 0                  -- per-project counter
);

-- The product areas a concern can be raised against (§7.5). A master table
-- rather than an enum: the ninth module must be a row somebody adds, not a
-- migration and a deployment. Named product_modules because "module" already
-- means an area of the application in §7 and in the role-permission matrix,
-- and one word with two meanings in one schema is a defect in waiting.
CREATE TABLE product_modules (
  id          INT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  code        VARCHAR(40) UNIQUE NOT NULL,   -- STUDENT, ADMISSION, FEES, …
  name        VARCHAR(80) NOT NULL,
  seq         SMALLINT DEFAULT 0,
  is_active   BOOLEAN DEFAULT TRUE,
  created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE tickets (
  id                    BIGSERIAL PRIMARY KEY,
  ticket_code           VARCHAR(30) UNIQUE NOT NULL,   -- CRM-26-00347
  project_id            BIGINT NOT NULL REFERENCES projects(id),
  title                 VARCHAR(200) NOT NULL,
  description           TEXT,
  task_type_id          INT  REFERENCES task_types(id),
  -- Where it happened (§7.5). All four nullable: tickets raised before these
  -- fields existed have no honest value, and Module is mandatory on the form
  -- for bug-type task types, which is a rule of the form and not of the column.
  module_id             INT  REFERENCES product_modules(id),
  screen_name           VARCHAR(120),
  feature               VARCHAR(120),
  steps_to_generate     TEXT,                          -- sanitised rich text
  level                 VARCHAR(10) NOT NULL,          -- LOW|MEDIUM|HIGH|CRITICAL
  original_level        VARCHAR(10) NOT NULL,
  status                VARCHAR(20) NOT NULL DEFAULT 'NEW',
  environment           VARCHAR(20),
  date_reported         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reported_by           BIGINT REFERENCES users(id),
  assigned_to           BIGINT REFERENCES users(id),
  assigned_by           BIGINT REFERENCES users(id),
  estimated_effort_hrs  NUMERIC(6,2),
  total_effort_hrs      NUMERIC(8,2) DEFAULT 0,        -- Σ all cycles
  planned_close_date    TIMESTAMPTZ,
  actual_close_date     TIMESTAMPTZ,
  is_reopened           BOOLEAN DEFAULT FALSE,
  reopen_count          SMALLINT DEFAULT 0,
  current_cycle_no      SMALLINT DEFAULT 1,
  is_delayed            BOOLEAN DEFAULT FALSE,
  delayed_since         TIMESTAMPTZ,
  created_at            TIMESTAMPTZ DEFAULT NOW(),
  updated_at            TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX ix_tickets_assignee_status ON tickets(assigned_to, status);
CREATE INDEX ix_tickets_project_status  ON tickets(project_id, status);
CREATE INDEX ix_tickets_module          ON tickets(module_id);
CREATE INDEX ix_tickets_pcd_open        ON tickets(planned_close_date)
       WHERE actual_close_date IS NULL;

CREATE TABLE ticket_cycles (
  id                  BIGSERIAL PRIMARY KEY,
  ticket_id           BIGINT NOT NULL REFERENCES tickets(id),
  cycle_no            SMALLINT NOT NULL,
  start_date          TIMESTAMPTZ NOT NULL,
  assigned_to         BIGINT REFERENCES users(id),
  assigned_by         BIGINT REFERENCES users(id),
  level               VARCHAR(10),
  planned_close_date  TIMESTAMPTZ,
  actual_close_date   TIMESTAMPTZ,
  effort_hrs          NUMERIC(8,2) DEFAULT 0,
  reopen_reason       TEXT,
  resolution_summary  TEXT,
  is_sealed           BOOLEAN DEFAULT FALSE,
  UNIQUE (ticket_id, cycle_no)
);

CREATE TABLE ticket_effort_logs (            -- APPEND ONLY
  id           BIGSERIAL PRIMARY KEY,
  ticket_id    BIGINT NOT NULL REFERENCES tickets(id),
  cycle_no     SMALLINT NOT NULL,
  user_id      BIGINT NOT NULL REFERENCES users(id),
  work_date    DATE NOT NULL,
  hours        NUMERIC(5,2) NOT NULL CHECK (hours > 0 AND hours <= 24),
  note         TEXT,
  logged_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE ticket_history (                -- APPEND ONLY + HASH CHAINED
  id            BIGSERIAL PRIMARY KEY,
  ticket_id     BIGINT NOT NULL REFERENCES tickets(id),
  cycle_no      SMALLINT,
  event_type    VARCHAR(40) NOT NULL,        -- CREATED|ASSIGNED|STATUS_CHANGED|…
  field_name    VARCHAR(60),
  old_value     TEXT,
  new_value     TEXT,
  actor_id      BIGINT REFERENCES users(id), -- NULL = SYSTEM
  actor_type    VARCHAR(10) DEFAULT 'USER',
  remarks       TEXT,
  prev_hash     CHAR(64),
  row_hash      CHAR(64),
  created_at    TIMESTAMPTZ DEFAULT NOW()
);

CREATE OR REPLACE FUNCTION block_mutation() RETURNS TRIGGER AS $$
BEGIN RAISE EXCEPTION 'Immutable table: % rows cannot be modified', TG_TABLE_NAME;
END; $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_hist_immutable BEFORE UPDATE OR DELETE ON ticket_history
  FOR EACH ROW EXECUTE FUNCTION block_mutation();
CREATE TRIGGER trg_effort_immutable BEFORE UPDATE OR DELETE ON ticket_effort_logs
  FOR EACH ROW EXECUTE FUNCTION block_mutation();
```

**Ticket ID generation (concurrency-safe):**
```sql
UPDATE projects SET ticket_seq = ticket_seq + 1
WHERE id = :project_id RETURNING project_code, ticket_seq;
-- → 'CRM' + '-26-' + LPAD(seq::text, 5, '0')
```

---

## 9. Technical Architecture

### 9.1 Recommended stack

| Layer | Choice | Why |
|---|---|---|
| Frontend | **React 18 + TypeScript + Vite** | Ecosystem, hiring pool |
| UI kit | **Tailwind CSS + shadcn/ui** | Fast, clean, fully light-themeable |
| Charts | **Recharts** (or ApexCharts) | Click events on every segment → drill-down |
| State/data | TanStack Query + Zustand | Server cache + light client state |
| Forms | React Hook Form + Zod | Schema validation shared with backend |
| Backend | **Node.js + NestJS (TypeScript)** | Modular, DI, guards map perfectly to RBAC. *Alternatives: .NET 8 Web API or Spring Boot — pick by team skill.* |
| ORM | Prisma or TypeORM | Migrations, type safety |
| Database | **PostgreSQL 16** | Triggers, partial indexes, JSONB, window functions for velocity |
| Cache/session | **Redis** | Token blacklist, rate limits, dashboard cache, socket adapter |
| Real-time | **Socket.IO** (WebSocket) | Notifications, chat, live dashboard |
| Queue/scheduler | **BullMQ** on Redis | SLA scanner, emails, digests, report generation |
| File storage | S3 / MinIO | Attachments, avatars |
| Search | PostgreSQL full-text (v1) → OpenSearch (scale) | Global search |
| Email | SMTP via SendGrid/SES | Templated notifications |
| Auth | JWT + refresh rotation | See §10 |
| Deploy | Docker Compose → Kubernetes | Nginx reverse proxy, TLS |
| Observability | Sentry + Winston/pino + Prometheus/Grafana | Errors, logs, metrics |
| CI/CD | GitHub Actions / GitLab CI | Lint → test → build → migrate → deploy |

### 9.2 System diagram

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   Browser    │   │  Mobile PWA  │   │ Email inbox  │
│  React SPA   │   │              │   │ (ticket in)  │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       │ HTTPS/REST + WSS │                  │ IMAP poll
       └──────────┬───────┘                  │
                  ▼                          │
        ┌────────────────────┐               │
        │   Nginx / ALB      │  TLS, gzip, rate limit
        └─────────┬──────────┘               │
                  ▼                          │
   ┌──────────────────────────────┐          │
   │      API GATEWAY LAYER       │◀─────────┘
   │  Auth guard · RBAC guard     │
   │  Row-scope guard · Validator │
   └──────────────┬───────────────┘
                  ▼
   ┌──────────────────────────────────────────────────────┐
   │                 SERVICE LAYER                        │
   │ Auth │ User │ Project │ Ticket │ Cycle │ Effort      │
   │ Notification │ Chat │ Dashboard │ Report │ Audit     │
   └───┬───────────┬──────────────┬───────────┬───────────┘
       ▼           ▼              ▼           ▼
 ┌──────────┐ ┌────────┐  ┌──────────────┐ ┌────────────┐
 │PostgreSQL│ │ Redis  │  │ Socket.IO    │ │ S3 / MinIO │
 │ (primary │ │ cache  │  │ gateway      │ │ files      │
 │ + replica│ │ pubsub │  │ (rooms/user) │ └────────────┘
 └──────────┘ └────────┘  └──────────────┘
       ▲
       │
 ┌─────┴──────────────────────────────────┐
 │  WORKER / SCHEDULER (BullMQ)           │
 │  • SLA & delay scanner (every 15 min)  │
 │  • Escalation dispatcher                │
 │  • Email / digest sender                │
 │  • Dashboard aggregate refresh (5 min)  │
 │  • Nightly history hash verification    │
 │  • Scheduled report generation          │
 └────────────────────────────────────────┘
```

### 9.3 Real-time channels

| Socket room | Members | Events |
|---|---|---|
| `user:{id}` | that user only | `ticket.assigned`, `ticket.escalated`, `chat.message`, `status.requested` |
| `ticket:{id}` | viewers of that ticket | `ticket.updated`, `comment.added`, `typing`, `stage.changed` (ribbon advances live) |
| `stage:{code}:{projectId}` | that team's queue | `stage.arrived`, `stage.left` — team inbox updates without refresh |
| `project:{id}` | project team | `ticket.created`, `dashboard.refresh` |
| `manager:{id}` | reporting manager | `sla.breached`, `reportee.updated` |

### 9.4 Performance notes

- Dashboard reads come from **pre-aggregated summary tables** (`daily_ticket_stats`, `resource_daily_stats`) refreshed by a worker every 5 minutes — never live `COUNT(*)` over the full ticket table.
- Partial index on open tickets makes the SLA scan O(breaches), not O(all tickets).
- Cursor pagination on lists; virtualised grid rendering beyond 200 rows.
- Ticket detail loads in one aggregated endpoint (`/tickets/:id/full`) to avoid a waterfall of 6 calls.

---

## 10. Authentication & Authorization

### 10.1 Login flow

```
User submits username + password
   → rate-limit check (Redis: 10 attempts / 15 min / IP)
   → fetch user; if inactive or locked_until > now → reject
   → Argon2id verify (constant-time)
      ✗ → failed_attempts++, lock at 5, generic error, audit log
      ✓ → reset counter
   → must_change_password? → force reset screen
   → 2FA enabled? → TOTP challenge
   → issue Access Token  (JWT, 15 min, in memory)
     issue Refresh Token (opaque, 7 days, HttpOnly + Secure + SameSite=Strict cookie)
   → store refresh jti in Redis (device-bound)
   → audit: LOGIN_SUCCESS (ip, user-agent)
   → redirect by role: Admin/PM → Dashboard · Developer → My Tasks · Support → Ticket Queue
```

**Access token claims:** `sub`, `role`, `permissions[]`, `projects[]`, `reportees[]`, `iat`, `exp`, `jti`.

**Refresh rotation:** each refresh issues a new token and invalidates the old jti. Re-use of a consumed refresh token ⇒ token theft ⇒ revoke the whole family and force re-login.

**Logout:** delete refresh jti, add access jti to a short-lived Redis blacklist. Idle timeout 30 min, absolute session 12 h.

### 10.2 Authorization — three guards, executed in order

```ts
@UseGuards(JwtAuthGuard, RolesGuard, DataScopeGuard)
@Roles('ADMIN', 'PM', 'SUPPORT', 'DEVELOPER')
@Get('/tickets')
findAll(@Req() req) { ... }
```

1. **JwtAuthGuard** — is the token valid and unrevoked?
2. **RolesGuard** — does the role hold the required permission (`ticket.read`, `ticket.assign`, `master.write`)?
3. **DataScopeGuard** — rewrites the query with a mandatory WHERE clause:

```ts
switch (user.role) {
  case 'ADMIN':     return {};                                    // all
  case 'PM':        return { projectId: { in: user.projects } };
  case 'SUPPORT':   return { projectId: { in: user.projects } };
  case 'DEVELOPER': return { assignedTo: user.id };                // hard scope
}
```

Same guard applies to `/tickets/:id` — a Developer guessing another ticket's ID gets **404**, not 403 (no information leak about existence).

### 10.3 Password & account policy

Min 8 chars with upper + lower + digit + symbol · Argon2id (memory 64 MB, iterations 3) · no reuse of last 3 · optional 90-day expiry · reset tokens single-use, 30-min TTL, hashed at rest · admin-triggered force logout · optional SSO (SAML/OIDC with Azure AD or Google Workspace) for phase 3.

### 10.4 Other security controls

CSRF token on cookie-based routes · Helmet security headers + strict CSP · input validation on every DTO (Zod/class-validator) · parameterised queries only · file upload: extension allow-list, MIME sniffing, size cap, AV scan, served via signed URLs · full audit log of auth and master-data events · TLS 1.3 everywhere · encryption at rest for DB and object storage · rate limiting per user and per IP · secrets in a vault, never in code.

---

## 11. Notification Matrix

| Event | In-app popup | Bell | Email | To |
|---|:--:|:--:|:--:|---|
| Ticket assigned to you | ✅ | ✅ | ✅ | Assignee |
| **Handoff received (ribbon moves to you)** | ✅ | ✅ | ✅ | New stage owner |
| **QA failed — sent for rework** | ✅ | ✅ | ✅ | Developer, PM |
| **Deployment done — please verify** | ✅ | ✅ | ✅ | Developer |
| **Deployment failed** | ✅ | ✅ | ✅ | Developer, PM, RM |
| **Stuck in stage past stage SLA** | ✅ | ✅ | ✅ | Stage owner, PM, RM |
| **Iteration count reaches 3** | — | ✅ | ✅ | PM, RM |
| Ticket reassigned away | — | ✅ | — | Previous assignee |
| Ticket reopened | ✅ | ✅ | ✅ | New assignee, PM |
| Level changed to Critical | ✅ | ✅ | ✅ | Assignee, RM, PM |
| **SLA breached / delayed** | ✅ | ✅ | ✅ | **Reporting Manager**, PM, Assignee |
| 80% SLA elapsed | — | ✅ | ✅ | Assignee |
| Status requested by manager | ✅ | ✅ | ✅ | Assignee |
| Reply to status request | ✅ | ✅ | — | Manager |
| @mention in chat/comment | ✅ | ✅ | ✅ | Mentioned user |
| Ticket closed | — | ✅ | ✅ | Reporter, watchers |
| Comment added | — | ✅ | ✅ | Assignee, watchers |
| Comment marked client-visible | — | ✅ | ✅ | Client contact |
| Attachment added | — | ✅ | opt | Assignee, watchers |
| Priority changed | ✅ | ✅ | ✅ | Assignee, PM |
| New unassigned ticket | — | ✅ | ✅ | PM, Support Desk |
| Daily digest (8:30 AM) | — | — | ✅ | All (opt-out) |
| Weekly manager summary | — | — | ✅ | RM, PM |

---

## 12. UI / UX Design System — Light Theme Only

### 12.1 Colour tokens

| Token | Hex | Use |
|---|---|---|
| `--bg-app` | `#F7F8FC` | Page background |
| `--bg-surface` | `#FFFFFF` | Cards, tables, modals |
| `--bg-subtle` | `#F1F3F9` | Table header, hover |
| `--border` | `#E5E8F0` | 1px dividers |
| `--primary` | `#4F46E5` | Buttons, active nav, links |
| `--primary-soft` | `#EEF2FF` | Selected rows, chips |
| `--text-primary` | `#111827` | Headings, body |
| `--text-secondary` | `#6B7280` | Labels, meta |
| `--success` | `#10B981` | Closed, on-time |
| `--warning` | `#F59E0B` | Delayed, High |
| `--danger` | `#EF4444` | Critical, breach |
| `--info` | `#3B82F6` | In progress, Medium |

**Level chips:** Low `#10B981` · Medium `#3B82F6` · High `#F59E0B` · Critical `#EF4444` — always soft-tinted background with a solid text colour, never a heavy solid block.

**Chart palette (colour-blind safe):** `#4F46E5 #06B6D4 #10B981 #F59E0B #EF4444 #8B5CF6 #EC4899 #14B8A6`

**Ribbon segment states:** done `#10B981` tick on `#ECFDF5` · current `#4F46E5` on `#EEF2FF` with a soft pulse ring · pending `#9CA3AF` on `#FFFFFF` with a dashed connector · reworked `#F59E0B` left edge + `↺ ×N` chip · skipped dashed outline with strikethrough label · breached stage SLA `#EF4444`. Never colour alone — every state also carries an icon and a text label, so it stays readable for colour-blind users and in print.

### 12.2 Foundations

- **Type:** Inter or Plus Jakarta Sans. Base 14px / 20px. H1 24px semibold, H2 20px, H3 16px, caption 12px.
- **Spacing:** 4px scale (4/8/12/16/24/32). Card padding 20px, page gutter 24px.
- **Radius:** 12px cards, 8px inputs & buttons, 999px chips.
- **Shadow:** `0 1px 2px rgba(16,24,40,.05)` resting; `0 8px 24px rgba(16,24,40,.10)` on modals. No heavy borders.
- **Motion:** 150–200 ms ease-out; slide-over panels from the right; toasts fade+rise.
- **Empty states:** friendly line illustration + one-line copy + primary action.
- **Skeleton loaders**, never spinners, on tables and charts.
- **Accessibility:** WCAG AA contrast (all tokens above pass on white), full keyboard navigation, focus rings, ARIA labels on charts, `prefers-reduced-motion` respected.
- **Responsive:** ≥1280px full layout · 768–1279px collapsed sidebar · <768px card list instead of tables, bottom tab bar.

### 12.3 Micro-interactions that make it feel "trendy"

The ribbon animates the connector fill left-to-right when a handoff lands, and the arriving segment lifts briefly — a one-second cue that tells the whole team the ticket moved. Beyond that: sticky table headers with subtle shadow on scroll · inline row hover actions · command palette on `Ctrl+K` for jump-to-ticket · optimistic UI on quick update · animated count-up on KPI cards · chart tooltips with a "View tickets →" link · avatar stacks for watchers · left border colour bar on delayed/critical rows · toast with an Undo action for reassignment.

---

## 13. API Surface (representative)

```
POST   /auth/login                      POST /auth/refresh    POST /auth/logout
POST   /auth/forgot-password            POST /auth/reset-password
GET    /me                              PATCH /me/password

GET    /users            POST /users            PATCH /users/:id
PATCH  /users/:id/status                GET  /users/:id/profile-360
GET    /users/:id/reportees             POST /users/bulk-import

GET    /projects         POST /projects         PATCH /projects/:id
POST   /projects/:id/members            DELETE /projects/:id/members/:userId
GET/PUT /projects/:id/sla-policies

GET    /tickets                         # DataScopeGuard applied
POST   /tickets
GET    /tickets/:id/full                # detail + cycles + history + effort
PATCH  /tickets/:id                     # field updates (role-checked)
POST   /tickets/:id/assign
POST   /tickets/:id/quick-update        # status + effort + note in one call
POST   /tickets/:id/effort              # append-only
POST   /tickets/:id/resolve
POST   /tickets/:id/close
POST   /tickets/:id/reopen              # seals cycle N, opens N+1
GET    /tickets/:id/history             # GET only — no PUT/DELETE routes exist
GET    /tickets/:id/effort-logs         # GET only
POST   /tickets/:id/handoff             # next stage + assignee + note; seals current row
POST   /tickets/:id/rework              # backward move; reason mandatory; iteration++
POST   /tickets/:id/skip-stage          # PM/Admin only; reason mandatory
GET    /tickets/:id/ribbon?cycle=2      # segments, current stage, iteration, effort per stage
GET    /tickets/:id/journey              # per-stage per-resource effort roll-up
GET    /stages/queue?stage=QA           # team inbox, sorted by time-in-stage
GET/POST /masters/workflow-templates    # stages, owner roles, SLA, return paths

GET    /clients                         # search, filter, paginate
POST   /clients          PATCH /clients/:id      PATCH /clients/:id/status
GET    /clients/:id/contacts            POST /clients/:id/contacts
GET    /clients/:id/tickets             # client 360 view
GET    /clients/import/template         # download the .xlsx template
POST   /clients/import/upload           # returns parsed headers for mapping
POST   /clients/import/validate         # dry run — preview + row-level errors
POST   /clients/import/commit           # background job, returns batch id
GET    /clients/import/:batchId         # progress + error report link

PATCH  /tickets/:id/priority            # reason mandatory once assigned
GET    /tickets/:id/comments            POST /tickets/:id/comments
PATCH  /tickets/:id/comments/:cid       # only within the 5-minute edit window
POST   /tickets/:id/attachments         # multipart; returns signed URL after scan
GET    /tickets/:id/attachments         DELETE /tickets/:id/attachments/:aid
GET    /tickets/:id/emails              # delivery log for this ticket
POST   /webhooks/email/inbound          # reply-to-comment and email-to-ticket
POST   /webhooks/email/bounce           # provider bounce and complaint handling
POST   /tickets/:id/ask-status
POST   /tickets/bulk-reassign

GET    /dashboard/summary?project&from&to
GET    /dashboard/widget/:key           # type-wise, velocity, daily, aging…
GET    /reports/:reportKey              # + ?export=xlsx|csv|pdf
POST   /reports/schedule

GET    /notifications        PATCH /notifications/:id/read     PATCH /notifications/read-all
GET    /chat/threads         GET /chat/threads/:id/messages    POST /chat/threads/:id/messages
GET    /masters/task-types   GET /masters/priorities           GET /masters/holidays
GET    /audit-logs                       # Admin only
WS     /socket                           # rooms per §9.3
```

Conventions: `/api/v1` prefix · consistent envelope `{ data, meta, error }` · problem-details error format · idempotency key on POST create · ETag on detail reads · pagination via `?cursor=&limit=`.

---

## 14. Sample Walkthroughs

**A. Client bug through the full ribbon, with rework, breach and reopen**

| # | What happens | Ribbon after the event |
|---|---|---|
| 1 | Priya (Support) raises `CRM-26-00347`, Production Bug, High. SLA sets PCD 05 Aug. Cycle 1, Iteration 1 begins. | ●Intake |
| 2 | Priya validates and hands off to Meera (PM). 0.5 h logged. | ✔Intake → ●Triage |
| 3 | Meera sets level, assigns Ravi (Dev). 1.0 h logged. Ravi gets a popup in ~1 s. | ✔✔ → ●Development |
| 4 | Ravi logs 9.0 h over two days, clicks **Ready for QA**, assigns Anil. | ✔✔✔ → ●QA |
| 5 | Anil tests, logs 3.5 h, clicks **Fail — send for rework** with a 3-defect list. **Iteration → 2.** Ravi and Meera are notified. | ●Development ↺×1 |
| 6 | Ravi fixes, logs 5.5 h, sends back to QA. Anil passes it in 2.0 h. | ✔QA → ●Deployment |
| 7 | Karan (Deployment) deploys to production, logs 1.5 h, clicks **Deployment done** → hands back to Ravi. | ●Verification |
| 8 | Ravi verifies in prod, logs 1.0 h, clicks **Work complete** → back to Meera. | ●Sign-off |
| 9 | Meera accepts and closes. Cycle 1 sealed: **24.5 h across 5 resources, 2 iterations.** | ✔ Closed |
| 10 | 08 Aug: client reports recurrence. Meera ticks **Reopen**, reason logged. Cycle 2 opens with a fresh ribbon at Triage; Cycle 1's ribbon stays viewable via the cycle selector. | Cycle 2 · ●Triage |
| 11 | 13 Aug 00:15: the scanner finds `PCD < now` → level *High → Critical*, `is_delayed = true`. RM and PM get the escalation; a red banner appears on the ticket; the Critical and Delayed KPIs tick up. | current segment turns red |
| 12 | RM clicks **Ask Status**; the structured message lands in the ticket chat, Ravi replies, response time recorded. | — |
| 13 | Closed 14 Aug. **Total across both cycles: 38.0 h, 3 iterations, 5 resources** — every hour attributed to a named person and a named stage. | ✔ Closed |

The Journey tab for this ticket is exactly the grid in §4A.4, and it is the artefact you show a client who asks "what did your team actually do on this issue?".

**B. Developer's day** — logs in → lands on *My Tasks* → sees 3 due today, 1 overdue (amber border), and one that came back from QA overnight with an `↺ ×2` badge → opens Quick Update on the overdue one, logs 2 h (auto-stamped to the Development stage, iteration 2), sets *In Progress*, revises ETA with a reason → clears the QA rework, hits **Ready for QA** and picks Anil → replies to the manager's status request from the chat tab → cannot see any other resource's tickets, and cannot move a ticket that is not currently sitting in his stage.

**C. QA and Deployment day** — Anil opens *Stage Queue → Waiting in QA*, sorted oldest-first. He picks the top item, logs effort, and either **Passes** it to Deployment or **Fails** it with a defect list that goes straight back to the developer with iteration + 1. Karan sees *Waiting in Deployment*, deploys, and clicks **Deployment done**, which returns the ticket to the developer for verification. Neither of them ever sees the ticket list of a project they aren't on.

---

## 15. Delivery Roadmap

| Phase | Weeks | Scope |
|---|---|---|
| **0 — Foundation** | 1–2 | Repo, CI/CD, DB schema + migrations, design system, auth skeleton |
| **1 — Core MVP** | 3–7 | Login, Resource master (6 roles), Role master, Project master, **Client master + Excel import**, Ticket CRUD with **priority, client and attachments**, **comment box**, assignment, My Tasks, Quick Update, cycle + history + effort model, workflow template master, stage transitions, ribbon (read + handoff), Journey tab, basic list/detail |
| **2 — Intelligence** | 8–11 | Dashboard with all widgets + drill-down, stage funnel and rework widgets, SLA engine, stage SLA / stuck-in-stage alerts, escalation, notification centre, real-time popups, **full mail alert engine with templates, threading, delivery log and bounce handling**, Stage Queue team inbox |
| **3 — Collaboration** | 12–14 | Chat (ticket / DM / project), Ask Status, mentions, watchers, attachments |
| **4 — Reporting** | 15–17 | Reports hub, resource scorecard, velocity, effort, **stage cycle time, rework analysis, deployment report, resource contribution roll-up**, exports, scheduled emails, Resource 360 |
| **5 — Hardening** | 18–20 | Audit viewer, hash-chain verification, performance tuning, security audit, UAT, training, go-live |
| **6 — Extensions** | post | Client portal, email-to-ticket, knowledge base, mobile app, SSO, Teams/Slack integration |

**Team:** 1 PM/BA · 1 architect · 2 backend · 2 frontend · 1 QA · 0.5 DevOps · 0.5 UI designer ≈ **5 months to go-live**. The ribbon adds roughly 2–3 weeks of the phase-1 budget: the transition table and handoff service are straightforward, but the ribbon component itself needs real design time to stay readable at eight stages on a laptop screen.

---

## 16. Gaps in the Original Brief — Recommended Additions

These are the things that will be requested in month two if they aren't built in month one. Ranked.

### Must-have (add to MVP)

1. ~~Comments / work notes separate from chat~~ — **specified in §4B.5.** Auditable, permanent, internal versus client-visible. Chat is conversational; comments are the record.
2. ~~Attachments~~ — **specified in §4B.4.** Clipboard paste is the detail that decides whether support agents actually use it.
3. **Status field separate from stage** — the ribbon tells you *which team* holds the ticket; you still need In Progress / On Hold / Awaiting Info to know whether work is actually moving. Without both, you cannot distinguish "sitting in QA because Anil is busy" from "sitting in QA because the test environment is down".
3b. **Stage SLA, distinct from ticket SLA** — a ticket can be well inside its Planned Close Date while rotting for four days in the Deployment queue. Per-stage SLAs are what make the ribbon actionable rather than decorative.
4. **Estimated vs Actual effort** — the brief captures effort but no estimate, so there's nothing to measure against.
5. **Working calendar + holidays + leave** — otherwise SLA breaches fire over weekends and utilisation is wrong.
6. **Watchers / CC** — more than one person cares about a ticket.
6b. *Now in scope (§4B): priority dropdown, client master with Excel import, attachments, comment box and the mail alert engine — items 1, 2 and 6 of the original gap list are superseded by that section.*
7. **Bulk reassignment** — mandatory the first time someone resigns or goes on leave.
8. **Audit log + soft delete** — nothing is ever hard-deleted; deactivate instead.
9. **Global search + Ticket ID deep link** — people share ticket IDs in email all day.
10. **Password policy, lockout, forced first-login change** — the brief says "username and password" but stops there.

11. **Effort logging enforced at handoff** — if a resource can leave a stage without confirming effort, the per-resource roll-up under the ribbon is fiction within a month. Make it a blocking field, or at minimum a hard warning with a manager report of who skips it.
12. **Stage queue / team inbox** — QA and Deployment are queue-driven teams, not assignment-driven ones. Without a shared "waiting in QA" list, tickets stall between the handoff and someone noticing.

### High value (phase 2–3)

13. **Client / Requester portal** — external users raise and track their own tickets. This is what turns it from an internal task tool into a real *client* ticketing system.
14. **Email-to-ticket** — a mailbox like `support@company.com` auto-creates tickets and threads replies. Biggest single adoption lever for a support desk.
15. **SLA policy master per project/type/level** — instead of manually typing a planned close date every time.
16. **Auto-assignment rules** — especially important for the QA and Deployment stages, where "assign to the team" should mean the least-loaded member, not nobody — round-robin or least-loaded, so unassigned tickets don't rot.
17. **Ticket linking, sub-tasks and duplicates** — real work is rarely one flat ticket.
18. **Approval flow for Change Requests** — CRs usually need PM/client sign-off before work starts.
19. **CSAT / feedback on closure** — a 1–5 rating drives the client-satisfaction report.
20. **Knowledge base / canned responses** — cuts repeat resolution time dramatically.
21. **Timesheet view** — now stage-aware, so a week shows not just which tickets but which stages a resource spent time in — a resource's week across all tickets, and an approval step for the manager.
22. **Capacity / workload planning** — assigned hours vs available hours, so the assigner sees overload *before* assigning.

### Nice to have (phase 4+)

23. Kanban board whose columns *are* the ribbon stages (same data, different lens) · 24. Release / build number captured at the Deployment stage, so you can answer "which tickets went out in build 4.2.1?" · 25. Environment promotion path (Dev → UAT → Prod) as sub-steps inside Deployment · 26. Rollback as a first-class ribbon action · 27. Recurring / scheduled tickets (maintenance) · 28. Saved filters and personal dashboards · 29. Mobile app or PWA with push · 30. SSO (Azure AD / Google) · 31. Teams / Slack / WhatsApp integration · 32. Data retention and archival policy · 33. Multi-timezone display · 34. Multi-language · 35. Public API + webhooks · 36. Root-cause analytics on recurring bug categories · 37. Gamified leaderboard for the support desk.

### Governance decisions to lock before build

- Can a Developer close a ticket, or only mark it Resolved for verification? *(Recommendation: Resolved only — with the ribbon, closure belongs to the Sign-off stage owner.)*
- Can a ticket skip QA? For which task types, and who authorises it? *(Recommendation: PM/Admin only, reason mandatory, and never for Production Bug.)*
- Does a rework loop reset the Planned Close Date, or does the original date stand? *(Recommendation: original date stands; that is the honest measure, and rework is what the iteration counter is for.)*
- Who owns a ticket sitting in a stage where nobody is assigned yet — the queue or the PM? *(Recommendation: the queue, with a PM alert after 2 hours.)*
- Is effort mandatory at handoff? *(Recommendation: yes, blocking — otherwise the per-resource roll-up degrades within weeks.)*
- Should comments default to internal or client-visible? *(Recommendation: internal, always — an accidental leak is far costlier than an extra click.)*
- Can a client contact be created inline from the ticket form, or only in the client master? *(Recommendation: inline, or the support desk will pick the wrong existing contact to avoid the detour.)*
- Does a client Excel import update existing records or only insert new ones? *(Recommendation: upsert on client code, with the dry run showing exactly which rows will change.)*
- Who may edit Planned Close Date after assignment, and does it require a reason? *(Recommendation: PM/Admin only, reason mandatory, logged.)*
- Does auto-escalation to Critical stay Critical after closure, or revert for reporting? *(Recommendation: keep `original_level` and report both.)*
- Is effort self-reported or timer-based? *(Recommendation: self-reported with optional timer; timers alone get forgotten.)*
- Backdating of effort logs — allowed for how many days? *(Recommendation: 7 days, then manager approval.)*
- Data retention for closed tickets and chat. *(Recommendation: 3 years live, then archive.)*

---

## 17. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| History integrity challenged in a client dispute | Hash-chained append-only tables + nightly verification + audit export |
| Notification fatigue → people ignore alerts | Per-user preference matrix, digests, escalations reserved for genuine breaches |
| Dashboard slows as data grows | Pre-aggregated summary tables, partial indexes, Redis cache, archival after 3 years |
| Resources under-log effort → velocity is fiction | Daily reminder for missing timesheet, manager approval, effort mandatory on close |
| Scope creep on masters | Configuration-driven masters (types, statuses, SLA, workflow) so changes are data, not code |
| Role scope bug leaks another team's tickets | Scope enforced in a single central guard + automated permission test suite covering every role × route |
| Adoption failure | Quick Update is two clicks; My Tasks is the landing page; email-to-ticket in phase 2 |
| Ribbon becomes unreadable at 8 stages on a laptop | Compact dot variant in lists, horizontal scroll with the current segment auto-centred, and a collapsed "…" grouping for completed stages beyond the first three |
| Teams game the ribbon (bounce tickets to stop their stage clock) | Idle-vs-active time is reported alongside duration, and the iteration counter makes ping-pong visible on the PM dashboard within a day |
| Workflow template edited while tickets are live | Stages can be deprecated, never deleted; live tickets keep the template version they started on |
| Assignee never sees the alert (offline, mail filtered) | Mail plus in-app plus queued popup on next login, with a per-ticket delivery log so a missed alert is provable rather than deniable |
| Client Excel import silently corrupts the master | Dry-run preview before any write, upsert on client code, per-row error report, and an import batch id that identifies every row a bad import touched |
| Malicious file uploaded as a client screenshot | MIME sniffing not extension alone, AV scan before the file is visible, signed URLs only, EXIF stripped |
| Internal debug notes leak to a client | Comments and attachments default to internal; client-visible is an explicit toggle shown in a different colour before posting |

---

*Prepared as a build-ready blueprint. Each screen number (S-01 … S-34) maps 1:1 to a Figma frame and a Jira epic, so design, development and QA can all reference the same identifier.*

*Revision 1.1 — added the Workflow Ribbon (§4A), the QA and Deployment roles, stage-aware effort attribution, iteration vs cycle counters, screens S-29 to S-31, and the stage-based reports.*

*Revision 1.2 — added §4B: the priority dropdown, the client dropdown and client master with Excel bulk import (screens S-32 to S-34), attachments with clipboard paste, the comment box with history interleaving, and the mail alert engine with templates, threading, delivery logging and bounce handling.*

*Revision 1.3 — 11 Aug 2026, at the client's request: the four "where it happened" fields on the ticket (Module, Screen Name, Feature, Steps to Generate), the `product_modules` master behind the first of them, the module filter and optional column on S-17, and the legacy binary Office formats on the §4B.4 attachment allow-list. Attachments themselves were already specified in §4B.4 and needed nothing.*

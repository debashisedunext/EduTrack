# Stream C — Tickets & Ribbon · Task Backlog

**Milestones:** M0 (design system) · M4 (tickets + the Workflow Ribbon)
**Owner:** Divyansh · `divyansh.edunext@gmail.com` · @Divyanshedunext
**Branch prefix:** `feat/tickets/…`
**Owns:** `backend/api/feature/{tickets,transitions}/`, `frontend/src/features/tickets/`, `frontend/src/components/{ui,ribbon}/`, `frontend/src/styles/tokens.css`

> **You carry the largest slice — roughly 40% of the product surface and the hardest UI in it.** Stream B joins you from week 10 to take the ribbon's frontend. You also own the shared component library, which all three other streams consume: changes there are additive only, and Storybook is the contract.

> Cross-stream sequencing — who is waiting on you and what to do if you are blocked — is in [`../DEPENDENCIES.md`](../DEPENDENCIES.md).

---

## Sprint 0 — weeks 1–2

*Depends on nothing. Start day 1.*

- [x] **C-001** Vite + React 18 + TypeScript scaffold, TanStack Query, Zustand, React Hook Form.
- [x] **C-002** 🔴 **Design tokens** from blueprint §12.1 → `tokens.css` + `tailwind.config.ts`. All 12 colour tokens, level chips, the colour-blind-safe chart palette, ribbon segment states. Type scale (Inter / Plus Jakarta Sans, base 14/20), 4px spacing scale, radius 12/8/999, the two shadow levels. **Frozen after Sprint 0** — other streams request tokens rather than adding them.
- [x] **C-003** Shared component library — button, input, select, searchable dropdown, chip, table, modal, slide-over, toast, skeleton loader, empty state, avatar stack.
- [x] **C-004** Storybook, with every shared component documented. If it isn't in Storybook, it isn't shared.
- [ ] **C-005** App shell — collapsible 240px sidebar, top bar with global search, project switcher, notification bell, chat badge, avatar menu. Toast layer bottom-right.
- [ ] **C-006** Command palette on `Ctrl+K` for jump-to-ticket.

**Exit:** `npm run storybook` renders the library in correct tokens; `npm run dev` serves the shell against D's MSW mocks with no backend running.

---

## M4 — Tickets · weeks 3–14

### Create & list
- [ ] **C-010** Create ticket — all field groups from blueprint §7.5: Identity, Core, People, Effort, Extra. **S-19**
- [ ] **C-011** 🔴 **Ticket ID generation** — `UPDATE projects SET ticket_seq = LAST_INSERT_ID(ticket_seq + 1)` then `SELECT LAST_INSERT_ID()`. **Never `COUNT(*)`** — it breaks silently under concurrency. Format `{CODE}-{YY}-{00001}`. *(PLAN.md §3.2)*
- [ ] **C-012** SLA policy resolution → auto-computed Planned Close Date, **previewed inline before the user commits**.
- [ ] **C-013** Actions: Save & Assign · Save as Draft · Save & Create Another. On save → ID generated → notification → `CREATED` history row → email.
- [ ] **C-014** Ticket list — filters (project, client, type, level, stage, status, assignee, dates), sticky header, density toggle, column chooser. **S-17**
- [ ] **C-015** Saved views — My Open, Due Today, Overdue, Unassigned, Reopened, Closed This Month.
- [ ] **C-016** Row colour cues — soft amber left border on delayed, soft red on critical.
- [ ] **C-017** Bulk select → reassign / change level / close (PM & Admin only).
- [ ] **C-018** My Tasks — hard-scoped to `assigned_to = me`, grouped Due Today / Overdue / This Week / Later, inline Quick Update on every row, optional Kanban toggle. **S-18**

### Detail page — S-20
- [ ] **C-019** Detail shell + summary panel — every entity a link (assignee → profile, project → dashboard, client → 360 view, linked ticket, cycle → its effort logs).
- [ ] **C-020** Priority dropdown — colour chips, pre-filled from task type default, recomputes and previews PCD on change. **Mandatory reason once assigned.** Inline-editable from the summary panel. Writes `LEVEL_CHANGED`; **`original_level` never overwritten**. *(§4B.1)*
- [ ] **C-021** Client + client-contact dependent dropdowns, type-ahead over name/code/domain, filtered to clients mapped to the project, inline "+ Add contact". Auto-fills SLA, account manager as watcher, client timezone. *(§4B.2)*
- [ ] **C-022** Client-raised flag driving client-wise reports, CSAT and the client-visible default on comments.

### Attachments — §4B.4
- [ ] **C-023** Upload surfaces: create form, detail, comment box, handoff dialog, quick update.
- [ ] **C-024** 🔴 **Clipboard paste** alongside drag-drop and file picker. The blueprint is right that paste decides whether support agents actually use it.
- [ ] **C-025** Security — extension allow-list **and** MIME sniffing, AV scan before the file becomes visible, EXIF stripped, S3/MinIO keys `tickets/{id}/{uuid}`, short-lived signed URLs, never a public bucket.
- [ ] **C-026** Thumbnails, gallery strip, lightbox with zoom and next/previous.
- [ ] **C-027** Limits — 10 MB/file, 50 MB/ticket, 20 files/ticket, all configurable.
- [ ] **C-028** Delete within 15 minutes by the uploader; after that a soft delete leaving a tombstone. `is_client_visible` flag per attachment.

### Comments — §4B.5
- [ ] **C-029** Rich-text comment box under the description, always visible above the tabs. Ctrl/Cmd+Enter to post.
- [ ] **C-030** `@mention` type-ahead over project members, firing notification + email.
- [ ] **C-031** Visibility toggle — **default internal, always.** Client-visible shown in a different colour before posting.
- [ ] **C-032** Stamping — author, role, **stage and iteration at time of writing**, timestamp.
- [ ] **C-033** 5-minute edit window, then locked with an "edited" marker and the original preserved. Deletion leaves a tombstone. **No role, including Admin, can silently rewrite a comment.**
- [ ] **C-034** 🔴 **Interleave comments into the History tab** — field changes, handoffs, attachments and comments in one chronological stream, not two lists to reconcile.

### Effort & quick update
- [ ] **C-035** Effort logging, append-only, **auto-stamped with current stage and iteration** so it lands in the right journey row with no user action.
- [ ] **C-036** Quick Update slide-over — status, effort + date, work note, % complete, revised ETA with reason, attach. Two clicks, no reload, optimistic UI, closes with a toast. **S-21**
- [ ] **C-037** Quick Update must **not** expose: ticket ID, reported by, assigned by, date reported, cycle history, the ribbon, prior effort logs, level (unless PM), project.

### Cycles & closure
- [ ] **C-038** 🔴 **Reopen transaction** — seal cycle N (`is_sealed`), insert cycle N+1 with fresh start/PCD/assignee, `reopen_count++`, clear `actual_close_date`, write history. All in one transaction. **Cycle 1's effort is never touched.** *(§4.1)*
- [ ] **C-039** Reopen dialog — mandatory reason, restart stage (defaults Triage), new assignee, new PCD, revised estimate. Warning banner that cycle N and its ribbon will be sealed. **S-22**
- [ ] **C-040** Close/resolve dialog — resolution summary, root cause category, actual close date, final effort confirmation, optional client verification request. **S-23**
- [ ] **C-041** Materialised `total_effort_hrs`, refreshed on every effort insert. Per-cycle and grand totals.

### The Workflow Ribbon — §4A
- [ ] **C-042** Transition service — writes append-only `ticket_stage_transitions` rows. Action codes: `FORWARD`, `REWORK`, `DEPLOY_FAILED`, `VERIFY_FAILED`, `SIGNOFF_REJECTED`, `CLARIFICATION`, `SKIP`, `OVERRIDE`.
- [ ] **C-043** 🔴 **The golden rule** — only the *current stage owner* (plus PM and Admin) may advance a ticket. A Developer cannot push a ticket into Deployment while it sits with QA. Enforced server-side.
- [ ] **C-044** Handoff dialog — next stage (pre-filled from template), assign-to filtered to the receiving role's project members **with current open load shown**, handoff note, **mandatory effort confirmation for the stage being left**, attachments. **S-29**
- [ ] **C-045** On submit: seal the current row (`exited_at`, `duration_mins` in working minutes), insert the next, notify the receiving owner, advance the ribbon live over WebSocket. *Needs D's STOMP topics.*
- [ ] **C-046** Backward moves — reason mandatory, defect list on QA fail, `iteration_no` increments for every subsequent row in that cycle.
- [ ] **C-047** Skip a stage — PM/Admin only, reason mandatory, segment renders struck-through with the reason on hover.
- [ ] **C-048** Force-move (`OVERRIDE`) — PM/Admin, logged as an override.
- [ ] **C-049** Reassignment *within* a stage does **not** create a new segment — writes `STAGE_REASSIGNED` and splits effort attribution so both resources appear in the roll-up.
- [ ] **C-050** Unassigned receiving role → ticket falls to a project-level queue, PM alerted after 2 hours.
- [ ] **C-051** 🔴 **Ribbon component** — 6 segment states, each showing stage name + icon, owner avatar + name, time in stage, effort logged, loop-back badge.
- [ ] **C-052** Interactions — click a segment filters History/Effort/Chat below to that stage and iteration; hover tooltip with entered/exited/owner/note/effort/idle-vs-active; current segment carries the inline contextual action button, hidden for everyone else.
- [ ] **C-053** Cycle selector above the ribbon; selecting cycle 1 renders that cycle's completed journey read-only. **Each cycle has its own ribbon.**
- [ ] **C-054** `Cycle 2 · Iteration 3` chips — two independent counters, easy to get wrong. Iteration = pushed backwards within a cycle; cycle = reopened after closure.

### Journey tab — §4A.4
- [ ] **C-055** Roll-up grid — iteration, stage, resource, role, in, out, duration, effort, per hop.
- [ ] **C-056** 🔴 **Active vs idle split** — `idle = duration − Σ effort in that stage+iteration`. A stage with 2 days duration and 2 hours effort is a queue problem, not a capacity problem. This single insight justifies the whole feature.
- [ ] **C-057** Per-resource roll-up + cycle total + all-cycles total.
- [ ] **C-058** Roll-up query — **must join effort logs on `cycle_no` as well as stage and iteration**, or cycle 1's effort double-counts into cycle 2 after a reopen. *(PLAN.md §3.4 — a defect in the blueprint's own query.)*

### Remaining tabs & screens
- [ ] **C-059** History tab — cycle-grouped, expandable to every field change and handoff. **No edit or delete icon exists for anyone**, and the API rejects it even if the DOM is manipulated.
- [ ] **C-060** Attachments tab — gallery, filterable by client-visible, grouped by cycle and stage.
- [ ] **C-061** Effort tab — every log line, sum per cycle + grand total.
- [ ] **C-062** Stage Queue / team inbox — "Waiting in QA", "Waiting in Deployment", sorted by time-in-stage descending. The landing page for QA and Deployment. **S-31**
- [ ] **C-063** Bulk reassignment wizard — source resource → tickets → target → reason → confirm. Each move writes its own history entry. **S-24**
- [ ] **C-064** Ticket linking — blocks / is blocked by / duplicate of / relates to.

**Exit:** blueprint §14 walkthrough A runs end to end — 8 stages, a QA rework, a reopen into cycle 2, and a Journey grid reconciling to 38.0 h across 5 resources and 3 iterations.

---

## Decisions you own

Answer before M4 (PLAN.md §5):

- **G-1** Is effort mandatory at handoff — blocking or warn-only? *(Recommended: blocking. Without it the per-resource roll-up is fiction within a month.)*
- **G-2** Does a rework loop reset the Planned Close Date? *(Recommended: no. The original date stands; rework is what `iteration_no` measures.)*
- **G-3** Can a Developer close a ticket, or only mark Resolved? *(Recommended: Resolved only — closure belongs to the Sign-off stage owner.)*
- Can a ticket skip QA, and who authorises it? *(Recommended: PM/Admin only, reason mandatory, never for Production Bug.)*
- Should comments default to internal or client-visible? *(Recommended: internal, always — an accidental leak costs far more than an extra click.)*

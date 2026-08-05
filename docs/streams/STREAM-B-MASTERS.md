# Stream B — Masters & Clients · Task Backlog

**Milestones:** M0 (seed + entities) · M3 (all master screens) · then joins C on the ribbon, then M6 reports
**Owner:** Ayush · `ayush.edunext123@gmail.com`
**Branch prefix:** `feat/masters/…`
**Owns:** `backend/api/feature/{masters,clients,imports,workflow}/`, `frontend/src/features/{masters,clients}/`

> **You own two decouplers the whole team depends on.** B-007 (ticket fixture corpus) unblocks D's SLA work and C's ribbon; B-021 (working-hours service) is a hard dependency for every SLA calculation D writes. Ship both early.

---

## Sprint 0 — weeks 1–2

*Starts day 3, once Stream A's baseline schema lands.*

- [ ] **B-001** Seed: 6 roles + the full permission matrix from blueprint §2. QA and Deployment included — the ribbon cannot be represented without them.
- [ ] **B-002** Seed: 11 task types (Change Request, Production Bug, Client Request, Future Release, Internal Bug, Client Bug, Server Issue, Network Issue, Browser Issue, Performance Issue, Other) with icon, colour, default level, default SLA. Plus 4 priorities with colour and escalation flag.
- [ ] **B-003** Seed: statuses (New, In Progress, On Hold, Awaiting Info, Rework, Resolved, Closed, Reopened) + the `workflow_transitions` allowed-transition matrix per role.
- [ ] **B-004** Seed: 3 workflow templates with their stages — Standard Dev Flow (8 stages), Support Fast-Track (5), Infra Flow (5). *(§4A.9)*
- [ ] **B-005** JPA entities + repositories for the full model, built on A's schema. Feature-packaged, not layer-packaged.
- [ ] **B-006** MapStruct base configuration.
- [ ] **B-007** 🔴 **Ticket fixture corpus** — 200 tickets across 3 projects, varied stages, iterations, cycles, breach states, effort logs and client attribution. *This is what lets D test the SLA scanner and C test the ribbon before either feature exists.*
- [ ] **B-008** Seed manifest with fixed load order. One seed file per stream, never a shared file.

**Exit:** a migrated DB loads full seed data; entities compile against it; the fixture corpus renders realistic ribbons.

---

## M3 — Master data · weeks 3–9

### Resource & role
- [ ] **B-010** Resource list — columns, filters by role/project/manager/status, bulk activate/deactivate, export. **S-07**
- [ ] **B-011** Resource create/edit — Personal, Access, Org, Work and Projects sections. **S-08**
- [ ] **B-012** 🔴 **Reporting-manager cycle detection** — A→B→A blocked **at any depth**, not just self-reference. The DB `CHECK` only catches self-reference.
- [ ] **B-013** Validations — unique username, email and emp code; auto-generated temporary password with force-change-on-first-login.
- [ ] **B-014** Deactivating a resource with open tickets forces the bulk reassignment wizard. *Coordinate with C on S-24.*
- [ ] **B-015** Role & permission master — module × CRUD/approve checkbox matrix. **System roles are non-deletable.** **S-09**

### Project
- [ ] **B-016** Project master list/create/edit — code (ticket-ID prefix), name, PM, dates, status, colour tag. **`project_code` is immutable once a ticket exists.** **S-10**
- [ ] **B-017** Team tab — resources + per-project role (PM/Dev/Support/QA/Deploy/Viewer) + allocation %.
- [ ] **B-018** SLA tab — per task type × level → response hrs, resolution hrs, L1/L2 escalation targets.
- [ ] **B-019** Settings tab — allowed task types, mandatory fields, auto-assign rule (round-robin / least-loaded / manual).

### Simple masters
- [ ] **B-020** Task type master — the 11 seeded types, Admin-extensible. **S-11**
- [ ] **B-021** Priority master — Low/Medium/High/Critical + colour + default SLA hours + escalation flag. Drives C's priority dropdown. **S-12**
- [ ] **B-022** Notification template master — event, channel, subject, HTML body, merge tags, per-event on/off, per-role recipients. Drives D's mail engine. **S-15**

### Working calendar
- [ ] **B-023** Working calendar & holiday master — org holidays, weekly off pattern, per-resource leave. **S-14**
- [ ] **B-024** 🔴 **Working-hours calculation service** — `workingHoursBetween(start, end)` and `addWorkingHours(start, n)`, honouring weekends, holidays and resource leave. **Every SLA, duration and utilisation figure in the system routes through this.** D is blocked on it. Blueprint §5 calls it the most commonly missed requirement.

### Client master
- [ ] **B-025** Client list — columns, filters, row-expand to show contacts inline, bulk activate/deactivate. **S-32**
- [ ] **B-026** Client create/edit across four tabs: Identity · Commercial · Contacts · Projects & SLA. **S-33**
- [ ] **B-027** `client_contacts` child grid — add/edit/remove, primary flag, notification opt-in, portal access.
- [ ] **B-028** Validation: unique client code, valid emails, **at least one primary contact before the client is selectable on a ticket**.
- [ ] **B-029** Deactivating a client with open tickets warns and blocks new tickets, but never hides historical ones.

### Excel import — S-34
- [ ] **B-030** 🔴 **Import engine as a schema registry** — built once, registered twice (clients, resources). Blueprint §4B.3: "build it once, register two schemas."
- [ ] **B-031** Step 1 — template download. Apache POI **SXSSF**, with data-validation dropdowns on Status and Support Plan, and one filled example row.
- [ ] **B-032** Step 2 — upload, max 5 MB / 5,000 rows, **event-driven SAX parse** (the DOM reader would load the whole workbook per concurrent import). Sheet selector for multi-sheet workbooks.
- [ ] **B-033** Step 3 — column mapping, auto-matched by header, manual override per column, saveable presets. Unmapped required columns block Next.
- [ ] **B-034** 🔴 **Step 4 — dry-run validation preview.** Nothing is written. Per-row status: will create / will update / duplicate in file / rejected + reason. Summary counts.
- [ ] **B-035** Step 5 — commit as a background job with progress bar. **Upsert on client code — update, never duplicate.**
- [ ] **B-036** Error report generation — `.xlsx` with an appended Reason column, so users fix and re-upload only the rejected rows.
- [ ] **B-037** `import_batches` traceability — every import identified, a bad one reversible as a set.
- [ ] **B-038** Resource bulk import — the second registration, not a second build.

### Workflow templates
- [ ] **B-039** Status/stage/workflow master tab 1 — statuses, categories, allowed-transition matrix per role. **S-13**
- [ ] **B-040** Tab 2 — stages: sequence, code, display name, owner role, icon, stage SLA hours, optional flag, allowed return targets. Drag to reorder.
- [ ] **B-041** Tab 3 — templates built by picking stages, mapped to project × task type, with a **live ribbon preview** rendering as the Admin edits.
- [ ] **B-042** 🔴 **Stages in use may be deprecated, never deleted** — deletion breaks every historical ribbon. Live tickets keep the template version they started on.
- [ ] **B-043** Workflow template designer — drag stages onto a canvas, set owner role and SLA, draw return paths, preview, map. **S-30**

**Exit:** an Admin can stand up a complete tenant — resources, projects, clients (bulk-imported), calendar, workflow templates — without touching the database.

---

## Weeks 10–11 — join Stream C on the ribbon

C owns the ribbon's service layer; you take the UI. Tasks assigned from `STREAM-C-TICKETS.md`, typically:

- [ ] **B-050** Ribbon segment component — 6 states (completed, current, pending, reworked, skipped, blocked), 5 data points each.
- [ ] **B-051** Compact dot variant for the ticket list.
- [ ] **B-052** Ribbon accessibility — keyboard navigation, ARIA label per segment reading stage, owner, state and effort.
- [ ] **B-053** Readability at 8 stages on a laptop — horizontal scroll with the current segment auto-centred, collapsed "…" grouping for completed stages beyond the first three. *(§17 risk mitigation)*

---

## Weeks 12–14 — M6 reports

Split with Stream A. Typically yours:

- [ ] **B-060** Client report — volume, open vs closed, SLA compliance, avg resolution, satisfaction per client; drills into the client 360 view.
- [ ] **B-061** Resource performance scorecard and workload/capacity report.
- [ ] **B-062** Export engine integration for all report types.
- [ ] **B-063** Timesheet view — stage-aware, a resource's week across all tickets.

---

## Decisions you own

Answer during M3 (PLAN.md §5, blueprint §16):

- Can a client contact be created inline from the ticket form? *(Recommended: yes — or the desk picks the wrong existing contact to avoid the detour.)*
- Does the client Excel import update existing records or only insert? *(Recommended: upsert on client code, with the dry run showing exactly what changes.)*
- Who may edit Planned Close Date after assignment? *(Recommended: PM/Admin only, reason mandatory, logged.)*
- Effort backdating window? *(Recommended: 7 days, then manager approval.)*

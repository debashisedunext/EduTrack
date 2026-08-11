# Stream B — Masters & Clients · Task Backlog

**Milestones:** M0 (seed + entities) · M3 (all master screens) · then joins C on the ribbon, then M6 reports
**Owner:** Ayush · `ayush.edunext123@gmail.com` · @Ayushedunext
**Branch prefix:** `feat/masters/…`
**Owns:** `backend/api/feature/{masters,clients,imports,workflow}/`, `frontend/src/features/{masters,clients}/`

> **You own two decouplers the whole team depends on.** B-007 (ticket fixture corpus) unblocks D's SLA work and C's ribbon; B-021 (working-hours service) is a hard dependency for every SLA calculation D writes. Ship both early.

> Cross-stream sequencing — who is waiting on you and what to do if you are blocked — is in [`../DEPENDENCIES.md`](../DEPENDENCIES.md).

---

## Sprint 0 — weeks 1–2

*Starts day 3, once Stream A's baseline schema lands.*

- [x] **B-001** Seed: 6 roles + the full permission matrix from blueprint §2. QA and Deployment included — the ribbon cannot be represented without them.
- [x] **B-002** Seed: 11 task types (Change Request, Production Bug, Client Request, Future Release, Internal Bug, Client Bug, Server Issue, Network Issue, Browser Issue, Performance Issue, Other) with icon, colour, default level, default SLA. Plus 4 priorities with colour and escalation flag.
- [x] **B-003** Seed: statuses (New, In Progress, On Hold, Awaiting Info, Rework, Resolved, Closed, Reopened) + the `workflow_transitions` allowed-transition matrix per role.
- [x] **B-004** Seed: 3 workflow templates with their stages — Standard Dev Flow (8 stages), Support Fast-Track (5), Infra Flow (5). *(§4A.9)* — *`V20260807_1700`; 3 templates, 18 stages*
- [x] **B-005** JPA entities + repositories for the full model, built on A's schema. Feature-packaged, not layer-packaged. — *merged in PR #34*
- [x] **B-006** MapStruct base configuration. — *`api/config/BaseMapperConfig.java`; mappers opt in with `@Mapper(config = BaseMapperConfig.class)` and inherit Spring component model, constructor injection, and two compile-time gates: an unmapped target property and a lossy numeric conversion are both build errors. Partial updates ignore null source properties, so a `PATCH` cannot blank a field it never sent. Shared by all four streams — additive, no existing file behaviour changed.*
- [x] **B-007** 🔴 **Ticket fixture corpus** — 200 tickets across 3 projects, varied stages, iterations, cycles, breach states, effort logs and client attribution. *This is what lets D test the SLA scanner and C test the ribbon before either feature exists.* — *`api/feature/fixtures/`, a profile-gated `ApplicationRunner` (`local,fixtures` — never a Flyway migration, per SEED-MANIFEST.md §5). `ReferenceDataFixture` creates the reference data no seed provides — 3 projects (`CRM`/`PAY`/`WEB`, matching D-004's mock so both fixture worlds read as one company), 18 resources across all 6 roles, 8 clients with contacts, 4 org-wide `sla_policies` rows. `SingleTicketFixture` walks each ticket through its real workflow template via `WorkingHoursService` (B-024) — cycles, stage transitions, history, effort logs, sealed exactly like the real write path would. ~14% reworked, ~15% of closed tickets reopened into cycle 2, ~20% deliberately breached (reported 45-65 days back), ~40% client-attributed. `FixtureCorpusIT` (Testcontainers MySQL) proves the shape and idempotency. Hash-chain columns (`prev_hash`/`row_hash`) are left NULL — A-040/A-044 hasn't landed, and inventing an algorithm now risks conflicting with Stream A's; the corpus needs a backfill once it does.*
- [x] **B-008** Seed manifest with fixed load order. One seed file per stream, never a shared file. — *`db/migration/SEED-MANIFEST.md`, held by `SeedManifestTest` (a migration with no manifest row fails the build) and `SeedDataIT` (referential integrity across the string references MySQL does not enforce).*

  > **Building the register found a live defect, fixed in `V20260808_1400`.** `V20260807_1030` renamed the Support role code to `SUPPORT`; `V20260807_1100` (B-003) runs *after* it and re-seeded the old `SUPPORT_DESK` string into 13 `workflow_transitions` rows. `role_code` has no FK, so the rows inserted cleanly and simply matched nobody — the Support Desk role could make **no status transition at all**, including the `RESOLVED → CLOSED` and `CLOSED → REOPENED` moves that G-3 and blueprint §2 reserve for exactly Admin/PM/Support Desk. Undetected because C-014, the first code to read that table, has not landed. B-001's grants were unaffected (that seed ran before the rename and resolves codes to ids through a JOIN).

**Exit:** a migrated DB loads full seed data; entities compile against it; the fixture corpus renders realistic ribbons.

---

## M3 — Master data · weeks 3–9

### Resource & role
- [x] **B-010** Resource list — columns, filters by role/project/manager/status, bulk activate/deactivate, export. **S-07** — *`api/feature/masters/resources/` and `features/masters/resources/`, at `/masters/resources`. Three queries per page whatever the page size — the grid, then projects for its ids, then open-ticket counts for its ids — held by a test that fails on the fifty-first. A real keyset cursor over `(full_name, id)`, not the base64-wrapped offset `CalendarController` uses: CONVENTIONS.md §6 forbids offset paging because a row inserted while somebody is on page 2 shifts one row to page 3 unseen, and the second key is there because two people called Priya Sharma is a normal thing for an organisation to contain. Export is SXSSF, streams every matching row in batches of 500, and neutralises every text cell against spreadsheet formula injection — a department typed `-Ops` is inert in MySQL and a negation in Excel. 112 tests: 93 unit, 19 against real MySQL.*

  > **Bulk deactivate answers 200 with a per-resource outcome, not one verdict.** A selection of forty in which two hold open tickets is the normal case; failing the batch punishes the thirty-eight and succeeding quietly orphans live work. Each row returns `CHANGED`, `UNCHANGED`, `BLOCKED_OPEN_TICKETS` or `NOT_FOUND`, and the blocked check sits *after* the already-in-that-state check — before it, somebody already deactivated but still holding tickets (what a half-finished reassignment leaves behind) would report blocked forever and never clear from the grid. The reassignment wizard itself is B-014; this screen names who it refused and stops.
  >
  > **`?export=` on a list operation is a trap, and `/reports/{key}` and `/audit-logs` are still sitting in it.** The contract idiom looks right and generates a client returning `Blob | UserListResponse` — at which point **two of Stream C's working pages stopped compiling**, because the ticket list's assignee filter and the create form's picker both call `useListUsers` and were suddenly required to narrow a union they have no interest in. Shipped that way for an hour and reverted to `GET /users/export`. Neither of the other two has a consumer yet, which is the only reason nobody has hit it. **Flagged for Streams A and D** rather than changed from here.
  >
  > **B-023's calendar endpoints were unreachable, and this task found it.** `CalendarController` was mapped at `/masters`; nothing in the application declares the `/api/v1` prefix globally, so all nine operations answered on a path the generated client, the MSW handlers and the contract all disagree with. Every calendar call would have 404'd on first contact with a real backend. It survived review and a full unit suite because `CalendarControllerTest` builds the controller with `new` and never asks Spring where it is mounted — a wiring mistake needs a wiring test, and `MasterRoutesTest` is now that test for every controller in the feature.
  >
  > **`FilterDropdown` moved to `components/ui/`**, which its own comment nominated as the trigger for a second caller. Unchanged, plus a Storybook entry. **`components/ui/` is Stream C's path — needs their sign-off.**
- [x] **B-011** Resource create/edit — Personal, Access, Org, Work and Projects sections. **S-08** — *`api/feature/masters/resources/` (`ResourceWriteService`, `ResourceWriteRepository`, `ResourceExceptionHandler`, `TemporaryPasswords`) and `features/masters/resources/`, at `/masters/resources/new` and `/masters/resources/:userId/edit`. One page for both verbs — they are one form, and two components would be the same file twice with one copy always slightly behind. 42 new backend tests (26 unit, 16 against real MySQL) and 35 new frontend ones; the full suites are green at 602 backend unit tests and 288 frontend tests.*

  > **Five of S-08's fields had no column.** `V20260811_1520` adds `date_of_joining`, `avatar_url`, `location`, `weekly_off` and `skills` to `users`; mobile, department, designation, daily capacity and time zone were already there. `weekly_off` is the per-resource override B-023's header said would "land later without changing this shape" — nullable, where **null means inherit the org week and `[]` means no weekly off at all**, which a support rota is a real reason to want. **B-024 does not read it yet**: a resource with an override still has their SLA computed against the org week, and wiring it into the service every SLA figure routes through is a change that should not ride along behind a form. **Flagged for Stream A** — `users` is A-003's table, and although CLAUDE.md only mandates their review for `tickets` and the three append-only tables, the identity table should not change on a Stream B branch unremarked.
  >
  > **`GET /users/{userId}` did not exist, and its absence made `PATCH /users/{userId}` uncallable.** CONVENTIONS.md §5 pairs every `If-Match` write with a detail read that emits the tag; the PATCH declared the precondition and there was nowhere to obtain it. `/projects/{id}` and `/clients/{id}` both have theirs — this was a gap, not a new requirement. Added, with a `428` for a PATCH that arrives without one, because treating a missing precondition as "no conflict" protects only the clients that already opted in.
  >
  > **`UserWriteRequest.projectIds` could not express the Projects section**, which S-08 specifies as "multi-select project assignment **with per-project role**". Replaced by `projects: [{projectId, roleInProject}]` — a breaking change to a request shape no shipped client sent. The role vocabulary is a new `ProjectRoleCode`, **deliberately not `RoleCode`, differing in both directions**: `VIEWER` is a project role and not a global one (a global viewer would mean read-only access to everything), and `ADMIN` is a global role and not a project one (an Admin already sees every project through `ScopeResolver`, so the row would be a grant that changes nothing — and a grant that changes nothing is one somebody later assumes does something). `ck_project_members_role` fixes the same set in the database; B-011 is the column's first writer, so this was the moment to decide it.
  >
  > **B-012 is not done, and the shipped `PATCH` accepts A→B→C→A.** Only self-reference is refused, which is what the DB trigger already caught. Stated in the contract's own description rather than quietly deferred — a contract describing a guarantee the server does not make is worse than no guarantee — and pinned by `ResourceWriteServiceTest.deeperCyclesAreNotYetDetected`, which **documents the hole rather than a guarantee** and inverts when B-012 lands. **The MSW mock already refuses the deeper cycle**, so the frontend will handle the 409 on day one.
  >
  > **B-013 is largely landed as a by-product, and could not not be.** A create that 500s on a duplicate username is not a deliverable, and `password_hash` is `NOT NULL`, so a create cannot happen without generating something. Uniqueness is reported per field and all three at once — an admin fixing a duplicate username should not then discover the duplicate email — and it is checked in the service *and* enforced by the indexes, because the check is the good error message and the index is the thing that is actually true under a race. `TemporaryPasswords` generates 16 characters satisfying §10.3 by construction, omitting `O`/`0`/`l`/`I`/`1` and the shell-hostile symbols: this string is read off a screen and pasted through a chat window, and a failed first login on a fresh account looks exactly like a provisioning bug. It comes back in `meta.temporaryPassword` on a response schema **no read operation returns** — "not populated by the list query" is a property of today's SQL, "not on the type the list returns" is a property of the code.
  >
  > **A MySQL `CHECK` violation is not a `DataIntegrityViolationException`.** Error 3819 arrives under SQL state `HY000` and Spring's translator maps it to `UncategorizedSQLException`, so an unhandled one is a 500. Acceptable here — these constraints sit behind Bean Validation and the service — but worth knowing before anyone adds a `CHECK` that something reachable can trip. Found by `ResourceFormIT` asserting the wrong exception type and being wrong about it.
  >
  > **`FormField` is duplicated from `features/tickets/create/`, on purpose.** A second caller is this codebase's trigger to promote a control to `components/ui/` — which is what B-010 did with `FilterDropdown`, and that move already needs Stream C's sign-off. A second unilateral change to their path in the same week is not one stream's call, so it is duplicated and **nominated for promotion to `components/ui/form-field.tsx`**, kept shape-identical so the merge is a delete rather than a reconciliation. `WeeklyOffPicker` needed no such treatment — it is B-023's, in this stream's own path, and is now reused by its second caller.
- [ ] **B-012** 🔴 **Reporting-manager cycle detection** — A→B→A blocked **at any depth**, not just self-reference. The DB `CHECK` only catches self-reference. *The seam is `ResourceWriteService.validateManager`; `ResourceWriteServiceTest.deeperCyclesAreNotYetDetected` is the test that inverts when this lands. The MSW mock's `createsCycle` already implements it, so the frontend needs no change.*
- [ ] **B-013** Validations — unique username, email and emp code; auto-generated temporary password with force-change-on-first-login. *Largely landed under B-011, which could not ship a working create without it. What remains: `@ValidPassword` is package-private in `feature.auth`, so `TemporaryPasswords` restates §10.3's rules rather than sharing them, and the two could drift.*
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
- [ ] **B-064** 🔴 **Module master read endpoint** — `GET /api/v1/masters/modules` over the `product_modules` table C-065 creates and seeds (Student, Admission, Fees, Examination, Attendance, Library, Inventory, Parent App). Read-only, **every row in `seq` order including deactivated ones**, each carrying `isActive` — same as `/masters/task-types`. A picker offers only the active ones; a grid still has to render the name of a module some old ticket was raised against, and filtering them out here leaves that cell blank. The contract shape is D-060's, and its mock has a retired module referenced by one seeded ticket so the distinction is exercised. **Blocks C-068.** Added 11 Aug 2026 with blueprint revision 1.3 — the eight rows are reference data, and a master served out of the tickets feature is how the masters pattern stops meaning anything. No admin CRUD screen: the client asked for a fixed list, and the table exists so that changing it later is a row rather than a release. If a Module Master screen is wanted, it is a new task on the S-11/S-12 pattern, not a change to this one.

### Working calendar
- [x] **B-023** Working calendar & holiday master — org holidays, weekly off pattern, per-resource leave. **S-14** — *`V20260808_1630__working_calendar.sql` + `WorkingCalendar`; `api/feature/masters/Calendar*`; `features/masters/calendar/`. 9 new contract operations. **B-024 now has everything it needs.***

  > **Days are ISO-8601 — Mon=1 … Sun=7 — everywhere.** The contract described `weeklyOff` as "ISO day numbers" while constraining it to `0–6`, and the mock sent `[0, 6]`: JavaScript's `Date.getDay()` convention wearing an ISO label. Read literally by a backend using `DayOfWeek`, `0` is not a day and **Sunday becomes a working day** — every SLA spanning a weekend short by a day, in the one calculation CLAUDE.md says must have a single implementation. A third numbering was also live in `docs/plan/calendar.json` (`[0,1,2,3,4]`, Python's `date.weekday()`), unread but documented as the shared source B-024 should use. All three are now ISO, and `ck_working_calendar_weekly_off` makes the database refuse a `0`.
  >
  > **Two defects the tests caught before merge.** The working day was first stored as `TIME`/`LocalTime`; both `api` and `worker` set `hibernate.jdbc.time_zone: UTC`, so it round-tripped shifted by the JVM offset — 09:30 written, 15:00 read. Now `SMALLINT` minutes from midnight, which nothing can convert. And MapStruct's default collection strategy mutates what the getter returns, but `getWeeklyOff()` derives a fresh `EnumSet` each call — so `PUT` answered 200, echoed the new week back, and saved nothing. Both have named regression tests.
- [x] **B-024** 🔴 **Working-hours calculation service** — `workingHoursBetween(start, end)` and `addWorkingHours(start, n)`, honouring weekends, holidays and resource leave. **Every SLA, duration and utilisation figure in the system routes through this.** D is blocked on it. Blueprint §5 calls it the most commonly missed requirement. — *`domain/masters/WorkingHoursService.java`, in `domain` rather than `api.feature.masters` so D's `worker` module and C's `api.feature.tickets` can both call it without reaching across a stream boundary. Both methods take an optional `projectId` (project holidays, unioned with the org calendar per B-023) and an optional `userId` (that resource's approved leave, including half-days). Walks calendar days in the working calendar's own zone — never UTC-naively, per the round-trip bug B-023 already hit once — and resolves a recurring holiday against every year the walk touches via `HolidayRepository.findAllOrgWideOrForProject`, a new query added because the existing date-windowed one cannot see a recurring holiday stored in a prior year. 21 unit tests (fixture calendar, no Docker) plus 8 integration tests against real MySQL, including the canonical blueprint §5 case — a Friday-18:00 ticket with a 4-hour SLA lands Monday morning, not Saturday.*

### Client master
- [ ] **B-025** Client list — columns, filters, row-expand to show contacts inline, bulk activate/deactivate. **S-32**
- [ ] **B-026** Client create/edit across four tabs: Identity · Commercial · Contacts · Projects & SLA. **S-33**
- [ ] **B-027** `client_contacts` child grid — add/edit/remove, primary flag, notification opt-in, portal access.
- [ ] **B-028** Validation: unique client code, valid emails, **at least one primary contact before the client is selectable on a ticket**.
- [ ] **B-029** Deactivating a client with open tickets warns and blocks new tickets, but never hides historical ones.

### Excel import — S-34
- [x] **B-030** 🔴 **Import engine as a schema registry** — built once, registered twice (clients, resources). Blueprint §4B.3: "build it once, register two schemas." — *`api/feature/imports/` — the SPI (`ImportSchemaDefinition`), the registry, the validation kernel, header auto-match and the staging seam; `schemas/ClientImportSchema` is the first registration and is a list of columns plus two methods. Adding an importable entity is adding one `@Component`: no route, no migration, no registry edit. B-031…B-037 are steps that plug into this; B-038 is a file. 83 unit tests plus 9 against real MySQL.*

  > **The engine's guarantees are enforced, not documented.** `ImportValidationEngine` holds no repository and no path to `upsert()`, so blueprint §4B.3's "nothing is written yet" cannot be eroded by a convenient `save()` later, and `ImportEngineIsolationTest` fails the build if the dependency appears. The same test fails any reference to `domain.clients` from the engine — the first half of a second implementation, which CLAUDE.md's stop rule otherwise relies on somebody remembering on a Thursday afternoon.
  >
  > **Two contract-vs-schema conflicts, found by reading them against each other before either had a consumer.** `ImportBatchResponse.batchId` was `format: uuid` while `import_batches.id` is a `BIGINT AUTO_INCREMENT` like every other id in the system — contract corrected to `int64`, client regenerated. And `import_batches.status` carried a private `PENDING|VALIDATING|COMMITTING|DONE` that no caller could observe while the contract promised `QUEUED|RUNNING|COMPLETED|FAILED`: a row at its own default serialised as a value the generated TypeScript union does not contain, so the frontend's zod schema would have rejected the response the backend just produced. The contract won; `V20260810_2010` adds a `CHECK` so they cannot diverge again. Two of the old states were unreachable anyway — the dry run writes nothing, so no row exists until commit.
  >
  > **`develop` was briefly red under this branch, for a reason unrelated to B-030 — and Stream A fixed it independently.** `SchemaIntegrationIT.schemaHasExpectedShape` expected 44 tables; A-027 added `password_reset_tokens` in PR #70 without bumping it. This branch carried the 44→45 repair for a few hours, then A-028 landed the same fix (to 46, with `password_history`) and B-030 dropped its version on the next rebase. Recorded because the near-collision is the argument the comment on that line already makes: a table count is a tripwire nobody can read, it makes every new table a cross-stream edit, and two branches nearly repaired it twice. Asserting named tables would do the same job without the coupling.
  >
  > **ArchUnit is currently a no-op on this project, and this would have been its first use.** It is a declared test dependency, but 1.3.0's bundled ASM stops at class file major 68 and PLAN.md §D-10 compiles at `release 25`, which is 69. It does not fail on a class it cannot read — it logs and skips, so all four rules passed having examined nothing. The tripwire was rewritten to scan source (and to fail if the scan finds no files). **Flagged for Stream A:** the real fix is an `archunit.version` bump in `backend/pom.xml`, and until then any ArchUnit test anyone adds is a green tick that proves nothing.
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

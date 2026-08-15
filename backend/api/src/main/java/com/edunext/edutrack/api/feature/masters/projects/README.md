# Project Master — S-10, all four tabs

`GET /api/v1/projects` · `POST` · `GET /{projectId}` · `PATCH /{projectId}`
`GET /{projectId}/members` · `POST` · `PATCH /{projectId}/members/{userId}` · `DELETE`
`GET /{projectId}/sla-policies` · `PUT`
`GET /{projectId}/settings` · `PUT`

The list, the create/edit form and the four rules S-10 states (B-016); the **Team
tab** — resources, per-project role and allocation % (B-017); the **SLA tab** —
the task type × level matrix (B-018); and the **Settings tab** — allowed task
types, mandatory fields and the auto-assign rule (B-019). S-10 is complete.

---

# Part four — the Settings tab (B-019)

Blueprint §7.5 S-10: "allowed task types, mandatory fields, auto-assign rule
(round-robin / least-loaded / manual)".

## An empty allow-list means unrestricted, and that is the whole feature

**No `project_task_types` rows for a project means every active task type may be
raised on it. It does not mean none may.**

Every project in the system is in that state, because the table did not exist
until this task's migration ran. Had the absence meant "nothing is permitted",
applying the migration would have stopped ticket creation everywhere at once.

A backfill could not have rescued the other reading either. Writing a row per
task type per project to preserve today's behaviour turns "this project has never
been configured" into "this project was configured, on 14 Aug 2026, to allow
exactly these eleven" — and the twelfth task type an Admin adds next month is
then silently barred on every project in the organisation, by a decision nobody
made.

What an administrator meets is that clearing every checkbox removes the
restriction rather than forbidding everything. The screen says so in words,
because eleven empty boxes say the opposite on their own. There is deliberately
**no separate "remove the restriction" control**: a project permitting no task
type could raise no ticket, so the state does not exist, and two controls for one
outcome is how they end up disagreeing — the call `SlaMatrixService` makes about
a cleared cell.

`restrictsTaskTypes` is on the wire rather than derived per client, because
"unrestricted" and "restricted, with every box ticked" are indistinguishable in
the flags alone and differ the moment a twelfth task type exists.

## The read returns retired task types this project allows

Every **active** task type, plus any **inactive** one with a membership row. The
second half is not tidiness. The `PUT` is a wholesale replace assembled from the
rows the screen was given, so a retired type that is allowed and not rendered
would be deleted by the next save through a screen that never displayed it — the
deletion-by-omission `SlaMatrixRepository.DEACTIVATE_OVERRIDES` guards against for
project-level SLA defaults, one master over.

Rows carry `isActive` so the screen can label them: they cannot be raised on a new
ticket whatever this tab says, and an unlabelled one would look like an option.

## Mandatory fields are JSON; allowed task types are a join table

Both are sets on a project, and they are stored differently on purpose.

Task type ids are **foreign keys into a master an Admin edits**, and referential
integrity is the whole argument for normalising. Field codes are a vocabulary in
the application with nothing to point at, nothing queries them *across* projects
("which projects require a module" is not a question the product asks), and a
`project_mandatory_fields` table would be a second join and a second read to
serve a checkbox list of ten values. `users.skills` made the same call for the
same reason.

`NULL` and `[]` both mean "requires nothing extra". The column is nullable, every
row predating this feature holds `NULL`, and the screen writes `[]` when the last
box is unticked; the repository collapses them so nothing downstream can tell, and
writes `NULL` so a save does not rewrite every row to record a decision nobody
made.

## The CHECK constrains shape, not vocabulary

`ck_projects_mandatory_fields` accepts a unique array of uppercase codes and stops
there. `ck_projects_status` (B-016) pins its three values because blueprint S-10
fixes them; this list is **exactly the optional fields of `TicketCreateRequest`**,
so pinning it would mean Stream C cannot add a form field without a migration in
Stream B's directory.

The consequence is that a code this build has never heard of is storable — after a
rollback, say. `ProjectSettingsService` refuses one on the way in and **drops** one
on the way out rather than throwing, because a settings read that threw would put
the only screen that could repair it behind the failure.

## The vocabulary excludes every always-required field

`projectId`, `title`, `taskTypeId` and `level` are required of every ticket
already, so a checkbox for one could not change any outcome. A control that cannot
do what it appears to do is worse than a missing one — somebody ticks it and
believes something happened.

## `auto_assign_rule` now has one editor

The column is B-016's and the General tab held its control, because this tab did
not exist. It does now, and the control moved: S-10 puts the field here, and
leaving it on both screens would have been worse than a duplicate. `toPatchRequest`
sends the whole form on every save, so a General-tab save would have carried that
form's value and **silently overwritten whatever this tab set** — the
`project_members` hazard B-011 and B-017 had to pin apart with two named
regression tests, avoided rather than documented.

`POST /projects` and `PATCH /projects/{projectId}` still *accept* the field:
removing it would be a breaking contract change for no gain, and a project may
reasonably be created with a rule. Nothing sends it twice.

`ProjectService` also stopped keeping its own `Set<String>` of the three values and
now derives them from `ProjectSettingsDtos.AutoAssignRule` — one vocabulary for one
column, checked against the database's `CHECK` by `ProjectSettingsIT`.

## Nothing enforces these settings yet

**This is the one part of B-019 that lands incomplete, by design.** The tab stores
and serves configuration; the thing that has to obey it is ticket creation —
`api/feature/tickets` and `CreateTicketPage`, which are **Stream C's**. Writing the
enforcement here would be reaching across a stream boundary; leaving it unsaid
would ship a screen whose settings quietly do nothing.

So it is said here, in the contract's `PUT` description, in the screen's own
javadoc and in the backlog. What C needs is on `GET /projects/{projectId}/settings`
already: `restrictsTaskTypes` + `taskTypes[].isAllowed` filters the task-type
picker, and `mandatoryFields` marks the rest.

## Permissions — and why they are wider than the SLA tab

| Operation | Who |
|---|---|
| `getProjectSettings` | all six roles |
| `replaceProjectSettings` | `project.manage`, which B-001 grants to **Admin and PM** |

The SLA tab one route over is `master.write` — Admin alone — and the difference is
deliberate. §2 has two rows: row 2, "Create/edit projects, map resources to
project", is ✅ for PM and covers General, Team and Settings; row 5, "Master data
(task types, **SLA**, workflow, holidays)", is Admin's alone and covers the SLA
tab. Choosing which task types a project accepts is configuring one project;
setting the response target a client is contractually held to is master data.

The decisive half is that narrowing this to Admin would **take a capability away
from PMs**: `auto_assign_rule` is one of the three settings here and has been
PM-writable through `PATCH /projects/{projectId}` since B-016.

The read is every role because all six can raise a ticket, and the create form
cannot filter its picker or mark a field mandatory without it.

## Files

| File | What |
|---|---|
| `ProjectSettingsController.java` | the two operations and the `If-Match` precondition |
| `ProjectSettingsService.java` | the empty-allow-list rule, the vocabularies, the four refusals |
| `ProjectSettingsRepository.java` | `JdbcClient` — the `LEFT JOIN` that keeps retired memberships visible, and the JSON column |
| `ProjectSettingsDtos.java` | wire types; `TicketField` is the vocabulary |
| `ProjectSettingsExceptionHandler.java` | RFC 9457 problems, scoped to this controller |

Schema: `V20260814_1120__project_settings.sql` creates `project_task_types` and
adds `projects.mandatory_fields`. **`projects` is Stream A's table** — flagged in
the migration header rather than slipped in.

---

# Part three — the SLA tab (B-018)

Blueprint §7.4 S-10: "per task type × level → response hrs, resolution hrs, L1/L2
escalation targets".

## Both operations existed on paper, and one of them was wrong

`getSlaPolicies` and `replaceSlaPolicies` have been in `openapi.yaml` and in the
generated TypeScript client **since D-001, with no server behind either**.
Nothing failed, because nothing called them. That is the third instance of the
gap — B-023's nine calendar operations, B-014's `PATCH /users/{userId}/status`,
and this — and `MasterRoutesTest` now asserts this mount point too.

The declaration was also wrong. `SlaPolicyWrite` carried
`l1EscalationUserId` and `l2EscalationUserId`, and:

- **there is no column for either.** A-007's table has `escalate_to_l1` and
  `escalate_to_l2`, both `TINYINT(1)`;
- **Stream D's shipped scanner does not want them.**
  `worker.sla.EscalationPolicies` documents at length that §6 *fixes* who each
  level means — L1 the assignee's reporting manager, L2 that manager's manager,
  the 48-hour rule — and `SlaEscalation` derives the recipients from the
  reporting chain. The matrix only says whether the level *applies* to this kind
  of ticket on this project, which is what lets a project decide that a
  Low-priority change request wakes nobody's manager without also having to
  decide who that manager is.

So the contract is corrected to flags. Storing a recipient here would put the
reporting chain in two places that can disagree; correcting the schema the other
way would have broken D-024 from a masters branch. Same shape as B-017's
`projectRole: RoleCode`, one screen over. The mock had been answering a
hardcoded `2` and `1` for every cell, which nothing read.

## The read is resolved and the write is only the overrides

`sla_policies` is layered — null `project_id` is org-wide, null `task_type_id`
is "any type" — so §6 resolves most-specific-first. A task type × level grid has
exactly one kind of cell, so **this screen writes rung 1 and nothing else**.
Rungs 2 and 3 are read, rendered, and left alone.

That makes the two halves deliberately asymmetric:

- **`GET` returns every cell resolved**, with the `source` that answered.
  Returning only this project's rows would render as a nearly empty grid for a
  project whose tickets all get perfectly good planned close dates — an
  administrator would read "nothing configured" and configure it, and the act of
  doing so is the defect below.
- **`PUT` takes only the cells that are overrides.** If the screen sent the
  resolved grid back — the obvious implementation, since the grid is what it
  holds — every inherited figure would become a project-level row and the
  project would **silently stop following the org-wide default it was displayed
  as following**. Nothing looks wrong until somebody changes that default six
  months later and this project does not move.

`isOverride` is on every cell so the two can be told apart, and
`slaMatrix.ts:buildOverrides` is the single place the body is decided.

## A project-level default survives a replace

`DEACTIVATE_OVERRIDES` is scoped `AND task_type_id IS NOT NULL`. The rung-2 row —
one policy covering every task type at a level — has no cell in the grid, so a
replace that dropped it would delete configuration through a screen that never
displayed it. B-007's corpus puts one on PAY, which makes it a live case rather
than a hypothetical; both `SlaMatrixIT` and the frontend mock exercise it.

## Cleared overrides are deactivated, never deleted

`clients.sla_policy_id` is a foreign key into this table **with no cascade**, and
`PlannedCloseDatePreview.slaPolicyId` puts row ids on the wire. A `DELETE` fails
on a constraint naming a MySQL index — or, if somebody "fixes" that with a
cascade, silently unsets a client's SLA policy from a project screen. `is_active
= 0` is also what the resolution ladder already reads, so a cleared cell falls
through to the next rung instead of leaving a hole, and `uq_sla_policies` makes
the write an upsert so restoring a cleared cell reuses its row rather than
colliding forever.

## The §6 ladder is written down twice, with a test between them

C-012's `PlannedCloseDateService.resolve` walks the same five rungs one cell at a
time through `SlaPolicyRepository`. Calling it per cell here is up to five
statements × eleven task types × four levels — **two hundred and twenty round
trips for one page load** — so `SlaMatrixService.Ladder` indexes three bounded
reads and walks the rungs in memory.

Nothing about either implementation can be *read* to establish that they agree,
and if they drift the SLA tab and the create form quote different numbers at the
same client while each screen looks correct on its own. Two tests are the seam:

| Test | Claim |
|---|---|
| `SlaMatrixIT.theGridAgreesWithThePlannedCloseDate` | every cell of a real grid, figures **and** source name, equals C-012's answer — with all five rungs seeded |
| `SlaMatrixIT.theSourceVocabulariesAreTheSame` | `SlaPolicyDtos.Source` and `SlaResolution.Source` have identical members, so the name comparison above means something |

`Source` is **copied rather than imported** to keep a masters DTO off a tickets
type. That copy is the cost; those two tests are what make it affordable.

## Four refusals, none enforced by the schema

| Refusal | Status | Why the database cannot catch it |
|---|---|---|
| the same cell twice in one body | 400 | both rows have one `uq_sla_policies` key, so `ON DUPLICATE KEY UPDATE` keeps the last **quietly** and the caller is told the save worked |
| an unknown task type | 400 | the FK would catch it, as a constraint violation naming an index |
| an unknown level | 400 | `level` is a `VARCHAR`, not an FK — S-12 lets an Admin add one without a release |
| response target longer than resolution | 400 | nothing downstream rejects it; the scanner would warn about a first response overdue after the ticket was already due to close |

All four run before anything is written. The body is one transaction, so a row
refused halfway through would roll back the rows before it and the caller would
be told about the twelfth cell of a save that also silently did not apply the
first eleven.

`resolutionHrs` is `@Positive`, not `@PositiveOrZero`:
`SlaResolution.hasTarget()` treats a non-positive figure as no target at all, so
a stored zero reads as configured here and behaves as absent everywhere else —
the ticket drops out of the breach sweep, the pre-breach warning and the delayed
KPI with nothing saying so.

## `@Valid` on a `List` body needs its own test

Every other write in this feature takes a single DTO, where `@Valid @RequestBody
Foo` is unambiguous. This one takes a bare array, and `@Valid` on a `List`
validates *the list* — which has no constraints. The element cascade comes from
method validation, a different mechanism with different triggers.

It does engage. The risk was never that validation would be wrong but that it
would **silently not run**, with the annotation sitting right there on the
record. `SlaPolicyBodyValidationTest` pins it, including that the *second*
element is validated and not only the first.

## The `ETag` is on the read because the write needs one

The contract required `If-Match` on the `PUT` and declared an `ETag` nowhere, so
the operation was uncallable — the gap B-016 closed by adding `GET
/projects/{projectId}`. `check-conventions.py` does not catch this class: its §5
detail-read rule fires on paths ending in a path variable and this one ends in a
collection segment, and widening it would fire on a dozen paginated lists that
legitimately have no tag. Recorded in `CONVENTIONS.md` §5 as a rule for humans
instead.

The tag is taken over the **resolved** grid, not over this project's own rows. A
change to the org-wide default therefore moves this project's tag even though
nothing on the project changed — correct, because the administrator was shown
inherited figures and is deciding which to override.

## Permissions — and why they differ from the Team tab

| Operation | Who |
|---|---|
| `getSlaPolicies` | all six roles |
| `replaceSlaPolicies` | `master.write`, which B-001 grants to **Admin alone** |

⚠ **This is one tab away from three rows that are Admin and PM, and that is not
an inconsistency to tidy up.** §2 has two separate rows: "Create/edit projects,
map resources to project" is ✅ for PM and is the General and Team tabs; "Master
data (task types, **SLA**, workflow, holidays)" is Admin and nobody else.
B-001's own description of `master.write` names SLA in it, and B-023 annotates
the working calendar — the other master in this feature — exactly this way.

Staffing your own project is project management. Setting the response target a
client is contractually held to, and deciding whose manager's manager gets woken
on a breach, is master data. The obvious "consistency" fix is to widen this row,
and it would be widening the wrong one.

The read is every role because this grid is what gives every ticket its planned
close date — the same figures already reach all six roles one at a time through
C-012's preview on the create form, and a Developer who cannot see the matrix
cannot find out why their ticket is due Thursday.

## What is deliberately not a rule

**The grid does not have to be complete.** No cell need be overridden and a
project need have no overrides at all — the ladder is exactly what makes an
unconfigured project work, and every project B-016 creates starts that way.

**An override is not checked against the rung it replaces.** A project may set a
Critical resolution target *longer* than the org-wide default; that is a
negotiated contract, not a mistake, and a server that refused it would mean the
true figure could not be written down anywhere. Same call B-017 made about
allocations summing past 100.

## Files

| File | What |
|---|---|
| `SlaPolicyController.java` | the two operations, the `If-Match` precondition, the content-derived `ETag` |
| `SlaMatrixService.java` | the ladder, the four refusals, the replace transaction |
| `SlaMatrixRepository.java` | `JdbcClient` — three bounded reads, the scoped deactivate, the upsert |
| `SlaPolicyDtos.java` | wire types; Bean Validation is the source of truth for field rules |
| `SlaPolicyExceptionHandler.java` | RFC 9457 problems, scoped to this controller |

**No migration.** A-007 created `sla_policies` and the columns are the ones
blueprint §6 names.

---

# Part two — the Team tab (B-017)

Blueprint §7.4 S-10: "add resources + project role (PM / Dev / Support / QA /
Deploy / Viewer) + allocation %".

## What already existed, and what did not

`project_members` has been in the schema since A-003 and B-011 fixed the
vocabulary of `role_in_project` with `ck_project_members_role`. Two of the four
operations were already in the contract. **What did not exist was the half that
makes them a screen:**

| Gap | Consequence |
|---|---|
| No `GET` | the Team tab had nothing to read the roster from |
| No `PATCH` | changing an allocation meant remove-and-re-add, which deactivates and reactivates the row and resets `addedAt` — an edit rendered as a fabricated departure and return |
| No `allocation_pct` column | the contract had promised `allocationPct` since D-001 with nothing behind it |
| `projectRole: RoleCode` | **the wrong enum, in both directions** |
| `'201': { description: Added. }` | no body, against CONVENTIONS.md §2 |

## `RoleCode` was wrong in both directions

`RoleCode` contains `ADMIN` — which `ck_project_members_role` refuses, so the
contract permitted a value that could only ever have arrived as a 500 — and
lacks `VIEWER`, which S-10 names and this screen has to offer. `ProjectRoleCode`
exists for exactly this reason and B-011 already writes the column through it.
`ProjectTeamIT.adminIsNotAProjectRole` is the constraint proving the first half.

The six codes were also written down twice in Java. They are now
`masters/ProjectRoles`, which `ResourceDtos.ProjectAssignment` points at too, and
`ProjectRolesTest` is the seam: nothing re-checks a `@Pattern` against a database
`CHECK`, so a divergence between two copies would have surfaced as one screen
accepting a role another refuses — B-013's argument about §10.3, applied here.

## `allocationPct` is nullable, and the contract's `default: 100` is gone

Landing it as `NOT NULL DEFAULT 100` would have written a claim nobody made onto
every row that already exists — B-007's fixture corpus puts several resources on
all three projects, so they would read 300%, and B-061's capacity report could
not tell a backfilled 100 from a stated one. `NULL` means **not stated**, which
is what `NULL` already means one column to the left.

**Zero is a different fact from null** and both are storable: "no capacity
committed" is a decision, "not stated" is an absence. That is why the mapper
reads the column with `getObject(…, Integer.class)` and not `getInt`, which
answers 0 for a SQL NULL.

## Two writers on one table

B-011's resource form writes these same rows from the other side. They are kept
from fighting by what each statement *does not* name:

| Statement | Names | Deliberately omits |
|---|---|---|
| `ResourceWriteRepository.UPSERT_MEMBERSHIP` (B-011) | `role_in_project`, `is_active` | `allocation_pct` — so a resource-form save preserves what this tab set |
| `ProjectTeamRepository.UPDATE_MEMBER` (B-017) | `role_in_project`, `allocation_pct` | `is_active` — so an edit cannot resurrect a membership the resource form removed |

`ProjectTeamIT.aResourceFormSaveDoesNotClearAllocation` reproduces B-011's
statement verbatim and fails if anybody widens it.
`patchDoesNotReactivate` is the mirror.

## Four refusals, none enforced by the schema

| Refusal | Status | `type` |
|---|---|---|
| Unknown or deactivated resource | 400 | `validation` |
| Already on this team | 409 | `already-on-team` |
| Member holds open tickets **here** | 409 | `open-tickets` |
| Unknown project | 404 | — |

The two 409s carry different `type` URIs because one is resolved by closing the
dialog and the other by reassigning live work — B-012's reasoning for splitting
`manager-cycle` from `duplicate`.

**"Open" is `statuses.is_open`**, never a literal `<> 'CLOSED'`: the status
vocabulary is master data an Admin extends through S-13, and this count decides
whether a removal is refused. It is also scoped to the project in the path —
work held elsewhere is not a fact this screen can act on.

## Removal is a deactivation, and therefore reversible

`is_active = 0`, for B-011's reason: the row is the record that this person was
on the project while the tickets assigned to them then were being worked, and a
`DELETE` would make historical project attribution depend on current team
composition.

That forces the add path to be an upsert. `uq_project_members` keeps one row per
pair forever, so **re-adding somebody who was removed reactivates rather than
conflicting** — a 409 there would make every removal permanent, which reads as a
bug in the remove button rather than as a rule. 409 is reserved for somebody who
is *already* on the team, which is a request with nothing to do.

**Removing a non-member answers 204.** It is a setter: a client retrying after a
dropped response, or the second half of a double-click, must not be told an error
about a thing that did happen. B-014's call for `UNCHANGED`, unchanged.

## No `If-Match` on the patch

A registered CONVENTIONS.md §5 exemption with its reason, not an omission. The
tag would have to come from `listProjectMembers`, a collection carrying no `ETag`
of its own — so honouring the precondition would mean minting a per-member tag on
a read nothing else preconditions, to guard a race between two people setting one
percentage whose loser typed a number a moment later and meant it. The service
read-modify-writes inside one transaction, so two people editing *different*
fields of one membership both land.

## The patch DTO is a class, and every other DTO here is a record

**A record cannot express omitted-versus-null.** Jackson binds a record through
its canonical constructor, and an absent `Optional` creator property is filled
with `Optional.empty()` — the same value an explicit JSON null produces. Both
collapse into "clear it", so `PATCH {"allocationPct": 60}` would also wipe the
member's project role, and the response would look correct because it echoes what
was saved.

A POJO does not have the problem: an absent property means the setter is never
called. That is why `ResourceDtos.ResourceWriteRequest` is one.
`ProjectMemberPatchTest` pins both directions.

## What is deliberately not a rule

- **The project manager is not forced onto their own team.** `projects.manager_id`
  is who escalations reach; `project_members` is who works on it. Auto-adding
  them would put an allocation nobody entered onto every project they own.
- **The project role is not checked against the global one.** A Developer mapped
  as QA here is the case the column exists for.
- **Allocations are not made to sum to 100.** Over-allocation is a real state an
  organisation gets into and needs to *see*; a server that refused it would mean
  the true figure could not be written down anywhere. The tab shows the project
  total. A *resource's* total across their projects is the figure that would be a
  warning, and this screen has one project's rows — **flagged for B-061** rather
  than approximated from here.

## Permissions

| Operation | Who |
|---|---|
| `listProjectMembers` | all six roles — the roster is who a ticket here can be assigned to |
| `addProjectMember`, `updateProjectMember`, `removeProjectMember` | `project.manage` (Admin + PM) |

Blueprint §2 gives PM "Create/edit projects; **map resources to a project**", and
that second clause is this screen.

## Files

| File | What |
|---|---|
| `ProjectMemberController.java` | the four operations, mounted at `/api/v1/projects/{projectId}/members` |
| `ProjectMemberService.java` | the four rules, and the add-versus-reactivate decision |
| `ProjectTeamRepository.java` | `JdbcClient` — the roster with its open-ticket aggregate, and the two statements that each omit one column on purpose |
| `ProjectMemberDtos.java` | wire types; the patch is a class and says why |
| `ProjectMemberExceptionHandler.java` | three problems, scoped to this controller |
| `../ProjectRoles.java` | the six project-role codes, in one place |

Schema: `V20260813_1810__project_member_allocation.sql`. **`project_members` is
Stream A's table** — flagged in the migration header, as B-011 and B-016 both
did.

---

# Part one — the Project Master (B-016)

## Why the path is `/projects` and not `/masters/projects`

The contract has served projects at the top level since D-001, and five screens
already call them there: the project switcher, the ticket list's filter, the
create-ticket form, the resource grid and the resource form. Moving the
controller under `/masters` to sit tidily beside the other master screens would
break all five to make a URL prettier. `MasterRoutesTest` asserts the path the
clients use rather than the one the package name suggests.

## `isActive` is derived, and it is not `status = 'ACTIVE'`

`status` is blueprint S-10's three-way **Active / On Hold / Closed**, and it is
what the grid renders and filters on. `isActive` is the boolean those five
pickers have always sent, and it means **`status <> 'CLOSED'`**.

Deriving it the obvious way would mean that putting a project on hold silently
removed it from the create-ticket picker, with nothing on the form saying why —
the support desk unable to file against it and unable to find out from the
screen. Whether On Hold *should* stop new tickets is a real product question and
possibly a yes; it is not one a derived boolean should answer on its way past.
**Flagged for Stream C**, whose create form is where that decision can carry a
message.

## `projectCode` immutability: the counter, not the ticket table

The test is `projects.ticket_seq > 0`, not "a ticket row exists".

`ticket_seq` counts codes that have been *issued*. A ticket created and later
hard-deleted still had `CRM-26-00347` sent to a client, quoted in a mail thread
and written into chat — counting live ticket rows would let a cleanup quietly
re-open the prefix for editing, and the orphaning would happen later and look
like something else. It is also on the row already, so the check costs no join.

**Resending the same code is always a no-op**, and has to be: S-10 submits the
whole form on every save, so any other reading would make every edit to a live
project a 409. Same shape as the `u.id <> ?` B-013 documents on the resource
form's uniqueness check.

`ticketsIssued` is on `ProjectDetail` and part of the `ETag`. A ticket allocated
while somebody has the form open therefore costs them a reload — deliberate, not
a defect: that number crossing zero is precisely the event that fixes the code,
and a save carrying a new one that was legal when the form loaded must not land
afterwards.

## Four refusals, one of them backed by the schema

| Refusal | Status | `type` | Enforced by |
|---|---|---|---|
| Duplicate `projectCode` | 409 | `duplicate` | the service **and** `uq_projects_code` |
| `projectCode` changed after the first ticket ID | 409 | `immutable-project-code` | the service alone |
| No manager, unknown manager, deactivated manager | 400 | `validation` | the service alone |
| Target end date before start date | 400 | `validation` | the service alone |

Only the first has an index behind it, which is why the handler catches
`DuplicateKeyException` as well: the check is the good error message and the
index is the thing that is actually true under a race.

The two 409s carry **different `type` URIs** because one is fixed by choosing
another code and the other cannot be fixed at all, and a client telling them
apart by reading `detail` is branching on the part of the document that may be
reworded without notice — B-012's reasoning for splitting `manager-cycle` from
`duplicate`.

## The manager's role is deliberately not checked

Restricting this to `PM` and `ADMIN` is the obvious rule and wrong twice over: an
organisation running a support-led project has a legitimate reason to name its
Support Desk lead, and a hardcoded role set is exactly what B-015 removed from
`ResourceController` — the first custom role an Admin creates would be refused
here by a screen that had just granted it every capability. What is checked is
that the person exists and is **active**, because this is who Stream D's SLA
scanners escalate to; naming somebody who has left sends every escalation on the
project to a mailbox nobody reads.

## There is no delete

S-10 does not ask for one and the table cannot support one. `tickets.project_id`
is a foreign key with no cascade, so the database would refuse anyway — as a
constraint violation naming an index rather than as a way forward. `CLOSED` is
the retirement path, the way deactivation is for a resource.

## Permissions

| Operation | Who |
|---|---|
| `listProjects`, `getProject` | all six roles — a Developer who cannot read this cannot raise a ticket |
| `createProject`, `updateProject` | `project.manage`, which B-001 grants to **Admin and PM** |

PM holding the write capability is B-001's grant, not a decision taken here:
blueprint §2 gives PM "Create/edit projects; map resources to a project."

The list is **not row-scoped**, and does not need to be. `ScopeResolver` scopes
*tickets*; the project list is the vocabulary a ticket is described in, and a PM
who could not see a project name could not read the tickets they do own.

## Files

| File | What |
|---|---|
| `ProjectController.java` | the four operations, cursor paging, the `If-Match` precondition |
| `ProjectService.java` | the four rules, and `isActive`'s derivation |
| `ProjectRepository.java` | `JdbcClient` — the grid projection, the detail read, and hand-written statements that never name `ticket_seq` |
| `ProjectCursor.java` | keyset over `(name, id)` |
| `ProjectDtos.java` | wire types; Bean Validation is the source of truth for field rules |
| `ProjectExceptionHandler.java` | RFC 9457 problems, scoped to this controller |

Schema: `V20260813_1420__project_master_fields.sql` adds `description` and
`auto_assign_rule` and constrains `status`. **`projects` is Stream A's table** —
flagged in the migration header rather than slipped in.

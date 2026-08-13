# Project Master — B-016 · S-10 · and the Team tab, B-017

`GET /api/v1/projects` · `POST` · `GET /{projectId}` · `PATCH /{projectId}`
`GET /{projectId}/members` · `POST` · `PATCH /{projectId}/members/{userId}` · `DELETE`

The list, the create/edit form and the four rules S-10 states (B-016); plus the
**Team tab** — resources, per-project role and allocation % (B-017). The **SLA
tab** is B-018 (`/projects/{id}/sla-policies`) and the **Settings tab** is B-019;
both have their own contract paths and neither is here.

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

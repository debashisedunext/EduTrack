# Project Master — B-016 · S-10

`GET /api/v1/projects` · `POST` · `GET /{projectId}` · `PATCH /{projectId}`

The list, the create/edit form, and the four rules S-10 states. The **Team tab**
is B-017 (`/projects/{id}/members`), the **SLA tab** is B-018
(`/projects/{id}/sla-policies`) and the **Settings tab** is B-019 — all three
have their own contract paths and none of them is here.

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

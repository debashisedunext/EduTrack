# Resource Master — the list (B-010, S-07) and the form (B-011, S-08)

B-010 built the read half: a filterable, paginated grid of everyone in the
organisation, a bulk activate/deactivate, and an export. B-011 added the write
half: `GET`, `POST` and `PATCH` of one resource, behind the S-08 form.

**B-012** then closed the reporting-manager hole: a cycle at any depth is a
`409`, not only self-reference. **B-014** added `PATCH /users/{id}/status` and
turned the deactivation refusal into a route through S-24 rather than a dead
end.

## B-014 · deactivation has to lead somewhere

Blueprint §S-07: "deactivating a resource with open tickets **forces** a bulk
reassignment wizard." Three routes here refuse that deactivation —
`POST /users/bulk-status`, `PATCH /users/{id}` and now
`PATCH /users/{id}/status` — and before this task every one of them named a
problem and offered no way through. A refusal with no exit is an obstacle, and
the reliable response to an obstacle is to stop trying, which leaves the
organisation with leavers whose accounts are still live.

**The wizard itself is not in this package and never will be.** S-24 is Stream
C's C-063, behind `POST /tickets/bulk-reassign`. What this package owns is the
refusal, the information needed to resolve it, and the route back:
reassign, then call the status route again and get a `204`.

### `PATCH /users/{id}/status` was declared and never served

The contract has carried `setUserStatus` from the first draft. The MSW mock has
answered it since B-010. Three Javadocs in this package described what it
refuses. **No server ever mounted it** — a `PATCH` to that path met
`/{userId}`'s sibling mapping and came back `405`, and nothing noticed, because
the operation had no caller until this flow needed one.

`MasterRoutesTest` could not have caught it: it asserts where *classes* are
mounted, and this was a missing *method*.
`ResourceControllerTest.SingularStatus.isMountedWhereTheContractSaysItIs` is now
the thing that would have. `ContractConformanceTest` reports unimplemented
operations as coverage rather than failing the build, deliberately (D-005), so
it was never going to be the alarm here.

### The singular route has no decision of its own

`ResourceService.setStatus(long, boolean)` calls the same `apply` the bulk route
does and translates the outcome into a status code. The contract has always said
the two refuse the same thing for the same reason; one `apply` is the only way
that stays true.

**`UNCHANGED` answers `204`, not `409`.** Setting a flag to the value it already
holds succeeded — there is nothing for the caller to do differently, and a
client retrying after a dropped response has to converge. It also matters for
the exact row this task exists to unblock: somebody already inactive who still
holds tickets, which is what a half-finished reassignment leaves behind, would
otherwise be refused forever.

### `reason` is accepted, validated, and not persisted

Both status bodies carry it and nothing writes it down. The place it belongs is
`audit_logs` — the table exists, and so does `domain.audit.AuditLog` — but
**nothing in this repository writes a row to it**, and `actor_id` wants a
principal `dev-noauth` does not supply. Introducing the first writer to a shared
audit table from a masters branch, with a null actor, would make the audit
trail's opening entries the ones nobody can attribute. Dropping the field from
the contract instead would mean re-adding it later as a breaking change to
clients that had learned to omit it. **Flagged for Stream A with A-016.**

### `reassignUrl` stays an API path

It names `POST /tickets/bulk-reassign`, which a browser cannot navigate to. The
UI route the screen actually goes to lives in
`frontend/src/features/masters/resources/reassignHandoff.ts`, along with the
`fromUserId` / `returnTo` handoff contract Stream C's wizard reads. A server
emitting `/tickets/bulk-reassign?fromUserId=…` would be shipping one web app's
routing table to every consumer of the API.

## B-012 · the reporting line

`validateManager` walks up `reporting_manager_id` from the proposed manager in
one recursive CTE and refuses if the edited resource is anywhere on the chain.
Three things about it are worth knowing before changing it.

**The walk is depth-bounded, and the bound is what makes it terminate.** A
recursive CTE over a table that already contains a cycle has no base case: it
runs to `cte_max_recursion_depth` and fails the statement, which arrives as a
500 on somebody's unrelated save. `MAX_MANAGER_CHAIN` is 64 — well under
MySQL's 1,000, so the recursion stops at a bound this code chose.

**A chain that fills the cap is refused, not assumed clean.** This service is
the only writer that refuses cycles, so the rows it reads may already contain
one — from before B-012, or from a hand-run `UPDATE` the trigger allowed
because it was not self-reference. On that data a truncated walk cannot tell
"no cycle" from "no cycle *yet*", and the optimistic reading fails open on
exactly the data most likely to be broken. Repairing the line above is a
separate edit; `ResourceFormIT.aPreExistingCycleIsSurvivable` is the case.

**Self-reference now returns 409, where B-011 returned 400.** Depth 1 and depth
4 are one rule, and two status codes for one rule is a client branch that
describes the implementation. The contract and the MSW mock both said 409
already — the server was the odd one out. It still carries `errors` keyed by
`reportingManagerId`, so the form behaves exactly as it did.

The walk also replaced the `userExists` call it sits next to rather than being
added alongside it: an empty chain *is* "no such manager", so the round-trip
count per save is unchanged.

One thing it does not do: name the people in the cycle. The message says how
many levels up, and ids in prose would not help an admin who thinks in names.
Resolving them is a second query for an error path, and worth doing only if the
message turns out not to be enough.

## Shape

| File | What it is |
|---|---|
| `ResourceController` | Every `/api/v1/users` route — list, export, bulk-status, get, create, update |
| `ResourceService` | Paging arithmetic, export streaming, the bulk-status decision per resource |
| `ResourceRepository` | Three `JdbcClient` projections and one narrow update |
| `ResourceWriteService` | B-011 · create, update, and the guards on both |
| `ResourceWriteRepository` | B-011 · the detail read, the insert, the dynamic update, the membership sync |
| `ResourceExceptionHandler` | B-011 · RFC 9457 problems, scoped to this controller |
| `TemporaryPasswords` | B-011 · the auto-generated password S-08 asks for |
| `ResourceExportWriter` | SXSSF `.xlsx` and RFC 4180 `.csv`, from the same batches |
| `ResourceCursor` | Keyset cursor over `(full_name, id)` |
| `ResourceFilter` | The four S-07 filters plus search, as one value |
| `ResourceDtos` | Wire shapes, matching `contracts/openapi.yaml` §users |

## Three queries per page, whatever the page size

The page, then the projects for its ids, then the open-ticket counts for its
ids. Not one query — a `GROUP_CONCAT` of projects caps silently at
`group_concat_max_len` and a correlated count subquery runs per row — and
emphatically not one plus two-per-row.

`ResourceServiceTest.hydratesInBatches` holds this: 50 rows, one `projectsFor`
call and one `openTicketCounts` call.

## Things that are deliberate

**JDBC, not the JPA entities.** B-005 built them and this does not use them. The
grid needs ten columns from four tables per row; loading `User` entities would
hang a lazy `role` proxy off every row, resolve `reportingManagerId` with one
query each, and fetch a password hash the screen must never see. `AuthUserRepository`
makes the same call from the other side and says why sharing the mapping would
be wrong.

**A real keyset cursor, not a base64-wrapped offset.** CONVENTIONS.md §6 forbids
offset paging because a row inserted while somebody is on page 2 shifts one row
to page 3 unseen — here, a person who silently does not appear. The sort key is
`(full_name, id)`, not `full_name` alone: two people called Priya Sharma is a
normal thing for an organisation to contain, and a keyset over a non-unique
column skips or repeats them. `ResourceListIT.pagingIsLosslessAcrossDuplicateNames`
walks one row per page over exactly that pair.

**`openTicketCount` is a live count, and that is not the banned pattern.**
CLAUDE.md forbids live `COUNT(*)` for *dashboards*, which count the whole ticket
table on every render and must read the pre-aggregated summaries. This counts at
most 200 assignees' worth of rows through `ix_tickets_assignee_status`, once per
page — and it has to be current, because the number decides whether deactivating
somebody is allowed. A five-minute-old summary saying zero would let live work be
orphaned. (No summary tables exist yet in any case; A-039 owns them.)

**"Open" is `statuses.is_open`, never `<> 'CLOSED'`.** The status vocabulary is
master data an Admin extends through S-13. A literal would silently miscount the
first status added after this shipped.

**Bulk status answers 200 with per-resource outcomes, not one verdict.** A
selection of forty in which two hold open tickets is the normal case. Failing the
batch punishes the thirty-eight; succeeding quietly hides the two. Each row comes
back `CHANGED`, `UNCHANGED`, `BLOCKED_OPEN_TICKETS` or `NOT_FOUND`.

The blocked check sits **after** the already-in-that-state check. Before it,
somebody already deactivated but still holding open tickets — what a
half-finished reassignment leaves behind — would report blocked forever.

**The export ignores cursor and limit.** It is every matching row. One that
stopped at the current page would produce a file that looks complete and is not,
which is the failure nobody checks for because the file opens. It streams in
batches of 500 through the same page query the grid uses, so the file and the
screen cannot disagree about what a filter means.

**SXSSF, not XSSF.** The DOM writer builds the whole sheet in memory before a
byte is emitted; two admins exporting a five-thousand-person directory at once is
two full workbooks resident at the same time. Same rule B-031 carries for the
import template, applied on the way out.

**Every text cell is neutralised against formula injection.** A department typed
as `-Ops` is inert in the database and a negation in Excel. `=`, `+`, `-`, `@`,
tab and CR get a leading apostrophe.

## B-011 · the form

### `GET /users/{userId}` did not exist, and its absence was a defect

CONVENTIONS.md §5 pairs every `If-Match` write with a detail read that emits the
tag. `PATCH /users/{userId}` declared the precondition and there was nowhere to
obtain it, so **the operation could not be called correctly by anybody**.
`/projects/{id}` and `/clients/{id}` both have their detail reads; this was the
gap. Added here, along with the `428` for a `PATCH` that arrives without one.

### Five columns S-08 needs and `users` did not have

`V20260811_1520` adds `date_of_joining`, `avatar_url`, `location`, `weekly_off`
and `skills`. Mobile, department, designation, daily capacity and time zone were
already there. **Flagged for Stream A** — `users` is A-003's table.

`weekly_off` is nullable and null means "inherit the org working week", which is
different from `[]` — "this person has no weekly off", which a support rota is a
real reason to want. **B-024 does not read the override yet**, so a resource with
one still has their SLA computed against the org week. Wiring it in touches the
service every SLA figure routes through and is deliberately a separate change.

The same migration adds `ck_project_members_role`. B-011 is the first writer of
`role_in_project`, so this is the moment its vocabulary gets decided.

### `ProjectRoleCode` is not `RoleCode`, in both directions

`VIEWER` is a project role and not a global one — read-only access to one
project is a per-project grant, and a global viewer would mean read-only access
to everything. `ADMIN` is a global role and not a project one — an Admin already
sees every project through `ScopeResolver`, so an `ADMIN` membership would be a
grant that changes nothing, and a grant that changes nothing is one somebody
later assumes does something.

### Three states per key, not two

A `PATCH` has to distinguish "absent", "explicit null" and "a value", and a
plain nullable field carries only the last two. The optional fields on
`ResourceWriteRequest` are `Optional<T>`: null when the key is absent, `empty`
when it was sent as JSON null. For `reportingManagerId` that is the difference
between "I am not editing their manager" and "their manager has left, detach
them".

**This departs from B-006's MapStruct default**, which ignores null source
properties so a `PATCH` cannot blank a field it never sent. That gets the common
case right and makes clearing a nullable field impossible. This feature writes
through `JdbcClient` rather than a mapper, so it is not bound by that config.

`ResourceWriteRepository.update` names only the columns the caller sent. A fixed
statement listing every column would rewrite the untouched ones with values read
a moment earlier — the lost update `If-Match` exists to prevent, reintroduced one
column at a time and invisible to the precondition, because the tag was correct
when the request was built.

### Uniqueness is checked twice, and both are needed

The service asks which of the three fields collide before writing; the indexes
enforce it. The check is not redundant — an index can only refuse the whole
statement, and the form needs to know *which* field to highlight and would like
all three at once, so an admin fixing a duplicate username does not then discover
the duplicate email. The index remains the thing that is actually true: two
admins submitting the same username in the same millisecond both pass the check,
and `ResourceExceptionHandler` turns the resulting constraint violation into the
same 409.

### The temporary password

Generated, hashed with A-020's Argon2id encoder, and returned in
`meta.temporaryPassword` of the `201` — the only time it is ever readable. Its
own response schema (`UserCreatedResponse`) rather than a nullable field on
`User`, so that **no read operation has a type that could carry a credential**.
"Not populated by the list query" is a property of today's SQL; "not on the type
the list returns" is a property of the code.

`must_change_password` is set on create and is not a request field. An admin who
could clear it would be creating an account whose password somebody other than
its owner knows and will keep.

`TemporaryPasswords` omits `O`, `0`, `l`, `I` and `1`, and the shell-hostile
symbols. This string gets read off a screen, pasted into a chat window and typed
into a login box, and a failed first login on a fresh account looks exactly like
a provisioning bug.

### The generator and the policy are one rule (B-013)

§10.3 has a single statement in Java: `feature.auth.PasswordComplexity`. The
validator applies it to a password somebody typed; `TemporaryPasswordsTest`
asserts every generated password against the same class.

The duplication this removes was **silent**. Nothing re-checks composition at
login, so a policy strengthened in `feature.auth` with the generator left behind
would fail no request and no build — the organisation would go on issuing
temporary passwords its own rules reject, and the discovery would be an
auditor's question rather than a red suite.

The alphabets above are **not** shared, and should not be. The policy says what
is permitted; the generator is deliberately narrower, for reasons that apply to
a password somebody has to transcribe and to nothing a user chooses for
themselves.

`feature/auth` is Stream A's path — that edit is one new file plus a delegation,
changes nothing about what auth accepts, and is flagged for their sign-off in
`STREAM-B-MASTERS.md`.

### The form cannot get round the status route's guard

`PATCH /users/{id}` refuses to deactivate somebody holding open tickets, exactly
as `PATCH /users/{id}/status` and the bulk route do. It is the most discoverable
of the three paths, so an unguarded one here would be the one people used.

The check sits **after** the already-in-that-state check, the ordering
`ResourceService.apply` already establishes: otherwise somebody deactivated while
still holding tickets — what a half-finished reassignment leaves behind — could
never have any other field edited again.

### The `ETag` excludes `openTicketCount`

It is the one component that moves without anybody editing the resource.
Somebody else closing one of their tickets would invalidate an admin's open form
and produce a `412` naming a conflict that does not exist. The tag covers what
the `PATCH` can change, and no more.

### A MySQL `CHECK` violation is not a `DataIntegrityViolationException`

Error 3819 arrives under SQL state `HY000`, which Spring's state translator maps
to `UncategorizedSQLException` — so an unhandled `CHECK` violation surfaces as a
500. Acceptable here because the constraints are backstops: Bean Validation and
the service refuse the same values first, and `ResourceFormIT` proves both layers
independently. Worth knowing before adding a `CHECK` something reachable can trip.

## The export is its own route, and that is a finding

`GET /users/export`, not `?export=` on `listUsers` — which is what
`/reports/{key}` and `/audit-logs` do.

An operation declaring both `application/json` and `application/octet-stream`
generates a client whose return type is `Blob | UserListResponse`. B-010 shipped
it that way for an hour and **two of Stream C's working pages stopped compiling**
— the ticket list's assignee filter and the create form's picker both call
`useListUsers` and were suddenly required to narrow a union they have no interest
in. Every future caller would pay the same tax.

**The same latent break sits in `/reports/{key}` and `/audit-logs`.** Neither has
a consumer yet, which is the only reason it has not surfaced. Raised with Streams
A and D rather than changed here.

## Permissions

A-036's parameterised matrix does not exist yet, so the rule is stated in the
controller's Javadoc and enforced when it lands:

| Route | Who |
|---|---|
| `GET /users` | All six roles. The assignee picker, `@mention` resolution and the reportee tree are the same data; hiding it would break three features to protect a list of colleagues' names. No row scoping — a directory scoped to your own row is not a directory. |
| `GET /users/export` | All six roles, same reasoning. |
| `GET /users/{id}` | All six roles. It carries no credential: the projection names its columns and `password_hash`, `failed_attempts`, `locked_until` and `password_changed_at` are not among them. |
| `POST /users` | Admin only, like every master write. |
| `PATCH /users/{id}` | Admin only. |
| `PATCH /users/{id}/status` | Admin only. |
| `POST /users/bulk-status` | Admin only. |

Until A-034's `ScopeResolver` and A-033's `@PreAuthorize` land this runs under
`dev-noauth`. **No filtering is hand-rolled here as a stand-in** — CLAUDE.md is
explicit that a local workaround becomes the permanent hole.

## Tests

- `ResourceCursorTest` — round trip, names containing the separator, malformed input decoding to "first page" rather than a 400
- `ResourceServiceTest` — the probe row, the exact-multiple off-by-one, batched hydration, every bulk outcome, and the singular route's four translations
- `ResourceExportWriterTest` — both formats read back, the BOM, RFC 4180 quoting, formula neutralisation, 250 rows against a 100-row window
- `ResourceControllerTest` — query string to filter, role validation, export format validation, the separate-route guard, and that the status route is mounted where the contract puts it
- `ResourceListIT` — real MySQL: `LIKE` escaping, the collation, keyset paging across duplicate names, `is_open`, the `DATETIME(6)` round trip, and that the deactivation refusal clears once the tickets have a new owner
- `TemporaryPasswordsTest` — every generated password meets §10.3, over 2,000 samples rather than by construction-and-hope
- `ResourceWriteServiceTest` — the three states of a key, both guards, membership normalisation, the ETag's exclusions
- `ResourceFormIT` — real MySQL: every section round-tripping, the Argon2id hash, a partial update leaving other columns alone, a dropped membership deactivating rather than deleting, and the new `CHECK` constraints firing

`ResourceWriteServiceTest.deeperCyclesAreNotYetDetected` **documented a hole,
not a guarantee** — B-011 wrote it asserting that A→B→C→A was accepted, so that
closing it would be a visible inversion rather than a silent one. B-012 inverted
it to `deeperCyclesAreRefused`, which is the change this task is.

The cycle cases split across the two suites on purpose. `ResourceWriteServiceTest`
stubs the walk and proves what the service *decides* — that a truncated chain is
refused, that self-reference costs no query, that a refusal happens before the
`UPDATE`. `ResourceFormIT` is the only place the recursive CTE actually runs, and
is what would catch it being invalid SQL or MySQL disagreeing about termination.

## One thing this task fixed on the way past

`CalendarController` (B-023) was mapped at `/masters`, not `/api/v1/masters`.
Nothing declares that prefix globally, so all nine calendar operations answered
on a path no client calls — the generated TypeScript, the MSW handlers and the
contract all say `/api/v1/masters/…`. It survived review because
`CalendarControllerTest` constructs the controller with `new` and never asks
Spring where it is mounted. `MasterRoutesTest` now pins the prefix for every
controller in this feature.

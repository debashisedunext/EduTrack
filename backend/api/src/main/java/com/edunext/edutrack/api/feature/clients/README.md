# feature/clients

**Owner: Stream B · Ayush**

Client master and contacts. Screens S-32, S-33.

## What is here (B-025 · S-32)

| Class | What it is |
|---|---|
| `ClientController` | `GET /clients`, `GET /clients/{id}`, `POST /clients`, `PATCH /clients/{id}`, `PATCH /clients/bulk-status`, `PATCH /clients/{id}/status`, and the four contact routes |
| `ClientService` | Filters, the keyset page, the detail read, and the two status writes |
| `ClientWriteService` | B-026 · S-33's create and edit, and the validation set |
| `ClientContactService` | B-027 · S-33's Contacts tab — the child grid's read and its three writes |
| `ClientQueryRepository` | The grid's SQL — the page, and the four aggregates S-32 adds to it |
| `ClientWriteRepository` | B-026 · the reference checks and the `client_projects` replace |
| `ClientContactWriteRepository` | B-027 · the four `client_contacts` statements |
| `ClientStatus` / `ClientSupportPlan` | B-026 · the two vocabularies, stated once |
| `ClientDtos` | Wire types, matching `contracts/openapi.yaml` |
| `ClientExceptionHandler` | RFC 9457 problems, scoped to this controller |

**The six client operations had been in the contract, in the MSW mock and in the
generated TypeScript client since D-001 with no server behind them** — the sixth
instance of "declared, mocked, never mounted" this stream has found, after
B-023's nine calendar operations, B-014's `PATCH /users/{userId}/status`,
B-018's two SLA operations, B-020's `listTaskTypes` and B-021's
`listPriorities`. `ClientRoutesTest` pins the mount point.

## Permissions

Reads are open to all six roles; writes assert `master.write` and are Admin's
alone. The reads cannot be Admin-only: blueprint §4B.2 puts a client dropdown on
the ticket create form and §2 row 3 lets every role raise a ticket, so this
route is that dropdown. `PermissionMatrix` states all four rows.

## Four queries per page, never four per row

The page is read first; the aggregates — open tickets, last ticket date,
projects, primary contact — are then fetched for exactly the ids it returned.
Joining all three collections into the page query would multiply rows by their
product and leave the service de-duplicating what the database just fanned out.

This is not the `COUNT(*)` CLAUDE.md forbids: that rule is about dashboards,
which are read constantly and have summary tables built for them. This is an
Admin grid capped at 200 rows a page, keyed on `ix_tickets_client`.

## No delete, and that is the design

`tickets.client_id`, `client_contacts.client_id` and `client_projects.client_id`
all point here. Blueprint §4B.2: deactivating warns and blocks *new* tickets and
**never hides historical ones**. Going away is `status = 'INACTIVE'`.

Enforcing the "blocks new tickets" half is B-029's, on the ticket create path.
What this task owes that decision is the number it is made against, which is why
`openTicketCount` is on every row.

## What is here (B-026 · S-33)

The create/edit form's three operations. `GET /clients/{clientId}` is the one
B-025 predicted would be needed: `updateClient` has declared `If-Match` since
D-001 with **no read emitting an `ETag`**, so the operation was uncallable — the
same gap B-011 closed with `GET /users/{userId}` and B-016 with
`GET /projects/{projectId}`.

`ClientDetail` is a separate schema from `Client`, not more fields on it. The
list inlines a `Client` per client per page; twenty-five more fields would be
weight paid for by the grid, by §4B.2's ticket-form dropdown and by
`Client360Response`, none of which render them.

### `isActive` is `status <> 'INACTIVE'`, and that changed under B-026

B-025 derived it as `status = 'ACTIVE'`, exact while the column carried two
values. §4B.2's Identity group names a third — **Prospect** — and the ticket
create form's client dropdown filters on this boolean, so the narrow reading
would have removed every prospect from that form the moment the field shipped,
silently. `ClientStatus` carries the full argument; B-016 made the same call on
`Project.isActive`.

The consequence lands on the status setter: `isActive: true` against a Prospect
is a **no-op**, because a Prospect is already active by that projection. Writing
`ACTIVE` anyway would let S-32's bulk Activate promote a shortlist of prospects
into contracted clients.

### `contract_start` / `contract_end` are read through `JdbcClient`, not JPA

Not a style choice. `2025-04-01` written through JPA read back as
**2025-03-31**, measured with the JVM, the connection and the MySQL session all
at UTC and the raw column correct throughout — `hibernate.jdbc.time_zone` puts
Hibernate on the `getDate`/`Calendar` path, which loses the day where
`getObject(LocalDate.class)` does not. `ClientQueryRepository.contractDates` has
the whole account and `ClientMasterIT.contractDatesAreNotReadADayEarly` pins it.

**`Holiday.holidayDate` has the same bug and is not fixed here** — an org
holiday a day out means `WorkingHoursService` treats the wrong day as
non-working, and every SLA crossing it is wrong. Flagged for B-023's follow-up
and for Stream A, who own the property.

## What is here (B-027 · S-33's Contacts tab)

`GET`, `POST`, `PATCH` and `DELETE` under `/clients/{clientId}/contacts`.
`createClientContact` was the **seventh** "declared, mocked, never mounted"
operation this stream has found; the other two verbs were not merely unmounted
but **undeclared**, and without the `PATCH` an edit is remove-and-re-add — which
deactivates the row a historical ticket points at and issues a new id, rendering
a corrected phone number as a departure and an arrival.

### Removal deactivates, and the foreign key is why

`tickets.client_contact_id` references `client_contacts` **without** a cascade. A
real `DELETE` fails as a constraint violation naming a MySQL index; "fixing" that
with a cascade would rewrite who a historical ticket says reported it.
`ClientMasterIT.removalDeactivatesBecauseTheForeignKeyIsRestrictive` asserts the
`DELETE_RULE` against `information_schema` rather than leaving it in a comment,
the way B-020 did for task types.

`is_primary` is cleared in the same statement, because `primaryContacts` filters
on `is_active = 1` while `demoteOtherPrimaries` does not — a removed contact
keeping its flag gives two answers to "who is the primary" that disagree.

### `?includeInactive=` is what separates the grid from the picker

Default false. The grid sends true, so a removed contact is rendered as removed
and a ticket raised by one still renders their name; every picker leaves it off,
so somebody who left the client stops being offered on new tickets. B-021 made
the same split on `listPriorities` for the same reason.

### The primary flag is single-writer, and losing it is allowed

Promoting demotes every other row in the same transaction — "at most one primary"
is not expressible in MySQL, which has no partial unique index, and
`ClientContact`'s javadoc has named the service as the enforcer since B-005.

**Demoting or removing the last primary is permitted.** B-021 refused the mirror
case on `is_escalation_trigger` and the two differ in kind: a level with no
escalation target silently switches off one of §1's headline behaviours, whereas
a client with no primary contact is the state every client is *created* in, is
reported by `hasPrimaryContact`, and may simply be the truth after somebody
leaves. Refusing the demotion while the `DELETE` produces the same state anyway
would be one rule with two answers.

### A duplicate email is refused within the client, and only within it

Case-insensitively, agreeing with `utf8mb4_0900_ai_ci` and reading through
`ix_client_contacts_email` rather than wrapping the column in `UPPER()`. The same
address under two different clients is legitimate — a consultant retained by both
— which is why that index is deliberately not unique and why D-039 disambiguates
inbound mail on `website_domain`. There is **no index enforcing this**, so the
service is the only thing refusing it; `ClientMasterIT` asserts it against a real
container for that reason.

### No `If-Match` on the contact `PATCH`

Exempted in `check-conventions.py` with its reason: the tag would have to come
from `listClientContacts`, a collection with no `ETag` of its own — the
`PATCH /projects/{id}/members/{userId}` call, unchanged. **The parent does have
one and every contact write moves it**, since `contactCount` and
`hasPrimaryContact` are inside `ClientDetail`'s `hashCode`, so an S-33 form that
edits contacts and then saves the client is still stopped by a precondition one
level up. `contactQueries.ts` invalidates the client for exactly that reason.

## B-028 — the validation set, and where each rule is stated

Blueprint line 948 asks S-33 for three things. Two of them existed and disagreed
with themselves.

### One email rule, in `domain/validation/EmailFormat`

There were three answers to "is this a valid email?" on the same columns:
`FieldValidators.EMAIL` (B-030's importer) required a dotted TLD, Jakarta's
`@Email` on `ClientWriteRequest`/`ContactWriteRequest` did not, and zod's
`.email()` on both screens did not either. So S-33 accepted `accounts@acme`,
which `notificationOptIn: true` turns into a subscription D-036 can only bounce,
and which B-035's import — upserting on the client code S-33 just issued — would
reject on re-import.

The strict reading won, because every one of these columns exists to be *sent
to* or *matched against*. It lives in `domain` rather than in either feature:
`imports` already depends on `domain` (it builds `Client` entities) and neither
feature should depend on the other — B-024's placement argument for
`WorkingHoursService`, unchanged. `@Email` is gone from both write shapes and
the services apply it, which also puts an email failure into the collected error
map rather than short-circuiting a four-tab form at the binding layer.

### One client-code rule, in `domain/clients/ClientCodeFormat`

The form allowed `ACME-IN`; `FieldValidators.alphanumeric()` on the same column
did not. Since `client_code` is B-035's upsert key, that refusal was not a
message somebody reads — it was a client the import silently declined to update.
`@Pattern(regexp = ClientCodeFormat.REGEX)` and `FieldValidators.clientCode()`
are now one statement. `alphanumeric()` stays for the fields where letters and
digits really is the rule.

### The gate is reported here and enforced on `POST /tickets`

`Client.hasPrimaryContact` is on the **list** row as well as `ClientDetail`,
because the list row is what §4B.2's ticket-form dropdown renders. Not derived
from `primaryContact` at the call site: that field is `@JsonInclude(NON_NULL)`,
so a picker reading it gets a missing key. Both come off the same active-only
lookup, so they cannot disagree.

Enforcement belongs on the ticket create path, which is where a caller can act
on the refusal — and there is no server behind `POST /tickets` yet. The
obligation is written into that operation's contract description, beside
B-029's, and **flagged for Stream C**. The form shows an unmet client and
refuses the selection with the reason, rather than filtering it out: a client
that is simply absent from a dropdown is indistinguishable from a dropdown that
has lost its data.

### `?isActive=true` was dropping every prospect

`ClientQueryRepository.page` filtered `status = 'ACTIVE'` while the row it
returned projected `status <> 'INACTIVE'`. B-026 widened the projection when it
added `PROSPECT` and left the predicate alone, so a prospect came back from
`/clients` saying it was active and did not come back from
`?isActive=true` — the one call `CreateTicketPage` makes. `ClientService.ACTIVE`
is deleted rather than corrected, because the wrong comparison should not be one
autocomplete away.

## Not here yet

Nothing reads `clients.sla_policy_id`. C-012's `PlannedCloseDate` ladder
resolves org → project → task type and never consults it, so the form shows the
stored value read-only rather than offering a picker that writes a number nobody
looks at. **Flagged**: making it resolve is a C-012 change.

# feature/clients

**Owner: Stream B · Ayush**

Client master and contacts. Screens S-32, S-33.

## What is here (B-025 · S-32)

| Class | What it is |
|---|---|
| `ClientController` | `GET /clients`, `GET /clients/{id}`, `POST /clients`, `PATCH /clients/{id}`, `GET /clients/{id}/contacts`, `PATCH /clients/bulk-status`, `PATCH /clients/{id}/status` |
| `ClientService` | Filters, the keyset page, the detail read, and the two status writes |
| `ClientWriteService` | B-026 · S-33's create and edit, and the validation set |
| `ClientQueryRepository` | The grid's SQL — the page, and the four aggregates S-32 adds to it |
| `ClientWriteRepository` | B-026 · the reference checks and the `client_projects` replace |
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

## Not here yet

The contact writes are B-027's — `POST /clients/{clientId}/contacts` is a
seventh "declared, mocked, never mounted" operation waiting for it. B-026's
Contacts tab reads them and reports `hasPrimaryContact`, which is B-028's gate;
enforcing that gate belongs on the ticket create path, where a caller can act on
it.

Nothing reads `clients.sla_policy_id`. C-012's `PlannedCloseDate` ladder
resolves org → project → task type and never consults it, so the form shows the
stored value read-only rather than offering a picker that writes a number nobody
looks at. **Flagged**: making it resolve is a C-012 change.

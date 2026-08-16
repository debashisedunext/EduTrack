# feature/clients

**Owner: Stream B · Ayush**

Client master and contacts. Screens S-32, S-33.

## What is here (B-025 · S-32)

| Class | What it is |
|---|---|
| `ClientController` | `GET /clients`, `GET /clients/{id}/contacts`, `PATCH /clients/bulk-status`, `PATCH /clients/{id}/status` |
| `ClientService` | Filters, the keyset page, and the two status writes |
| `ClientQueryRepository` | The grid's SQL — the page, and the four aggregates S-32 adds to it |
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

## Not here yet

`POST /clients` and `PATCH /clients/{clientId}` are B-026's; the contact writes
are B-027's. `GET /clients/{clientId}` does not exist and B-026 will need it —
its `PATCH` takes `If-Match`, and without a detail read there is nowhere to get
the tag.

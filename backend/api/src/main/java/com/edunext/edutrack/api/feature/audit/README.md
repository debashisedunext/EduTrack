# feature/audit

**Owner: Stream A · Shivendra**

A-071 · S-16, the Audit Log Viewer. `GET /audit-logs`, Admin only, export only.

## What is here

| Piece | What |
|---|---|
| `AuditInterceptor` | writes a row for every mutating request, and for every 403 |
| `LoginAudit` | the six login-path terms, written by `AuthController` |
| `AuditActions` | the vocabulary, and how a route derives one term of it |
| `AuditController` / `AuditService` / `AuditQueryRepository` | the viewer |
| `AuditExportService` | the same rows as `.xlsx` or `.csv`, through A-064's writers |

The writer itself is `domain.audit.AuditTrail`, not here — it is called from
the login path as well, and a service in another stream that wants to record
something richer than a derived term calls it directly.

## The one decision this package turns on

**The log is derived from HTTP, not written by hand at each call site.**

A-071's line covers four modules owned by four developers. The obvious
implementation — `audit.record(...)` at the end of each service method — was
rejected twice over: it is an edit in Streams B, C and D's directories, and the
completeness of the result is exactly as good as the last person who remembered.
**An audit log with holes is worse than no audit log, because the holes are
invisible and the log is trusted.** So `AuditInterceptor` sits in
`afterCompletion` and every mutating route is covered the day it is written,
including routes that do not exist yet, with nobody outside Stream A changing a
line.

What that costs, stated rather than discovered:

- **No before-and-after on most rows.** The interceptor knows *that* a ticket
  was updated, not *which field*. `old_value`/`new_value` are left null rather
  than reconstructed from the request body, which would produce a diff that
  looks authoritative and is a guess. A service that genuinely holds both values
  calls `AuditTrail.record` itself and gets a second, richer row.
- **Best-effort, not non-repudiable.** `afterCompletion` runs after the response
  is sent, so a failed write cannot refuse the operation — `AuditTrail` logs at
  ERROR and swallows. Recording inside the business transaction would make "the
  operation did not happen if it could not be audited" true, and is exactly the
  per-call-site design rejected above. Coverage was chosen over strictness. The
  two compose: a specific operation can have both.
- **Granularity is the route, not the intent.** One user action that is three
  requests writes three rows.

## The vocabulary

`action` is `<LEAF>_<VERB>`; `entity_type` is the **module** — the first static
path segment, not the leaf resource.

```
POST   /api/v1/tickets/{ticketId}/comments   →  COMMENTS_CREATED   tickets  CRM-26-00347
PATCH  /api/v1/masters/roles/{roleId}         →  ROLES_UPDATED      masters  4
DELETE /api/v1/masters/holidays/{holidayId}   →  HOLIDAYS_DELETED   masters  12
GET    /api/v1/audit-logs            (403)    →  ACCESS_DENIED      audit_logs
```

Module rather than leaf is what makes S-16's "filter by module" a closed set of
about a dozen values instead of every leaf resource in the product, and it is
what makes `ix_audit_logs_entity (entity_type, entity_id)` answer "everything
that happened to this ticket" — §4A.7's Activity tab. The leaf is not lost; it
is the first half of the action.

Six terms are written by name because a route cannot say enough:
`LOGIN_SUCCESS`, `LOGIN_FAILED`, `LOGIN_THROTTLED`, `LOGIN_LOCKED_OUT`,
`LOGIN_2FA_FAILED`, `LOGOUT`. Every login outcome is the same route, and filing
them as one term would collapse the distinction the screen exists for. A wrong
second factor matters most: it is reachable only after a correct password, and
without its own term it leaves **no trace at all** — nothing counts it as a
failure, and the route records itself so the interceptor writes nothing either.

## What is deliberately not recorded

- **401.** An expired access token answers 401 on whatever the SPA was polling
  and the client silently refreshes — several rows per user per fifteen minutes,
  all describing a token lifetime rather than a person. **403 is recorded**: an
  authenticated human was told no, and on a read it is the only trace there is.
- **Failed mutations** other than 403. A validation error changed nothing.
- **`POST /auth/refresh`.** Measured, not argued: one idle browser wrote **eight
  rows in six minutes** on the first run against a real database. It fires on a
  timer, per session, for as long as anybody is signed in — into a table nothing
  can be pruned from. `LOGIN_SUCCESS` and `LOGOUT` bracket the session; the
  refreshes in between only say it had not ended yet. Token *reuse* is A-024's
  alarm and has its own path.
- **`/webhooks/**`.** The caller is a mail provider with no account, so every row
  would read as SYSTEM, which is reserved for our own scanners. `email_log`
  already covers it.
- **Reads.** Every successful `GET` would multiply the table by an order of
  magnitude to record that somebody looked at a screen. If read-auditing is ever
  wanted it should be scoped to specific resources, not switched on globally.

## Immutability

Four layers, and `V20260818_1500` is where the table stopped merely claiming it:

| Layer | Where |
|---|---|
| 1 · no writer but `record` | `AuditTrail`; `AuditLogRepository` narrowed off `JpaRepository` |
| 2 · no mutating route | this package; `AppendOnlyRulesTest.noRouteOffersToEditTheAuditLog` |
| 3 · `SELECT, INSERT` only | `docker/grants/apply-app-grants.sql` |
| 4 · triggers | `V20260818_1500__audit_log_immutability.sql` |

Layer 2 is stricter here than on `ticket_history`: **POST is banned too.** A
correction to history is a new row and must stay possible; an audit entry
submitted over HTTP is a forged one, with whatever actor and timestamp the
caller chose, indistinguishable in the table from a row the application wrote.

**There is no fifth layer.** `audit_logs` is not hash-chained, so nothing
detects tampering that first defeated the other four, and nothing detects a
truncated tail the way A-044's `chain_anchors` does for the three protected
tables. Chaining it is a real option and belongs with A-075's external
anchoring. Until then this table is strongly protected, not provably intact, and
the difference matters if it is ever quoted as evidence.

**Consequence of the DELETE trigger: the table only ever grows.** There is no
retention policy in the blueprint, and acquiring one by leaving DELETE available
would be the wrong order. Pruning is a DBA operation — drop the trigger,
archive, restore it — and that friction is the point.

## Scope

None, and that is deliberate. `audit.view` is Admin's alone (§2), and an Admin's
scope is everything, so a scope object here would have one branch and the
unexercised branch is the one that is wrong later. **If a second role is ever
granted `audit.view`, `AuditService` is what has to grow a scope** — recorded
because that change otherwise looks like a one-line edit to a seed migration.

## Not here

- **No `totalCount`.** Counting this table is not cheap and gets worse daily; the
  contract's `Meta.totalCount` is documented as "present only where a count is
  cheap". The screen says "most recent N" and offers Load more.
- **No PDF export.** The contract offers `xlsx` and `csv` here against three on
  reports. A PDF of an audit extract is a document somebody would reasonably
  treat as a signed record, and it is nothing of the kind.
- **Exports are capped at 10,000 rows**, and the cap is written onto the sheet —
  a file of exactly 10,000 rows is otherwise indistinguishable from a complete
  extract, and somebody will quote it as one.

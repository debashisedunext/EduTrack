# API conventions

**D-002.** Normative. Every endpoint in [`openapi.yaml`](openapi.yaml) follows
these, and a new endpoint that does not is a review comment, not a style
preference.

Blueprint §13 lists six conventions in one line. This is what each means in
practice, where it does *not* apply, and why — because an undocumented exception
gets "fixed" by the next person and the exception was usually the correct call.

---

## 1 · `/api/v1` prefix

Declared once in `servers`; no path repeats it. Version in the path rather than a
header because it survives being pasted into a browser, a curl command and a bug
report.

Breaking changes get `/api/v2` alongside `v1`. Adding an optional field is not
breaking; removing one, renaming one, or narrowing an enum is.

## 2 · `{ data, meta }` on success

Every 2xx JSON body is an object with `data`. Never a bare array, never a bare
scalar.

```json
{ "data": { "ticketId": "CRM-26-00347", … } }
{ "data": [ … ], "meta": { "nextCursor": "…", "hasMore": true } }
```

A bare array cannot grow a sibling field later without breaking every client.
`meta` carries pagination and anything else about the response rather than in it —
`unreadCount` on notifications, `grandTotalHrs` on effort logs.

**Audited: all 79 operations conform.**

## 3 · Errors are RFC 9457 problem documents

```
Content-Type: application/problem+json

{ "type": "https://edutrack/errors/stage-owner-required",
  "title": "Only the current stage owner may advance this ticket",
  "status": 422, "detail": "…", "traceId": "…" }
```

`type` is a stable URI and is what code branches on. `title` and `detail` are for
humans and may be reworded without notice — **do not match on them.**

> **This deviates from the blueprint**, which specifies `{ data, meta, error }`
> with errors wrapped in the same envelope. Recorded as **D-12** in PLAN.md §4.
>
> Wrapping a problem document in `{ error: … }` while still calling it
> `problem+json` would be wrong — RFC 9457 defines the body *as* the problem
> object. And Spring Boot 3 emits exactly this shape natively from
> `ProblemDetail`, so conforming costs nothing while the envelope version means
> hand-writing an exception handler that fights the framework.
>
> The distinct media type is itself useful: a client branches on
> `Content-Type` rather than probing for an `error` key.

Standard problems, all defined in `components/responses`:

| Status | When |
|---|---|
| `400` | Bean Validation failed. `errors` is field-keyed |
| `401` | No token, expired, or revoked |
| `403` | Permitted-by-role failure that **does not depend on a row existing** |
| `404` | Not found **or out of scope** — see §7 |
| `409` | Unique constraint, or an immutable field |
| `412` | `If-Match` stale — someone else changed the row |
| `413` `415` | Upload too large; type not allowed |
| `422` | The workflow forbids this move |
| `429` | Rate limited. Carries `Retry-After` |

## 4 · `Idempotency-Key` on every create

Every `POST` returning `201`, plus `/tickets/{id}/quick-update` and
`/imports/{schema}/commit`. Replaying a key within 24 hours returns the original
response instead of acting twice.

**Audited: all creates accept it.**

This is not defensive over-engineering. A retried request after a network timeout
is the normal case on mobile networks, and the specific failure it prevents —
duplicate effort logs — **cannot be cleaned up afterwards**, because effort logs
are append-only. There is no delete to reach for.

## 5 · `ETag` on reads, `If-Match` on writes

Detail reads return `ETag`; send `If-None-Match` to get `304`. Writes that risk a
lost update take `If-Match` and answer `412` when stale.

**`If-Match` applies where two people editing the same record silently overwrite
each other:**

| Endpoint | Why |
|---|---|
| `PATCH /users/{id}` | Field updates |
| `PATCH /projects/{id}` | Field updates |
| `PUT /projects/{id}/sla-policies` | Wholesale replace — the worst case for a lost update |
| `PUT /projects/{id}/settings` | Wholesale replace of three settings behind one Save button |
| `PATCH /clients/{id}` | Field updates |
| `PATCH /tickets/{id}` | Field updates |

**It deliberately does not apply here**, and these are not oversights:

| Endpoint | Why not |
|---|---|
| `PATCH /me/password` | `currentPassword` already proves you hold the current state |
| `PATCH /users/{id}/status`, `/clients/{id}/status` | Idempotent setters — last write wins is the correct semantic |
| `PATCH /notifications/{id}/read`, `/read-all` | Idempotent; a race is harmless |
| `PATCH /tickets/{id}/priority` | Reason is mandatory and every change is logged, so concurrent changes are visible rather than lost |
| `PATCH .../comments/{id}` | Author-only, five-minute window — nobody else can be editing it |
| `PATCH /projects/{id}/members/{userId}` | Two fields on one membership row. The tag would have to come from `listProjectMembers`, a collection with no `ETag` of its own — so honouring it would mean minting a per-member tag on a read nothing else preconditions, to guard a race whose loser typed a number a moment later and meant it |

`ETag` is on every detail read plus the three that are polled or expensive:
`/import-batches/{batchId}` (polled every couple of seconds while a job runs),
`/dashboard/widget/{key}` (summary tables refresh every five minutes, so a faster
poll gets `304` for free) and `/reports/{key}` (expensive to compute, and re-run
constantly as people toggle filters back and forth).

**And on two non-detail reads: `GET /projects/{id}/sla-policies` and
`GET /projects/{id}/settings`.** Each is the only source of the tag its own
`PUT` requires, so without it that operation is
uncallable — the gap B-016 closed by adding `GET /projects/{projectId}`, found
again on a route that had been in the spec since D-001 with no server behind it.
`check-conventions.py` does not catch this class: its rule fires on paths ending
in a path variable, and widening it to every collection read would fire on a
dozen paginated lists that legitimately have no tag — and `/settings` slips the
rule from the other side, since its `data` is an object rather than an array and
its path ends in a literal segment, so neither the detail-read rule nor the
collection rule has anything to say about it. **Pair the tag with the
precondition by hand — a `PUT` or `PATCH` whose `If-Match` has no read to come
from is not a strict endpoint, it is a broken one.**

## 6 · Cursor pagination, never offset

`?cursor=&limit=`, with `meta.nextCursor` and `meta.hasMore`. Default limit 50,
maximum 200.

Offset paging over a table being written to **skips and repeats rows**: insert a
ticket while somebody is on page 2 and one row shifts to page 3 unseen. On a
ticket list that is a ticket nobody works on.

**Paginated:** every top-level collection, plus `history`, `effort-logs`,
`comments`, `emails`, `chat messages`, `reportees`, `stage queue`, `audit logs`.

**Deliberately not paginated**, because each is bounded by a constraint the
product already enforces:

| Endpoint | Bound |
|---|---|
| `/masters/task-types`, `/masters/priorities` | 11 and 4 rows |
| `/masters/modules` | 8 seeded rows; a ninth is a row somebody adds |
| `/masters/permissions` | 18 rows, and reference data — a nineteenth arrives by migration, not by a screen |
| `/masters/roles` | The six of blueprint §2 plus whatever an Admin adds; the matrix is unreadable long before it is unpageable |
| `/masters/workflow-templates` | A handful per project |
| `/projects/{id}/sla-policies` | Task types × 4 levels |
| `/clients/{id}/contacts` | A short list per client |
| `/tickets/{id}/attachments` | Capped at 20 per ticket |
| `/notifications/pending` | Capped, and drained by acknowledging rather than paged |
| `/me/notification-preferences` | One row per `NotificationEvent` — 25, bounded by the enum |
| `/tickets/{id}/status-requests` | At most one open ask per manager entitled to make one |
| `/me/awaiting-response` | Your own unanswered asks, capped server-side |
| `/chat/ticket-cards` | Bounded by the caller's own `codes` list, and capped below that |
| `/projects/{id}/members` | One project's team — tens of people, and the S-10 Team tab totals their allocations, so it reads the whole set every time regardless |

These return `data` with no `meta`. That is the signal that the list is complete.

`/notifications/pending` (D-046) is the one exemption that is not a size bound.
It is a **queue**: the client acknowledges what it showed, and the next call
returns whatever is still unacknowledged. A cursor would point past rows that
have since left the result set entirely, which is a worse answer than no cursor.
It carries `hasMore` instead, so a capped page still says so.

`/me/awaiting-response` (D-056) is capped rather than paged for a different
reason: it is ordered **longest wait first** so the most-ignored question is at
the top, and a cursor over that order would page a manager *away* from the rows
the list exists to surface. A manager with more outstanding than the cap has a
problem no page control fixes.

---

## Two rules that are not negotiable

### 7 · Out-of-scope returns `404`, never `403`

Row scoping is applied server-side by `ScopeResolver` on every ticket query. A
caller cannot widen it with a query parameter.

A `403` on `/tickets/CRM-26-00347` tells the caller that ticket **exists**. That
is an existence leak, and enumerating IDs is trivial — they are sequential by
project. So out-of-scope and non-existent are indistinguishable.

`403` remains correct where the failure does not depend on a row: `/audit-logs` is
Admin-only regardless of what is in it.

### 8 · No mutation verb on append-only paths

`/tickets/{id}/history`, `/tickets/{id}/effort-logs` and `/audit-logs` expose
`GET` only. `/tickets/{id}/effort` exposes `POST` only.

**This is not an omission to be filled in later.** A correction is a new
compensating entry with `isCorrection` and `correctsEntryId`, exactly like an
accounting reversal. The database enforces the same rule independently through
triggers and grants, so a bug in the service layer cannot rewrite history — and
`SchemaIntegrationIT` proves it on every CI run.

If a task appears to need `PATCH` here, the design is wrong. Raise it.

---

## Reviewing a new endpoint

- [ ] Response wrapped in `{ data }`; list responses carry `meta`
- [ ] Errors are `application/problem+json` with a stable `type` URI
- [ ] `operationId` set — it becomes the generated client's function name
- [ ] Creates accept `Idempotency-Key`
- [ ] Detail reads return `ETag`; risky writes take `If-Match` and can answer `412`
- [ ] Collections take `cursor` and `limit`, **or** are on the bounded list above
- [ ] Scoped resources document `404` as "not found or out of scope"
- [ ] No mutation verb on anything append-only
- [ ] Every role has a documented outcome — permission-matrix entry required (A-036)

```bash
npx @redocly/cli lint contracts/openapi.yaml
npx openapi-typescript contracts/openapi.yaml -o /tmp/t.d.ts   # must compile --strict
```

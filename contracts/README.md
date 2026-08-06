# The API contract

[`openapi.yaml`](openapi.yaml) is the agreed shape of every endpoint in blueprint
§13 — 68 paths, 79 operations, 101 schemas. Owned by Stream D.

## It changes hands twice

**Now (D-001) — hand-authored.** No controllers exist yet, so the contract cannot
be generated from code. This file is the design being reviewed, and the thing all
four streams build against: Divyansh's mocks, Ayush's screens and Shivendra's
security all assume these shapes.

**From D-003 — generated.** `springdoc` emits the spec from the Java DTOs.
**Bean Validation annotations on those DTOs become the single source of truth for
validation rules** — `@NotBlank`, `@Size`, `@Pattern` — and springdoc renders them
into the schema. Nobody hand-writes a validation rule twice.

**From D-005 — enforced.** CI regenerates the TypeScript client and fails the
build if the committed one is stale. That check is the only thing standing between
Java and TypeScript now that shared Zod schemas are off the table (PLAN.md §2.2,
deviation D-4).

So this file's status flips from *specification* to *record of what was agreed*.
When the generated spec and this one disagree after D-003, that disagreement is a
bug in one of them — not something to reconcile by editing whichever is more
convenient.

## Checking it

```bash
npx @redocly/cli lint contracts/openapi.yaml
npx openapi-typescript contracts/openapi.yaml -o /tmp/t.d.ts   # must compile strict
```

Currently: **valid, 1 warning** — the `localhost` dev server entry, which is
deliberate and useful.

## What the shape itself enforces

Four rules are load-bearing. They are not stylistic, and changing them silently
breaks a guarantee somewhere else in the system.

**Out-of-scope IDs return `404`, never `403`.** A `403` on `/tickets/{id}` confirms
the ticket exists, which is an existence leak. `403` is reserved for failures that
do not depend on a row — an Admin-only screen. Every ticket route documents `404`
with this meaning.

**No mutation verb exists on `/history`, `/effort-logs` or `/audit-logs`.** Not an
omission to be filled in later. A correction is a new compensating entry
(`isCorrection`, `correctsEntryId`), and the database rejects mutation
independently through triggers and grants, so a bug in the service layer cannot
rewrite history. If a task appears to need `PATCH` here, the design is wrong.

**`cycleNo` and `iterationNo` are separate counters and appear separately
everywhere.** `iterationNo` increments on a backward move within a cycle; `cycleNo`
increments when a closed ticket is reopened. The journey roll-up joins effort logs
on *both* — omitting `cycleNo` double-counts cycle 1's effort into cycle 2, which
is a defect in the blueprint's own query (PLAN.md §3.4).

**Every create takes `Idempotency-Key`.** A retried request after a network timeout
is the normal case. Without it, a flaky connection produces duplicate tickets and
duplicate effort logs, and the effort logs cannot be deleted.

## Reviewing it

The contract needs sign-off from all four streams before Sprint 0 closes. What is
worth arguing about, by stream:

| Stream | Read closely |
|---|---|
| **A** Shivendra | `404`-not-`403` on every detail route · the `403` cases that remain · `availableActions` on ticket detail, which is where server-side permission resolution surfaces |
| **B** Ayush | `/imports/{schema}/…` — one engine, two registrations · the SLA matrix `PUT` replacing wholesale · workflow template versioning |
| **C** Divyansh | `TicketDetailResponse` — is one aggregated call enough for the page? · `Ribbon.canAdvance` · `QuickUpdateRequest`, which must stay narrow |
| **D** Debashis | STOMP destinations against §9.3 · `EmailLogEntry.nextAttemptAt` for outbox backoff |

Disagree in the pull request, not after the endpoint is built.

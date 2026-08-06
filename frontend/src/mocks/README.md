# The mock API

**`npm run dev` works with no backend running at all.** That is the whole point
of D-004 — Streams B and C build entire screens, ribbon included, before a single
endpoint exists. When the real one lands, the only change is a flag.

```bash
npm run dev                      # mocks on (default in development)
VITE_USE_MOCKS=false npm run dev # talk to a real backend via the Vite proxy
```

Owned by Stream D. Tests run against the same handlers — see
[`src/test/setup.ts`](../test/setup.ts).

---

## It holds state, on purpose

A mock that returns static JSON lets you build a *screen*. This one lets you
build a *flow*: create a ticket and it appears in the list; hand it off and the
ribbon advances and the receiving user gets a notification; log effort and the
journey roll-up changes.

Stream C's hardest work is the ribbon, and it cannot be built against a frozen
payload. State resets on reload, and `resetDb()` runs between tests.

Seeded deterministically — the same 24 tickets every time. A screenshot in a bug
report still matches next week.

## Walkthrough A is in there, to the hour

`CRM-26-00347` is blueprint §14 walkthrough A, and it is the fixture Stream C is
judged against:

| | |
|---|---|
| Cycle 1 | 24.5 h · 2 iterations · sealed |
| Cycle 2 | 13.5 h · 1 iteration |
| **Total** | **38.0 h across 5 named resources, 3 iterations** |

It exercises every hard case at once: a QA rework that increments
`iterationNo`, a reopen that increments `cycleNo` and seals cycle 1, an
auto-escalation from `SYSTEM` that leaves `originalLevel` intact, a bounced
email in the delivery log, a client-visible comment among internal ones, and a
Development stage with nine hours of effort against two days of duration — the
idle/active split that justifies the Journey tab.

`mocks.test.ts` asserts those totals. **If someone changes the fixture and the
sums stop reconciling, the test fails** rather than the discrepancy surfacing in
a demo.

## It enforces the rules, not just the shapes

| Rule | What the mock does |
|---|---|
| The golden rule | Only the current stage owner, PM or Admin may advance. Anyone else gets `422` |
| Row scoping | A Developer sees only `assignedTo = me`; PM and Support see their projects |
| 404, not 403 | An out-of-scope ticket is indistinguishable from a missing one |
| Effort at handoff | Missing `effortHours` is a `400`, not a silent success — decision G-1 |
| Two counters | Rework moves `iterationNo`; reopen moves `cycleNo`. Never both |
| Allowed returns | A backward move to a stage not in `canReturnTo` is `422` |
| Comments | Default internal; the five-minute edit window really expires |
| Deletes | Comments and attachments tombstone — nothing vanishes |

Building against a mock that returns everything to everyone means the first day
on the real backend is the day half the screens turn out empty for a Developer.

## A missing handler is loud

Any unmocked path under `/api/v1` returns **`501` naming the method and path**,
rather than falling through to the network where it fails with a confusing CORS
or connection error and sends you looking in the wrong place.

```json
{ "type": "https://edutrack/errors/mock-not-implemented",
  "title": "No mock handler for POST /api/v1/tickets/T-1/watchers",
  "hint": "Add one in frontend/src/mocks/handlers/ — Stream D owns D-004." }
```

In tests, `onUnhandledRequest: 'error'` makes it fail the test outright.

## Switching who you are

Scoping is mirrored, so the screens look different per role. The seed logs you
in as **Ravi (Developer)** — the most restricted view, because that is where
scoping bugs actually show up.

```ts
import { getDb } from '@/mocks/db';
getDb().currentUserId = 2;   // 1 Anita ADMIN · 2 Meera PM · 3 Ravi DEV
                             // 4 Anil QA · 5 Karan DEPLOY · 6 Priya SUPPORT
```

Or `POST /auth/login` with any of those usernames and any non-empty password.

## Layout

```
db.ts                 types, seed, the store
handlers/util.ts      envelope, problem+json, pagination, scoping, mappers
handlers/tickets.ts   tickets, comments, attachments, effort, history
handlers/ribbon.ts    handoff, rework, skip, journey, stage queue
handlers/rest.ts      auth, users, projects, clients, imports, masters,
                      dashboard, reports, notifications, chat, webhooks, audit
handlers/index.ts     composition + the 501 catch-all (must stay last)
browser.ts server.ts  the two entry points
```

## Adding an endpoint

1. Add it to `contracts/openapi.yaml` first — the contract leads.
2. `npm run api:generate`.
3. Add a handler here, in the file matching its tag.
4. If it has a rule worth getting wrong, add a test to `mocks.test.ts`.

There is ~120 ms of artificial latency on every response, so loading states are
visible while developing rather than only appearing on a slow connection in
production.

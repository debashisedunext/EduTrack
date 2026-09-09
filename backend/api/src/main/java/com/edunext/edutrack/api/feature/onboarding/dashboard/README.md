# feature/onboarding/dashboard

**Owner: Stream B · Ayush** — PHASE-2-BUILD-PLAN.md assigns OB-02 and OB-10 to
Stream B; A-108 named B-121 in the migration that created the tables read here.

OB-02, the card board.

## The routes

`GET /onboarding/dashboard/summary` (**B-121**) — seven counters, one round
trip, the board's whole first paint.

`GET /onboarding/dashboard/cards/{cardKey}/items` (**B-127**) — the S-06
slide-over behind one card, its own section below.

`GET /onboarding/dashboard/delayed-projects` and
`GET /onboarding/dashboard/implementor-workload` (**B-128**) — plan §9's two
grids, its own section further below. All four land in this package rather
than in packages of their own — that is why `ObDashboardCardKey` and
`ObDashboardScope` are types with a `fromWire`, an `appliedScope` and (since
B-127/B-128) three SQL predicates on them rather than private constants
inside one service.

## 🔴 Three of the seven cards overstate on the all-products board

The one thing to know before reading a number off this screen, and the thing
most likely to arrive as a bug report otherwise.

`ob_dashboard_summary` is keyed `(stat_date, product_id)`. Journeys and steps
belong to exactly one product, so `journeys_*`, `steps_*` and `rag_*` partition
across the product rows and summing them is **exact**.

`clients_overdue`, `clients_live` and `clients_escalated` are written by B-120
as `COUNT(DISTINCT ob_client_id)` *within* a product — which is what makes each
per-product figure right, and is A-108's stated intent: a client late on four
services is one client to chase. A client who bought ERP and Biometric and is
late on both contributes 1 to each row, so the sum says 2.

A distinct count cannot be reconstructed from per-group distinct counts. The
information is gone at write time, so no query against this table recovers it.

**What the API does about it:** `countIsUpperBound` on the card, and the screen
renders `≈` with the reason. Exact whenever `productId` is supplied — one row
is selected and nothing is summed.

**What actually fixes it:** storage, not arithmetic. Either an org-wide row
(blocked today by `product_id`'s foreign key to `ob_products`) or a
client-keyed `ob_client_daily_stats` beside the two A-108 created — which is
the shape ticketing already landed on with `client_daily_stats`. Both are
Stream A migrations plus a B-120 pass. Raised in `STREAM-B-MASTERS.md` under
B-121.

## 🔴 Two of the five module roles cannot be answered from this table at all

`OnboardingScopeResolver` narrows **OB_SALES** to "journeys whose client they
created" and **OB_STEP_OWNER** to "journeys containing their steps". The
summary table has no scope dimension — there is no column to intersect either
predicate against — and CLAUDE.md forbids answering a dashboard by counting the
journey tables live.

So the contract's "scoped by A-112's `OnboardingScopeResolver` like every other
read" and CLAUDE.md's "never a live `COUNT(*)` for dashboards" cannot both hold
for those two roles. This is a real conflict between a contract requirement and
the only permitted storage, not a filter left off.

B-121 resolves it by **saying so on the wire**: those two roles get seven cards
carrying `unavailableReason` and an `appliedScope` naming what they can see.
The two quiet alternatives are both worse.

- Return the org-wide numbers. Every other read they make is narrowed, so the
  board would disagree with the client list beside it and with the slide-over
  it opens — and it discloses the size of the book to somebody scoped out of
  most of it.
- Return zeroes. A zero renders as "nothing is overdue", which is a factual
  claim about the data and is false. A-056 settled this exact question for the
  ticketing widgets a Developer's table cannot serve, and the answer was words
  rather than an empty chart.

The fix is a scope dimension on the summary table. Same follow-up entry.

`TodayStatsRepository` records the same shape of gap one module over —
`resource_daily_stats` has no project column, so a PM's "my project's
resources" is approximated by membership, and the approximation is written
down. The difference here is that no approximation exists: "clients I created"
has no proxy among these columns.

## Smaller decisions worth not re-litigating

- **The delta compares the two most recent *stored* days**, not today and
  yesterday. A worker that was down on Tuesday leaves no Tuesday row, and
  `latest - 1 day` would find nothing and report every delta as null — which
  reads as "we have never had a previous day" rather than as "Tuesday is
  missing".
- **`computedAt: null` means B-120 has never run.** A-108 makes that reachable
  on purpose: both tables "start empty and fill forward from the day they
  land". It is a different claim from a computed board that happens to be
  quiet, and the cards say which by carrying an `unavailableReason` of their
  own.
- **`ongoing-projects` is locked + held + running**, written as the three open
  buckets rather than as `total - completed`. The buckets partition the total
  by construction, and naming the open three says what the card *means*.
- **`at-risk` is amber + red and excludes `journeys_locked`.** A journey whose
  gate has not cleared has no colour at all, and folding it in would put a
  whole fresh intake on this card.
- **The ETag hashes the applied scope, not only the URL.** Two callers with
  different onboarding roles ask the same URL; sharing a validator lets a cache
  hand one of them the other's board after a grant changes.
- **No ETag on an unavailable or never-computed board.** Both are states that
  change with no `computed_at` to prove it, so a stable validator would pin an
  empty board on screen until the URL changed.
- **Not the ticketing dashboard's types.** A-115's ArchUnit rule refuses the
  import and A-118 gives the three reasons above `/onboarding/dashboard/summary`
  — different shapes, non-overlapping vocabularies, and a module gate on the
  route tree that a shared route would leak past.

## Auth

`isAuthenticated()`, and the real decision is inside the service, from the
`moduleRoles` claim. "Manager and Admin only" is not something `RolePermissions`
can express: it is an onboarding role, and `JwtAuthoritiesConverter` does not
turn those into Spring authorities.

A caller with no `ONBOARDING` entitlement should get A-111's 404 before
reaching the handler. That guard is still written-but-unwired, so today they
reach it and get a board of unavailable cards. Deliberately not patched over
with a second gate here — a module gate in a feature package is how the first
one comes to be relaxed without anybody noticing.

## B-127 · `GET /cards/{cardKey}/items` — the S-06 slide-over

Landed in this package beside the summary, exactly as this file said it would:
`ObDashboardCardItemsController` (folded into `ObDashboardController`),
`ObDashboardCardItemsService`, `ObDashboardCardItemsRepository`,
`UnrecognisedCardKeyException` and `InvalidCursorException` (both 400 via
`ObDashboardExceptionHandler`). `ObDashboardScope` grew a `userId` and two SQL
predicates (`journeyPredicate`, `clientPredicate`) for it.

**Not a live `COUNT(*)`.** The count above stays pre-aggregated; this is a
bounded, `LIMIT`-and-cursor row fetch against `ob_journey_steps` and
`ob_client_prereq_tasks` directly — CLAUDE.md's rule is about the *count*
behind a dashboard, and a card's rows are not one.

**Every module role is answerable here, unlike the summary above.** The two
roles the summary board cannot narrow — OB_SALES, OB_STEP_OWNER — read this
route's own tables directly, which carry every column
`OnboardingScopeResolver` filters on. `ObDashboardCardItemsIT`'s
`theScopePredicateAndTheSpecificationAgree` asserts the SQL predicate and the
JPA specification select the same journeys, for all seven role inputs.

Per-card semantics (which rows a card's click surfaces) are documented on
`ObDashboardCardItemsRepository`'s class javadoc rather than repeated here —
restated from `ObDashboardStatsRepository`'s `OPEN`/`OVERDUE`/`AMBER`
constants, since the `worker` module cannot be depended on from `api`.

**A genuine contract gap, fixed and flagged.** `ObDashboardItemListResponse.meta`
was a bare `{ $ref: Meta }` while its own prose promised `computedAt`, which
`Meta` has no field for — the only place in the whole contract shaped that
way. Fixed with the `allOf` extension `ObNotificationListResponse.meta` and
`EffortLogListResponse.meta` already use for their own extra fields; the
frontend client is regenerated. Flagged for Stream A's sign-off since
`contracts/openapi.yaml` is not this stream's file.

- *`api/feature/onboarding/dashboard/` — 60 unit cases across 5 classes
  (`ObDashboardCardItemsServiceTest`, `ObDashboardCardItemsRepositoryTest`, the
  `ObDashboardScopeTest` additions, plus the two existing B-121 classes still
  green), 23 against real MySQL in `ObDashboardCardItemsIT`.*
- *`frontend/src/features/onboarding/dashboard/ObDashboardDrillPanel.tsx` — the
  generic panel, exported for B-128's workload-grid cells to open the same way
  via `ownerUserId`. `ObDashboardCardTile` needed no changes — B-121's own note
  said B-127 would only ever need to pass it a handler.*

## B-128 · `GET /delayed-projects` and `GET /implementor-workload` — the two grids

`ObDelayedProjectsRepository`/`ObDelayedProjectsService` and
`ObImplementorWorkloadRepository`/`ObImplementorWorkloadService`, folded into
`ObDashboardController` beside the two routes above. `ObDashboardScope`
grew `implementorPredicate` — a third SQL predicate beside B-127's two, over
`ob_implementor_daily_stats`'s own grain (one row per person, not per journey
or client). `ObDashboardCardItemsRepository.OVERDUE_SERVICE` and
`.JOURNEY_IS_RUNNING` went from `private` to package-private so
`ObDelayedProjectsRepository` restates neither a third time within one
package.

**Delayed Projects is a bounded live query, per CLAUDE.md's own carve-out for
this task** — not a `COUNT(*)`, a row fetch over currently-open, ungated,
overdue journeys, which is naturally a fraction of the journey table.
`delayedByDays` cannot live in SQL: it is ceiling working days through
`WorkingCalendarRepository`/`WorkingHoursService`, so the repository hands
back `expectedCompletionAt` as a plain instant and the service converts,
filters on `minDelayDays`, sorts and paginates in Java — the same split
`ObDashboardStatsRepository.refreshBlockedHours` makes for `blocked_hours`,
for the identical reason.

**`currentStep` and the row's `responsible` can name different steps.** The
overdue step (earliest due, `OVERDUE_SERVICE`) is what put the journey on the
grid and is always who `responsible` names; the in-flight step
(lowest-sequence `IN_PROGRESS`/`WAITING_ON_CLIENT`) is `currentStep`, and is
null exactly when the contract says it should be — "every step blocked or not
yet activated". `ObDashboardStepDot.rag` is always null on this route: computing
it for real would be a second per-row calendar call through
`ObJourneyStepRagService` for a chip plan §9 does not ask this grid to show.
Named as a decision, not discovered as a gap.

**Implementor workload never leaves `ob_implementor_daily_stats`** — B-120's
table, read straight through, `isActive` joined against
`user_module_access`. `performanceScore` is derived in
`ObImplementorWorkloadService`, never stored, from the four inputs A-108
names: quality from the three completion counters (an early finish weighted
above an on-time one, a late one weighted to zero), a capped penalty from
`blockedHours` converted to working days through the same calendar. Null for
zero completions, per the contract's own words.

**OB_SALES is denied on the workload route, not shown everyone's** —
`ob_implementor_daily_stats` carries no client-creator column, the identical
gap `ObDashboardScope`'s own header names for the summary board, restated for
a table keyed by person. OB_STEP_OWNER narrows to exactly their own row,
which this table's grain answers exactly, unlike the journey- and
client-scoped routes elsewhere in this module.

**The reports hub's Owner filter is narrowed by this route**, per the note it
carried since B-122 — `frontend/src/features/onboarding/reports/ObReportFilterBar.tsx`
now reads `listObImplementorWorkload` instead of the full user directory.
`features/onboarding/reports/` is this stream's own path.

- *`api/feature/onboarding/dashboard/` — `ObDelayedProjectsRepository`,
  `ObDelayedProjectsService`, `ObImplementorWorkloadRepository`,
  `ObImplementorWorkloadService`, the `ObDashboardDtos` additions and
  `ObDashboardScope.implementorPredicate`. Unit tests in
  `ObDelayedProjectsServiceTest`, `ObImplementorWorkloadServiceTest` and the
  `ObDashboardScopeTest` additions (mocked calendar/repository — the ceiling
  conversion, `minDelayDays`, the descending sort, the cursor, the
  `performanceScore` formula pinned to exact figures, and the
  sums-to-`clientsOpen` arithmetic asserted on a row read through the
  service); `ObDelayedProjectsIT` and `ObImplementorWorkloadIT` against real
  MySQL, including the Friday-due/Monday-open example CLAUDE.md itself names,
  run against the real `WorkingHoursService`.*
- *`frontend/src/features/onboarding/dashboard/ObDelayedProjectsGrid.tsx` and
  `ObImplementorWorkloadGrid.tsx` — wired into `ObDashboardPage.tsx` below the
  card board, in plan §9's order. `ObDashboardPage`'s `openCard` state became
  `drill: { cardKey, ownerUserId?, title? }` so the one `ObDashboardDrillPanel`
  instance serves both the card tiles and the workload grid's cells, exactly
  what B-127 built the panel's `title`/`ownerUserId` props for.*

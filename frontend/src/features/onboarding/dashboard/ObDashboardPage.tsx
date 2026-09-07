import { useGetObDashboardSummary } from '@/api/generated/onboarding/onboarding'
import { EmptyState } from '@/components/ui/empty-state'

import { ObDashboardCardTile, ObDashboardCardTileSkeleton } from './ObDashboardCardTile'

/**
 * B-121 · OB-02, the onboarding dashboard — `/onboarding/dashboard`.
 *
 * <h2>Seven counters, one request</h2>
 *
 * `GET /onboarding/dashboard/summary` is the board's whole first paint. Not
 * seven requests and not a request per card: the contract's own reasoning, and
 * the same call A-073 made for the ticketing dashboard after the per-widget
 * shape cost eleven round trips on load.
 *
 * <h2>Every number is pre-aggregated, and the screen says how old it is</h2>
 *
 * CLAUDE.md forbids a live `COUNT(*)` behind a dashboard, so these come from
 * `ob_dashboard_summary` as B-120's job last wrote it. That makes them up to
 * one refresh interval stale by design — and a board that cannot say so invites
 * somebody to compare it against a client list and file a bug about the
 * difference. Hence the "as of" line, which is `computedAt` and nothing
 * cleverer.
 *
 * A **null** `computedAt` is a different claim and gets a different screen: the
 * refresh has never run, which A-108 makes correct rather than broken for a
 * deployment's first days. An empty board there is honest; a board of zeroes
 * would not be.
 *
 * <h2>What this screen is not, yet</h2>
 *
 * The cards do not open anything. Plan §9 wants each to open the S-06 right
 * slide-over listing the matching clients, and that is **B-127** — the route
 * behind it (`/cards/{cardKey}/items`) is in the contract and has no
 * implementation. The tiles are already the right control for it: they render
 * as plain regions without an `onOpen`, so nothing here is a button that does
 * nothing when pressed, and B-127 passes a handler rather than rebuilding them.
 *
 * The two grids below the board — Delayed Projects, and Implementor workload &
 * performance — are **B-128**.
 */
export function ObDashboardPage() {
  const { data, isPending, isError } = useGetObDashboardSummary()

  const summary = data?.data
  const cards = summary?.cards ?? []

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-4 p-6">
      <header className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-lg font-semibold text-content">Onboarding</h1>
        {summary && <AsOf computedAt={summary.computedAt} appliedScope={summary.appliedScope} />}
      </header>

      {isError ? (
        <EmptyState
          title="The board could not be loaded"
          description="Refresh to try again. If it keeps failing, the onboarding module may not be enabled for your account."
        />
      ) : (
        <div
          /*
            A list, not a bare grid of divs. Seven tiles with no grouping are
            seven unrelated announcements; naming the group is what tells a
            screen-reader user how many there are and that they belong together.
          */
          role="list"
          aria-label="Onboarding summary"
          className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4"
        >
          {isPending
            ? Array.from({ length: 7 }, (_, index) => (
                <div role="listitem" key={index}>
                  <ObDashboardCardTileSkeleton />
                </div>
              ))
            : cards.map((card) => (
                <div role="listitem" key={card.key}>
                  <ObDashboardCardTile card={card} />
                </div>
              ))}
        </div>
      )}
    </div>
  )
}

/**
 * How stale the board is, and what it counted.
 *
 * Both in one line because they answer the same question — "why does this not
 * match what I am looking at" — and a user who has to hunt for them separately
 * will conclude the screen is wrong instead.
 *
 * `appliedScope` is the server's sentence, never re-derived here. CLAUDE.md's
 * rule is that scope is resolved server-side and never by a frontend filter,
 * and A-056 makes the narrower point this line depends on: saying it explicitly
 * is what stops the SPA re-deriving the role rule for itself.
 */
function AsOf({
  computedAt,
  appliedScope,
}: {
  computedAt?: string | null
  appliedScope?: string
}) {
  if (!computedAt) {
    // Not "as of never". The cards each carry their own sentence about it; this
    // line simply has nothing to add, and an "as of —" would read as a bug.
    return appliedScope ? (
      <p className="text-sm text-content-muted">Counting {appliedScope}</p>
    ) : null
  }

  return (
    <p className="text-sm text-content-muted">
      {appliedScope ? `Counting ${appliedScope} · ` : ''}
      as of{' '}
      <time dateTime={computedAt}>
        {new Date(computedAt).toLocaleString(undefined, {
          dateStyle: 'medium',
          timeStyle: 'short',
        })}
      </time>
    </p>
  )
}

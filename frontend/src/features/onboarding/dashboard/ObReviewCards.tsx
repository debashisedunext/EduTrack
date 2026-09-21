import { Link } from 'react-router-dom'

import { useGetObReviewSummary } from '@/api/generated/onboarding/onboarding'
import { cn } from '@/lib/utils'

import { OB_DASHBOARD_QUERY } from './obDashboardFreshness'

/**
 * C-141 · the two review cards, above the seven of OB-02.
 *
 * <h2>Why these are not an eighth and ninth card</h2>
 *
 * <p>The seven are a fixed layout keyed by `ObDashboardCardKey`, deliberately
 * closed, and they are counted **per product** out of `ob_dashboard_summary` —
 * every one of them a figure about this scope's clients. These are counted per
 * **person**, out of a different table, and mean nothing summed across a
 * product dimension. Forcing them into that vocabulary would have made the
 * board's one invariant quietly untrue.
 *
 * <h2>Two cards, one request, and a zero is not drawn</h2>
 *
 * <p>Plenty of people are both a manager and an implementor, and the client
 * cannot know in advance which figures it will need, so the route answers
 * both. A card with nothing in it is not rendered: an implementor who has
 * never sent anything sees neither, and the row disappears rather than
 * standing there as two zeroes.
 *
 * <h2>Stock and flow on one card, said in words</h2>
 *
 * <p>**Sent** is what is out right now; **approved** and **rejected** are what
 * happened today and reset overnight. The figures are not comparable and must
 * never look like a total, which is why the card says "today" on the two that
 * are and nothing on the one that is not.
 *
 * <p>The counts come from a summary table on a five-minute cycle, so they can
 * lag the queue they describe. That is why each card is a link rather than a
 * destination: the count invites, and My Tasks — computed live — is what
 * actually directs.
 *
 * <h2>Why it passes the board's freshness options</h2>
 *
 * <p>These two were the only cards on OB-02 reading on the app default — 30
 * seconds stale, no refetch on focus or on mount — so approving a row and
 * coming back to the board re-rendered the figures the reader had already
 * seen, and the one card describing the reader's own actions was the one that
 * would not admit they had happened. `OB_DASHBOARD_QUERY` is the same object
 * every other card passes; see `obDashboardFreshness.ts` for why it is
 * per-query rather than a change to the default.
 *
 * <p>It moves the figure within the worker's refresh window, not on the press:
 * the counters are pre-aggregated, and live `COUNT(*)` on a dashboard is not
 * something this project does.
 */
export function ObReviewCards() {
  const { data, isPending, isError } = useGetObReviewSummary({ query: OB_DASHBOARD_QUERY })
  const s = data?.data

  if (isPending || isError || !s) return null

  const pending = s.reviewsPending ?? 0
  const sent = s.sentForReview ?? 0
  const approved = s.reviewsApproved ?? 0
  const rejected = s.reviewsRejected ?? 0
  const asOf = computedAtLabel(s.computedAt)

  const hasManager = pending > 0
  const hasOwn = sent > 0 || approved > 0 || rejected > 0
  if (!hasManager && !hasOwn) return null

  return (
    <div
      data-testid="ob-review-cards"
      className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3"
    >
      {hasManager && (
        <Link
          to="/onboarding/my-tasks"
          data-testid="ob-review-card-pending"
          className={cn(
            'flex flex-col gap-0.5 rounded-card border border-level-medium bg-level-medium-soft',
            'px-4 py-3.5 text-level-medium-text shadow-rest',
            'hover:border-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
          )}
        >
          <span className="text-caption font-semibold">Pending your verification</span>
          <span className="text-3xl font-semibold tabular-nums leading-9">{pending}</span>
          <span className="text-[11.5px]">
            {/*
              Rows, not tasks, and it says so. A half-reviewed task is an
              ordinary state now, so "4 tasks" would say nothing about how much
              reading is left in them.
            */}
            check-list rows waiting on you · opens your queue
          </span>
        </Link>
      )}

      {hasOwn && (
        <Link
          to="/onboarding/my-tasks"
          data-testid="ob-review-card-own"
          className={cn(
            'flex flex-col gap-0.5 rounded-card border border-border bg-surface px-4 py-3.5 shadow-rest',
            'hover:border-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
            'sm:col-span-2',
          )}
        >
          <span className="text-caption font-semibold text-content-muted">Your verifications</span>
          <div className="mt-1 flex flex-wrap gap-4">
            <Figure n={sent} label="out for review" tone="review" />
            <Figure n={approved} label="approved today" tone="good" />
            <Figure n={rejected} label="came back today" tone="back" />
          </div>
          <span className="mt-1 text-[11.5px] text-content-muted">
            {/*
              One card rather than three tiles: they are one story, and split up
              they read as three unrelated numbers. The "today" on two of them
              is load-bearing — those two are events and reset overnight, the
              first is a standing figure, and nobody should add them up.
            */}
            check-list rows, not tasks — one task can be in all three
          </span>
          {/*
            When the figures were computed, because "today" on the two flow
            figures is a claim about freshness the reader cannot otherwise
            check. These come from a summary table a separate worker writes,
            so a figure that does not move has two possible explanations —
            nothing happened, or nothing is computing — and they are
            indistinguishable without this line. Every other card on the board
            already carries it; this one was reported as broken partly because
            it did not.
          */}
          {asOf && (
            <span data-testid="ob-review-card-own-asof" className="text-[11px] text-content-muted">
              {asOf}
            </span>
          )}
        </Link>
      )}
    </div>
  )
}

/**
 * "Counted at 14:32" — or nothing at all when the worker has never run.
 *
 * <p>Null rather than "unknown": on a database the stats worker has not
 * touched, the service answers four zeroes with a null stamp, and an empty
 * card that says nothing is honest where "counted at — " would only raise a
 * question the card cannot answer.
 */
function computedAtLabel(computedAt: string | null | undefined): string | null {
  if (!computedAt) return null
  const at = new Date(computedAt)
  if (Number.isNaN(at.getTime())) return null
  return `counted at ${at.toLocaleTimeString(undefined, { timeStyle: 'short' })}`
}

function Figure({ n, label, tone }: { n: number; label: string; tone: 'review' | 'good' | 'back' }) {
  return (
    <div className="flex min-w-[76px] flex-col gap-0.5">
      <b
        className={cn(
          'text-2xl font-semibold tabular-nums leading-8',
          tone === 'good'
            ? 'text-success-text'
            : tone === 'back'
              ? 'text-danger-text'
              : 'text-level-medium-text',
        )}
      >
        {n}
      </b>
      <span className="text-[11.5px] text-content-muted">{label}</span>
    </div>
  )
}

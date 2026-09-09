import { useNavigate } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import { useListObDelayedProjects } from '@/api/generated/onboarding/onboarding'
import type { ObDelayedProject } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

/** One page. Plan §9 draws this as a worklist, not an archive — {@link ObDashboardDrillPanel}'s own choice for the identical reason. */
const PAGE_LIMIT = 50

/**
 * B-128 · plan §9's first grid — `GET /onboarding/dashboard/delayed-projects`.
 *
 * <h2>One row per journey, not per client</h2>
 *
 * The contract's own reasoning, restated on the frontend: a client running an
 * ERP journey three weeks late and a Biometric journey on track is one
 * delayed project, not a client with an averaged status. `productsBought`
 * is still shown, because it is a fact about the client the grid names as a
 * column of its own.
 *
 * <h2>Ordered by delayed-by-days descending, and that column is the point</h2>
 *
 * The contract orders the grid "to be worked from the top" — this component
 * never re-sorts what the server sends, since a client-side sort would
 * silently disagree with a page boundary the server already drew.
 *
 * <h2>No product/owner filter bar yet</h2>
 *
 * The route accepts `productId`, `ownerUserId` and `minDelayDays`, and OB-02's
 * own design does not draw controls for this grid in v1.2 — plan §9 names the
 * columns, not a filter row. Left for a follow-up rather than guessed at.
 */
export function ObDelayedProjectsGrid() {
  const navigate = useNavigate()
  const { data, isPending, isError } = useListObDelayedProjects({ limit: PAGE_LIMIT })

  const rows = data?.data ?? []
  const hasMore = data?.meta?.hasMore ?? false

  return (
    <section
      aria-labelledby="ob-delayed-projects-heading"
      className="overflow-hidden rounded-card border border-border bg-surface shadow-sm"
    >
      <div className="flex flex-wrap items-center gap-2 px-4 pb-1 pt-3.5">
        <h2
          id="ob-delayed-projects-heading"
          className="text-[11px] font-semibold uppercase tracking-[.08em] text-content-muted"
        >
          Delayed projects
        </h2>
        <span className="text-xs text-content-muted">
          every journey running behind, with who holds it and the recomputed finish
        </span>
      </div>
      <div className="p-4 pt-2">
      {isPending ? (
        <div className="flex flex-col gap-2">
          {Array.from({ length: 4 }, (_, i) => (
            <Skeleton key={i} className="h-12 w-full" />
          ))}
        </div>
      ) : isError ? (
        <EmptyState
          title="The delayed projects grid could not be loaded"
          description="Refresh to try again."
        />
      ) : rows.length === 0 ? (
        <EmptyState
          title="Nothing is currently delayed"
          description="Every open journey with a due date is on schedule, so there is nothing to work from here."
        />
      ) : (
        <div className="overflow-x-auto rounded-card border border-border">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">
              Journeys currently past their expected completion, most delayed first.
            </caption>
            <thead className="text-xs text-content-muted">
              <tr>
                <th scope="col" className="py-2 pl-3 font-medium">Client</th>
                <th scope="col" className="py-2 font-medium whitespace-nowrap">Start date</th>
                <th scope="col" className="py-2 font-medium">Products bought</th>
                <th scope="col" className="py-2 font-medium">Module</th>
                <th scope="col" className="py-2 font-medium">Current stage</th>
                <th scope="col" className="py-2 font-medium">Responsible</th>
                <th scope="col" className="py-2 font-medium whitespace-nowrap">Expected completion</th>
                <th scope="col" className="py-2 pr-3 font-medium whitespace-nowrap">Delayed by</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <DelayedProjectRow
                  key={row.journeyId}
                  row={row}
                  onOpenClient={() => navigate(`/onboarding/clients/${row.obClientId}`)}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}

      {hasMore && (
        <p className="mt-2 text-xs text-content-muted">
          Showing the first {PAGE_LIMIT}, worst first. Narrow with a product or owner filter to see the rest.
        </p>
      )}
      </div>
    </section>
  )
}

function DelayedProjectRow({ row, onOpenClient }: { row: ObDelayedProject; onOpenClient: () => void }) {
  return (
    <tr
      tabIndex={0}
      onClick={onOpenClient}
      onKeyDown={(e) => {
        if (e.key === 'Enter') onOpenClient()
      }}
      className="cursor-pointer border-t border-border align-top hover:bg-subtle
                 focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2
                 focus-visible:outline-primary"
    >
      <td className="py-2 pl-3 pr-3 whitespace-nowrap font-medium text-content">{row.obClientName}</td>
      <td className="py-2 pr-3 whitespace-nowrap text-content-muted">
        {row.startedAt ? (
          <time dateTime={row.startedAt}>{format(parseISO(row.startedAt), 'd MMM yyyy')}</time>
        ) : (
          '—'
        )}
      </td>
      <td className="py-2 pr-3 text-content-muted">
        {row.productsBought && row.productsBought.length > 0
          ? row.productsBought.map((p) => p.code ?? p.name).join(', ')
          : '—'}
      </td>
      <td className="py-2 pr-3 text-content-muted">{row.product.name}</td>
      <td className="py-2 pr-3 text-content-muted">{row.currentStep ? row.currentStep.name : '—'}</td>
      <td className="py-2 pr-3 text-content-muted">{row.responsible?.displayName ?? '—'}</td>
      <td className="py-2 pr-3 whitespace-nowrap text-content-muted">
        {row.expectedCompletionAt ? (
          <time dateTime={row.expectedCompletionAt}>
            {format(parseISO(row.expectedCompletionAt), 'd MMM yyyy')}
          </time>
        ) : (
          '—'
        )}
      </td>
      <td className="py-2 pr-3 whitespace-nowrap">
        <Chip variant={row.delayedByDays >= 5 ? 'critical' : 'high'}>
          {row.delayedByDays} working {row.delayedByDays === 1 ? 'day' : 'days'}
        </Chip>
      </td>
    </tr>
  )
}

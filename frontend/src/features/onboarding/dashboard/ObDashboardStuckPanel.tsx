import { useNavigate } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import { useListObDashboardCardItems } from '@/api/generated/onboarding/onboarding'
import type { ObDashboardItem } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'

const ITEMS_LIMIT = 50

const STUCK_STATUSES = new Set(['BLOCKED', 'WAITING_ON_CLIENT'])

/**
 * "Blocked" or "Waiting on client" — `ObDashboardItem.status` is a plain
 * string (the contract's own note: a slide-over column with nothing to act
 * on by discriminating enums), so this is the only place that turns it into
 * the two chips the stuck table shows.
 */
function stuckChip(status: string) {
  return status === 'BLOCKED'
    ? <Chip variant="danger"><span aria-hidden="true">⛔</span> Blocked</Chip>
    : <Chip variant="neutral"><span aria-hidden="true">⏸</span> Waiting on client</Chip>
}

/**
 * B-121/A-118 · "Where it's stuck" and "TAT breaches" — plan §9's board,
 * beside the RAG columns. Both tables read the same route the seven cards'
 * own slide-over does (`listObDashboardCardItems`), because the contract
 * already shapes that data for exactly this: `ObDashboardItem.blockedReason`
 * is documented as "the 'Where it's stuck' table's Reason column reads it",
 * and `overdue-clients` is by definition every item `isOverdue`. Nothing
 * here is a new read — it is the `ongoing-projects` and `overdue-clients`
 * cards' own items, filtered and re-labelled for this section's two tables.
 */
export function ObDashboardStuckPanel() {
  const ongoing = useListObDashboardCardItems('ongoing-projects', { limit: ITEMS_LIMIT })
  const overdue = useListObDashboardCardItems('overdue-clients', { limit: ITEMS_LIMIT })

  const stuck = (ongoing.data?.data ?? []).filter((item) => STUCK_STATUSES.has(item.status))
  const breaches = overdue.data?.data ?? []

  return (
    <section aria-label="Where it's stuck" className="rounded-card border border-border bg-surface p-5 shadow-sm">
      <StuckTable
        title="Where it's stuck"
        description="Blocked or waiting-on-client steps, with the recorded reason."
        rows={stuck}
        isPending={ongoing.isPending}
        isError={ongoing.isError}
        emptyText="Nothing stuck right now."
      />
      <div className="mt-5">
        <StuckTable
          title="TAT breaches"
          description="Services past their due date, oldest first."
          rows={breaches}
          isPending={overdue.isPending}
          isError={overdue.isError}
          emptyText="No breaches. The scanner will flag one the moment it happens."
          breach
        />
      </div>
    </section>
  )
}

function StuckTable({
  title, description, rows, isPending, isError, emptyText, breach,
}: {
  title: string
  description: string
  rows: ObDashboardItem[]
  isPending: boolean
  isError: boolean
  emptyText: string
  /** TAT breaches: Client · Step · Owner · Due. Stuck: Client · Step · State · Reason. */
  breach?: boolean
}) {
  const navigate = useNavigate()

  return (
    <div>
      <h3 className="text-base font-semibold text-content">{title}</h3>
      <p className="mt-0.5 text-xs text-content-muted">{description}</p>

      {isPending ? (
        <div className="mt-3 flex flex-col gap-2">
          {Array.from({ length: 2 }, (_, i) => (
            <Skeleton key={i} className="h-10 w-full" />
          ))}
        </div>
      ) : isError ? (
        <p className="mt-3 text-xs text-content-muted">This list could not be loaded.</p>
      ) : rows.length === 0 ? (
        <p className="mt-3 rounded-control bg-subtle px-3 py-4 text-center text-xs text-content-muted">
          {emptyText}
        </p>
      ) : (
        <div className="mt-3 overflow-x-auto rounded-control border border-border">
          <table className="w-full text-left text-sm">
            <thead className="text-xs text-content-muted">
              <tr>
                <th scope="col" className="bg-subtle py-2 pl-3 font-medium">Client</th>
                <th scope="col" className="bg-subtle py-2 font-medium">Step</th>
                {breach ? (
                  <>
                    <th scope="col" className="bg-subtle py-2 font-medium">Owner</th>
                    <th scope="col" className="bg-subtle py-2 pr-3 font-medium">Due</th>
                  </>
                ) : (
                  <>
                    <th scope="col" className="bg-subtle py-2 font-medium">State</th>
                    <th scope="col" className="bg-subtle py-2 pr-3 font-medium">Reason</th>
                  </>
                )}
              </tr>
            </thead>
            <tbody>
              {rows.map((item) => (
                <tr
                  key={`${item.itemType}-${item.itemId}`}
                  tabIndex={0}
                  onClick={() => navigate(`/onboarding/clients/${item.obClientId}`)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') navigate(`/onboarding/clients/${item.obClientId}`)
                  }}
                  className="cursor-pointer border-t border-border hover:bg-subtle"
                >
                  <td className="py-2 pl-3 pr-3 font-medium text-content">{item.obClientName}</td>
                  <td className="py-2 pr-3 text-content-muted">{item.title}</td>
                  {breach ? (
                    <>
                      <td className="py-2 pr-3 text-content-muted">{item.owner?.displayName ?? '—'}</td>
                      <td className="py-2 pr-3">
                        <Chip variant="danger">
                          <span aria-hidden="true">⚠</span>{' '}
                          {item.dueAt ? format(parseISO(item.dueAt), "d MMM, HH:mm") : 'overdue'}
                        </Chip>
                      </td>
                    </>
                  ) : (
                    <>
                      <td className="py-2 pr-3">{stuckChip(item.status)}</td>
                      <td className="py-2 pr-3 text-content-muted">
                        {item.blockedReason ?? 'Waiting on client input'}
                      </td>
                    </>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

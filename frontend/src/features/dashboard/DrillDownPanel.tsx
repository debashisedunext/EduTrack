import * as React from 'react'
import { useNavigate } from 'react-router-dom'
import { format, parseISO } from 'date-fns'

import { useListTickets } from '@/api/generated/tickets/tickets'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import {
  SlideOver,
  SlideOverBody,
  SlideOverContent,
  SlideOverFooter,
  SlideOverHeader,
  SlideOverTitle,
} from '@/components/ui/slide-over'
import { toast } from '@/components/ui/use-toast'

import { buildDrillDownCsv, MAX_ROWS } from './drillDownCsv'
import { describeDrillDown, drillDownToParams } from './drillDownParams'
import { useDrillDownStore } from './drillDownStore'

/**
 * A-061 · §S-06, the chart drill-down modal.
 *
 * <p>Slides in from the right, shows the filtered grid, and offers "Open full
 * list" and a CSV export — the three things §S-06 asks for.
 *
 * <h2>Why a panel at all, when the link already worked</h2>
 *
 * A-060 made every card and segment open a correctly filtered list. That is a
 * full page navigation, and it costs the reader their place: the dashboard is a
 * scanning surface, and checking *which eleven tickets* is a glance, not a
 * destination. The panel answers the glance and keeps "Open full list" for when
 * it turns out to be a destination after all.
 *
 * <h2>The panel is a second reader of the same string, never a second filter</h2>
 *
 * It fetches from the identical server-built `/tickets?…` the link uses, parsed
 * by {@link drillDownToParams}. Nothing here decides what a card means. That
 * matters because a modal showing eleven rows above a card reading twelve is
 * exactly the disagreement A-055 and A-060 were both written to prevent, and a
 * panel that built its own query would be the third place for it to reappear.
 *
 * <h2>Preview, not the ticket list</h2>
 *
 * A deliberately small set of columns and a single page. It is not a second
 * implementation of S-17 — that screen has column choosing, density, saved
 * views and paging, and reproducing any of it here would be two ticket lists to
 * keep in step. When the reader wants those, "Open full list" is right there
 * and lands on the real one, filtered identically.
 */

/** One page. The panel is a look, not a workspace — see the class note. */
const PREVIEW_LIMIT = 25

export function DrillDownPanel() {
  const navigate = useNavigate()
  const drillDown = useDrillDownStore((s) => s.drillDown)
  const title = useDrillDownStore((s) => s.title)
  const close = useDrillDownStore((s) => s.close)
  const cardCount = useDrillDownStore((s) => s.count)

  const [exporting, setExporting] = React.useState(false)

  const params = React.useMemo(
    () => (drillDown ? drillDownToParams(drillDown) : null),
    [drillDown],
  )

  const { data, isPending, isError } = useListTickets(
    { ...(params ?? {}), limit: PREVIEW_LIMIT },
    // `enabled` rather than conditionally calling the hook, which React forbids.
    // Closed means no request at all — a panel nobody opened should not be
    // fetching the last filter they looked at.
    { query: { enabled: drillDown !== null } },
  )

  const rows = data?.data ?? []
  // D-064 · the figure the clicked card printed, carried through the store.
  // `meta.totalCount` is deliberately absent for tickets — the contract calls it
  // "present only where a count is cheap, never computed live over tickets" —
  // so it is kept as a fallback for any future list that does supply one
  // cheaply, and the card's own number wins when there is one.
  const total = cardCount ?? data?.meta?.totalCount
  const hasMore = data?.meta?.hasMore ?? false

  async function exportCsv() {
    if (!params || !drillDown) return
    setExporting(true)
    try {
      const csv = await buildDrillDownCsv(
        params,
        title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '') || 'drill-down',
        new Date().toISOString().slice(0, 10),
      )
      download(csv.filename, csv.content)
      toast({
        title: `Exported ${csv.rowCount} ticket${csv.rowCount === 1 ? '' : 's'}`,
        // Never silently. A file short of the filter it claims to represent is
        // worse than no file, because nobody re-checks a download.
        description: csv.truncated
          ? `Stopped at the ${MAX_ROWS.toLocaleString()}-row limit — narrow the filter, or use Reports for the full set.`
          : undefined,
      })
    } catch {
      toast({
        title: 'The export could not be completed',
        description: 'Nothing was downloaded. Try again, or open the full list.',
        variant: 'danger',
      })
    } finally {
      setExporting(false)
    }
  }

  return (
    <SlideOver open={drillDown !== null} onOpenChange={(next) => !next && close()}>
      <SlideOverContent className="max-w-2xl" aria-describedby="drill-down-description">
        <SlideOverHeader>
          <SlideOverTitle>{title}</SlideOverTitle>
          <p id="drill-down-description" className="text-xs text-[color:var(--text-secondary)]">
            {drillDown ? describeDrillDown(drillDown) : ''}
            {total != null && ` · ${total.toLocaleString()}`}
          </p>
        </SlideOverHeader>

        <SlideOverBody>
          {isPending ? (
            <div className="flex flex-col gap-2">
              {Array.from({ length: 6 }, (_, i) => (
                <Skeleton key={i} className="h-10 w-full" />
              ))}
            </div>
          ) : isError ? (
            <EmptyState
              title="These tickets could not be loaded"
              description="The figure on the dashboard is unaffected. Try opening the full list."
            />
          ) : rows.length === 0 ? (
            <EmptyState
              title="Nothing matches this filter"
              // A figure that opens an empty panel is a real discrepancy, not a
              // dead end — the summary tables are up to five minutes behind the
              // tickets, and saying so beats leaving somebody to wonder.
              description="The dashboard reads figures computed up to five minutes ago, so a ticket closed since then will already have left this list."
            />
          ) : (
            <table className="w-full text-left text-sm">
              <caption className="sr-only">{`${title} — ${describeDrillDown(drillDown ?? '')}`}</caption>
              <thead className="text-xs text-[color:var(--text-secondary)]">
                <tr>
                  <th scope="col" className="py-2 font-medium">Ticket</th>
                  <th scope="col" className="py-2 font-medium">Title</th>
                  <th scope="col" className="py-2 font-medium">Level</th>
                  <th scope="col" className="py-2 font-medium">Assignee</th>
                  {/* D-064 · created by default, as asked on 18 Aug. */}
                  <th scope="col" className="py-2 font-medium whitespace-nowrap">Created</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((ticket) => (
                  <tr
                    key={ticket.ticketId}
                    className="border-t border-[color:var(--border)] align-top"
                  >
                    <td className="py-2 pr-3 whitespace-nowrap">
                      <button
                        type="button"
                        onClick={() => {
                          close()
                          navigate(`/tickets/${ticket.ticketId}`)
                        }}
                        className="text-[color:var(--primary)] underline-offset-2 hover:underline
                                   focus-visible:outline focus-visible:outline-2
                                   focus-visible:outline-offset-2
                                   focus-visible:outline-[color:var(--primary)] rounded-sm"
                      >
                        {ticket.ticketId}
                      </button>
                    </td>
                    <td className="py-2 pr-3">{ticket.title}</td>
                    <td className="py-2 pr-3">
                      <Chip variant={levelVariant(ticket.level)}>{ticket.level}</Chip>
                    </td>
                    <td className="py-2 pr-3 text-[color:var(--text-secondary)]">
                      {ticket.assignee?.displayName ?? 'Unassigned'}
                    </td>
                    <td className="py-2 whitespace-nowrap text-[color:var(--text-secondary)]">
                      {ticket.createdAt ? format(parseISO(ticket.createdAt), 'd MMM yyyy') : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {hasMore && (
            <p className="mt-3 text-xs text-[color:var(--text-secondary)]">
              Showing the first {PREVIEW_LIMIT}. Open the full list to page through the rest.
            </p>
          )}
        </SlideOverBody>

        <SlideOverFooter>
          <Button
            variant="secondary"
            onClick={exportCsv}
            disabled={exporting || rows.length === 0}
          >
            {exporting ? 'Exporting…' : 'Export CSV'}
          </Button>
          <Button
            onClick={() => {
              // Closed first: navigating with the panel open leaves Radix
              // restoring focus into a tree the router has already replaced.
              close()
              if (drillDown) navigate(drillDown)
            }}
          >
            Open full list
          </Button>
        </SlideOverFooter>
      </SlideOverContent>
    </SlideOver>
  )
}

/**
 * §12.1's level tokens, via C-003's chip — which already carries a variant per
 * level, so this maps rather than restyles. Introducing a colour here would
 * give the same four levels one palette on the ticket list and another in this
 * panel, on the same screen, for the same tickets.
 */
function levelVariant(level: string | undefined): 'low' | 'medium' | 'high' | 'critical' | 'neutral' {
  switch (level) {
    case 'LOW':
      return 'low'
    case 'MEDIUM':
      return 'medium'
    case 'HIGH':
      return 'high'
    case 'CRITICAL':
      return 'critical'
    default:
      return 'neutral'
  }
}

/**
 * Hands the browser a file.
 *
 * <p>An object URL and a synthetic click, revoked on the next frame. Kept here
 * rather than in `drillDownCsv` so that module stays pure and testable — the
 * CSV it builds is a string, and asserting on a string is the whole reason the
 * download is not part of it.
 */
function download(filename: string, content: string) {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/csv;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = filename
  document.body.appendChild(anchor)
  anchor.click()
  anchor.remove()
  URL.revokeObjectURL(url)
}

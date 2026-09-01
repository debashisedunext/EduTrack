import * as React from 'react'
import { Link } from 'react-router-dom'
import { format, parseISO } from 'date-fns'
import { ChevronDown } from 'lucide-react'

import { useListTickets } from '@/api/generated/tickets/tickets'
import { AvatarStack } from '@/components/ui/avatar-stack'
import { Chip } from '@/components/ui/chip'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { LEVEL_VARIANT, STATUS_LABEL, STATUS_VARIANT } from '@/features/tickets/list/columns'

import {
  TODAY_SECTIONS,
  todaySectionListParams,
  todaySectionViewAllHref,
  type TodaySectionKey,
  type TodaySectionScope,
} from './todaySectionQueries'

/**
 * Dashboard Rework Dev 1 · tab 1, PR 8 — the six collapsible sections below
 * the Assignee MIS grid.
 *
 * <h2>Lazy-fetching on expand, per the plan</h2>
 *
 * Six independent `GET /tickets` calls firing the moment the Today tab loads
 * would be exactly what CLAUDE.md's "no live `COUNT(*)` for any dashboard
 * figure" rule is guarding against in spirit — the figures above already come
 * from the summary tables; there is no reason the *list* behind an unopened
 * section should be fetched before anyone asks to see it. Each section's own
 * `useListTickets` call is gated on `enabled: open`.
 *
 * <h2>Rendered on both variants</h2>
 *
 * Unlike the MIS grid, these sections are not skipped for the OWN_WORK
 * variant — `GET /tickets` is row-scoped server-side by `ScopeResolver`
 * regardless of who is asking, so a Developer's "Not started" section
 * already comes back as their own not-started tickets with no client-side
 * branching needed here.
 */
export function TodaySections({ scope }: { scope: TodaySectionScope }) {
  return (
    <div className="flex flex-col gap-3">
      {TODAY_SECTIONS.map((section) => (
        <TodaySection key={section.key} sectionKey={section.key} title={section.title} scope={scope} />
      ))}
    </div>
  )
}

function TodaySection({
  sectionKey,
  title,
  scope,
}: {
  sectionKey: TodaySectionKey
  title: string
  scope: TodaySectionScope
}) {
  const [open, setOpen] = React.useState(false)
  const panelId = `today-section-${sectionKey}`

  const params = todaySectionListParams(sectionKey, scope)
  const { data, isPending, isError } = useListTickets(params, { query: { enabled: open } })
  const rows = data?.data ?? []
  const total = data?.meta?.totalCount ?? rows.length
  const hasMore = data?.meta?.hasMore ?? false

  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)]">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2 p-3 text-left
                   focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                   focus-visible:outline-[color:var(--primary)]"
      >
        <span className="text-sm font-semibold text-[color:var(--text-primary)]">{title}</span>
        {open && !isPending && !isError && (
          <span className="rounded-chip bg-subtle px-2 py-0.5 text-xs font-semibold text-content-muted">
            {total}
          </span>
        )}
        <ChevronDown
          className={`ml-auto h-4 w-4 text-[color:var(--text-tertiary)] transition-transform ${open ? 'rotate-180' : ''}`}
          aria-hidden="true"
        />
      </button>

      {open && (
        <div id={panelId} className="border-t border-[color:var(--border)]">
          {isPending ? (
            <div className="flex flex-col gap-2 p-3">
              {Array.from({ length: 3 }, (_, i) => (
                <Skeleton key={i} className="h-8 w-full" />
              ))}
            </div>
          ) : isError ? (
            <EmptyState
              title="These tickets could not be loaded"
              description="The figures above are unaffected. Try expanding this section again."
            />
          ) : rows.length === 0 ? (
            <EmptyState title="Nothing here" description="No tickets currently match this section." />
          ) : (
            <>
              <div className="overflow-x-auto">
                <Table>
                  <caption className="sr-only">{title}</caption>
                  <TableHeader>
                    <TableRow>
                      <TableHead scope="col">ID</TableHead>
                      <TableHead scope="col">Description</TableHead>
                      <TableHead scope="col">Assignee</TableHead>
                      <TableHead scope="col">Level</TableHead>
                      <TableHead scope="col">Status</TableHead>
                      <TableHead scope="col">Due</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {rows.map((ticket) => (
                      <TableRow key={ticket.ticketId}>
                        <TableCell className="whitespace-nowrap font-mono">{ticket.ticketId}</TableCell>
                        <TableCell className="max-w-[24rem] truncate" title={ticket.title}>
                          {ticket.title}
                        </TableCell>
                        <TableCell>
                          {ticket.assignee ? (
                            <div className="flex items-center gap-2 whitespace-nowrap">
                              <AvatarStack
                                people={[{ id: String(ticket.assignee.id), name: ticket.assignee.displayName }]}
                                max={1}
                                size="sm"
                              />
                              <span>{ticket.assignee.displayName}</span>
                            </div>
                          ) : (
                            <span className="text-content-muted">Unassigned</span>
                          )}
                        </TableCell>
                        <TableCell>
                          <Chip variant={LEVEL_VARIANT[ticket.level]}>{ticket.level}</Chip>
                        </TableCell>
                        <TableCell>
                          <Chip variant={STATUS_VARIANT[ticket.status]}>{STATUS_LABEL[ticket.status]}</Chip>
                        </TableCell>
                        <TableCell className="whitespace-nowrap">
                          {ticket.plannedCloseDate ? format(parseISO(ticket.plannedCloseDate), 'd MMM') : '—'}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              {hasMore && (
                <div className="p-3 text-xs">
                  <Link
                    to={todaySectionViewAllHref(sectionKey, scope)}
                    className="text-[color:var(--primary)] underline-offset-2 hover:underline
                               focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2
                               focus-visible:outline-[color:var(--primary)] rounded-sm"
                  >
                    View all in ticket list →
                  </Link>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  )
}

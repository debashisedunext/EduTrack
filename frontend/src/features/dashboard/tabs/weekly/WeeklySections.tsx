import * as React from 'react'
import { Link } from 'react-router-dom'
import { ChevronDown } from 'lucide-react'

import { useListTickets } from '@/api/generated/tickets/tickets'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { GroupedTicketAccordion, type WeeklyTicketRow } from './GroupedTicketAccordion'
import {
  WEEKLY_SECTIONS,
  WEEKLY_SECTION_PAGE_SIZE,
  weeklySectionParams,
  weeklySectionViewAllHref,
  type WeeklySectionDef,
  type WeeklySectionScope,
} from './weeklySectionQueries'

/**
 * S-05 tab 3, PR 13b — the five grouped accordion sections below the cards.
 *
 * <h2>Lazy on expand, like the Today tab's six</h2>
 *
 * Five `GET /tickets` calls firing when the tab loads would be five live
 * queries behind a screen whose figures deliberately come from summary
 * tables. Each section's `useListTickets` is gated on `enabled: open`, so a
 * section nobody opens costs nothing — `TodaySections`' own reasoning.
 *
 * <h2>The badge is the list's own total, and the accordion beneath it must agree</h2>
 *
 * The plan's definition of done: "accordion group totals must equal the
 * section badge count — if they diverge, the two are querying differently and
 * that is a defect to fix, not a discrepancy to explain." Both numbers here
 * come from one fetch: the badge is `meta.totalCount` where the server sends
 * it and the row count otherwise, and the group counts are `groupTickets`'
 * roll-up over those same rows. They can only disagree when the cap truncates,
 * which the accordion says out loud rather than papering over.
 */

export interface WeeklySectionsProps {
  scope: WeeklySectionScope
  /** From the priorities master, worst first — passed through to the grouping. */
  severityOrder?: readonly string[]
  moduleLabel?: (moduleId: number) => string | null | undefined
}

export function WeeklySections({ scope, severityOrder, moduleLabel }: WeeklySectionsProps) {
  return (
    <section aria-label="Weekly ticket sections" className="flex flex-col gap-2">
      {WEEKLY_SECTIONS.map((section) => (
        <WeeklySection
          key={section.key}
          section={section}
          scope={scope}
          severityOrder={severityOrder}
          moduleLabel={moduleLabel}
        />
      ))}
    </section>
  )
}

function WeeklySection({
  section,
  scope,
  severityOrder,
  moduleLabel,
}: {
  section: WeeklySectionDef
  scope: WeeklySectionScope
} & Pick<WeeklySectionsProps, 'severityOrder' | 'moduleLabel'>) {
  const [open, setOpen] = React.useState(false)
  const panelId = React.useId()

  const { data, isPending, isError } = useListTickets(
    { ...weeklySectionParams(section.key, scope), limit: WEEKLY_SECTION_PAGE_SIZE },
    { query: { enabled: open } },
  )

  // No cast: `WeeklyTicketRow` is shaped so `TicketSummary` satisfies it
  // structurally, which is what makes a renamed field a compile error here
  // rather than a blank cell on the screen.
  const rows: WeeklyTicketRow[] = data?.data ?? []
  // `meta.totalCount` where the server can afford it, the fetched length
  // otherwise — never a second query just to count.
  const total = data?.meta?.totalCount ?? rows.length

  return (
    <div className="rounded-card border border-[color:var(--border)] bg-[color:var(--bg-surface)]">
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((was) => !was)}
        className="flex w-full items-center gap-2 rounded-card px-3 py-2 text-left transition-colors hover:bg-[color:var(--bg-subtle)] focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
      >
        <span className="text-sm font-semibold text-[color:var(--text-primary)]">
          {section.title}
        </span>
        {open && !isPending && !isError && (
          <span className="rounded-chip bg-subtle px-2 py-0.5 text-xs font-semibold tabular-nums text-content-muted">
            {total}
          </span>
        )}
        <ChevronDown
          className={`ml-auto h-4 w-4 text-[color:var(--text-secondary)] transition-transform ${
            open ? 'rotate-180' : ''
          }`}
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
              description="The cards above are unaffected. Try expanding this section again."
            />
          ) : rows.length === 0 ? (
            <EmptyState
              title="Nothing here"
              description="No tickets match this section for the selected week."
            />
          ) : (
            <>
              <GroupedTicketAccordion
                tickets={rows}
                severityOrder={severityOrder}
                moduleLabel={moduleLabel}
              />
              <div className="border-t border-[color:var(--border)] px-3 py-2">
                <Link
                  to={weeklySectionViewAllHref(section.key, scope)}
                  className="rounded-control text-xs text-[color:var(--primary)] underline-offset-2 hover:underline focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--primary)]"
                >
                  View all in the ticket list →
                </Link>
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}

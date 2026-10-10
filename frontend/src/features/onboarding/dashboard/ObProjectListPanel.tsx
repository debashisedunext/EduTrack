import { useNavigate } from 'react-router-dom'

import type { ObProjectBoardRow } from '@/api/generated/model'
import { Chip } from '@/components/ui/chip'
import {
  SlideOver,
  SlideOverBody,
  SlideOverContent,
  SlideOverHeader,
  SlideOverTitle,
} from '@/components/ui/slide-over'

import {
  bucketLook,
  byLatenessAscending,
  byLatenessDescending,
  lateLabel,
  latenessOf,
} from './obProjectBoard'

/**
 * The projects behind a figure on OB-02's project board — the S-06 slide-over,
 * opened from a card in the band or a slice of a donut.
 *
 * <h2>Why a panel rather than a trip to the Projects grid</h2>
 *
 * <p>Both used to navigate: a card selected the Summary tab, and a donut slice
 * opened `/onboarding/projects` filtered by the person. Every figure on this
 * board now opens here instead, which is what the seven counters above the
 * strip have always done. A reader asking "which four?" gets four rows over
 * the board they are already reading, and closing the panel puts them back
 * where they were rather than on another screen they have to navigate out of.
 *
 * <h2>It holds no query</h2>
 *
 * <p>The rows are handed in, cut from the board response the page already has
 * by {@code projectsForCard} or carried on the slice itself. That is the
 * point: the number on the card and the length of this list are the same set
 * by construction, not two reads that agree most of the time.
 *
 * <p>It is also why this panel is not {@link ObDashboardDrillPanel}. That one
 * fetches `listObDashboardCardItems`, which answers in <em>steps and
 * prerequisite tasks</em> — the grain the seven counters are in. Opening it
 * from a figure counted in projects showed a list whose length disagreed with
 * the number just clicked, which is the reason these cards navigated instead.
 * A project-grained figure now gets a project-grained list, and the two panels
 * stay in the grain of the thing that opened them.
 *
 * <h2>Rows name the project</h2>
 *
 * <p>Not the client. The panel is headed and counted in projects, and a client
 * with three engagements otherwise put one name on three rows with nothing to
 * tell them apart. The client is on the line beneath, where it qualifies the
 * project rather than standing in for it.
 */
export interface ObProjectListPanelProps {
  /** What was clicked, in the words that were on it — the panel's heading. */
  title: string
  rows: ObProjectBoardRow[]
  open: boolean
  onClose: () => void
}

export function ObProjectListPanel({ title, rows, open, onClose }: ObProjectListPanelProps) {
  const navigate = useNavigate()
  /*
    Which way round the list reads is decided by the list itself.

    A figure counting nothing but breaches — Overdue, At risk, or the schedule
    donut's own late arcs — has no running work for a late row to bury, and
    the reader opened it to find the one that has slipped furthest: worst
    first. Every other figure is a mixed list, where the late rows belong at
    the bottom; that is the bug this fixed, seven running projects sitting
    under the two that had slipped.

    Read off the rows rather than passed in, because the donut slices open this
    panel too and a slice has no card key to test. The sort is stable either
    way, so rows that are equally late keep the order the board sent them in.
  */
  const allLate = rows.length > 0 && rows.every((row) => latenessOf(row) > 0)
  const sorted = [...rows].sort(allLate ? byLatenessDescending : byLatenessAscending)

  return (
    <SlideOver open={open} onOpenChange={(next) => !next && onClose()}>
      <SlideOverContent>
        <SlideOverHeader>
          <SlideOverTitle>{title}</SlideOverTitle>
          <p className="m-0 text-caption text-content-muted">
            {sorted.length} {sorted.length === 1 ? 'project' : 'projects'}
          </p>
        </SlideOverHeader>

        <SlideOverBody>
          {sorted.length === 0 ? (
            <p className="m-0 text-sm text-content-muted">
              No project is in this state right now.
            </p>
          ) : (
            <ul className="m-0 flex list-none flex-col gap-2 p-0">
              {sorted.map((row) => {
                const look = bucketLook(row.bucket)
                const late = lateLabel(row)
                return (
                  <li key={row.id}>
                    <button
                      type="button"
                      onClick={() => {
                        onClose()
                        navigate(`/onboarding/projects/${row.id}`)
                      }}
                      aria-label={`${row.name}, ${row.client.name}. ${
                        row.implementor
                          ? `Implementor ${row.implementor.displayName}.`
                          : 'No implementor assigned.'
                      } ${late ?? look.label}. Open the project.`}
                      className="flex w-full items-start gap-3 rounded-control border border-border
                                 px-3 py-2.5 text-left hover:bg-subtle focus-visible:outline
                                 focus-visible:outline-2 focus-visible:-outline-offset-2
                                 focus-visible:outline-primary"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-sm font-medium text-content">
                          {row.name}
                        </span>
                        <span className="mt-0.5 block truncate text-caption text-content-muted">
                          {row.client.name} · {row.product.name} ·{' '}
                          {row.implementor ? row.implementor.displayName : 'Unassigned'}
                        </span>
                      </span>
                      <Chip variant={late ? 'danger' : 'neutral'}>{late ?? look.label}</Chip>
                    </button>
                  </li>
                )
              })}
            </ul>
          )}
        </SlideOverBody>
      </SlideOverContent>
    </SlideOver>
  )
}

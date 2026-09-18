import {
  AlertTriangle,
  CalendarDays,
  Clock,
  Flag,
  FolderKanban,
  PackageCheck,
  type LucideIcon,
} from 'lucide-react'

import type { ObProjectBoard } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'

/**
 * OB-02's second band — the six project-level figures.
 *
 * <h2>Why these are tinted where the card row above them is white</h2>
 *
 * The seven counters at the top of this page are journey-level figures from a
 * pre-aggregate, and they are deliberately quiet. These six are the
 * project-level reading of the same module, and the screen's job is to make
 * the three that need action findable in a glance — so each carries its state
 * as a tint, an icon and a number, never colour alone (blueprint §12.1, and
 * WCAG SC 1.4.1 behind it).
 *
 * <h2>Every figure is the project's completion date</h2>
 *
 * Which is exactly what makes them different from the row above: "overdue"
 * here means the *project* is past the date it was sold to finish on, where
 * the card above counts clients with an overdue *task*. Two different
 * questions that a reader will otherwise assume are the same one, so each
 * caption says which it is.
 */

type Tone = 'indigo' | 'blue' | 'green' | 'orange' | 'red' | 'amber'

/**
 * The six tints.
 *
 * Tokens, never literals — CLAUDE.md. Each is a soft background with the
 * matching **text** shade for the number, the pairing `Chip` uses everywhere
 * else: the soft fill is a 3:1 surface and the `text` shade is the 4.5:1 one.
 *
 * <h2>Why none of them is the chart palette</h2>
 *
 * The first draft gave "Today's delivery" the donut's cyan, `--chart-2`, to
 * echo the chart above it. Measured on its own tile that is **2.2:1** — it
 * fails AA at any size, and the series palette has no darkened `text` shade to
 * fall back to, because it was built for marks on a white surface rather than
 * for type. Every pair below is from a ramp that has one, and the six measure
 * 5.6 / 6.2 / 5.2 / 4.9 / 5.9 / 4.8 against their own fills.
 *
 * Left to right they also escalate — indigo, blue, green, orange, red, amber —
 * so the two that need action sit together and the tail card is far enough
 * from the orange one to read as a different thing.
 */
const TONE: Record<Tone, { tile: string; ink: string; icon: string; rule: string }> = {
  indigo: {
    tile: 'bg-primary-soft',
    ink: 'text-[color:var(--primary)]',
    icon: 'text-[color:var(--primary)]',
    rule: 'bg-[color:var(--primary)]',
  },
  blue: {
    tile: 'bg-level-medium-soft',
    ink: 'text-info-text',
    icon: 'text-info-text',
    rule: 'bg-info',
  },
  green: {
    tile: 'bg-level-low-soft',
    ink: 'text-success-text',
    icon: 'text-success-text',
    rule: 'bg-success',
  },
  orange: {
    tile: 'bg-status-delayed-soft',
    ink: 'text-status-delayed-text',
    icon: 'text-status-delayed-text',
    rule: 'bg-status-delayed',
  },
  red: {
    tile: 'bg-level-critical-soft',
    ink: 'text-danger-text',
    icon: 'text-danger-text',
    rule: 'bg-danger',
  },
  amber: {
    tile: 'bg-level-high-soft',
    ink: 'text-warning-text',
    icon: 'text-warning-text',
    rule: 'bg-warning',
  },
}

interface CardSpec {
  key: string
  label: string
  icon: LucideIcon
  tone: Tone
  caption: string
  /** What the figure is *of*, for the accessible name — the label alone is a heading. */
  unit: string
  count: (cards: ObProjectBoard['cards']) => number
}

/**
 * The band's order, which is the order somebody reads it in: what exists, what
 * is due, what is late, what has been escalated. Left to right is increasing
 * urgency, so a reader scanning for trouble scans in one direction.
 */
const CARDS: CardSpec[] = [
  {
    key: 'ongoing',
    label: 'Ongoing projects',
    icon: FolderKanban,
    tone: 'indigo',
    caption: 'running now, across every client',
    unit: 'projects running',
    count: (c) => c.ongoingProjects,
  },
  {
    key: 'week',
    label: "This week's deadlines",
    icon: CalendarDays,
    tone: 'blue',
    caption: 'completion dates Mon–Sun, the days gone included',
    unit: 'projects due this week',
    count: (c) => c.thisWeeksDeadlines,
  },
  {
    key: 'today',
    label: "Today's delivery",
    icon: PackageCheck,
    tone: 'green',
    caption: 'projects whose completion date is today',
    unit: 'projects due today',
    count: (c) => c.todaysDelivery,
  },
  {
    key: 'overdue',
    label: 'Overdue',
    icon: Clock,
    tone: 'orange',
    caption: 'up to 7 working days past the completion date',
    unit: 'projects overdue',
    count: (c) => c.overdueProjects,
  },
  {
    key: 'risk',
    label: 'At risk',
    icon: AlertTriangle,
    tone: 'red',
    caption: 'more than 7 working days past it',
    unit: 'projects at risk',
    count: (c) => c.atRiskProjects,
  },
  {
    key: 'escalations',
    label: 'Client escalations',
    icon: Flag,
    tone: 'amber',
    caption: 'running projects with an open portal escalation',
    unit: 'projects escalated',
    count: (c) => c.clientEscalations,
  },
]

export interface ObProjectCardBandProps {
  board?: ObProjectBoard
  isPending: boolean
  /** Opens the detail behind a card. Absent while nothing is wired — a tile is then a region, never a dead button. */
  onOpen?: (key: string) => void
}

export function ObProjectCardBand({ board, isPending, onOpen }: ObProjectCardBandProps) {
  return (
    <div
      role="list"
      aria-label="Project figures"
      /*
        Six equal shares on a wide screen, three on a tablet, two on a phone.
        `minmax(0, 1fr)` rather than `1fr`: a track's implicit minimum is the
        widest thing inside it, so a long label would otherwise refuse to shrink
        and push the row wider than the page.
      */
      className="grid grid-cols-2 items-stretch gap-3 sm:grid-cols-3 xl:grid-cols-6"
    >
      {CARDS.map((card) => (
        <div role="listitem" key={card.key} className="min-w-0">
          {isPending || !board ? (
            <Skeleton className="h-[7.5rem] w-full rounded-card" />
          ) : (
            <Tile card={card} count={card.count(board.cards)} onOpen={onOpen} />
          )}
        </div>
      ))}
    </div>
  )
}

function Tile({
  card,
  count,
  onOpen,
}: {
  card: CardSpec
  count: number
  onOpen?: (key: string) => void
}) {
  const tone = TONE[card.tone]
  const Icon = card.icon
  const name = `${card.label}: ${count} ${card.unit}. ${card.caption}.`

  const body = (
    <>
      <span className="flex items-start justify-between gap-2">
        <span
          className={`text-[11px] font-semibold uppercase leading-tight tracking-[.06em] ${tone.ink}`}
        >
          {card.label}
        </span>
        <span
          aria-hidden="true"
          className={`grid h-7 w-7 shrink-0 place-items-center rounded-control bg-surface ${tone.icon}`}
        >
          <Icon className="h-4 w-4" />
        </span>
      </span>
      <span className={`text-[32px] font-[650] leading-none ${tone.ink}`}>{count}</span>
      <span className="text-xs text-content-muted">{card.caption}</span>
      <span aria-hidden="true" className={`mt-auto h-[3px] w-10 rounded-full ${tone.rule}`} />
    </>
  )

  const shell = `flex h-full min-h-[7.5rem] w-full min-w-0 flex-col gap-1.5 rounded-card
                 ${tone.tile} px-4 py-3.5 text-left`

  if (!onOpen) {
    return (
      <div className={shell} role="group" aria-label={name}>
        {body}
      </div>
    )
  }

  return (
    <button
      type="button"
      onClick={() => onOpen(card.key)}
      aria-label={`${name} Open the matching projects.`}
      className={`${shell} transition-shadow hover:shadow-modal focus-visible:outline
                  focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary`}
    >
      {body}
    </button>
  )
}

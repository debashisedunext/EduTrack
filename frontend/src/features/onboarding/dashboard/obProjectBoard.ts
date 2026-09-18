import type { ObProjectBoardBucket, ObProjectBoardRow } from '@/api/generated/model'

/**
 * How OB-02's project board reads the server's five schedule buckets, and how
 * it groups the rows behind the two people donuts.
 *
 * <h2>Here rather than in the components</h2>
 *
 * Three charts, six cards and three lists on one screen all answer questions
 * about the same rows — "which of these is at risk", "who owns it", "what
 * colour is that". Two copies of any of those answers is how the donut and the
 * list beneath it come to disagree while both look right, which is the exact
 * bug `obDashboardCards.ts` was extracted to prevent one board up.
 */

/** What a bucket is called, what colour it wears, and what it actually means. */
export interface BucketLook {
  label: string
  /** A design token, never a literal — CLAUDE.md. Used as an SVG fill, which `var()` serves directly. */
  colour: string
  /** Chip variant for the same state in a list row. */
  chip: 'success' | 'info' | 'warning' | 'danger' | 'neutral'
  /** The rule, in the words a reader would use. Shown on the legend and in the accessible name. */
  meaning: string
}

/**
 * Keyed by the contract's wire tokens, and the key order is the donut's order.
 *
 * The four scheduled buckets run best-to-worst so the arc reads as an
 * escalation rather than as an arbitrary sequence; `NOT_SCHEDULED` sits last
 * because it is not a point on that scale at all — it is the absence of one.
 *
 * **The colours are not the chart palette.** These are states, not series:
 * green/blue/orange/red is a status vocabulary the rest of the module already
 * speaks (`--status-delayed` exists for exactly this ramp), and painting them
 * from `--chart-N` would make "at risk" whatever colour the fourth series
 * happens to be.
 */
const BUCKET: Record<ObProjectBoardBucket, BucketLook> = {
  ON_TIME: {
    label: 'On time',
    colour: 'var(--success)',
    chip: 'success',
    meaning: 'the completion date is still ahead',
  },
  AHEAD: {
    label: 'Ahead',
    colour: 'var(--info)',
    chip: 'info',
    meaning: 'more of the work is done than of the budget is spent',
  },
  DELAYED: {
    label: 'Delayed',
    colour: 'var(--status-delayed)',
    chip: 'warning',
    meaning: 'up to 7 working days past the completion date',
  },
  AT_RISK: {
    label: 'At risk',
    colour: 'var(--danger)',
    chip: 'danger',
    meaning: 'more than 7 working days past the completion date',
  },
  NOT_SCHEDULED: {
    label: 'Not scheduled',
    colour: 'var(--text-secondary)',
    chip: 'neutral',
    meaning: 'no task has been created yet, so there is no date to measure',
  },
}

/** The donut's slice order — and the only place that order is decided. */
export const BUCKET_ORDER: ObProjectBoardBucket[] = [
  'ON_TIME',
  'AHEAD',
  'DELAYED',
  'AT_RISK',
  'NOT_SCHEDULED',
]

/**
 * How one bucket is drawn.
 *
 * **Tolerant of an unknown key**, like `cardLook` one file over: the enum is
 * closed in the contract, so an unrecognised value means a server newer than
 * this bundle, and throwing would give back the whole screen to gain nothing.
 */
export function bucketLook(bucket: string): BucketLook {
  return (
    BUCKET[bucket as ObProjectBoardBucket] ?? {
      label: bucket,
      colour: 'var(--text-secondary)',
      chip: 'neutral',
      meaning: '',
    }
  )
}

/** A slice of one of the three donuts: who or what, how many, and the rows behind it. */
export interface Slice {
  key: string
  label: string
  colour: string
  rows: ObProjectBoardRow[]
}

/**
 * The schedule donut — every bucket that has a project in it.
 *
 * Empty buckets are dropped rather than drawn at zero width. A donut is not the
 * card row: a zero-width arc with a legend entry against it is a slice a reader
 * cannot hover, cannot click and cannot see, and the count it would have
 * carried is on the legend of the buckets that do exist. (The cards above make
 * the opposite choice for the opposite reason — a card reading nought is a
 * statement, and an absent card is not.)
 */
export function scheduleSlices(rows: ObProjectBoardRow[]): Slice[] {
  return BUCKET_ORDER.map((bucket) => {
    const look = bucketLook(bucket)
    return {
      key: bucket,
      label: look.label,
      colour: look.colour,
      rows: rows.filter((row) => row.bucket === bucket),
    }
  }).filter((slice) => slice.rows.length > 0)
}

/** The eight-colour chart palette, in the blueprint's colour-blind-safe order. */
const CHART_PALETTE = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
  'var(--chart-6)',
  'var(--chart-7)',
  'var(--chart-8)',
] as const

/**
 * How many people get their own slice before the rest are pooled.
 *
 * Eight is the palette, and past it a ninth slice would either repeat a colour
 * or invent one nobody checked for contrast. `chartTokens.categorical` wraps
 * for the ticketing donut because that chart's categories are named in a legend
 * a reader can match by text; here the slice *is* a person, and two people in
 * the same colour on a chart about workload is a misreading waiting to happen.
 */
const MAX_PEOPLE_SLICES = 8

/**
 * Group the rows by a person — the salesperson or the implementor.
 *
 * <h2>Largest first, and the tail pooled</h2>
 *
 * Ranking is the information: "who is carrying the most" is the question both
 * people donuts answer. Everyone past the eighth becomes one "Others" slice
 * that still carries its rows, so the hover list names them rather than hiding
 * them behind a count.
 *
 * <h2>Unassigned is a slice, not a gap</h2>
 *
 * A project with no implementor is the single most actionable row on this
 * screen, and dropping it would make the donut's total disagree with the
 * Ongoing projects card for a reason no reader could see. It is drawn in the
 * muted token rather than a palette colour, because it is an absence rather
 * than a person.
 */
export function peopleSlices(
  rows: ObProjectBoardRow[],
  who: 'salesPerson' | 'implementor',
): Slice[] {
  const byPerson = new Map<string, { label: string; rows: ObProjectBoardRow[] }>()

  for (const row of rows) {
    const person = row[who]
    const key = person ? `u${person.id}` : 'unassigned'
    const label = person?.displayName ?? 'Unassigned'
    const bucket = byPerson.get(key) ?? { label, rows: [] }
    bucket.rows.push(row)
    byPerson.set(key, bucket)
  }

  const unassigned = byPerson.get('unassigned')
  byPerson.delete('unassigned')

  const ranked = [...byPerson.entries()].sort(
    (a, b) => b[1].rows.length - a[1].rows.length || a[1].label.localeCompare(b[1].label),
  )

  const named = ranked.slice(0, MAX_PEOPLE_SLICES).map(([key, value], index) => ({
    key,
    label: value.label,
    colour: CHART_PALETTE[index],
    rows: value.rows,
  }))

  const pooled = ranked.slice(MAX_PEOPLE_SLICES)
  const slices: Slice[] = [...named]

  if (pooled.length > 0) {
    slices.push({
      key: 'others',
      label: `Others (${pooled.length} people)`,
      colour: 'var(--text-secondary)',
      rows: pooled.flatMap(([, value]) => value.rows),
    })
  }
  if (unassigned) {
    slices.push({
      key: 'unassigned',
      label: 'Unassigned',
      colour: 'var(--text-secondary)',
      rows: unassigned.rows,
    })
  }
  return slices
}

/**
 * The lateness figure a row is sorted and chipped by.
 *
 * `daysPastCompletion` first — the project's own date, which is what this
 * screen is about — and `delayedByDays` only where there is no completion date
 * to have passed. Never their sum: they measure from two different dates and
 * adding them would produce a number that is true of neither.
 */
export function latenessOf(row: ObProjectBoardRow): number {
  return row.daysPastCompletion ?? row.delayedByDays ?? 0
}

/** Worst first. What every list on this board is worked from the top of. */
export function byLatenessDescending(a: ObProjectBoardRow, b: ObProjectBoardRow): number {
  return latenessOf(b) - latenessOf(a)
}

/** `3 days late`, `1 day late`, or null when the row is not late at all. */
export function lateLabel(row: ObProjectBoardRow): string | null {
  const days = latenessOf(row)
  if (days < 1) return null
  return `${days} ${days === 1 ? 'day' : 'days'} late`
}

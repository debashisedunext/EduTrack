import { describe, expect, it } from 'vitest'

import type { ObProjectBoardRow } from '@/api/generated/model'

import {
  projectsForCard,
  byLatenessAscending,
  byLatenessDescending,
  bucketLook,
  lateLabel,
  latenessOf,
  peopleSlices,
  scheduleSlices,
} from './obProjectBoard'

/**
 * The grouping behind OB-02's three donuts.
 *
 * <h2>Why this is tested apart from the chart</h2>
 *
 * Recharts draws what it is given, and what it is given is decided here — so
 * every claim worth checking (the ranking, the pooled tail, who counts as
 * unassigned, the totals adding up) is a claim about these functions. Asserting
 * it through a rendered SVG would test the library.
 */

/** A row with only the fields the grouping reads. The rest is not this module's business. */
function row(over: Partial<ObProjectBoardRow> & { id: number }): ObProjectBoardRow {
  return {
    name: `Project ${over.id}`,
    client: { id: 100 + over.id, name: `Client ${over.id}`, clientCode: null, city: null },
    product: { id: 1, code: 'ERP', name: 'ERP Suite' },
    startDate: '2026-09-01',
    gateStatus: 'OPEN',
    bucket: 'ON_TIME',
    tasksTotal: 4,
    tasksDone: 1,
    openEscalations: 0,
    ...over,
  } as ObProjectBoardRow
}

function person(id: number, displayName: string) {
  return { id, displayName }
}

describe('scheduleSlices', () => {
  it('orders the buckets best to worst and drops the empty ones', () => {
    const slices = scheduleSlices([
      row({ id: 1, bucket: 'AT_RISK' }),
      row({ id: 2, bucket: 'ON_TIME' }),
      row({ id: 3, bucket: 'ON_TIME' }),
    ])

    // On time before at risk, whatever order the rows arrived in — the arc
    // reads as an escalation rather than as the server's row order.
    expect(slices.map((s) => s.key)).toEqual(['ON_TIME', 'AT_RISK'])
    expect(slices.map((s) => s.rows.length)).toEqual([2, 1])
  })

  it('draws no zero-width slice for a bucket nothing is in', () => {
    const slices = scheduleSlices([row({ id: 1, bucket: 'DELAYED' })])

    expect(slices).toHaveLength(1)
    expect(slices[0].label).toBe('Delayed')
  })

  it('gives every bucket a status token rather than a chart-palette colour', () => {
    // Delayed and At risk must not share a hue: the module's strip says four
    // things in four words, and `--status-delayed` exists for exactly this.
    expect(bucketLook('DELAYED').colour).not.toBe(bucketLook('AT_RISK').colour)
    expect(bucketLook('DELAYED').colour).toContain('--status-delayed')
    expect(bucketLook('AT_RISK').colour).toContain('--danger')
  })

  it('renders an unknown bucket rather than throwing on it', () => {
    // A server newer than this bundle must not blank the screen.
    expect(bucketLook('SOMETHING_NEW').label).toBe('SOMETHING_NEW')
  })
})

describe('peopleSlices', () => {
  it('ranks people by how many projects they carry', () => {
    const slices = peopleSlices(
      [
        row({ id: 1, implementor: person(7, 'Meera Iyer') }),
        row({ id: 2, implementor: person(8, 'Aarav Joshi') }),
        row({ id: 3, implementor: person(8, 'Aarav Joshi') }),
      ],
      'implementor',
    )

    expect(slices.map((s) => s.label)).toEqual(['Aarav Joshi', 'Meera Iyer'])
    expect(slices[0].rows).toHaveLength(2)
  })

  it('keeps a project with no implementor as its own slice rather than dropping it', () => {
    // The donut total must equal the Ongoing projects card. A silently dropped row
    // would make the two disagree for a reason no reader could see — and an
    // unassigned project is the most actionable row on the screen.
    const slices = peopleSlices(
      [row({ id: 1, implementor: person(7, 'Meera Iyer') }), row({ id: 2 })],
      'implementor',
    )

    expect(slices.map((s) => s.label)).toContain('Unassigned')
    expect(slices.reduce((sum, s) => sum + s.rows.length, 0)).toBe(2)
  })

  it('sorts Unassigned last however many projects it holds', () => {
    const slices = peopleSlices(
      [row({ id: 1 }), row({ id: 2 }), row({ id: 3, implementor: person(7, 'Meera Iyer') })],
      'implementor',
    )

    expect(slices.at(-1)?.label).toBe('Unassigned')
  })

  it('pools everyone past the eighth person and keeps their rows', () => {
    const rows = Array.from({ length: 10 }, (_, index) =>
      row({ id: index + 1, salesPerson: person(index + 1, `Person ${index + 1}`) }),
    )

    const slices = peopleSlices(rows, 'salesPerson')

    // Eight named slices plus one pooled — never a ninth colour, which would
    // either repeat a hue or invent one nobody checked.
    expect(slices).toHaveLength(9)
    expect(slices.at(-1)?.label).toBe('Others (2 people)')
    expect(slices.at(-1)?.rows).toHaveLength(2)
    expect(slices.reduce((sum, s) => sum + s.rows.length, 0)).toBe(10)
  })

  it('gives the first eight people eight different colours', () => {
    const rows = Array.from({ length: 8 }, (_, index) =>
      row({ id: index + 1, salesPerson: person(index + 1, `Person ${index + 1}`) }),
    )

    const colours = peopleSlices(rows, 'salesPerson').map((s) => s.colour)

    expect(new Set(colours).size).toBe(8)
  })
})

describe('lateness', () => {
  it("reads the project's own date first and the task delay only as a fallback", () => {
    // The two measure from different dates. The project board is about the
    // completion date; the task delay is reported beside it, never added to it.
    expect(latenessOf(row({ id: 1, daysPastCompletion: 3, delayedByDays: 9 }))).toBe(3)
    expect(latenessOf(row({ id: 2, delayedByDays: 9 }))).toBe(9)
    expect(latenessOf(row({ id: 3 }))).toBe(0)
  })

  it('says nothing about a row that is not late', () => {
    expect(lateLabel(row({ id: 1 }))).toBeNull()
  })

  it('counts one day in the singular', () => {
    expect(lateLabel(row({ id: 1, daysPastCompletion: 1 }))).toBe('1 day late')
    expect(lateLabel(row({ id: 2, daysPastCompletion: 4 }))).toBe('4 days late')
  })

  it('sorts worst first, which is how the At risk and Overdue lists are worked', () => {
    const sorted = [
      row({ id: 1, daysPastCompletion: 2 }),
      row({ id: 2, daysPastCompletion: 11 }),
      row({ id: 3 }),
    ].sort(byLatenessDescending)

    expect(sorted.map((r) => r.id)).toEqual([2, 1, 3])
  })

  /** The panel a card opens reads the other way — see byLatenessAscending. */
  it('sorts least late first for the panel, leaving the worst at the bottom', () => {
    const sorted = [
      row({ id: 1, daysPastCompletion: 2 }),
      row({ id: 2, daysPastCompletion: 11 }),
      row({ id: 3 }),
    ].sort(byLatenessAscending)

    expect(sorted.map((r) => r.id)).toEqual([3, 1, 2])
  })

  /** Equally late rows keep the order the board sent, so the list never shuffles. */
  it('leaves rows that are not late in the order they arrived', () => {
    const sorted = [row({ id: 7 }), row({ id: 3 }), row({ id: 5 })].sort(byLatenessAscending)

    expect(sorted.map((r) => r.id)).toEqual([7, 3, 5])
  })
})


/**
 * Which projects sit behind each of the band's six figures.
 *
 * <p>The panel a card opens lists exactly this set, and the card's own count
 * comes from the server — so these cases are what keeps "At risk: 4" and four
 * rows in the panel the same four projects.
 */
describe('projectsForCard', () => {
  const board = {
    today: '2026-09-18',
    weekStart: '2026-09-14',
    weekEnd: '2026-09-20',
    projects: [
      row({ id: 1, bucket: 'ON_TIME', tentativeCompletion: '2026-09-18' }),
      row({ id: 2, bucket: 'DELAYED', tentativeCompletion: '2026-09-16' }),
      row({ id: 3, bucket: 'AT_RISK', tentativeCompletion: '2026-08-30' }),
      row({ id: 4, bucket: 'ON_TIME', tentativeCompletion: '2026-10-05', openEscalations: 2 }),
      row({ id: 5, bucket: 'ON_TIME' }),
    ],
  }

  const ids = (key: string) => projectsForCard(key, board).map((r) => r.id)

  it('answers every running project for the Ongoing card', () => {
    expect(ids('ongoing')).toEqual([1, 2, 3, 4, 5])
  })

  /*
    Mon–Sun inclusive, the days already gone included — the card's own
    caption. A project with no completion date is in no week.
  */
  it("takes this week's deadlines from the board's own week, inclusive", () => {
    expect(ids('week')).toEqual([1, 2])
  })

  it('answers the completion date being today, exactly', () => {
    expect(ids('today')).toEqual([1])
  })

  /*
    The mapping that reads backwards, which is why it is pinned here:
    Overdue is DELAYED (up to seven working days past), At risk is AT_RISK
    (more than seven).
  */
  it('maps Overdue to DELAYED and At risk to AT_RISK', () => {
    expect(ids('overdue')).toEqual([2])
    expect(ids('risk')).toEqual([3])
  })

  it('answers the projects carrying an open escalation', () => {
    expect(ids('escalations')).toEqual([4])
  })

  /*
    A card added to the band before it is added here opens a panel that is too
    broad, which a reader can see, rather than an empty one that reads as
    "nothing matched".
  */
  it('answers the whole set for a key it does not know', () => {
    expect(ids('something-new')).toEqual([1, 2, 3, 4, 5])
  })
})

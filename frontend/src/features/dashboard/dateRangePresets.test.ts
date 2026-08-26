import { describe, expect, it } from 'vitest'

import {
  DATE_RANGE_PRESETS,
  dateRangeForPreset,
  matchDateRangePreset,
  type DateRangePreset,
} from './dateRangePresets'

/** Mid-morning UTC on an unremarkable Wednesday. */
const TODAY = new Date('2026-08-26T09:41:00Z')

function preset(id: string): DateRangePreset {
  const found = DATE_RANGE_PRESETS.find((p) => p.id === id)
  if (!found) throw new Error(`no preset ${id}`)
  return found
}

/** Inclusive day count, which is what the 'exactly as long as its label' rule is about. */
function lengthInDays({ from, to }: { from: string | null; to: string | null }): number {
  return (Date.parse(to!) - Date.parse(from!)) / 86_400_000 + 1
}

describe('dateRangeForPreset', () => {
  it.each([
    ['1w', '2026-08-20'],
    ['2w', '2026-08-13'],
    ['3w', '2026-08-06'],
    ['1m', '2026-07-27'],
    ['3m', '2026-05-27'],
    ['6m', '2026-02-27'],
    ['1y', '2025-08-27'],
  ])('%s starts on %s and ends today', (id, from) => {
    expect(dateRangeForPreset(preset(id), TODAY)).toEqual({ from, to: '2026-08-26' })
  })

  it.each([
    ['1w', 7],
    ['2w', 14],
    ['3w', 21],
  ])('makes %s exactly %i days long, counting both ends', (id, days) => {
    // The off-by-one this guards: `today - 7` reads as seven days and returns
    // eight, so every 'last week' figure quietly included an extra Wednesday.
    expect(lengthInDays(dateRangeForPreset(preset(id), TODAY))).toBe(days)
  })

  it('clamps a month shift onto a short month rather than overflowing past it', () => {
    // 31 March minus a month is 31 February, which `Date.UTC` rolls forward to
    // 3 March — a window starting *after* the month it is named for.
    expect(dateRangeForPreset(preset('1m'), new Date('2026-03-31T12:00:00Z'))).toEqual({
      from: '2026-03-01',
      to: '2026-03-31',
    })
  })

  it('reads the day off the UTC clock, not the local one', () => {
    // Late UTC evening is already tomorrow east of Greenwich. The summary
    // tables bucket by UTC day, so the boundary has to follow them.
    expect(dateRangeForPreset(preset('1w'), new Date('2026-08-26T23:30:00Z')).to).toBe('2026-08-26')
  })

  it('ignores the time of day — two instants on one date give one window', () => {
    expect(dateRangeForPreset(preset('1m'), new Date('2026-08-26T00:00:00Z'))).toEqual(
      dateRangeForPreset(preset('1m'), new Date('2026-08-26T18:12:44Z')),
    )
  })
})

describe('matchDateRangePreset', () => {
  it.each(DATE_RANGE_PRESETS.map((p) => [p.id]))(
    'recognises the range %s resolved to',
    (id) => {
      const range = dateRangeForPreset(preset(id), TODAY)
      expect(matchDateRangePreset(range, TODAY)?.id).toBe(id)
    },
  )

  it('returns null for a hand-picked range', () => {
    expect(matchDateRangePreset({ from: '2026-08-01', to: '2026-08-19' }, TODAY)).toBeNull()
  })

  it('returns null when either end is missing', () => {
    expect(matchDateRangePreset({ from: null, to: null }, TODAY)).toBeNull()
    expect(matchDateRangePreset({ from: '2026-08-20', to: null }, TODAY)).toBeNull()
  })

  it('stops recognising a link shared yesterday, which is the documented cost of storing dates', () => {
    const yesterdaysWeek = dateRangeForPreset(preset('1w'), new Date('2026-08-25T09:41:00Z'))
    expect(matchDateRangePreset(yesterdaysWeek, TODAY)).toBeNull()
  })
})

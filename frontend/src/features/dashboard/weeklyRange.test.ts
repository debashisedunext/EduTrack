import { describe, expect, it } from 'vitest'

import {
  formatWeek,
  isMonday,
  isoMonday,
  isoWeekNumber,
  lastWeek,
  parseIsoDate,
  pickerWeeks,
  previousWeek,
  shiftWeeks,
  thisWeek,
  toIsoDate,
  weekFromParam,
  weekOf,
} from './weeklyRange'

/**
 * S-05 tab 3 · ISO week arithmetic.
 *
 * The anchors below were verified against an independent implementation of the
 * ISO algorithm before being written down, not reasoned out on paper. Four of
 * them are the cases a naive `startOfWeek` gets wrong:
 *
 *   2025-12-29 Mon  →  2026-W01   week-year ahead of its own dates
 *   2026-12-28 Mon  →  2026-W53   a 53-week year
 *   2027-01-03 Sun  →  2026-W53   January in the previous week-year
 *   2021-01-01 Fri  →  2020-W53   week-year behind
 */

describe('parseIsoDate', () => {
  it('reads YYYY-MM-DD as UTC midnight', () => {
    expect(parseIsoDate('2026-08-31').toISOString()).toBe('2026-08-31T00:00:00.000Z')
  })

  it.each(['31-08-2026', '2026-8-31', '2026/08/31', '', 'today', '2026-08-31T00:00:00Z'])(
    'refuses malformed input %j rather than yielding an Invalid Date',
    (bad) => {
      expect(() => parseIsoDate(bad)).toThrow(RangeError)
    },
  )

  it('refuses a well-formed date that does not exist', () => {
    // Every JS engine rolls this to 2 March. A picker silently showing the
    // wrong week is worse than one that throws.
    expect(() => parseIsoDate('2026-02-30')).toThrow(RangeError)
    expect(() => parseIsoDate('2026-13-01')).toThrow(RangeError)
  })

  it('accepts a real leap day and rejects one that is not', () => {
    expect(toIsoDate(parseIsoDate('2028-02-29'))).toBe('2028-02-29')
    expect(() => parseIsoDate('2026-02-29')).toThrow(RangeError)
  })
})

describe('isoMonday', () => {
  it.each([
    ['2026-08-31', '2026-08-31'], // a Monday is its own week start
    ['2026-09-01', '2026-08-31'], // Tuesday
    ['2026-09-06', '2026-08-31'], // Sunday still belongs to the week that opened
    ['2026-01-01', '2025-12-29'], // Thursday, week opens in the previous year
    ['2027-01-03', '2026-12-28'],
  ])('%s → %s', (input, expected) => {
    expect(isoMonday(input)).toBe(expected)
  })

  it('is idempotent', () => {
    expect(isoMonday(isoMonday('2026-09-04'))).toBe(isoMonday('2026-09-04'))
  })

  /**
   * Worth knowing what this does and does not prove. On a host offset from UTC
   * it genuinely catches `getDate()` written for `getUTCDate()` — verified by
   * mutation. On a UTC runner, which is what CI uses, the two accessors are
   * identical and no assertion can tell them apart. So the UTC discipline is
   * enforced here for developers (most of whom are not on UTC) and by reading
   * UTC accessors exclusively in the module, which is visible in review.
   */
  it('reads UTC fields, so an instant late in the UTC day lands in the right week', () => {
    // 23:30 UTC Sunday is already Monday in Auckland. The week must not move.
    expect(isoMonday(new Date('2026-09-06T23:30:00.000Z'))).toBe('2026-08-31')
    // And an instant just after UTC midnight on Monday opens the new week.
    expect(isoMonday(new Date('2026-09-07T00:30:00.000Z'))).toBe('2026-09-07')
  })
})

describe('isMonday — the server refuses anything else', () => {
  it('accepts a Monday', () => {
    expect(isMonday('2026-08-31')).toBe(true)
  })

  it.each(['2026-09-01', '2026-09-05', '2026-09-06'])('rejects %s', (day) => {
    expect(isMonday(day)).toBe(false)
  })
})

describe('isoWeekNumber — the week-year is not the calendar year', () => {
  it.each([
    ['2026-08-31', 36, 2026],
    ['2025-12-29', 1, 2026], // Monday, week-year ahead of its own dates
    ['2026-01-01', 1, 2026],
    ['2026-01-04', 1, 2026], // Sunday, still week 1
    ['2026-12-28', 53, 2026], // 2026 has a week 53
    ['2027-01-01', 53, 2026],
    ['2027-01-03', 53, 2026], // Sunday, last day of 2026-W53
    ['2027-01-04', 1, 2027], // and now 2027 begins
    ['2024-12-30', 1, 2025], // week-year ahead
    ['2021-01-01', 53, 2020], // week-year behind
  ])('%s is week %i of %i', (date, weekNumber, weekYear) => {
    expect(isoWeekNumber(date)).toEqual({ weekNumber, weekYear })
  })

  it('gives every day of one week the same number', () => {
    const days = ['2026-12-28', '2026-12-29', '2026-12-30', '2026-12-31',
      '2027-01-01', '2027-01-02', '2027-01-03']
    const seen = new Set(days.map((d) => JSON.stringify(isoWeekNumber(d))))
    expect(seen.size).toBe(1)
    expect(isoWeekNumber(days[0])).toEqual({ weekNumber: 53, weekYear: 2026 })
  })
})

describe('weekOf', () => {
  it('spans Monday to Sunday inclusive', () => {
    expect(weekOf('2026-09-02')).toEqual({
      start: '2026-08-31',
      end: '2026-09-06',
      weekNumber: 36,
      weekYear: 2026,
    })
  })

  it('spans the year boundary without splitting the week', () => {
    expect(weekOf('2027-01-01')).toEqual({
      start: '2026-12-28',
      end: '2027-01-03',
      weekNumber: 53,
      weekYear: 2026,
    })
  })

  it('always spans exactly seven days', () => {
    for (const d of ['2026-01-01', '2026-06-15', '2026-12-31', '2027-01-04', '2028-02-29']) {
      const { start, end } = weekOf(d)
      const days = (parseIsoDate(end).getTime() - parseIsoDate(start).getTime()) / 86_400_000
      expect(days).toBe(6)
    }
  })
})

describe('previousWeek — the delta window', () => {
  it('is the same ISO week seven days earlier', () => {
    expect(previousWeek('2026-08-31').start).toBe('2026-08-24')
  })

  it('crosses the year boundary into the correct week-year', () => {
    expect(previousWeek('2027-01-04')).toEqual({
      start: '2026-12-28',
      end: '2027-01-03',
      weekNumber: 53,
      weekYear: 2026,
    })
  })

  it('steps out of a 53-week year into week 52', () => {
    expect(previousWeek('2026-12-28')).toEqual({
      start: '2026-12-21',
      end: '2026-12-27',
      weekNumber: 52,
      weekYear: 2026,
    })
  })

  it('accepts a Week as readily as a date', () => {
    expect(previousWeek(weekOf('2026-08-31')).start).toBe(previousWeek('2026-08-31').start)
  })
})

describe('shiftWeeks', () => {
  it('moves forwards and backwards', () => {
    expect(shiftWeeks('2026-08-31', 2).start).toBe('2026-09-14')
    expect(shiftWeeks('2026-08-31', -2).start).toBe('2026-08-17')
    expect(shiftWeeks('2026-08-31', 0).start).toBe('2026-08-31')
  })

  it('round-trips across the year boundary', () => {
    const start = weekOf('2027-01-04')
    expect(shiftWeeks(shiftWeeks(start, -5), 5)).toEqual(start)
  })
})

describe('thisWeek / lastWeek — the clock is supplied, never read', () => {
  const now = '2026-09-03' // a Thursday

  it('resolves the week containing the given instant', () => {
    expect(thisWeek(now).start).toBe('2026-08-31')
    expect(lastWeek(now).start).toBe('2026-08-24')
  })

  it('is the same on every day of that week', () => {
    const starts = ['2026-08-31', '2026-09-01', '2026-09-06'].map((d) => thisWeek(d).start)
    expect(new Set(starts).size).toBe(1)
  })

  it('takes a Date as well as a string', () => {
    expect(thisWeek(new Date('2026-09-03T11:00:00.000Z')).start).toBe('2026-08-31')
  })
})

describe('pickerWeeks', () => {
  it('offers this week then last week, newest first', () => {
    const options = pickerWeeks('2026-09-03')
    expect(options.map((o) => o.label)).toEqual(['This week', 'Last week'])
    expect(options.map((o) => o.week.start)).toEqual(['2026-08-31', '2026-08-24'])
  })
})

describe('weekFromParam — the URL is user-editable', () => {
  const now = '2026-09-03'

  it('defaults to this week when absent or empty', () => {
    expect(weekFromParam(undefined, now)?.start).toBe('2026-08-31')
    expect(weekFromParam(null, now)?.start).toBe('2026-08-31')
    expect(weekFromParam('', now)?.start).toBe('2026-08-31')
  })

  it('accepts a Monday', () => {
    expect(weekFromParam('2026-08-24', now)?.start).toBe('2026-08-24')
  })

  it('rejects a non-Monday rather than snapping to the containing week', () => {
    // Snapping would show figures for a week the URL does not name — and the
    // endpoint 400s on the same value, so the picker would disagree with it.
    expect(weekFromParam('2026-09-02', now)).toBeNull()
  })

  it('rejects nonsense without throwing', () => {
    for (const bad of ['not-a-date', '2026-02-30', '02-09-2026']) {
      expect(weekFromParam(bad, now)).toBeNull()
    }
  })
})

describe('formatWeek', () => {
  it('names the week-year, not the year the dates are in', () => {
    expect(formatWeek(weekOf('2027-01-01'))).toBe('Week 53, 2026')
    expect(formatWeek(weekOf('2026-08-31'))).toBe('Week 36, 2026')
  })
})

describe('agreement with what already shipped', () => {
  /**
   * The MSW handler for `GET /dashboard/weekly` computes its own Monday, and
   * the contract refuses a `weekStart` that is not one. If this module ever
   * disagreed with either, the picker and the endpoint would report different
   * weeks and every figure on the tab would be off by seven days.
   */
  const handlerIsoMonday = (d: Date): string => {
    const out = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()))
    out.setUTCDate(out.getUTCDate() - ((out.getUTCDay() + 6) % 7))
    return out.toISOString().slice(0, 10)
  }

  it('matches the mock handler on every day of a two-year span', () => {
    const mismatches: string[] = []
    for (let t = Date.UTC(2025, 5, 1); t <= Date.UTC(2027, 5, 1); t += 86_400_000) {
      const date = new Date(t)
      const mine = isoMonday(date)
      const theirs = handlerIsoMonday(date)
      if (mine !== theirs) mismatches.push(`${date.toISOString().slice(0, 10)}: ${mine} vs ${theirs}`)
    }
    expect(mismatches).toEqual([])
  })

  it('never produces a start the server would reject', () => {
    for (let t = Date.UTC(2026, 0, 1); t <= Date.UTC(2027, 11, 31); t += 86_400_000) {
      expect(isMonday(weekOf(new Date(t)).start)).toBe(true)
    }
  })
})

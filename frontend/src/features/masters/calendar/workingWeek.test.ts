import { describe, expect, it } from 'vitest'
import {
  DAY_NAMES,
  MAX_DAYS_OFF,
  isNonWorkingDay,
  isoDayOf,
  normaliseWeeklyOff,
  toMinutes,
  toTimeInput,
  validateWorkingWeek,
  workingDayLength,
  type IsoDay,
} from './workingWeek'

/**
 * B-023 · the JS↔ISO weekday conversion.
 *
 * The bug this feature exists downstream of was a numbering mismatch that read
 * correctly six days out of seven, so the table below walks all seven
 * deliberately. A test that only checked Monday would have passed against the
 * broken convention.
 */
describe('isoDayOf', () => {
  // 2026-08-10 is a Monday, so this walks Mon → Sun.
  const MONDAY = new Date(2026, 7, 10)

  const cases: Array<[string, number, IsoDay]> = [
    ['Monday', 0, 1],
    ['Tuesday', 1, 2],
    ['Wednesday', 2, 3],
    ['Thursday', 3, 4],
    ['Friday', 4, 5],
    ['Saturday', 5, 6],
    ['Sunday', 6, 7],
  ]

  it.each(cases)('%s is ISO day %s', (name, offset, iso) => {
    const date = new Date(MONDAY)
    date.setDate(MONDAY.getDate() + offset)

    expect(date.getDay() === 0 ? 'Sunday' : DAY_NAMES[iso]).toBe(name)
    expect(isoDayOf(date)).toBe(iso)
  })

  it('maps Sunday to 7, not the 0 that Date.getDay reports', () => {
    const sunday = new Date(2026, 7, 16)

    expect(sunday.getDay()).toBe(0)
    expect(isoDayOf(sunday)).toBe(7)
  })
})

describe('isNonWorkingDay', () => {
  const WEEKEND = [6, 7]

  it('treats Saturday and Sunday as non-working under a [6, 7] pattern', () => {
    expect(isNonWorkingDay(WEEKEND, new Date(2026, 7, 15))).toBe(true) // Sat
    expect(isNonWorkingDay(WEEKEND, new Date(2026, 7, 16))).toBe(true) // Sun
  })

  it('treats weekdays as working', () => {
    expect(isNonWorkingDay(WEEKEND, new Date(2026, 7, 14))).toBe(false) // Fri
    expect(isNonWorkingDay(WEEKEND, new Date(2026, 7, 10))).toBe(false) // Mon
  })

  /**
   * The regression guard. Under the old `[0, 6]` convention Sunday carried the
   * number 0, so an ISO-aware check would find Sunday workable — the exact
   * defect that silently mis-stated every SLA spanning a weekend.
   */
  it('does not treat Sunday as working, which the old [0, 6] pattern would', () => {
    expect(isNonWorkingDay([0, 6], new Date(2026, 7, 16))).toBe(false)
    expect(isNonWorkingDay([6, 7], new Date(2026, 7, 16))).toBe(true)
  })

  it('supports a Fri/Sat weekend', () => {
    expect(isNonWorkingDay([5, 6], new Date(2026, 7, 14))).toBe(true) // Fri
    expect(isNonWorkingDay([5, 6], new Date(2026, 7, 16))).toBe(false) // Sun
  })
})

describe('normaliseWeeklyOff', () => {
  it('sorts, so the stored pattern does not depend on click order', () => {
    expect(normaliseWeeklyOff([7, 6])).toEqual([6, 7])
  })

  it('drops duplicates', () => {
    expect(normaliseWeeklyOff([6, 6, 7])).toEqual([6, 7])
  })

  it('drops values outside 1–7, including the JavaScript 0', () => {
    expect(normaliseWeeklyOff([0, 6, 8])).toEqual([6])
  })
})

describe('validateWorkingWeek', () => {
  const valid = { weeklyOff: [6, 7], workDayStart: '09:30', workDayEnd: '18:30' }

  it('accepts a standard week', () => {
    expect(validateWorkingWeek(valid)).toBeNull()
  })

  it('rejects a day that ends before it starts', () => {
    expect(validateWorkingWeek({ ...valid, workDayStart: '18:30', workDayEnd: '09:30' }))
      .toMatch(/end after it starts/)
  })

  it('rejects a zero-length day', () => {
    expect(validateWorkingWeek({ ...valid, workDayEnd: '09:30' })).toMatch(/end after it starts/)
  })

  it('rejects missing times', () => {
    expect(validateWorkingWeek({ ...valid, workDayEnd: '' })).toMatch(/start and an end/)
  })

  /** The bound comes from the generated Zod, so this follows the contract. */
  it('rejects a week with no working day left', () => {
    const everyDay = Array.from({ length: MAX_DAYS_OFF + 1 }, (_, i) => i + 1)

    expect(validateWorkingWeek({ ...valid, weeklyOff: everyDay }))
      .toMatch(/at least one working day/)
  })

  it('rejects the JavaScript 0 reaching the payload', () => {
    expect(validateWorkingWeek({ ...valid, weeklyOff: [0, 6] })).toMatch(/ISO weekday numbers/)
  })
})

describe('time helpers', () => {
  it('converts HH:mm to minutes from midnight', () => {
    expect(toMinutes('09:30')).toBe(570)
    expect(toMinutes('18:30')).toBe(1110)
    expect(toMinutes('00:00')).toBe(0)
  })

  it('trims the seconds a server may send, so <input type="time"> accepts it', () => {
    expect(toTimeInput('09:30:00')).toBe('09:30')
    expect(toTimeInput(undefined)).toBe('')
  })

  it('describes the length of the working day', () => {
    expect(workingDayLength('09:30', '18:30')).toBe('9h')
    expect(workingDayLength('09:00', '17:30')).toBe('8h 30m')
    expect(workingDayLength('18:30', '09:30')).toBe('—')
  })
})

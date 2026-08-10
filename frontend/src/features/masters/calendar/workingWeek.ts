import {
  updateWorkingWeekBodyWeeklyOffItemMax,
  updateWorkingWeekBodyWeeklyOffMax,
} from '@/api/generated/zod/masters/masters.zod'

/**
 * B-023 · the day model for S-14, and the **only** place this app converts
 * between a JavaScript weekday and an ISO one.
 *
 * The API speaks ISO-8601: Mon=1 … Sun=7, matching `DayOfWeek.getValue()` on
 * the backend. `Date.getDay()` is Sunday-zero-based, Sun=0 … Sat=6. The two
 * agree on nothing except Saturday, which is what makes the mismatch so easy to
 * ship: a calendar grid built with `getDay()` renders correctly all week and
 * marks the wrong day non-working.
 *
 * Convert at the boundary, once — `isoDayOf` below. A conversion repeated per
 * component does not fix the bug, it distributes it.
 */

/** ISO-8601 weekday numbers. */
export const ISO_DAYS = [1, 2, 3, 4, 5, 6, 7] as const
export type IsoDay = (typeof ISO_DAYS)[number]

/** Indexed by ISO number, so `DAY_NAMES[6]` is Saturday. Index 0 is unused. */
export const DAY_NAMES: Record<IsoDay, string> = {
  1: 'Monday',
  2: 'Tuesday',
  3: 'Wednesday',
  4: 'Thursday',
  5: 'Friday',
  6: 'Saturday',
  7: 'Sunday',
}

export const DAY_ABBREVIATIONS: Record<IsoDay, string> = {
  1: 'Mon',
  2: 'Tue',
  3: 'Wed',
  4: 'Thu',
  5: 'Fri',
  6: 'Sat',
  7: 'Sun',
}

/**
 * Bounds come from the generated Zod, never a copy — the same guard C-010 put
 * on the ticket form. If the contract loosens `maxItems`, this follows without
 * anyone remembering to.
 */
export const MAX_DAYS_OFF = updateWorkingWeekBodyWeeklyOffMax
export const MAX_ISO_DAY = updateWorkingWeekBodyWeeklyOffItemMax

/**
 * The one conversion from a JS `Date` to an ISO weekday.
 *
 * `getDay()` returns 0 for Sunday; ISO calls it 7. Every other day already
 * agrees, which is exactly why `|| 7` is enough and also why an unconverted
 * value looks right six days out of seven.
 */
export function isoDayOf(date: Date): IsoDay {
  return (date.getDay() || 7) as IsoDay
}

export function isIsoDay(value: number): value is IsoDay {
  return Number.isInteger(value) && value >= 1 && value <= MAX_ISO_DAY
}

/** Sorted, so a stored pattern does not depend on the order boxes were ticked. */
export function normaliseWeeklyOff(days: readonly number[]): IsoDay[] {
  return [...new Set(days)].filter(isIsoDay).sort((a, b) => a - b)
}

export function isNonWorkingDay(weeklyOff: readonly number[], date: Date): boolean {
  return weeklyOff.includes(isoDayOf(date))
}

/**
 * Why the form cannot be submitted, or null if it can.
 *
 * Returned as a message rather than a boolean because every one of these is
 * shown to the user verbatim; a boolean would mean writing the reason twice.
 */
export function validateWorkingWeek(values: {
  weeklyOff: readonly number[]
  workDayStart: string
  workDayEnd: string
}): string | null {
  if (values.weeklyOff.some((d) => !isIsoDay(d))) {
    return 'Days off must be ISO weekday numbers, 1 (Monday) to 7 (Sunday).'
  }
  if (values.weeklyOff.length > MAX_DAYS_OFF) {
    return 'A week needs at least one working day.'
  }
  if (!values.workDayStart || !values.workDayEnd) {
    return 'The working day needs a start and an end time.'
  }
  if (toMinutes(values.workDayEnd) <= toMinutes(values.workDayStart)) {
    return 'The working day must end after it starts.'
  }
  return null
}

/**
 * `HH:mm` to minutes from midnight — the form's comparison unit, and the same
 * encoding the database stores for the reason its migration explains.
 */
export function toMinutes(time: string): number {
  const [hours, minutes] = time.split(':').map(Number)
  return (hours || 0) * 60 + (minutes || 0)
}

/** The server may answer `09:30:00`; an `<input type="time">` wants `09:30`. */
export function toTimeInput(time: string | undefined): string {
  return (time ?? '').slice(0, 5)
}

/** Length of the working day, for the summary line on the screen. */
export function workingDayLength(start: string, end: string): string {
  const minutes = toMinutes(end) - toMinutes(start)
  if (minutes <= 0) return '—'
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours}h` : `${hours}h ${rest}m`
}

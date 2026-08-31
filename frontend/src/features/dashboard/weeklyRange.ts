/**
 * S-05 tab 3 · ISO week arithmetic, in UTC, with no clock of its own.
 *
 * <h2>UTC, because storage is</h2>
 *
 * Everything on this platform is stored in UTC and converted for display only.
 * A week computed from the viewer's local midnight would put the same ticket in
 * different weeks for two people looking at the same screen, and the one in
 * Auckland would see Monday's work filed under last week. Every function here
 * reads and writes UTC fields exclusively — no `getDay`, no `getMonth`, no
 * `toISOString()` on a date built from local parts.
 *
 * <h2>The week-year is not the calendar year</h2>
 *
 * ISO week 1 is the week containing the first Thursday of the year, so a week
 * routinely belongs to a year its own dates do not:
 *
 * - Mon 2024-12-30 is ISO **2025**-W01 — the week-year runs ahead.
 * - Fri 2021-01-01 is ISO **2020**-W53 — and behind.
 * - 2026 has a week 53; 2027-W01 does not begin until Mon 2027-01-04.
 *
 * `new Date(y, 0, 1)` plus a division by seven gets all three wrong, and gets
 * them wrong for one week in fifty-two, which is how a date bug reaches
 * production with everyone having tested it.
 *
 * <h2>No clock</h2>
 *
 * `thisWeek()` and `lastWeek()` take the current instant as an argument. A pure
 * module that reads the clock is a module whose tests either fake time or pass
 * only on the day they were written; the caller has a clock and can pass it.
 *
 * <h2>Agreeing with the server</h2>
 *
 * `GET /dashboard/weekly` refuses a `weekStart` that is not a Monday — a 400
 * rather than a silently shifted window, because a Wednesday-to-Wednesday range
 * returns figures that look ordinary and compare against the wrong seven days.
 * `isMonday` is that same rule, so the picker never builds a URL the endpoint
 * will reject.
 */

/** A calendar date, `YYYY-MM-DD`, always UTC. The wire format for `weekStart`. */
export type IsoDate = string

export interface Week {
  /** Monday, inclusive. */
  start: IsoDate
  /** Sunday, inclusive — not the exclusive end. `dueFrom`/`dueTo` are both inclusive. */
  end: IsoDate
  /** ISO week number, 1–53. */
  weekNumber: number
  /** The ISO week-year, which is not always the calendar year of `start`. */
  weekYear: number
}

const DAY_MS = 86_400_000
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** Formats as `YYYY-MM-DD` from UTC fields. */
export function toIsoDate(date: Date): IsoDate {
  return date.toISOString().slice(0, 10)
}

/**
 * Parses `YYYY-MM-DD` as UTC midnight.
 *
 * Rejects anything else rather than returning an Invalid Date that propagates
 * as `NaN` into arithmetic and surfaces three functions later as a blank
 * screen. It also rejects a well-formed but non-existent date — `2026-02-30`
 * parses to 2 March in every JavaScript engine, and a week picker silently
 * showing the wrong week is worse than one that throws.
 */
export function parseIsoDate(value: IsoDate): Date {
  if (!ISO_DATE.test(value)) {
    throw new RangeError(`Expected a YYYY-MM-DD date, got "${value}"`)
  }
  const date = new Date(`${value}T00:00:00.000Z`)
  if (Number.isNaN(date.getTime()) || toIsoDate(date) !== value) {
    throw new RangeError(`Not a real date: "${value}"`)
  }
  return date
}

const asDate = (value: Date | IsoDate): Date =>
  typeof value === 'string' ? parseIsoDate(value) : new Date(value.getTime())

/** Monday = 0 … Sunday = 6, which is the offset ISO arithmetic wants. */
const isoDayIndex = (date: Date): number => (date.getUTCDay() + 6) % 7

/** True when `value` is a Monday — the server's precondition for `weekStart`. */
export function isMonday(value: Date | IsoDate): boolean {
  return isoDayIndex(asDate(value)) === 0
}

/** The Monday of the week containing `value`. Idempotent on a Monday. */
export function isoMonday(value: Date | IsoDate): IsoDate {
  const date = asDate(value)
  date.setUTCDate(date.getUTCDate() - isoDayIndex(date))
  return toIsoDate(date)
}

/**
 * ISO week number and week-year.
 *
 * Both are read off the **Thursday** of the week rather than the date itself.
 * That is the whole trick: every ISO week has exactly one Thursday, and the
 * year that Thursday falls in is the week-year by definition, so the awkward
 * cases stop being special.
 */
export function isoWeekNumber(value: Date | IsoDate): { weekNumber: number; weekYear: number } {
  const thursday = asDate(value)
  thursday.setUTCDate(thursday.getUTCDate() - isoDayIndex(thursday) + 3)
  const weekYear = thursday.getUTCFullYear()

  // 4 January is always in week 1, whatever weekday it lands on.
  const firstThursday = new Date(Date.UTC(weekYear, 0, 4))
  firstThursday.setUTCDate(firstThursday.getUTCDate() - isoDayIndex(firstThursday) + 3)

  const weekNumber = 1 + Math.round((thursday.getTime() - firstThursday.getTime()) / (7 * DAY_MS))
  return { weekNumber, weekYear }
}

/** The whole ISO week containing `value`. */
export function weekOf(value: Date | IsoDate): Week {
  const start = isoMonday(value)
  const end = toIsoDate(new Date(parseIsoDate(start).getTime() + 6 * DAY_MS))
  return { start, end, ...isoWeekNumber(start) }
}

/**
 * The week `count` weeks before `week`.
 *
 * Adding a multiple of seven days is safe here where adding months would not
 * be: UTC has no daylight saving, so every day is exactly 86,400,000 ms.
 */
export function shiftWeeks(week: Week | IsoDate, count: number): Week {
  const start = typeof week === 'string' ? week : week.start
  return weekOf(new Date(parseIsoDate(start).getTime() + count * 7 * DAY_MS))
}

/**
 * The window a week's deltas compare against — the same ISO week, seven days
 * earlier. Never "the previous 7 days from today", which would slide with the
 * clock and make the same card show a different delta each morning.
 */
export const previousWeek = (week: Week | IsoDate): Week => shiftWeeks(week, -1)

export const thisWeek = (now: Date | IsoDate): Week => weekOf(now)
export const lastWeek = (now: Date | IsoDate): Week => shiftWeeks(weekOf(now), -1)

/** `Week 36, 2026` — and `Week 53, 2026` for a date sitting in January 2027. */
export const formatWeek = (week: Week): string => `Week ${week.weekNumber}, ${week.weekYear}`

/**
 * The two choices the picker offers, newest first.
 *
 * Only two, by decision: the tab reports one week against the one before it, so
 * a longer history would be a different screen. Analytics already answers
 * "how has this trended".
 */
export function pickerWeeks(now: Date | IsoDate): { label: string; week: Week }[] {
  return [
    { label: 'This week', week: thisWeek(now) },
    { label: 'Last week', week: lastWeek(now) },
  ]
}

/**
 * Normalises whatever arrived in `?weekStart=`.
 *
 * A URL is user-editable, so this takes the three things that actually turn up
 * — absent, a Monday, or something else — and answers with a week for the first
 * two and `null` for the third. Returning null rather than snapping to the
 * containing Monday is deliberate: silently correcting the input would show
 * figures for a week the URL does not name, and the endpoint would 400 on the
 * same value anyway.
 */
export function weekFromParam(weekStart: string | null | undefined, now: Date | IsoDate): Week | null {
  if (weekStart == null || weekStart === '') return thisWeek(now)
  let date: Date
  try {
    date = parseIsoDate(weekStart)
  } catch {
    return null
  }
  return isMonday(date) ? weekOf(date) : null
}

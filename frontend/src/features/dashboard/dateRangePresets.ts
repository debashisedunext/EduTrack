/**
 * The relative-window presets behind S-05's date control.
 *
 * <h2>Why the URL still carries `from` and `to`</h2>
 *
 * A preset is a way of *picking* a range, not a new kind of range. The moment
 * one is chosen it is resolved to two concrete dates, and those are what go in
 * the URL and to the summary endpoint — so a shared link keeps meaning the
 * fortnight it meant when it was sent, rather than silently re-resolving to a
 * different fortnight in the recipient's week. It also means nothing on the
 * server, in the contract, or in `useDashboardFilters` had to change.
 *
 * The cost of that choice is the one asymmetry here: `matchDateRangePreset`
 * reads a range back and can only recognise it as a preset while it still ends
 * *today*. Yesterday's "Last 1 week" link opens as a custom range showing the
 * same seven days. That is the honest reading — the dates are what was shared.
 *
 * <h2>Windows are inclusive and exactly as long as their label</h2>
 *
 * "Last 1 week" is seven calendar days *ending today*, so `from` is today minus
 * six days, not today minus seven. Both bounds are inclusive at the endpoint,
 * and a naive `today - 7` would quietly return eight days under a label that
 * says seven.
 *
 * <h2>UTC</h2>
 *
 * Every shift here is done on UTC civil dates, per CLAUDE.md — the daily
 * summary tables are bucketed by UTC day, so resolving a preset against a local
 * calendar would put the boundary in a different bucket for anyone west of
 * Greenwich. UTC also has no DST, so day arithmetic is plain milliseconds.
 */

export interface DateRange {
  from: string | null
  to: string | null
}

export type DateRangeUnit = 'week' | 'month'

export interface DateRangePreset {
  /** Stable key — the dropdown option key, and never shown to anyone. */
  id: string
  label: string
  amount: number
  unit: DateRangeUnit
}

/**
 * Ordered shortest to longest — the order somebody scanning the list expects,
 * and the order they widen through when a week turns out to be too narrow.
 */
export const DATE_RANGE_PRESETS: readonly DateRangePreset[] = [
  { id: '1w', label: 'Last 1 week', amount: 1, unit: 'week' },
  { id: '2w', label: 'Last 2 weeks', amount: 2, unit: 'week' },
  { id: '3w', label: 'Last 3 weeks', amount: 3, unit: 'week' },
  { id: '1m', label: 'Last 1 month', amount: 1, unit: 'month' },
  { id: '3m', label: 'Last 3 months', amount: 3, unit: 'month' },
  { id: '6m', label: 'Last 6 months', amount: 6, unit: 'month' },
  { id: '1y', label: 'Last 1 year', amount: 12, unit: 'month' },
]

const DAY_MS = 86_400_000

/** The `YYYY-MM-DD` an `<input type="date">` and the summary endpoint both speak. */
export function toIsoDate(date: Date): string {
  return date.toISOString().slice(0, 10)
}

/** Strips the time, keeping the UTC civil date — see the UTC note above. */
function utcMidnight(date: Date): Date {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()))
}

function shiftUtcDays(date: Date, days: number): Date {
  return new Date(date.getTime() + days * DAY_MS)
}

/**
 * Month arithmetic that clamps rather than overflowing.
 *
 * `Date.UTC(2026, 2, 31)` minus a month is 31 February, which the constructor
 * rolls forward to 3 March — a "last 1 month" ending 31 March would start
 * *after* the month it named. The last day of the target month is the answer
 * every calendar gives, so take that.
 */
function shiftUtcMonths(date: Date, months: number): Date {
  const year = date.getUTCFullYear()
  const month = date.getUTCMonth() + months
  const lastDayOfTarget = new Date(Date.UTC(year, month + 1, 0)).getUTCDate()
  return new Date(Date.UTC(year, month, Math.min(date.getUTCDate(), lastDayOfTarget)))
}

function shiftUtcWeeks(date: Date, weeks: number): Date {
  return shiftUtcDays(date, weeks * 7)
}

/**
 * Resolves a preset against a given day. `today` is a parameter rather than a
 * `new Date()` inside so the whole thing stays pure and the tests are not
 * dated.
 */
export function dateRangeForPreset(preset: DateRangePreset, today: Date): DateRange {
  const to = utcMidnight(today)
  const start =
    preset.unit === 'week' ? shiftUtcWeeks(to, -preset.amount) : shiftUtcMonths(to, -preset.amount)

  // +1 day, so the window is inclusive of both ends and exactly as long as its
  // label claims — see the header.
  return { from: toIsoDate(shiftUtcDays(start, 1)), to: toIsoDate(to) }
}

/** The preset a range came from, or `null` if it is a custom range. */
export function matchDateRangePreset(range: DateRange, today: Date): DateRangePreset | null {
  if (range.from == null || range.to == null) return null

  return (
    DATE_RANGE_PRESETS.find((preset) => {
      const candidate = dateRangeForPreset(preset, today)
      return candidate.from === range.from && candidate.to === range.to
    }) ?? null
  )
}

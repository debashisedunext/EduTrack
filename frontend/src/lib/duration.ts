/**
 * Duration and effort formatters — the §4A.4 columns, shared.
 *
 * These were written for the Journey grid (C-055, `features/tickets/journey/
 * journeyFormat.ts`) and moved here by B-050 because the ribbon renders the
 * same two numbers for the same hop, on the same page, a few hundred pixels
 * above the grid. Two implementations would let the ribbon say `2d 1h` while
 * the row underneath it says `49h`, and nothing would report that as a bug.
 *
 * `journeyFormat.ts` re-exports both, so no Journey call site changed.
 *
 * They live in `lib/` rather than in either consumer because one of the two is
 * a shared component (`components/ribbon/`) and the other is a feature. A
 * shared component importing from a feature is the wrong direction, and the
 * first thing that direction costs you is that `components/` stops being
 * reusable without dragging `features/tickets/` in behind it.
 */

/**
 * `130` → `2h 10m`, `2940` → `2d 1h`, blueprint §4A.4's own column.
 *
 * **Two units at most, largest first, and the smaller one is dropped when it
 * is zero.** §4A.4's grid shows `2d 1h` and `1h 10m`, never `2d 1h 0m` — a
 * duration column that changes width per row is harder to scan down, which is
 * the only thing this column is for.
 *
 * **A day here is 24 hours, not a working day.** This formats an elapsed
 * span that the server already computed; it is not the SLA maths, which runs
 * on the working calendar (B-024) server-side. Rendering `1d` as "one working
 * day" would quietly claim a stage sat idle over a weekend when it did not.
 *
 * Null is `—` and not `0m`: an open hop has entered and not left, and `0m`
 * would read as "took no time" rather than "still running".
 */
export function formatDuration(mins: number | null | undefined): string {
  if (mins == null) return '—'
  if (mins < 0) return '—'
  if (mins < 1) return '0m'

  const days = Math.floor(mins / 1440)
  const hours = Math.floor((mins % 1440) / 60)
  const minutes = Math.round(mins % 60)

  if (days > 0) return hours > 0 ? `${days}d ${hours}h` : `${days}d`
  if (hours > 0) return minutes > 0 ? `${hours}h ${minutes}m` : `${hours}h`
  return `${minutes}m`
}

/**
 * `0.5` → `0.5 h`. One decimal, always — `9` reads as `9.0 h` beside `9.5 h`
 * so the column stays aligned under `tabular-nums`.
 *
 * Effort is hours because that is the unit it is *logged* in (§4B.3); duration
 * is minutes because that is what the server measured. They are deliberately
 * not converted into one another here — the conversion is C-056's idle split,
 * and it belongs where that subtraction is explained.
 */
export function formatEffortHrs(hrs: number | null | undefined): string {
  if (hrs == null) return '—'
  return `${hrs.toFixed(1)} h`
}

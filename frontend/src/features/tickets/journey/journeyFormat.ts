/**
 * C-055 · the two formatters the Journey grid needs, kept apart from the
 * component so they can be tested without rendering anything.
 *
 * Both take the shapes the contract actually emits, which includes `null` —
 * `JourneyRow.durationMins` and `exitedAt` are nullable for the hop the ticket
 * is sitting in right now. An open hop has entered and not left, and rendering
 * that as `0m` would read as "took no time" rather than "still running".
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
 * wall-clock span that the server already computed; it is not the SLA maths,
 * which runs on the working calendar (B-024) server-side. Rendering `1d` as
 * "one working day" would quietly claim a stage sat idle over a weekend when
 * it did not.
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

/**
 * The two date helpers the task strip and the task panel share.
 *
 * <p>A module of their own rather than exports beside `TaskFacts`: a file that
 * exports both components and plain functions loses fast refresh, and these are
 * wanted by the panel (for an escalation's timestamp) as well as by the rail.
 */
/** `2026-09-15T11:00:00Z` → "15 Sep, 11:00". */
export function formatDue(value: string): string {
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return value
  return `${parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })}, ${parsed.toLocaleTimeString(
    undefined,
    { hour: '2-digit', minute: '2-digit', hour12: false },
  )}`
}

/**
 * `2026-09-15T11:00:00Z` and `2026-09-15` both → "15 Sep"; nothing → "—".
 *
 * <p>No year, because the Module strip prints a start and an expected end side
 * by side on a caption line and four extra digits twice over is what pushes
 * that line onto a second row. The full date is in each one's `title`.
 *
 * <p>A plain `2026-09-15` is read as local midnight rather than as UTC
 * midnight — `new Date('2026-09-15')` is UTC by spec, which renders as the 14th
 * for every reader west of Greenwich.
 */
export function formatDay(value: string | null | undefined): string {
  if (!value) return '—'
  const parsed = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  if (Number.isNaN(parsed.getTime())) return '—'
  return parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short' })
}

/** The same date, spelled out — for a `title` where the short form is ambiguous. */
export function formatFullDay(value: string | null | undefined): string {
  if (!value) return 'not set'
  const parsed = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  if (Number.isNaN(parsed.getTime())) return value
  return parsed.toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' })
}

export function overdue(value: string | null | undefined): boolean {
  if (!value) return false
  const parsed = new Date(value)
  return !Number.isNaN(parsed.getTime()) && parsed.getTime() < Date.now()
}

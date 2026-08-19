/**
 * A-065 · turning what is on the report viewer into a schedule request.
 *
 * Two pure functions in their own module rather than inside
 * `ScheduleReportDialog`, because they are the part worth testing on their own
 * — and because a component file that also exports helpers loses fast refresh.
 */

/**
 * The viewer's filters, minus the date range.
 *
 * <p>A schedule's period comes from its cadence — a weekly run always covers
 * the week just finished — so a stored `from`/`to` would win over it and make
 * every run email the same window for ever. That failure looks exactly like a
 * working schedule until two files are compared, which is why it is dropped in
 * two places: the server drops it because it must, and this drops it so the
 * request says what it means. If only one side did, a reader of either would
 * reasonably conclude the dates were honoured.
 *
 * <p>Empty values are dropped too. `?projectId=` with nothing after it is a
 * filter bar that has been cleared, and storing it would be a filter nobody
 * chose.
 */
export function filtersFrom(params: URLSearchParams): Record<string, unknown> {
  const filters: Record<string, unknown> = {}
  params.forEach((value, key) => {
    if (key === 'from' || key === 'to' || value === '') {
      return
    }
    filters[key] = value
  })
  return filters
}

/**
 * Splits the recipients field.
 *
 * <p>Empty entries are dropped, so a trailing comma is not an error somebody
 * has to find and remove — it is the most common way this field is typed.
 * De-duplication is the server's job, since it is the side that has to compare
 * addresses case-insensitively against real accounts.
 */
export function splitAddresses(raw: string): string[] {
  return raw
    .split(',')
    .map((address) => address.trim())
    .filter((address) => address.length > 0)
}

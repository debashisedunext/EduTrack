import * as React from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * S-05's three filters, held in the URL — A-054's rule, extracted so it can be
 * tested.
 *
 * <h2>Why this is its own hook now</h2>
 *
 * It lived inline in `DashboardPage` and carried a bug that reached a user:
 * the date range wrote `from` and `to` through two separate calls, and
 * `setSearchParams` updaters do not compose across separate calls in the same
 * tick — the second read the URL as it was *before* the first and overwrote it.
 * Choosing a From and a To kept only the To, and the From box emptied itself.
 *
 * The ticket list had the identical defect in C-014's picker, found the same
 * afternoon. Two screens, two authors, one shape. What both were missing is a
 * way to write several keys at once, so that is the primitive here and the
 * single-key setter is derived from it — the composition that loses data is no
 * longer the one that is easiest to reach for.
 *
 * Filter state stays in the URL for A-054's original reason: a dashboard
 * somebody has narrowed to one project and a fortnight is a thing they send to
 * a colleague, bookmark, or return to after clicking a card and coming back.
 */
export interface DashboardFilters {
  projectId: string | null
  assigneeId: string | null
  from: string | null
  to: string | null
  /**
   * Dashboard Rework Dev 1, PR 2 · which of the four tabs is showing. Raw
   * URL value, possibly absent — `DashboardPage` applies the `today`
   * default, the same way every other consumer of this hook applies its
   * own default rather than this hook guessing one for it.
   */
  tab: string | null
}

export function useDashboardFilters() {
  const [params, setParams] = useSearchParams()

  const filters: DashboardFilters = React.useMemo(
    () => ({
      projectId: params.get('projectId'),
      assigneeId: params.get('assigneeId'),
      from: params.get('from'),
      to: params.get('to'),
      tab: params.get('tab'),
    }),
    [params],
  )

  /**
   * Writes any number of keys in one update.
   *
   * An empty string is treated as absent, not as a filter for the empty
   * string: a cleared date input reports `''`, and `?from=` in the URL would
   * both look wrong when shared and read back as a value.
   */
  const setFilters = React.useCallback(
    (updates: Partial<Record<keyof DashboardFilters, string | null>>) => {
      setParams(
        (current) => {
          const next = new URLSearchParams(current)
          for (const [key, value] of Object.entries(updates)) {
            if (value === null || value === '') {
              next.delete(key)
            } else {
              next.set(key, value)
            }
          }
          return next
        },
        // `replace`, so narrowing a dashboard does not fill the back button
        // with every intermediate filter state on the way to the one wanted.
        { replace: true },
      )
    },
    [setParams],
  )

  const setFilter = React.useCallback(
    (key: keyof DashboardFilters, value: string | null) => setFilters({ [key]: value }),
    [setFilters],
  )

  return { filters, setFilter, setFilters }
}

import * as React from 'react'
import { useSearchParams } from 'react-router-dom'
import type { Level } from '@/api/generated/model/level'
import type { StatusCode } from '@/api/generated/model/statusCode'

export interface TicketListFilters {
  q: string
  projectId: number | null
  clientId: number | null
  taskTypeId: number | null
  level: Level | null
  stage: string | null
  status: StatusCode | null
  assigneeId: number | null
  dueFrom: string | null
  dueTo: string | null
  // C-015 — saved views. Booleans mirror the contract's isDelayed / isClientRaised
  // style: present-and-true or absent, never a literal 'false' in the URL.
  isDelayed: boolean | null
  reopenedOnly: boolean | null
  unassigned: boolean | null
  excludeClosed: boolean | null
  closedFrom: string | null
  closedTo: string | null
}

export const EMPTY_FILTERS: TicketListFilters = {
  q: '',
  projectId: null,
  clientId: null,
  taskTypeId: null,
  level: null,
  stage: null,
  status: null,
  assigneeId: null,
  dueFrom: null,
  dueTo: null,
  isDelayed: null,
  reopenedOnly: null,
  unassigned: null,
  excludeClosed: null,
  closedFrom: null,
  closedTo: null,
}

const NUMERIC_KEYS = ['projectId', 'clientId', 'taskTypeId', 'assigneeId'] as const
const STRING_KEYS = ['level', 'stage', 'status', 'dueFrom', 'dueTo', 'closedFrom', 'closedTo'] as const
const BOOLEAN_KEYS = ['isDelayed', 'reopenedOnly', 'unassigned', 'excludeClosed'] as const

function parse(params: URLSearchParams): TicketListFilters {
  const filters = { ...EMPTY_FILTERS, q: params.get('q') ?? '' }
  for (const key of NUMERIC_KEYS) {
    const raw = params.get(key)
    filters[key] = raw != null && raw !== '' ? Number(raw) : null
  }
  for (const key of STRING_KEYS) {
    filters[key] = (params.get(key) as never) ?? null
  }
  for (const key of BOOLEAN_KEYS) {
    filters[key] = params.get(key) === 'true' ? true : null
  }
  return filters
}

/** Writes one key into a `URLSearchParams`, dropping it rather than ever writing `'false'`. */
function writeKey<K extends keyof TicketListFilters>(
  params: URLSearchParams,
  key: K,
  value: TicketListFilters[K],
) {
  if (value == null || value === '' || value === false) params.delete(key)
  else params.set(key, String(value))
}

/**
 * Filter state lives in the URL, not component state — `TopBar`'s global
 * search (C-005) already navigates here as `/tickets?q=…`, so this list has
 * to read `q` out of the URL on mount rather than owning it privately. Every
 * other filter follows the same rule for the same reason a saved view (C-015)
 * wants it: a filtered list is a link a manager can paste into chat.
 */
export function useTicketListFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = React.useMemo(() => parse(searchParams), [searchParams])

  const setFilter = React.useCallback(
    <K extends keyof TicketListFilters>(key: K, value: TicketListFilters[K]) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          writeKey(next, key, value)
          return next
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )

  /**
   * Applies several filter keys atomically in one URL update — a saved view
   * sets multiple keys at once, and going through `setFilter` N times would
   * have each call read the *pre-update* URL and clobber the previous call's
   * write (`setSearchParams` updaters don't compose across separate calls in
   * the same tick the way a single functional update does).
   *
   * Defaults to replacing the whole filter row (`opts.replace !== false`),
   * the same semantics as `resetFilters`: every key not named in `next` is
   * cleared. `q` is the one exception — it survives a replace unless `next`
   * names it explicitly, because the search box is a header control the
   * filter row's Reset already leaves alone for the same reason.
   */
  const applyFilters = React.useCallback(
    (next: Partial<TicketListFilters>, opts?: { replace?: boolean }) => {
      const replace = opts?.replace !== false
      setSearchParams(
        (prev) => {
          const params = replace ? new URLSearchParams() : new URLSearchParams(prev)
          if (replace && !('q' in next)) {
            const q = prev.get('q')
            if (q) params.set('q', q)
          }
          for (const key of Object.keys(next) as (keyof TicketListFilters)[]) {
            writeKey(params, key, next[key] as TicketListFilters[typeof key])
          }
          return params
        },
        { replace: true },
      )
    },
    [setSearchParams],
  )

  // Clears the filter row only — the search box lives in the header, not the
  // filter row the wireframe's "↺ Reset" sits in, so a search the user typed
  // to find this list should survive resetting the filters underneath it.
  const resetFilters = React.useCallback(() => {
    setSearchParams(
      (prev) => {
        const q = prev.get('q')
        const next = new URLSearchParams()
        if (q) next.set('q', q)
        return next
      },
      { replace: true },
    )
  }, [setSearchParams])

  const activeCount = (Object.keys(EMPTY_FILTERS) as (keyof TicketListFilters)[]).filter(
    (key) => key !== 'q' && filters[key] != null && filters[key] !== '',
  ).length

  return { filters, setFilter, applyFilters, resetFilters, activeCount }
}

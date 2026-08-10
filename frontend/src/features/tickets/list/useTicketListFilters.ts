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
}

const NUMERIC_KEYS = ['projectId', 'clientId', 'taskTypeId', 'assigneeId'] as const
const STRING_KEYS = ['level', 'stage', 'status', 'dueFrom', 'dueTo'] as const

function parse(params: URLSearchParams): TicketListFilters {
  const filters = { ...EMPTY_FILTERS, q: params.get('q') ?? '' }
  for (const key of NUMERIC_KEYS) {
    const raw = params.get(key)
    filters[key] = raw != null && raw !== '' ? Number(raw) : null
  }
  for (const key of STRING_KEYS) {
    filters[key] = (params.get(key) as never) ?? null
  }
  return filters
}

/**
 * Filter state lives in the URL, not component state — `TopBar`'s global
 * search (C-005) already navigates here as `/tickets?q=…`, so this list has
 * to read `q` out of the URL on mount rather than owning it privately. Every
 * other filter follows the same rule for the same reason a saved view (C-015)
 * will want: a filtered list is a link a manager can paste into chat.
 */
export function useTicketListFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = React.useMemo(() => parse(searchParams), [searchParams])

  const setFilter = React.useCallback(
    <K extends keyof TicketListFilters>(key: K, value: TicketListFilters[K]) => {
      setSearchParams(
        (prev) => {
          const next = new URLSearchParams(prev)
          if (value == null || value === '') next.delete(key)
          else next.set(key, String(value))
          return next
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

  return { filters, setFilter, resetFilters, activeCount }
}

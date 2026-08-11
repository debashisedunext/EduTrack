import * as React from 'react'
import { useSearchParams } from 'react-router-dom'
import type { RoleCode } from '@/api/generated/model/roleCode'

/**
 * B-010 · the four S-07 filters plus search.
 *
 * `isActive` is a tri-state and the third state is the default. Null means
 * both, not active — the screen whose job includes reactivating people cannot
 * open with the deactivated ones hidden.
 */
export interface ResourceFilters {
  q: string
  role: RoleCode | null
  projectId: number | null
  managerId: number | null
  isActive: boolean | null
}

export const EMPTY_RESOURCE_FILTERS: ResourceFilters = {
  q: '',
  role: null,
  projectId: null,
  managerId: null,
  isActive: null,
}

const NUMERIC_KEYS = ['projectId', 'managerId'] as const

function parse(params: URLSearchParams): ResourceFilters {
  const filters: ResourceFilters = {
    ...EMPTY_RESOURCE_FILTERS,
    q: params.get('q') ?? '',
    role: (params.get('role') as RoleCode) ?? null,
  }
  for (const key of NUMERIC_KEYS) {
    const raw = params.get(key)
    filters[key] = raw != null && raw !== '' ? Number(raw) : null
  }

  // Explicitly three-valued, unlike the ticket list's boolean filters. There
  // `isDelayed=false` and absent mean the same thing, so `false` is never
  // written; here they are different questions — "show me the deactivated
  // ones" against "show me everyone".
  const isActive = params.get('isActive')
  filters.isActive = isActive === 'true' ? true : isActive === 'false' ? false : null

  return filters
}

function writeKey<K extends keyof ResourceFilters>(
  params: URLSearchParams,
  key: K,
  value: ResourceFilters[K],
) {
  if (value == null || value === '') params.delete(key)
  else params.set(key, String(value))
}

/**
 * Filter state lives in the URL, for the reason the ticket list gives: a
 * filtered directory is a link. "Everyone reporting to Meera who is still
 * active" is a thing a manager pastes into chat, and it cannot be if the state
 * is private to a component.
 */
export function useResourceFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = React.useMemo(() => parse(searchParams), [searchParams])

  const setFilter = React.useCallback(
    <K extends keyof ResourceFilters>(key: K, value: ResourceFilters[K]) => {
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

  /** Clears the filter row. The search box is a header control and survives. */
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

  const activeCount = (Object.keys(EMPTY_RESOURCE_FILTERS) as (keyof ResourceFilters)[]).filter(
    (key) => key !== 'q' && filters[key] != null,
  ).length

  return { filters, setFilter, resetFilters, activeCount }
}

/**
 * The filters as the generated client's query parameters.
 *
 * Shared by the grid and the export link so the two cannot drift — the same
 * reason the backend routes both through one `filterFrom`.
 */
export function toQueryParams(filters: ResourceFilters) {
  return {
    q: filters.q || undefined,
    role: filters.role ?? undefined,
    projectId: filters.projectId ?? undefined,
    managerId: filters.managerId ?? undefined,
    isActive: filters.isActive ?? undefined,
  }
}

import * as React from 'react'
import { useSearchParams } from 'react-router-dom'

/**
 * B-025 · the four S-32 filters plus search.
 *
 * Blueprint line 946 names them: status, support plan, account manager and
 * project. `q` is the contract's free-text over name, code and domain.
 *
 * `isActive` is a tri-state and the third state is the default. Null means both,
 * not active — the screen whose job includes reactivating a client cannot open
 * with the deactivated ones hidden, which is the same call `useResourceFilters`
 * made for S-07 and for the same reason.
 */
export interface ClientFilters {
  q: string
  isActive: boolean | null
  projectId: number | null
  supportPlan: string | null
  accountManagerId: number | null
}

export const EMPTY_CLIENT_FILTERS: ClientFilters = {
  q: '',
  isActive: null,
  projectId: null,
  supportPlan: null,
  accountManagerId: null,
}

const NUMERIC_KEYS = ['projectId', 'accountManagerId'] as const

function parse(params: URLSearchParams): ClientFilters {
  const filters: ClientFilters = {
    ...EMPTY_CLIENT_FILTERS,
    q: params.get('q') ?? '',
    supportPlan: params.get('supportPlan') || null,
  }
  for (const key of NUMERIC_KEYS) {
    const raw = params.get(key)
    filters[key] = raw != null && raw !== '' ? Number(raw) : null
  }

  // Three-valued on purpose: "show me the deactivated ones" and "show me
  // everyone" are different questions, so `false` is written rather than
  // collapsed into absent.
  const isActive = params.get('isActive')
  filters.isActive = isActive === 'true' ? true : isActive === 'false' ? false : null

  return filters
}

function writeKey<K extends keyof ClientFilters>(
  params: URLSearchParams,
  key: K,
  value: ClientFilters[K],
) {
  if (value == null || value === '') params.delete(key)
  else params.set(key, String(value))
}

/**
 * Filter state lives in the URL. A filtered client list is a link — "every
 * Premium client of Meera's that is still active" is a thing an account manager
 * pastes into chat, and it cannot be if the state is private to a component.
 */
export function useClientFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = React.useMemo(() => parse(searchParams), [searchParams])

  const setFilter = React.useCallback(
    <K extends keyof ClientFilters>(key: K, value: ClientFilters[K]) => {
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

  const activeCount = (Object.keys(EMPTY_CLIENT_FILTERS) as (keyof ClientFilters)[]).filter(
    (key) => key !== 'q' && filters[key] != null,
  ).length

  return { filters, setFilter, resetFilters, activeCount }
}

/** The filters as the generated client's query parameters. */
export function toQueryParams(filters: ClientFilters) {
  return {
    q: filters.q || undefined,
    isActive: filters.isActive ?? undefined,
    projectId: filters.projectId ?? undefined,
    supportPlan: filters.supportPlan ?? undefined,
    accountManagerId: filters.accountManagerId ?? undefined,
  }
}

import * as React from 'react'
import { useSearchParams } from 'react-router-dom'

import type { ListObClientsParams } from '@/api/generated/model/listObClientsParams'
import type { ObClientStatus } from '@/api/generated/model/obClientStatus'
import type { ObGateStatus } from '@/api/generated/model/obGateStatus'
import type { ObRag } from '@/api/generated/model/obRag'

/**
 * B-108 · OB-03's filter row, in the URL.
 *
 * The backlog names four — status, RAG, owner and sales person — and the
 * contract carries two more the screen would be poorer without: `productId`,
 * which is how "who bought the LMS" is asked, and `gateStatus`, which is the
 * only way to ask for the state §9 calls "Prerequisites pending".
 *
 * ## RAG and the gate are two filters because they are two questions
 *
 * A client whose journeys are all `LOCKED` has `rag: null` and is returned by
 * none of the three colours. That is not a hole in the enum — `ObRag`'s own
 * description spends a paragraph refusing to fold six states into one chip, and
 * "prerequisites pending" is a gate state rather than a colour. So the screen
 * offers both controls and says so in the labels, rather than adding a fourth
 * value to a colour list and quietly meaning something else by it.
 *
 * Selecting a colour **and** `LOCKED` is therefore a combination that returns
 * nothing, correctly: it asks for a client that is both running and not. The
 * page's empty state says as much rather than looking broken.
 *
 * ## `ownerId` is not a column on the row
 *
 * A journey has no owner of its own, so a client with four products has as many
 * owners as it has running services. The filter asks "who is implementing this
 * client", which the server answers by walking the client's journeys to their
 * steps — see the contract's note on the parameter. It is the single most used
 * control on this screen, because "my clients" is what an implementor opens it
 * for, and it is why the row itself carries no owner column to sort by.
 */
export interface ObClientFilters {
  q: string
  status: ObClientStatus | null
  rag: ObRag | null
  gateStatus: ObGateStatus | null
  productId: number | null
  salesPersonId: number | null
  ownerId: number | null
}

export const EMPTY_OB_CLIENT_FILTERS: ObClientFilters = {
  q: '',
  status: null,
  rag: null,
  gateStatus: null,
  productId: null,
  salesPersonId: null,
  ownerId: null,
}

const NUMERIC_KEYS = ['productId', 'salesPersonId', 'ownerId'] as const

/**
 * Read back through the enum rather than trusted.
 *
 * The URL is user input — a hand-edited `?status=live` or a link that outlived
 * a rename reaches the server as a filter it does not recognise, and a list
 * that silently returns everything under a filter chip that says "Live" is
 * worse than one that shows the filter as unset. So an unknown value is
 * dropped here, where the chip and the request both read the same parsed state.
 */
const STATUSES: readonly ObClientStatus[] = ['ONBOARDING', 'LIVE', 'ON_HOLD', 'DROPPED']
const RAGS: readonly ObRag[] = ['GREEN', 'AMBER', 'RED']
const GATES: readonly ObGateStatus[] = ['LOCKED', 'OPEN']

function oneOf<T extends string>(allowed: readonly T[], raw: string | null): T | null {
  return raw != null && (allowed as readonly string[]).includes(raw) ? (raw as T) : null
}

function parse(params: URLSearchParams): ObClientFilters {
  const filters: ObClientFilters = {
    ...EMPTY_OB_CLIENT_FILTERS,
    q: params.get('q') ?? '',
    status: oneOf(STATUSES, params.get('status')),
    rag: oneOf(RAGS, params.get('rag')),
    gateStatus: oneOf(GATES, params.get('gateStatus')),
  }
  for (const key of NUMERIC_KEYS) {
    const raw = params.get(key)
    // `Number('')` is 0 and `Number('abc')` is NaN, and both would go out as a
    // filter for a user or product that cannot exist. Neither is a valid id.
    const parsed = raw != null && raw !== '' ? Number(raw) : null
    filters[key] = parsed != null && Number.isFinite(parsed) && parsed > 0 ? parsed : null
  }
  return filters
}

function writeKey<K extends keyof ObClientFilters>(
  params: URLSearchParams,
  key: K,
  value: ObClientFilters[K],
) {
  if (value == null || value === '') params.delete(key)
  else params.set(key, String(value))
}

/**
 * Filter state lives in the URL, on `useClientFilters`' reasoning for S-32.
 *
 * A filtered onboarding list is a link — "every red client Ravi is implementing"
 * is what one manager sends another during a stand-up, and it cannot be if the
 * state is private to a component. It is also what OB-02's cards will deep-link
 * into once B-127's slide-over rows need a "see all of these" escape hatch.
 */
export function useObClientFilters() {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = React.useMemo(() => parse(searchParams), [searchParams])

  const setFilter = React.useCallback(
    <K extends keyof ObClientFilters>(key: K, value: ObClientFilters[K]) => {
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

  const activeCount = (Object.keys(EMPTY_OB_CLIENT_FILTERS) as (keyof ObClientFilters)[]).filter(
    (key) => key !== 'q' && filters[key] != null,
  ).length

  return { filters, setFilter, resetFilters, activeCount }
}

/** The filters as `listObClients`' query parameters. */
export function toQueryParams(filters: ObClientFilters): ListObClientsParams {
  return {
    q: filters.q.trim() || undefined,
    status: filters.status ?? undefined,
    rag: filters.rag ?? undefined,
    gateStatus: filters.gateStatus ?? undefined,
    productId: filters.productId ?? undefined,
    salesPersonId: filters.salesPersonId ?? undefined,
    ownerId: filters.ownerId ?? undefined,
  }
}

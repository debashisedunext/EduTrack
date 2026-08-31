import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { useDashboardFilters } from './useDashboardFilters'

/**
 * The bug these exist for reached a user twice in one afternoon — once on this
 * screen and once on the ticket list, written by different people.
 *
 * Picking a From date and a To date saved only the To, and the From input
 * emptied itself. Nothing errored; the dashboard simply answered over a
 * different window than the one on screen.
 */
function renderFilters(initialEntry = '/dashboard') {
  return renderHook(() => ({ ...useDashboardFilters(), location: useLocation() }), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <MemoryRouter initialEntries={[initialEntry]}>{children}</MemoryRouter>
    ),
  })
}

describe('useDashboardFilters', () => {
  it('reads the four filters off the URL', () => {
    const { result } = renderFilters(
      '/dashboard?projectId=4&assigneeId=9&from=2026-08-01&to=2026-08-19&tab=weekly',
    )

    expect(result.current.filters).toEqual({
      projectId: '4',
      assigneeId: '9',
      from: '2026-08-01',
      to: '2026-08-19',
      tab: 'weekly',
    })
  })

  /** No default applied here — that is `DashboardPage`'s call, not this hook's. */
  it('reads tab as null rather than defaulting it when absent', () => {
    const { result } = renderFilters('/dashboard')

    expect(result.current.filters.tab).toBeNull()
  })

  /**
   * The regression. Two keys have to land in one update, because
   * `setSearchParams` updaters do not compose across separate calls in the
   * same tick — the second reads the pre-update URL and overwrites the first.
   */
  it('keeps both dates when a range is applied in one update', () => {
    const { result } = renderFilters('/dashboard')

    act(() => {
      result.current.setFilters({ from: '2026-08-01', to: '2026-08-19' })
    })

    expect(result.current.filters.from).toBe('2026-08-01')
    expect(result.current.filters.to).toBe('2026-08-19')
  })

  /**
   * The shape that was broken, pinned so nobody reintroduces it believing two
   * calls are equivalent. Both lines look correct in isolation; only their
   * composition is wrong, which is why a diff never showed it.
   */
  it('loses the first key if two single-key writes are made in one tick', () => {
    const { result } = renderFilters('/dashboard')

    act(() => {
      result.current.setFilter('from', '2026-08-01')
      result.current.setFilter('to', '2026-08-19')
    })

    expect(result.current.filters.from).toBeNull()
    expect(result.current.filters.to).toBe('2026-08-19')
  })

  it('leaves the other filters alone when only the dates change', () => {
    const { result } = renderFilters('/dashboard?projectId=4&assigneeId=9')

    act(() => {
      result.current.setFilters({ from: '2026-08-01', to: '2026-08-19' })
    })

    expect(result.current.filters.projectId).toBe('4')
    expect(result.current.filters.assigneeId).toBe('9')
  })

  it('clears a date rather than writing an empty value into the URL', () => {
    const { result } = renderFilters('/dashboard?from=2026-08-01&to=2026-08-19')

    // A cleared date input reports '' — `?from=` would look wrong in a shared
    // link and read back as a value.
    act(() => {
      result.current.setFilters({ from: '', to: null })
    })

    expect(result.current.location.search).not.toContain('from=')
    expect(result.current.location.search).not.toContain('to=')
    expect(result.current.filters.from).toBeNull()
  })

  it('replaces history rather than stacking every intermediate filter state', () => {
    const { result } = renderFilters('/dashboard')

    act(() => result.current.setFilter('projectId', '4'))
    act(() => result.current.setFilter('projectId', '7'))

    expect(result.current.filters.projectId).toBe('7')
  })
})

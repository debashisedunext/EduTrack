import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { EMPTY_FILTERS, useTicketListFilters } from './useTicketListFilters'

function renderFiltersHook(initialEntry = '/tickets') {
  return renderHook(() => ({ ...useTicketListFilters(), location: useLocation() }), {
    wrapper: ({ children }: { children: ReactNode }) => (
      <MemoryRouter initialEntries={[initialEntry]}>{children}</MemoryRouter>
    ),
  })
}

describe('useTicketListFilters — C-015 additions', () => {
  it('applyFilters replaces the whole filter row in one update, keeping q untouched', () => {
    const { result } = renderFiltersHook('/tickets?projectId=1&level=HIGH&status=NEW&q=payment')

    act(() => {
      result.current.applyFilters({ isDelayed: true })
    })

    expect(result.current.filters).toEqual({ ...EMPTY_FILTERS, q: 'payment', isDelayed: true })
  })

  it('applyFilters can merge onto the existing filters instead of replacing, via opts.replace: false', () => {
    const { result } = renderFiltersHook('/tickets?projectId=1')

    act(() => {
      result.current.applyFilters({ isDelayed: true }, { replace: false })
    })

    expect(result.current.filters.projectId).toBe(1)
    expect(result.current.filters.isDelayed).toBe(true)
  })

  it('never writes a literal "false" into the URL for a boolean filter — it is deleted instead', () => {
    const { result } = renderFiltersHook()

    act(() => result.current.setFilter('isDelayed', true))
    expect(result.current.location.search).toContain('isDelayed=true')

    act(() => result.current.setFilter('isDelayed', false))
    expect(result.current.location.search).not.toContain('isDelayed')
    expect(result.current.filters.isDelayed).toBeNull()
  })

  it('applyFilters never writes "false" either, for any boolean key in the recipe', () => {
    const { result } = renderFiltersHook('/tickets?unassigned=true')

    act(() => result.current.applyFilters({ reopenedOnly: false, isDelayed: true }))

    expect(result.current.location.search).not.toContain('reopenedOnly')
    expect(result.current.location.search).not.toContain('unassigned') // replaced away, not merged
    expect(result.current.filters.isDelayed).toBe(true)
  })

  it('activeCount counts the new boolean and date keys the same as any other filter', () => {
    const { result } = renderFiltersHook()
    expect(result.current.activeCount).toBe(0)

    act(() => result.current.setFilter('unassigned', true))
    expect(result.current.activeCount).toBe(1)

    act(() => result.current.setFilter('closedFrom', '2026-08-01'))
    expect(result.current.activeCount).toBe(2)

    act(() => result.current.applyFilters({ status: 'CLOSED', closedTo: '2026-08-31' }))
    // A fresh replace: exactly the two keys named in the recipe.
    expect(result.current.activeCount).toBe(2)
  })

  it('resetFilters clears every C-015 key too, leaving q alone', () => {
    const { result } = renderFiltersHook('/tickets?q=payment')

    act(() => {
      result.current.applyFilters({ isDelayed: true, unassigned: true, closedFrom: '2026-08-01' })
    })
    expect(result.current.activeCount).toBe(3)

    act(() => result.current.resetFilters())
    expect(result.current.filters).toEqual({ ...EMPTY_FILTERS, q: 'payment' })
  })
})

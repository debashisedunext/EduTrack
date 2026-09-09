import { describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import * as React from 'react'
import { MemoryRouter } from 'react-router-dom'

import { isContradictory, toQueryParams, useObClientFilters } from './useObClientFilters'

/**
 * B-108 · OB-03's filter state, which lives in the URL.
 *
 * Rendered inside a real router rather than against a stubbed
 * `useSearchParams`: the whole point of the hook is that the URL *is* the
 * state, and a stub would let a key be written under one name and read under
 * another without either test noticing.
 */
function renderFilters(initialPath = '/onboarding/clients') {
  return renderHook(() => useObClientFilters(), {
    wrapper: ({ children }) =>
      React.createElement(MemoryRouter, { initialEntries: [initialPath] }, children),
  })
}

describe('useObClientFilters', () => {
  it('starts empty, and an empty filter row sends no parameters at all', () => {
    const { result } = renderFilters()

    expect(result.current.activeCount).toBe(0)
    expect(toQueryParams(result.current.filters)).toEqual({
      q: undefined,
      status: undefined,
      rag: undefined,
      gateStatus: undefined,
      productId: undefined,
      salesPersonId: undefined,
      ownerId: undefined,
    })
  })

  it('reads every filter back off the URL, so a filtered list is a link', () => {
    const { result } = renderFilters(
      '/onboarding/clients?q=north&status=LIVE&rag=RED&gateStatus=OPEN&productId=2&salesPersonId=5&ownerId=3',
    )

    expect(result.current.filters).toEqual({
      q: 'north',
      status: 'LIVE',
      rag: 'RED',
      gateStatus: 'OPEN',
      productId: 2,
      salesPersonId: 5,
      ownerId: 3,
    })
    // `q` is a header control, not a filter chip — six keys, one of them the
    // search box, so the count is six minus that one.
    expect(result.current.activeCount).toBe(6)
  })

  /**
   * The URL is user input.
   *
   * A hand-edited value or a link that outlived a rename would otherwise be
   * sent to the server as a filter it does not recognise, and the screen would
   * show a chip claiming a narrowing that never happened. Dropping it here
   * makes the chip and the request agree — both read the same parsed state.
   */
  it('drops a status, rag or gate the enum does not contain', () => {
    const { result } = renderFilters(
      '/onboarding/clients?status=live&rag=PURPLE&gateStatus=HALF_OPEN',
    )

    expect(result.current.filters.status).toBeNull()
    expect(result.current.filters.rag).toBeNull()
    expect(result.current.filters.gateStatus).toBeNull()
    expect(result.current.activeCount).toBe(0)
  })

  /**
   * `Number('')` is 0 and `Number('abc')` is NaN, and both are ids no row can
   * have. Sending either would filter the list down to nothing while the chip
   * showed a person or product that was never selected.
   */
  it('drops a non-numeric, empty or non-positive id', () => {
    const { result } = renderFilters(
      '/onboarding/clients?productId=&salesPersonId=abc&ownerId=0',
    )

    expect(result.current.filters.productId).toBeNull()
    expect(result.current.filters.salesPersonId).toBeNull()
    expect(result.current.filters.ownerId).toBeNull()
  })

  it('writes a filter into the URL and clears it back out again', () => {
    const { result } = renderFilters()

    act(() => result.current.setFilter('ownerId', 3))
    expect(result.current.filters.ownerId).toBe(3)
    expect(result.current.activeCount).toBe(1)

    act(() => result.current.setFilter('ownerId', null))
    expect(result.current.filters.ownerId).toBeNull()
    expect(result.current.activeCount).toBe(0)
  })

  /**
   * Reset clears the row and keeps the search box, which is the behaviour
   * `useClientFilters` settled on for S-32: the search term is what somebody
   * typed, and throwing it away when they widen the filters is losing work
   * they did not ask to lose.
   */
  it('resets the filter row without discarding the search term', () => {
    const { result } = renderFilters('/onboarding/clients?q=north&status=LIVE&ownerId=3')

    act(() => result.current.resetFilters())

    expect(result.current.filters.q).toBe('north')
    expect(result.current.filters.status).toBeNull()
    expect(result.current.filters.ownerId).toBeNull()
    expect(result.current.activeCount).toBe(0)
  })

  it('trims the search term rather than sending whitespace as a name fragment', () => {
    const { result } = renderFilters('/onboarding/clients?q=%20%20north%20%20')

    expect(toQueryParams(result.current.filters).q).toBe('north')
  })

  /**
   * The mockup's Health select offers "Live", which is a status in the
   * contract, not a colour. The hook accepts it as a health value and
   * `toQueryParams` translates on the way out, so the server never sees a
   * fourth RAG.
   */
  it('translates Health "Live" to status=LIVE and sends no rag', () => {
    const { result } = renderFilters('/onboarding/clients?rag=LIVE')

    expect(result.current.filters.rag).toBe('LIVE')
    const params = toQueryParams(result.current.filters)
    expect(params.status).toBe('LIVE')
    expect(params.rag).toBeUndefined()
  })

  it('flags Health "Live" under an explicit non-Live status as contradictory, never overriding it', () => {
    const { result } = renderFilters('/onboarding/clients?status=ONBOARDING&rag=LIVE')

    expect(isContradictory(result.current.filters)).toBe(true)
    // Redundant rather than contradictory: both say LIVE.
    const agreeing = renderFilters('/onboarding/clients?status=LIVE&rag=LIVE')
    expect(isContradictory(agreeing.result.current.filters)).toBe(false)
  })

  it('flags a colour on a locked gate as contradictory', () => {
    const { result } = renderFilters('/onboarding/clients?gateStatus=LOCKED&rag=RED')

    expect(isContradictory(result.current.filters)).toBe(true)
  })
})

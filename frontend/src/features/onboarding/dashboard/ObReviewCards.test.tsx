import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ObReviewCards } from './ObReviewCards'
import { OB_DASHBOARD_QUERY } from './obDashboardFreshness'

/**
 * C-141 · the two review cards against a mocked `useGetObReviewSummary`, the
 * shape {@code ObImplementorWorkloadGrid.test.tsx} uses. What the reporter saw
 * — figures that never move — was not a rendering fault, so the assertion that
 * matters here is the one on the options the hook is called with.
 */

const useGetObReviewSummary = vi.fn()
vi.mock('@/api/generated/onboarding/onboarding', () => ({
  useGetObReviewSummary: (...args: unknown[]) => useGetObReviewSummary(...args),
}))

function served(data: unknown) {
  useGetObReviewSummary.mockReturnValue({ data: { data }, isPending: false, isError: false })
}

function draw() {
  render(
    <MemoryRouter>
      <ObReviewCards />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  useGetObReviewSummary.mockReset()
})

describe('ObReviewCards', () => {
  it('reads on the board’s shared freshness options, not the app default', () => {
    served({ reviewsPending: 0, sentForReview: 2, reviewsApproved: 6, reviewsRejected: 0 })
    draw()

    /*
      The bug: this card was the one query on OB-02 passing nothing, so it kept
      a 30-second cache with no refetch on mount or focus and the reader's own
      approval never showed. Asserting on the shared object rather than on its
      three fields keeps this test honest if the cadence is ever retuned — the
      claim is "the same as every other card", not "60 seconds".
    */
    expect(useGetObReviewSummary).toHaveBeenCalledWith({ query: OB_DASHBOARD_QUERY })
  })

  it('draws the three own-verification figures', () => {
    served({ reviewsPending: 0, sentForReview: 2, reviewsApproved: 6, reviewsRejected: 1 })
    draw()

    const own = screen.getByTestId('ob-review-card-own')
    expect(own).toHaveTextContent('2')
    expect(own).toHaveTextContent('out for review')
    expect(own).toHaveTextContent('6')
    expect(own).toHaveTextContent('approved today')
    expect(own).toHaveTextContent('1')
    expect(own).toHaveTextContent('came back today')
    expect(screen.queryByTestId('ob-review-card-pending')).not.toBeInTheDocument()
  })

  it('says when the figures were counted, so a dead worker is visible', () => {
    served({
      reviewsPending: 0, sentForReview: 2, reviewsApproved: 6, reviewsRejected: 0,
      computedAt: '2026-09-21T09:38:53Z',
    })
    draw()

    expect(screen.getByTestId('ob-review-card-own-asof')).toHaveTextContent(/counted at/i)
  })

  it('says nothing about freshness when nothing has computed the figures', () => {
    /*
      The service answers a null stamp on a database the stats worker has never
      run against. "counted at —" would raise a question the card cannot
      answer, so the line is absent instead.
    */
    served({
      reviewsPending: 0, sentForReview: 2, reviewsApproved: 0, reviewsRejected: 0,
      computedAt: null,
    })
    draw()

    expect(screen.queryByTestId('ob-review-card-own-asof')).not.toBeInTheDocument()
  })

  it('draws nothing when the caller has neither figure', () => {
    served({ reviewsPending: 0, sentForReview: 0, reviewsApproved: 0, reviewsRejected: 0 })
    draw()

    expect(screen.queryByTestId('ob-review-cards')).not.toBeInTheDocument()
  })
})

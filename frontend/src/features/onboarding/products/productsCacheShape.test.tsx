import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { resetDb } from '@/mocks/db'
import { Toaster } from '@/components/ui/toaster'

import { ObProductMasterPage } from './ObProductMasterPage'
import { ModuleServiceCataloguePage } from '../journeys/ModuleServiceCataloguePage'

/**
 * The regression this file exists for: **two screens, one query key, one
 * shape.**
 *
 * <p>`/onboarding/products` is read by the Products master through
 * `useProducts()` and by the Module Service catalogue (and the journey
 * designer, and the report filter bar) through the generated
 * `useListObProducts()`. Both sit on `getListObProductsQueryKey()`, so
 * whatever the first of them writes is what the second reads back.
 *
 * <p>When `useProducts()` cached the bare `ObProduct[]` instead of the
 * `{ data }` envelope the generated hook caches, the second screen to open got
 * the other one's shape: `query.data.data` was `undefined` arriving at the
 * catalogue, and `products.map` was not a function arriving at the master.
 * A full reload emptied the cache, so whichever page you landed on fetched its
 * own shape and worked — which is exactly why this was reported as "the data
 * only shows up after a refresh" rather than as a cache disagreement.
 *
 * <p>So both orders are tested, and they must be: the bug was never visible in
 * one page on its own, and either page's test suite passes with it present.
 * One `QueryClient` across both renders is the whole point — a fresh client per
 * page is the reload that hid it.
 */
vi.setConfig({ testTimeout: 20000 })
const SLOW = { timeout: 8000 }

/**
 * One cache for the session, as the app has — see the docstring.
 *
 * <p>`staleTime` is `app/queryClient.ts`'s own 30s rather than the 0 most
 * tests here leave it at, and that is load-bearing. At 0 every remount
 * refetches immediately, and the refetch writes the arriving screen's own
 * shape over the cache before the shape it was handed can matter — which
 * hides half of this bug rather than testing it. Thirty seconds is what the
 * app actually does, and it is why navigating between these two screens
 * showed the other one's data shape.
 */
function session() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
  })
  return function visit(page: React.ReactNode) {
    return render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>{page}</MemoryRouter>
        <Toaster />
      </QueryClientProvider>,
    )
  }
}

describe('the products list survives navigating between the screens that read it', () => {
  it('lists the catalogue after the Products master has filled the cache', async () => {
    resetDb()
    const visit = session()

    const master = visit(<ObProductMasterPage />)
    await screen.findByRole('cell', { name: 'EduTrack ERP' }, SLOW)
    master.unmount()

    // Inside `staleTime`, so this renders straight from what the master wrote
    // — no refetch to paper over a shape it cannot read.
    visit(<ModuleServiceCataloguePage />)
    expect(await screen.findByRole('link', { name: 'ERP Suite onboarding' }, SLOW)).toBeInTheDocument()
  })

  it('lists the Products master after the catalogue has filled the cache', async () => {
    resetDb()
    const visit = session()

    const catalogue = visit(<ModuleServiceCataloguePage />)
    await screen.findByRole('link', { name: 'ERP Suite onboarding' }, SLOW)
    catalogue.unmount()

    visit(<ObProductMasterPage />)
    expect(await screen.findByRole('cell', { name: 'EduTrack ERP' }, SLOW)).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('Products could not be loaded.')).not.toBeInTheDocument())
  })
})

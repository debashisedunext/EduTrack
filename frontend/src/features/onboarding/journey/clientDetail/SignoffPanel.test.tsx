import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { SignoffPanel } from './SignoffPanel'

/**
 * §8's staff panel, read as a page reader meets it rather than through one of
 * the pages that mount it.
 *
 * <h2>Why this file exists at all</h2>
 *
 * The project page draws one go-live panel per finished Module Service, which
 * is correct — a sign-off belongs to a service — but was reported as a bug:
 * two panels, one under the other, both headed "Go-live sign-off" and neither
 * saying which service it settled. They read as the same panel drawn twice.
 * What makes them tell apart is `serviceName`, and this is where that is held.
 *
 * <h2>The fixtures</h2>
 *
 * GreenValley (client 1) is the reported shape exactly: journey 11 (ERP Suite
 * onboarding) carries the signed go-live, journey 12 (Biometric Attendance
 * onboarding) has never been asked. One settled panel and one offering the
 * request, on one page — the pair the report was about.
 */
function renderPanels(names?: { erp: string; bio: string }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <SignoffPanel kind="GO_LIVE" journeyId={11} obClientId={1} serviceName={names?.erp} />
      <SignoffPanel kind="GO_LIVE" journeyId={12} obClientId={1} serviceName={names?.bio} />
    </QueryClientProvider>,
  )
}

/** MSW adds latency and the suite is heavily parallel — `ClientListPage.test.tsx`'s convention. */
const SLOW = { timeout: 5000 }

vi.setConfig({ testTimeout: 20_000 })

describe('SignoffPanel', () => {
  it('names the module service, so two panels on one page are two sign-offs', async () => {
    renderPanels({ erp: 'ERP Suite onboarding', bio: 'Biometric Attendance onboarding' })

    const erp = await screen.findByRole(
      'region',
      { name: 'Go-live sign-off — ERP Suite onboarding' },
      SLOW,
    )
    const bio = screen.getByRole('region', { name: 'Go-live sign-off — Biometric Attendance onboarding' })

    // Named apart, and genuinely two different sign-offs rather than one
    // rendered twice: the states differ, which is the thing the shared heading
    // was hiding. findBy on both — each mounts before its own read lands.
    expect(await within(erp).findByText('✓ Accepted', undefined, SLOW)).toBeInTheDocument()
    expect(await within(bio).findByText('Not requested', undefined, SLOW)).toBeInTheDocument()
    expect(within(bio).getByRole('button', { name: /Request sign-off/ })).toBeInTheDocument()
  })

  it('keeps the bare heading where the call site has already named the service', async () => {
    // OB-05 mounts one panel inside the service's own accordion, which heads
    // itself. Repeating the name there would be the heading said twice.
    renderPanels()
    const panels = await screen.findAllByRole('region', { name: 'Go-live sign-off' }, SLOW)
    expect(panels).toHaveLength(2)
  })
})

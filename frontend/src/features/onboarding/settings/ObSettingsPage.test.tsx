import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { ObSettingsPage } from './ObSettingsPage'

/**
 * B-113 · OB-11 against the mock server.
 *
 * Fixture note — `db.ts`'s `OB_SETTINGS`: PHASE-2-BUILD-PLAN §2's locked values,
 * amber 75, scanner 5, and the ladder at 0 / 4 / 8. The mock's `PUT` enforces
 * the ascending rule too, so the client-side check below is asserting that the
 * admin is told *before* they press Save rather than that the rule exists.
 */
/**
 * The first `findBy` on each test waits longer than the default second.
 *
 * These screens paint "Loading…" until MSW answers, and under a full-suite run
 * that round trip regularly exceeds the default — the whole onboarding suite
 * shows the same behaviour. A longer wait is the honest fix: the assertion is
 * about what the page renders, not about how fast a mock server is under load.
 */
const SLOW = { timeout: 5000 }

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <ObSettingsPage />
    </QueryClientProvider>,
  )
}

describe('OB-11 TAT settings', () => {
  it('shows the seeded values as the starting point', async () => {
    renderPage()

    expect(await screen.findByLabelText(/amber threshold/i, {}, SLOW)).toHaveValue(75)
    expect(screen.getByLabelText(/scanner interval/i)).toHaveValue(5)
    expect(screen.getByLabelText(/L1 after working hours/i)).toHaveValue(0)
    expect(screen.getByLabelText(/L3 after working hours/i)).toHaveValue(8)
  })

  it('says the hours are working hours, not clock hours', async () => {
    renderPage()

    // CLAUDE.md routes all duration maths through the working calendar. An
    // admin who reads "hours" and types 48 meaning two days has misconfigured
    // the ladder, and nothing downstream would tell them.
    expect(await screen.findByText(/not clock hours/i, {}, SLOW)).toBeInTheDocument()
  })

  it('refuses a ladder that does not ascend, before Save is pressed', async () => {
    const user = userEvent.setup()
    renderPage()

    const l3 = await screen.findByLabelText(/L3 after working hours/i, {}, SLOW)
    await user.clear(l3)
    await user.type(l3, '2')

    expect(await screen.findByRole('alert')).toHaveTextContent(/L3 must fire after L2/i)
    expect(screen.getByRole('button', { name: /save/i })).toBeDisabled()
  })

  it('saves a valid change', async () => {
    const user = userEvent.setup()
    renderPage()

    const amber = await screen.findByLabelText(/amber threshold/i, {}, SLOW)
    await user.clear(amber)
    await user.type(amber, '60')
    await user.click(screen.getByRole('button', { name: /save/i }))

    expect(await screen.findByRole('status')).toHaveTextContent(/saved/i)
  })

  it('offers no way to add a fourth rung', async () => {
    renderPage()

    await screen.findByLabelText(/L1 after working hours/i, {}, SLOW)
    // ObEscalationLevel is closed and uq_ob_escalations_open is keyed on it, so
    // a fourth rung would have no level to be. A button that cannot work is
    // worse than its absence.
    expect(screen.queryByRole('button', { name: /add rung/i })).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/L4/i)).not.toBeInTheDocument()
  })

  it('states that a change is read forward and does not reopen what already breached', async () => {
    renderPage()

    // A settings change that reissued historical notifications would mail every
    // owner in the organisation about steps they closed last week.
    expect(await screen.findByText(/are not re-opened/i, {}, SLOW)).toBeInTheDocument()
  })
})

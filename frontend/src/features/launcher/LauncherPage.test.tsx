import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter } from 'react-router-dom'

import { LauncherPage } from './LauncherPage'
import { ModuleSwitcher } from './ModuleSwitcher'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'
import type { Me } from '@/api/generated/model/me'

/**
 * A-116 · OB-01.
 *
 * The assertions worth having here are about **what is not asked for**. A
 * launcher that requested both modules' summaries and drew whatever came back
 * would look identical to this one on a dual-module account, and would fire a
 * request the module gate answers 404 for on every other kind — so the tests
 * that matter check the absence of a call, not the presence of a card.
 */

function signedInWith(modules: string[], displayName = 'Priya Nair') {
  useAuthStore.setState({
    ...initialAuthState,
    status: 'authenticated',
    user: { id: 1, displayName, modules } as unknown as Me,
  })
}

function renderLauncher() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LauncherPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function renderSwitcher() {
  return render(
    <MemoryRouter>
      <ModuleSwitcher />
    </MemoryRouter>,
  )
}

const SLOW = { timeout: 5000 }

describe('the module launcher', () => {
  it('greets by first name and says how many modules are held', async () => {
    signedInWith(['TICKETING', 'ONBOARDING'])
    renderLauncher()

    expect(
      await screen.findByRole('heading', { name: 'Where would you like to work, Priya?' }, SLOW),
    ).toBeInTheDocument()
    expect(screen.getByText(/entitled to 2 modules/i)).toBeInTheDocument()
  })

  it('asks the question without a name rather than greeting "undefined"', async () => {
    signedInWith(['TICKETING', 'ONBOARDING'], '')
    renderLauncher()

    expect(
      await screen.findByRole('heading', { name: 'Where would you like to work?' }, SLOW),
    ).toBeInTheDocument()
  })

  it('links each held module to its own destination', async () => {
    signedInWith(['TICKETING', 'ONBOARDING'])
    renderLauncher()

    expect(await screen.findByRole('link', { name: 'Open Ticketing module' }, SLOW)).toHaveAttribute(
      'href',
      '/dashboard',
    )
    expect(screen.getByRole('link', { name: 'Open Client Onboarding module' })).toHaveAttribute(
      'href',
      '/onboarding/dashboard',
    )
  })

  it('shows a module you do not hold as unreachable, and says who to ask', async () => {
    // Not hidden. Hiding it answers "why can I not see onboarding?" with
    // silence, and the person who needs the answer is exactly the one who
    // cannot see it.
    signedInWith(['TICKETING'])
    renderLauncher()

    await screen.findByRole('link', { name: 'Open Ticketing module' }, SLOW)
    expect(screen.queryByRole('link', { name: 'Open Client Onboarding module' })).toBeNull()

    const card = screen.getByLabelText('Client Onboarding — no access')
    expect(within(card).getByText(/ask your admin/i)).toBeInTheDocument()
  })

  it('does not request the summary of a module the caller does not hold', async () => {
    // THE ASSERTION THIS FILE EXISTS FOR. That request 404s behind A-111's
    // gate, so asking is a round trip spent learning what `/me` already said,
    // and it flashes an error card while it happens.
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    signedInWith(['TICKETING'])
    renderLauncher()

    await screen.findByRole('link', { name: 'Open Ticketing module' }, SLOW)

    const asked = fetchSpy.mock.calls.map(([input]) => String(input))
    expect(asked.some((url) => url.includes('/onboarding/dashboard/summary'))).toBe(false)
    fetchSpy.mockRestore()
  })

  it('says nothing is held when no module is granted', async () => {
    signedInWith([])
    renderLauncher()

    expect(await screen.findByText(/not been granted a module yet/i, undefined, SLOW)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Open .* module/ })).toBeNull()
  })

  it('offers a way out', async () => {
    signedInWith(['TICKETING', 'ONBOARDING'])
    renderLauncher()

    expect(await screen.findByRole('button', { name: 'Sign out' }, SLOW)).toBeInTheDocument()
  })
})

describe('the module switcher in the top bar', () => {
  it('appears for a caller holding both modules', () => {
    signedInWith(['TICKETING', 'ONBOARDING'])
    renderSwitcher()

    expect(screen.getByRole('link', { name: /switch module/i })).toHaveAttribute('href', '/launcher')
  })

  it.each([[['TICKETING']], [['ONBOARDING']], [[]]])(
    'renders nothing at all for modules %s',
    (modules) => {
      // Not a disabled button. That would occupy the top bar of every
      // single-module user in the product to advertise something they cannot
      // use — and single-module users are most of them.
      signedInWith(modules)
      const { container } = renderSwitcher()

      expect(container).toBeEmptyDOMElement()
    },
  )
})

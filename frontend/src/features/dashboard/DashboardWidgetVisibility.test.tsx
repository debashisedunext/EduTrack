import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Me } from '@/api/generated/model/me'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { useDashboardWidgetPreferencesStore } from './dashboardWidgetPreferencesStore'
import { DashboardWidgets } from './DashboardWidgets'

/**
 * The settings menu's other half, from the grid's side: a widget hidden in
 * `useDashboardWidgetPreferencesStore` is dropped from both the batch request
 * and the render — not merely hidden with CSS, which would still pay for the
 * query. `DashboardVariant.test.tsx` already pins the default (nothing
 * hidden) reproducing today's full and own-work lists; this file is about
 * what changes once something is.
 */

const useGetDashboardWidget = vi.fn()
const useGetDashboardWidgets = vi.fn()
vi.mock('@/api/generated/dashboard/dashboard', () => ({
  useGetDashboardWidget: (...args: unknown[]) => useGetDashboardWidget(...args),
  useGetDashboardWidgets: (...args: unknown[]) => useGetDashboardWidgets(...args),
}))

function pending() {
  useGetDashboardWidget.mockReturnValue({ data: undefined, isPending: true, isError: false })
  useGetDashboardWidgets.mockReturnValue({ data: undefined, isPending: true, isError: false })
}

function signedInAs(role: string | undefined) {
  useAuthStore.setState({
    ...initialAuthState,
    status: 'authenticated',
    user: { id: 7, displayName: 'Test', role } as Me,
  })
}

function renderWidgets() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <DashboardWidgets params={{}} />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function requestedKeys(): string[] {
  const calls = useGetDashboardWidgets.mock.calls
  if (calls.length === 0) {
    return []
  }
  return ((calls[0][0] as { keys?: string[] }).keys ?? []).slice()
}

beforeEach(() => {
  vi.clearAllMocks()
  window.localStorage.clear()
  useAuthStore.setState(initialAuthState)
  useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: [] })
  pending()
})

describe('hiding a dashboard widget', () => {
  it('drops it from the batch request on the organisation dashboard', () => {
    signedInAs('ADMIN')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['sla-gauge'] })

    renderWidgets()

    expect(requestedKeys()).not.toContain('sla-gauge')
    expect(requestedKeys()).toHaveLength(14)
  })

  it('does not render the hidden widget’s frame', () => {
    signedInAs('ADMIN')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['sla-gauge'] })

    renderWidgets()

    expect(screen.queryByText('SLA compliance')).not.toBeInTheDocument()
  })

  it('leaves the rest of the grid alone', () => {
    signedInAs('ADMIN')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['sla-gauge'] })

    renderWidgets()

    expect(screen.getByText('Task type distribution')).toBeInTheDocument()
    expect(screen.getByText('Rework')).toBeInTheDocument()
  })

  it('applies to the own-work variant’s shorter list too', () => {
    signedInAs('DEVELOPER')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['velocity'] })

    renderWidgets()

    expect(requestedKeys()).toEqual(['aging-buckets'])
    expect(screen.queryByText(/My velocity/)).not.toBeInTheDocument()
  })

  it('says so plainly when every widget has been hidden, rather than rendering an empty grid', () => {
    signedInAs('DEVELOPER')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['velocity', 'aging-buckets'] })

    renderWidgets()

    expect(requestedKeys()).toEqual([])
    expect(screen.getByText(/No dashboard components are selected/)).toBeInTheDocument()
  })
})

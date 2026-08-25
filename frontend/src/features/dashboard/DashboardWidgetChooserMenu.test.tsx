import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'

import type { Me } from '@/api/generated/model/me'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { DashboardWidgetChooserMenu } from './DashboardWidgetChooserMenu'
import { useDashboardWidgetPreferencesStore } from './dashboardWidgetPreferencesStore'
import { ALL_WIDGET_KEYS, WIDGET_CATALOG } from './widgetCatalog'

function signedInAs(role: string | undefined) {
  useAuthStore.setState({
    ...initialAuthState,
    status: 'authenticated',
    user: { id: 7, displayName: 'Test', role } as Me,
  })
}

beforeEach(() => {
  window.localStorage.clear()
  useDashboardWidgetPreferencesStore.setState({
    hiddenWidgets: [],
    widgetOrder: [...ALL_WIDGET_KEYS],
  })
  useAuthStore.setState(initialAuthState)
})

describe('the dashboard widget chooser', () => {
  it('opens on the Widgets button and lists every widget, checked by default', async () => {
    signedInAs('ADMIN')
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))

    for (const widget of WIDGET_CATALOG) {
      expect(screen.getByRole('checkbox', { name: widget.label })).toBeChecked()
    }
  })

  /**
   * The reason this menu reads `useDashboardVariant` at all: a Developer's
   * dashboard only ever renders two of the fourteen widgets, and offering a
   * checkbox for the other twelve would let them toggle controls that do
   * nothing — indistinguishable from a broken settings menu.
   */
  it('offers only the widgets a Developer’s own-work dashboard actually renders', async () => {
    signedInAs('DEVELOPER')
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))

    expect(screen.getByRole('checkbox', { name: 'Resource velocity' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Ticket aging' })).toBeInTheDocument()
    expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    expect(screen.queryByRole('checkbox', { name: 'SLA compliance' })).not.toBeInTheDocument()
    expect(screen.queryByRole('checkbox', { name: 'Task type distribution' })).not.toBeInTheDocument()
  })

  it.each(['DEVELOPER', 'QA', 'DEPLOYMENT'])(
    'applies the same shorter list to %s',
    async (role) => {
      signedInAs(role)
      const user = userEvent.setup()
      render(<DashboardWidgetChooserMenu />)

      await user.click(screen.getByRole('button', { name: /Widgets/ }))
      expect(screen.getAllByRole('checkbox')).toHaveLength(2)
    },
  )

  it.each(['ADMIN', 'PM', 'SUPPORT'])('leaves %s on the full fourteen', async (role) => {
    signedInAs(role)
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))
    expect(screen.getAllByRole('checkbox')).toHaveLength(WIDGET_CATALOG.length)
  })

  it('unchecking one widget hides only that one in the store', async () => {
    signedInAs('ADMIN')
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))
    await user.click(screen.getByRole('checkbox', { name: 'SLA compliance' }))

    expect(useDashboardWidgetPreferencesStore.getState().hiddenWidgets).toEqual(['sla-gauge'])
    expect(screen.getByRole('checkbox', { name: 'SLA compliance' })).not.toBeChecked()
    expect(screen.getByRole('checkbox', { name: 'Rework' })).toBeChecked()
  })

  it('re-checking a hidden widget shows it again', async () => {
    signedInAs('ADMIN')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['rework'] })
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))
    expect(screen.getByRole('checkbox', { name: 'Rework' })).not.toBeChecked()

    await user.click(screen.getByRole('checkbox', { name: 'Rework' }))
    expect(useDashboardWidgetPreferencesStore.getState().hiddenWidgets).toEqual([])
  })
})

/**
 * The order is changed on the grid itself, by dragging a panel or using its ↑/↓
 * buttons. All this menu carries is the way back — and only once there is
 * something to go back from.
 */
describe('resetting the dashboard order', () => {
  it('offers no Reset on a dashboard nobody has rearranged', async () => {
    signedInAs('ADMIN')
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))

    expect(screen.queryByRole('button', { name: /Reset widget order/ })).not.toBeInTheDocument()
    expect(screen.getByText(/Drag a panel by its handle/)).toBeInTheDocument()
  })

  it('offers it once something has moved, and puts the catalogue order back', async () => {
    signedInAs('ADMIN')
    useDashboardWidgetPreferencesStore
      .getState()
      .moveWidget('rework', 'type-donut')
    const user = userEvent.setup()
    render(<DashboardWidgetChooserMenu />)

    await user.click(screen.getByRole('button', { name: /Widgets/ }))
    await user.click(screen.getByRole('button', { name: /Reset widget order/ }))

    expect(useDashboardWidgetPreferencesStore.getState().widgetOrder).toEqual([...ALL_WIDGET_KEYS])
  })
})

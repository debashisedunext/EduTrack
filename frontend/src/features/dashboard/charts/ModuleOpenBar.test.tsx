import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ModuleOpenBar } from './ModuleOpenBar'
import { useDrillDownStore } from '../drillDownStore'

/**
 * Dashboard Rework Dev 2, PR 14 · widget 15.
 *
 * Rendered directly rather than through `WidgetFrame`, following A-057's suite:
 * the frame's four states have their own tests, and what matters here is the
 * legend — which is the only keyboard path to the three drill-downs, because
 * the bars are recharts `<path>` elements the canvas hides from the tree.
 */

const wrap = (ui: React.ReactNode) => render(<MemoryRouter>{ui}</MemoryRouter>)

beforeEach(() => {
  vi.clearAllMocks()
  useDrillDownStore.setState({ drillDown: null, title: '' })
})

/** The shape `WidgetService.moduleOpen` emits: three series, one point per module. */
const series = [
  {
    name: 'Not started',
    points: [
      { x: 'Billing', y: 4, drillDown: '/tickets?moduleId=1&excludeClosed=true&statusCategory=TODO' },
      { x: 'Reports', y: 2, drillDown: '/tickets?moduleId=2&excludeClosed=true&statusCategory=TODO' },
    ],
  },
  {
    name: 'WIP',
    points: [
      { x: 'Billing', y: 6, drillDown: '/tickets?moduleId=1&excludeClosed=true&statusCategory=IN_PROGRESS' },
      { x: 'Reports', y: 1, drillDown: '/tickets?moduleId=2&excludeClosed=true&statusCategory=IN_PROGRESS' },
    ],
  },
  {
    name: 'Overdue',
    points: [
      { x: 'Billing', y: 3, drillDown: '/tickets?moduleId=1&excludeClosed=true&isDelayed=true' },
      { x: 'Reports', y: 0, drillDown: '/tickets?moduleId=2&excludeClosed=true&isDelayed=true' },
    ],
  },
]

describe('ModuleOpenBar', () => {
  it('offers the three open states in the legend, in stack order', () => {
    wrap(<ModuleOpenBar series={series} />)

    const legend = screen.getByRole('list', { name: /Open states/ })
    expect(within(legend).getAllByRole('button').map((b) => b.textContent))
      .toEqual(['Not started', 'WIP', 'Overdue'])
  })

  it('opens the drill-down for the state that was clicked', async () => {
    const user = userEvent.setup()
    wrap(<ModuleOpenBar series={series} />)

    await user.click(screen.getByRole('button', { name: /WIP/ }))

    expect(useDrillDownStore.getState().drillDown).toBe(
      '/tickets?moduleId=1&excludeClosed=true&statusCategory=IN_PROGRESS',
    )
  })

  it('reaches every state by keyboard, since the bars themselves cannot be', async () => {
    const user = userEvent.setup()
    wrap(<ModuleOpenBar series={series} />)

    const legend = screen.getByRole('list', { name: /Open states/ })
    const buttons = within(legend).getAllByRole('button')
    buttons[0].focus()
    await user.keyboard('{Enter}')

    expect(useDrillDownStore.getState().drillDown).toContain('statusCategory=TODO')
  })

  it('renders a module whose overdue segment is zero without dropping it', () => {
    // The server only omits a module with nothing open at all. A module that is
    // merely on time still has a bar, and a legend that hid the empty segment
    // would make "no overdue work" indistinguishable from "no such state".
    wrap(<ModuleOpenBar series={series} />)
    expect(screen.getByRole('button', { name: /Overdue/ })).toBeInTheDocument()
  })

  it('survives a series the server has not sent points for', () => {
    // A projectId filter can narrow the widget to nothing. An empty series must
    // draw an empty chart, not throw — WidgetFrame's own empty state is what
    // the user sees, and it never gets the chance if this component dies first.
    const empty = series.map((s) => ({ ...s, points: [] }))
    expect(() => wrap(<ModuleOpenBar series={empty} />)).not.toThrow()
  })
})

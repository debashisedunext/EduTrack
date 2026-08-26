import * as React from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { DashboardDateRange } from './DashboardDateRange'
import type { DateRange } from './dateRangePresets'

const TODAY = new Date('2026-08-26T09:41:00Z')
const LAST_WEEK: DateRange = { from: '2026-08-20', to: '2026-08-26' }

/**
 * The control is controlled, so a test that never feeds `onChange` back cannot
 * see what the trigger says afterwards — which is half of what these assert.
 */
function renderControl(initial: DateRange = { from: null, to: null }) {
  const onChange = vi.fn<(value: DateRange) => void>()

  function Harness() {
    const [value, setValue] = React.useState(initial)
    return (
      <DashboardDateRange
        value={value}
        today={TODAY}
        onChange={(next) => {
          onChange(next)
          setValue(next)
        }}
      />
    )
  }

  render(<Harness />)
  return { onChange, user: userEvent.setup() }
}

const openList = (user: ReturnType<typeof userEvent.setup>) =>
  user.click(screen.getByRole('button', { name: /^Dates/ }))

describe('DashboardDateRange', () => {
  it('offers every relative window plus a way back to explicit dates', async () => {
    const { user } = renderControl()
    await openList(user)

    expect(screen.getAllByRole('option').map((o) => o.textContent)).toEqual([
      'All dates',
      'Last 1 week',
      'Last 2 weeks',
      'Last 3 weeks',
      'Last 1 month',
      'Last 3 months',
      'Last 6 months',
      'Last 1 year',
      'Custom range…',
    ])
  })

  it('resolves a chosen window to two dates and shows it as the selection', async () => {
    const { user, onChange } = renderControl()

    await openList(user)
    await user.click(screen.getByRole('option', { name: 'Last 1 week' }))

    expect(onChange).toHaveBeenCalledWith(LAST_WEEK)
    expect(screen.getByRole('button', { name: /Dates: Last 1 week/ })).toBeInTheDocument()
    // A preset is the whole control — the boxes stay away until asked for.
    expect(screen.queryByLabelText('From')).not.toBeInTheDocument()
  })

  it('recognises a range that arrived in the URL as the preset it came from', () => {
    renderControl(LAST_WEEK)

    expect(screen.getByRole('button', { name: /Dates: Last 1 week/ })).toBeInTheDocument()
  })

  it('opens an arbitrary range as a custom one, with the dates filled in', () => {
    renderControl({ from: '2026-08-01', to: '2026-08-19' })

    expect(screen.getByRole('button', { name: /Dates: Custom range/ })).toBeInTheDocument()
    expect(screen.getByLabelText('From')).toHaveValue('2026-08-01')
    expect(screen.getByLabelText('To')).toHaveValue('2026-08-19')
  })

  it('hands the current preset over to the inputs when custom is picked', async () => {
    const { user, onChange } = renderControl(LAST_WEEK)

    await openList(user)
    await user.click(screen.getByRole('option', { name: 'Custom range…' }))

    // Switching how you pick a range must not change the range you had.
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByLabelText('From')).toHaveValue('2026-08-20')
    expect(screen.getByLabelText('To')).toHaveValue('2026-08-26')
  })

  it('keeps the other end when one custom date is edited', async () => {
    const { user, onChange } = renderControl({ from: '2026-08-01', to: '2026-08-19' })

    // The regression this screen already shipped once: writing one end used to
    // drop the other. See `useDashboardFilters`.
    await user.clear(screen.getByLabelText('From'))
    await user.type(screen.getByLabelText('From'), '2026-08-05')

    expect(onChange).toHaveBeenLastCalledWith({ from: '2026-08-05', to: '2026-08-19' })
  })

  it('clears back to no range at all, and puts the inputs away with it', async () => {
    const { user, onChange } = renderControl({ from: '2026-08-01', to: '2026-08-19' })

    await openList(user)
    await user.click(screen.getByRole('option', { name: 'All dates' }))

    expect(onChange).toHaveBeenCalledWith({ from: null, to: null })
    expect(screen.queryByLabelText('From')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Dates' })).toBeInTheDocument()
  })
})

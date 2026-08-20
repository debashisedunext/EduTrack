import { beforeAll, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { format, parseISO } from 'date-fns'

import type { Cycle } from '@/api/generated/model/cycle'
import { CycleSelector } from './CycleSelector'

// Matches `CycleSelector`'s own `DATE_FORMAT`. Computed rather than
// hardcoded — `format` renders in the machine's local timezone
// (`RibbonSegment.test.tsx`'s own note), so a literal date string here would
// pass in UTC and fail everywhere west of it.
function dateStamp(iso: string): string {
  return format(parseISO(iso), 'd MMM yyyy')
}

beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
})

const CYCLES: Cycle[] = [
  { cycleNo: 1, isSealed: true, startedAt: '2026-08-03T09:12:00Z', closedAt: '2026-08-07T17:40:00Z', reason: null },
  {
    cycleNo: 2,
    isSealed: true,
    startedAt: '2026-08-08T10:05:00Z',
    closedAt: '2026-08-14T16:30:00Z',
    reason: 'Client reports recurrence on two further accounts',
  },
]

describe('CycleSelector · C-053', () => {
  it('renders nothing for a ticket with a single cycle — nothing to select between', () => {
    const { container } = render(
      <CycleSelector cycles={[{ cycleNo: 1, isSealed: false }]} selectedCycleNo={1} onSelect={vi.fn()} />,
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when there are no cycles at all', () => {
    const { container } = render(<CycleSelector cycles={[]} selectedCycleNo={1} onSelect={vi.fn()} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('lists every cycle in order and marks the selected one', () => {
    render(<CycleSelector cycles={CYCLES} selectedCycleNo={2} onSelect={vi.fn()} />)

    const group = screen.getByRole('group', { name: 'Cycle' })
    const buttons = within(group).getAllByRole('button')
    expect(buttons.map((b) => b.textContent)).toEqual(['Cycle 1', 'Cycle 2'])

    expect(within(group).getByRole('button', { name: 'View cycle 1' })).not.toHaveAttribute('aria-current')
    expect(within(group).getByRole('button', { name: 'View cycle 2' })).toHaveAttribute('aria-current', 'true')
  })

  it('names each button distinctly from the History/Effort tabs\' own "Cycle N" group toggle', () => {
    render(<CycleSelector cycles={CYCLES} selectedCycleNo={2} onSelect={vi.fn()} />)

    // Same visible text as the tabs' per-cycle toggle, deliberately — the
    // accessible name is what has to differ, so a page rendering all three at
    // once (`TicketHistory.test.tsx`) never gets an ambiguous role query.
    expect(screen.queryByRole('button', { name: 'Cycle 1' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'View cycle 1' })).toHaveTextContent('Cycle 1')
  })

  it('fires onSelect with the clicked cycle number', async () => {
    const onSelect = vi.fn()
    render(<CycleSelector cycles={CYCLES} selectedCycleNo={2} onSelect={onSelect} />)

    await userEvent.setup().click(screen.getByRole('button', { name: 'View cycle 1' }))
    expect(onSelect).toHaveBeenCalledWith(1)
  })

  it('sorts out-of-order cycles by cycle number', () => {
    render(<CycleSelector cycles={[CYCLES[1], CYCLES[0]]} selectedCycleNo={1} onSelect={vi.fn()} />)

    const group = screen.getByRole('group', { name: 'Cycle' })
    expect(within(group).getAllByRole('button').map((b) => b.textContent)).toEqual(['Cycle 1', 'Cycle 2'])
  })

  it("shows a sealed cycle's start, close and reopen reason on hover", async () => {
    render(<CycleSelector cycles={CYCLES} selectedCycleNo={2} onSelect={vi.fn()} />)

    await userEvent.hover(screen.getByRole('button', { name: 'View cycle 2' }))
    const tooltip = await screen.findByRole('tooltip')

    expect(within(tooltip).getByText(dateStamp('2026-08-08T10:05:00Z'))).toBeInTheDocument()
    expect(within(tooltip).getByText(dateStamp('2026-08-14T16:30:00Z'))).toBeInTheDocument()
    expect(within(tooltip).getByText('Client reports recurrence on two further accounts')).toBeInTheDocument()
  })

  it('reads "In progress" and carries no reason for the open cycle', async () => {
    render(
      <CycleSelector
        cycles={[CYCLES[0], { cycleNo: 2, isSealed: false, startedAt: '2026-08-08T10:05:00Z', closedAt: null, reason: null }]}
        selectedCycleNo={2}
        onSelect={vi.fn()}
      />,
    )

    await userEvent.hover(screen.getByRole('button', { name: 'View cycle 2' }))
    const tooltip = await screen.findByRole('tooltip')

    expect(within(tooltip).getByText('In progress')).toBeInTheDocument()
    expect(within(tooltip).queryByText(/Reopened because/)).not.toBeInTheDocument()
  })

  it('is reachable by keyboard focus, not only pointer hover', async () => {
    render(<CycleSelector cycles={CYCLES} selectedCycleNo={2} onSelect={vi.fn()} />)

    await userEvent.tab()
    expect(await screen.findByRole('tooltip')).toBeInTheDocument()
  })
})

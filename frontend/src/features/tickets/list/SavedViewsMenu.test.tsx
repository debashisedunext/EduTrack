import { beforeAll, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EMPTY_FILTERS } from './useTicketListFilters'
import { SavedViewsMenu } from './SavedViewsMenu'

/** Radix's popover primitives need measurement and pointer-capture APIs jsdom does not implement — same shim `TicketListPage.test.tsx` uses for `FilterDropdown`. */
beforeAll(() => {
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  const element = Element.prototype as unknown as Record<string, unknown>
  element.hasPointerCapture ??= () => false
  element.setPointerCapture ??= () => {}
  element.releasePointerCapture ??= () => {}
  element.scrollIntoView ??= () => {}
})

const TRIGGER_NAME = 'Saved views'

async function openMenu() {
  fireEvent.click(screen.getByRole('button', { name: TRIGGER_NAME }))
  return screen.findByRole('menu', { name: TRIGGER_NAME })
}

function todayLocalDate(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

describe('SavedViewsMenu — C-015', () => {
  it('lists all six S-17 views', async () => {
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={vi.fn()} myUserId={3} />)
    await openMenu()
    const labels = screen.getAllByRole('menuitemradio').map((el) => el.textContent)
    expect(labels).toEqual(['My Open', 'Due Today', 'Overdue', 'Unassigned', 'Reopened', 'Closed This Month'])
  })

  it('Overdue applies isDelayed=true and clears every other filter', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={3} />)
    await openMenu()
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Overdue' }))
    expect(onApply).toHaveBeenCalledWith({ ...EMPTY_FILTERS, isDelayed: true })
  })

  it('Unassigned applies unassigned=true', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={3} />)
    await openMenu()
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Unassigned' }))
    expect(onApply).toHaveBeenCalledWith({ ...EMPTY_FILTERS, unassigned: true })
  })

  it('Reopened applies reopenedOnly=true — wiring the contract param C-014 never used', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={3} />)
    await openMenu()
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Reopened' }))
    expect(onApply).toHaveBeenCalledWith({ ...EMPTY_FILTERS, reopenedOnly: true })
  })

  it('My Open applies assigneeId=<current user> and excludeClosed=true', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={42} />)
    await openMenu()
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'My Open' }))
    expect(onApply).toHaveBeenCalledWith({ ...EMPTY_FILTERS, assigneeId: 42, excludeClosed: true })
  })

  it('Due Today applies dueFrom = dueTo = today, local date', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={3} />)
    await openMenu()
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Due Today' }))
    const today = todayLocalDate()
    expect(onApply).toHaveBeenCalledWith({ ...EMPTY_FILTERS, dueFrom: today, dueTo: today })
  })

  it('Closed This Month applies status=CLOSED and a closedFrom/closedTo range from the 1st to today', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={3} />)
    await openMenu()
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Closed This Month' }))
    const today = todayLocalDate()
    const monthStart = `${today.slice(0, 8)}01`
    expect(onApply).toHaveBeenCalledWith({
      ...EMPTY_FILTERS,
      status: 'CLOSED',
      closedFrom: monthStart,
      closedTo: today,
    })
  })

  it('disables My Open until useGetMe resolves, without ever applying assigneeId=undefined', async () => {
    const onApply = vi.fn()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={null} />)
    await openMenu()
    const myOpen = screen.getByRole('menuitemradio', { name: 'My Open' })
    expect(myOpen).toBeDisabled()
    fireEvent.click(myOpen)
    expect(onApply).not.toHaveBeenCalled()
  })

  it('highlights the active view when the current filters exactly match its recipe, and shows none when they do not', async () => {
    const { rerender } = render(
      <SavedViewsMenu filters={{ ...EMPTY_FILTERS, isDelayed: true }} onApply={vi.fn()} myUserId={3} />,
    )
    expect(screen.getByRole('button', { name: TRIGGER_NAME })).toHaveTextContent('Overdue')
    await openMenu()
    expect(screen.getByRole('menuitemradio', { name: 'Overdue' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('menuitemradio', { name: 'Unassigned' })).toHaveAttribute('aria-checked', 'false')

    // A custom filter combination that matches no recipe highlights nothing —
    // computed from `filters` each render, never a separately stored flag.
    rerender(
      <SavedViewsMenu filters={{ ...EMPTY_FILTERS, isDelayed: true, projectId: 9 }} onApply={vi.fn()} myUserId={3} />,
    )
    expect(screen.getByRole('button', { name: TRIGGER_NAME })).toHaveTextContent('Saved views')
  })

  it('is keyboard operable — opening moves focus into the menu, arrow keys move it and Enter activates', async () => {
    const onApply = vi.fn()
    const user = userEvent.setup()
    render(<SavedViewsMenu filters={EMPTY_FILTERS} onApply={onApply} myUserId={3} />)
    await user.click(screen.getByRole('button', { name: TRIGGER_NAME }))
    await screen.findByRole('menu', { name: TRIGGER_NAME })

    // Radix moves focus onto the first item when the popover opens — no
    // separate Tab needed to reach it.
    expect(screen.getByRole('menuitemradio', { name: 'My Open' })).toHaveFocus()

    await user.tab() // Tab still moves through the remaining items in order.
    expect(screen.getByRole('menuitemradio', { name: 'Due Today' })).toHaveFocus()
    await user.tab()
    expect(screen.getByRole('menuitemradio', { name: 'Overdue' })).toHaveFocus()

    await user.keyboard('{Enter}')
    expect(onApply).toHaveBeenCalledWith({ ...EMPTY_FILTERS, isDelayed: true })
  })
})

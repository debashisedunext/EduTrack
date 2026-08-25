import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Me } from '@/api/generated/model/me'
import { initialAuthState, useAuthStore } from '@/features/auth/authStore'

import { useDashboardWidgetPreferencesStore } from './dashboardWidgetPreferencesStore'
import { DashboardWidgets } from './DashboardWidgets'
import type { WidgetKey } from './WidgetFrame'
import { ALL_WIDGET_KEYS } from './widgetCatalog'

/**
 * Arranging the dashboard, from the grid's side.
 *
 * The store's own tests cover the two reasoning-heavy helpers directly. This
 * file is about the things only a render can show: that the stored order is what
 * the grid actually draws, that a drag and the keyboard buttons produce the same
 * move, and that the batch request follows the arrangement rather than a
 * hard-coded list.
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

function signedInAs(role: string) {
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

/** The widget keys the grid drew, top to bottom. */
function renderedOrder(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('[data-widget-key]')).map(
    (el) => el.getAttribute('data-widget-key') ?? '',
  )
}

function requestedKeys(): string[] {
  const calls = useGetDashboardWidgets.mock.calls
  if (calls.length === 0) return []
  return ((calls[0][0] as { keys?: string[] }).keys ?? []).slice()
}

function setOrder(order: WidgetKey[]) {
  useDashboardWidgetPreferencesStore.setState({ widgetOrder: order })
}

beforeEach(() => {
  vi.clearAllMocks()
  window.localStorage.clear()
  useAuthStore.setState(initialAuthState)
  useDashboardWidgetPreferencesStore.setState({
    hiddenWidgets: [],
    widgetOrder: [...ALL_WIDGET_KEYS],
  })
  pending()
})

describe('the default dashboard order', () => {
  it('is the catalogue order, so nobody who never drags anything sees a change', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    expect(renderedOrder(container)).toEqual([...ALL_WIDGET_KEYS])
  })
})

describe('rearranging by keyboard', () => {
  it('moves a widget one place earlier and redraws the grid in the new order', async () => {
    signedInAs('ADMIN')
    const user = userEvent.setup()
    const { container } = renderWidgets()

    await user.click(screen.getByRole('button', { name: /Move Daily task status earlier/ }))

    expect(renderedOrder(container).slice(0, 2)).toEqual(['daily-stacked', 'type-donut'])
  })

  it('moves a widget one place later', async () => {
    signedInAs('ADMIN')
    const user = userEvent.setup()
    const { container } = renderWidgets()

    await user.click(screen.getByRole('button', { name: /Move Task type distribution later/ }))

    expect(renderedOrder(container).slice(0, 2)).toEqual(['daily-stacked', 'type-donut'])
  })

  it('disables the controls that would run off the ends of the list', () => {
    signedInAs('ADMIN')
    renderWidgets()

    expect(screen.getByRole('button', { name: /Move Task type distribution earlier/ })).toBeDisabled()
    expect(
      screen.getByRole('button', { name: /Move Time waiting between stages later/ }),
    ).toBeDisabled()
  })

  it('announces the move, so the keyboard path is not a silent one', async () => {
    signedInAs('ADMIN')
    const user = userEvent.setup()
    renderWidgets()

    await user.click(screen.getByRole('button', { name: /Move Daily task status earlier/ }))

    expect(screen.getByText(/Daily task status moved to position 1 of 14/)).toBeInTheDocument()
  })

  /**
   * The reason `moveWidgetInOrder` takes keys rather than indices. With
   * `daily-stacked` hidden the grid draws `type-donut` then `velocity`, so one
   * press of "earlier" on velocity has to step over a widget that is not on
   * screen — landing it first, not merely swapping it with the hidden one.
   */
  it('steps over a hidden widget rather than trading places with something invisible', async () => {
    signedInAs('ADMIN')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['daily-stacked'] })
    const user = userEvent.setup()
    const { container } = renderWidgets()

    await user.click(
      screen.getByRole('button', { name: /Move Resource velocity \(tickets closed per week\) earlier/ }),
    )

    expect(renderedOrder(container).slice(0, 2)).toEqual(['velocity', 'type-donut'])
  })
})

/**
 * jsdom implements no drag and drop at all, so a `dragStart` it synthesises
 * carries no `dataTransfer`. Every real browser puts one on the event — the
 * frame writes to it because Firefox starts no drag without it — so the fake
 * belongs in the test rather than as a `?.` in the component, which would be
 * guarding production code against a condition only the test harness produces.
 */
function dragData() {
  return {
    dataTransfer: {
      setData: vi.fn(),
      getData: vi.fn(),
      setDragImage: vi.fn(),
      effectAllowed: 'none',
    },
  }
}

/**
 * The grip, which is what carries `draggable` — the card itself does not, so
 * that ordinary gestures inside it are left alone. Dragging is started from here
 * and caught by the card as the event bubbles, which is the path a real pointer
 * takes and so the one worth exercising.
 */
/**
 * The cards drawing a drop-target ring. Scoped to the cards deliberately: the
 * move buttons carry `focus-visible:ring-2` in their own class list, so a bare
 * attribute-substring selector matches all twenty-eight of them.
 */
function ringedCards(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll('[data-widget-key]'))
    .filter((el) => el.className.includes('ring-2'))
    .map((el) => el.getAttribute('data-widget-key') ?? '')
}

function gripOf(container: HTMLElement, key: WidgetKey): Element {
  const grip = container.querySelector(`[data-widget-key="${key}"] [draggable="true"]`)
  if (!grip) throw new Error(`no drag handle rendered for '${key}'`)
  return grip
}

describe('rearranging by drag', () => {
  it('drops a widget onto another’s position and takes it there', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    const target = container.querySelector('[data-widget-key="type-donut"]')!

    fireEvent.dragStart(gripOf(container, 'rework'), dragData())
    fireEvent.dragOver(target, dragData())
    fireEvent.drop(target, dragData())

    expect(renderedOrder(container)[0]).toBe('rework')
  })

  it('leaves the order alone when a widget is dropped on itself', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    fireEvent.dragStart(gripOf(container, 'velocity'), dragData())
    fireEvent.drop(container.querySelector('[data-widget-key="velocity"]')!, dragData())

    expect(renderedOrder(container)).toEqual([...ALL_WIDGET_KEYS])
  })

  /**
   * Thirteen ringed cards say "any of these" and answer the only question the
   * reader has — where will it land — with nothing. One says where.
   */
  it('marks only the card under the pointer as the drop target', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    const target = container.querySelector('[data-widget-key="sla-gauge"]')!
    fireEvent.dragStart(gripOf(container, 'rework'), dragData())
    fireEvent.dragOver(target, dragData())

    expect(ringedCards(container)).toEqual(['sla-gauge'])
  })

  it('clears the drop indicator when the pointer leaves again', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    const target = container.querySelector('[data-widget-key="sla-gauge"]')!
    fireEvent.dragStart(gripOf(container, 'rework'), dragData())
    fireEvent.dragOver(target, dragData())
    fireEvent.dragLeave(target, dragData())

    expect(ringedCards(container)).toEqual([])
  })

  /**
   * The card is not draggable itself — only its grip is. This is what keeps a
   * pointer dragged across a chart from picking the whole panel up.
   */
  it('does not mark the card itself draggable, so gestures inside it are left alone', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    expect(
      container.querySelector('[data-widget-key="velocity"]')!.getAttribute('draggable'),
    ).toBeNull()
  })

  it('ignores a drop that no drag of ours started, so a file dragged in does nothing', () => {
    signedInAs('ADMIN')
    const { container } = renderWidgets()

    fireEvent.drop(container.querySelector('[data-widget-key="type-donut"]')!, dragData())

    expect(renderedOrder(container)).toEqual([...ALL_WIDGET_KEYS])
  })
})

describe('an arrangement and the rest of the dashboard', () => {
  it('asks the batch for the widgets in the arranged order', () => {
    signedInAs('ADMIN')
    setOrder(['rework', ...ALL_WIDGET_KEYS.filter((k) => k !== 'rework')])

    renderWidgets()

    expect(requestedKeys()[0]).toBe('rework')
    expect(requestedKeys()).toHaveLength(14)
  })

  it('still drops a hidden widget from both the order and the request', () => {
    signedInAs('ADMIN')
    setOrder(['rework', ...ALL_WIDGET_KEYS.filter((k) => k !== 'rework')])
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['rework'] })

    const { container } = renderWidgets()

    expect(renderedOrder(container)).not.toContain('rework')
    expect(requestedKeys()).not.toContain('rework')
  })

  it('applies the arrangement to the own-work variant’s shorter list too', () => {
    signedInAs('DEVELOPER')
    setOrder(['aging-buckets', ...ALL_WIDGET_KEYS.filter((k) => k !== 'aging-buckets')])

    const { container } = renderWidgets()

    expect(renderedOrder(container)).toEqual(['aging-buckets', 'velocity'])
    expect(requestedKeys()).toEqual(['aging-buckets', 'velocity'])
  })

  it('offers no reorder controls when a single widget is left, since there is nowhere to move it', () => {
    signedInAs('DEVELOPER')
    useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: ['velocity'] })

    renderWidgets()

    expect(screen.getByRole('button', { name: /Move My ticket aging earlier/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Move My ticket aging later/ })).toBeDisabled()
  })
})

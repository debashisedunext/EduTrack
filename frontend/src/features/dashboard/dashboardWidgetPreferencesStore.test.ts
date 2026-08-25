import { beforeEach, describe, expect, it } from 'vitest'

import type { WidgetKey } from './WidgetFrame'
import { ALL_WIDGET_KEYS } from './widgetCatalog'
import {
  WIDGET_ORDER_STORAGE_KEY,
  WIDGET_PREFERENCES_STORAGE_KEY,
  moveWidgetInOrder,
  reconcileOrder,
  storedHiddenWidgets,
  storedWidgetOrder,
  useDashboardWidgetPreferencesStore,
} from './dashboardWidgetPreferencesStore'

/**
 * The settings menu's storage half. `DashboardWidgetChooserMenu` and
 * `DashboardWidgets` read the same `hiddenWidgets` array from this one store —
 * the interesting cases are persistence surviving a reload, a corrupted or
 * hand-edited value not crashing the dashboard, and an unknown key never
 * being written back out.
 */

beforeEach(() => {
  window.localStorage.clear()
  useDashboardWidgetPreferencesStore.setState({
    hiddenWidgets: [],
    widgetOrder: [...ALL_WIDGET_KEYS],
  })
})

describe('the dashboard widget preferences store', () => {
  it('defaults to nothing hidden, so nobody who never opens the menu sees a change', () => {
    expect(storedHiddenWidgets()).toEqual([])
    expect(useDashboardWidgetPreferencesStore.getState().hiddenWidgets).toEqual([])
  })

  it('hides a widget on the first toggle and shows it again on the second', () => {
    useDashboardWidgetPreferencesStore.getState().toggleWidget('sla-gauge')
    expect(useDashboardWidgetPreferencesStore.getState().hiddenWidgets).toEqual(['sla-gauge'])

    useDashboardWidgetPreferencesStore.getState().toggleWidget('sla-gauge')
    expect(useDashboardWidgetPreferencesStore.getState().hiddenWidgets).toEqual([])
  })

  it('persists the hidden list under its own key', () => {
    useDashboardWidgetPreferencesStore.getState().toggleWidget('rework')
    useDashboardWidgetPreferencesStore.getState().toggleWidget('stage-funnel')

    const stored = JSON.parse(window.localStorage.getItem(WIDGET_PREFERENCES_STORAGE_KEY) ?? '[]')
    expect(stored).toEqual(['rework', 'stage-funnel'])
  })

  it('reads a stored hidden list back, the way a reload would', () => {
    window.localStorage.setItem(WIDGET_PREFERENCES_STORAGE_KEY, JSON.stringify(['velocity']))
    expect(storedHiddenWidgets()).toEqual(['velocity'])
  })

  it('ignores a corrupted stored value instead of hiding everything or crashing', () => {
    window.localStorage.setItem(WIDGET_PREFERENCES_STORAGE_KEY, '{not json')
    expect(storedHiddenWidgets()).toEqual([])
  })

  it('drops keys that are not a real widget, rather than storing them forever', () => {
    window.localStorage.setItem(
      WIDGET_PREFERENCES_STORAGE_KEY,
      JSON.stringify(['velocity', 'not-a-real-widget']),
    )
    expect(storedHiddenWidgets()).toEqual(['velocity'])
  })

  it('survives storage being denied, and still toggles for the rest of the session', () => {
    const denied = () => {
      throw new DOMException('denied')
    }
    const originalSet = window.localStorage.setItem
    window.localStorage.setItem = denied

    try {
      expect(() =>
        useDashboardWidgetPreferencesStore.getState().toggleWidget('priority-bar'),
      ).not.toThrow()
      expect(useDashboardWidgetPreferencesStore.getState().hiddenWidgets).toEqual(['priority-bar'])
    } finally {
      window.localStorage.setItem = originalSet
    }
  })
})

/**
 * The arrangement half. `moveWidgetInOrder` and `reconcileOrder` are the two
 * pieces with actual reasoning in them, so they are tested directly rather than
 * only through the grid — the interesting cases (a widget added to the catalogue
 * after somebody arranged their dashboard, a move across a hidden widget) are
 * awkward to stage through a render and trivial to state here.
 */
describe('moveWidgetInOrder', () => {
  const order: WidgetKey[] = ['type-donut', 'daily-stacked', 'velocity', 'rework']

  it('moves a widget forwards, closing the gap behind it', () => {
    expect(moveWidgetInOrder(order, 'rework', 'daily-stacked')).toEqual([
      'type-donut',
      'rework',
      'daily-stacked',
      'velocity',
    ])
  })

  it('moves a widget backwards, to the position it was dropped on', () => {
    expect(moveWidgetInOrder(order, 'type-donut', 'velocity')).toEqual([
      'daily-stacked',
      'velocity',
      'type-donut',
      'rework',
    ])
  })

  it('returns the same array reference for a no-op, so the store can skip the write', () => {
    expect(moveWidgetInOrder(order, 'velocity', 'velocity')).toBe(order)
  })

  it('ignores a key that is not in the order rather than throwing', () => {
    expect(moveWidgetInOrder(order, 'sla-gauge', 'velocity')).toBe(order)
  })

  /**
   * The reason moves are made by key and not by index. With `daily-stacked`
   * hidden, `velocity` is drawn second and `type-donut` first — so "move
   * velocity up" must put it before `type-donut` in the stored order, stepping
   * over a widget that is not on screen at all.
   */
  it('moves across a widget that is hidden, because the hidden one has no position on screen', () => {
    expect(moveWidgetInOrder(order, 'velocity', 'type-donut')).toEqual([
      'velocity',
      'type-donut',
      'daily-stacked',
      'rework',
    ])
  })
})

describe('reconcileOrder', () => {
  it('keeps a stored arrangement as it was written', () => {
    const stored = [...ALL_WIDGET_KEYS].reverse()
    expect(reconcileOrder(stored)).toEqual(stored)
  })

  it('appends widgets the stored order has never heard of, so a new one is never invisible', () => {
    const result = reconcileOrder(['rework', 'velocity'])

    expect(result.slice(0, 2)).toEqual(['rework', 'velocity'])
    expect(result).toHaveLength(ALL_WIDGET_KEYS.length)
    expect(new Set(result)).toEqual(new Set(ALL_WIDGET_KEYS))
  })

  it('drops keys that are no longer widgets, so nothing asks the server for them', () => {
    const result = reconcileOrder(['rework', 'a-retired-widget', 'velocity'])

    expect(result).not.toContain('a-retired-widget')
    expect(result).toHaveLength(ALL_WIDGET_KEYS.length)
  })

  it('collapses a duplicate to its first position rather than rendering two frames under one key', () => {
    const result = reconcileOrder(['rework', 'velocity', 'rework'])

    expect(result.filter((k) => k === 'rework')).toHaveLength(1)
    expect(result.slice(0, 2)).toEqual(['rework', 'velocity'])
  })

  it('ignores entries that are not even strings', () => {
    expect(() => reconcileOrder([null, 7, { key: 'rework' }])).not.toThrow()
    expect(reconcileOrder([null, 7])).toEqual([...ALL_WIDGET_KEYS])
  })
})

describe('the stored widget order', () => {
  it('defaults to the catalogue order, so an untouched dashboard is unchanged', () => {
    expect(storedWidgetOrder()).toEqual([...ALL_WIDGET_KEYS])
  })

  it('persists a move under its own key, leaving the hidden list alone', () => {
    useDashboardWidgetPreferencesStore.getState().moveWidget('rework', 'type-donut')

    const stored = JSON.parse(window.localStorage.getItem(WIDGET_ORDER_STORAGE_KEY) ?? '[]')
    expect(stored[0]).toBe('rework')
    expect(window.localStorage.getItem(WIDGET_PREFERENCES_STORAGE_KEY)).toBeNull()
  })

  it('reads an arrangement back the way a reload would', () => {
    window.localStorage.setItem(
      WIDGET_ORDER_STORAGE_KEY,
      JSON.stringify(['sla-gauge', 'rework']),
    )

    const order = storedWidgetOrder()
    expect(order.slice(0, 2)).toEqual(['sla-gauge', 'rework'])
    expect(order).toHaveLength(ALL_WIDGET_KEYS.length)
  })

  it('falls back to the catalogue order on a corrupted value rather than an empty dashboard', () => {
    window.localStorage.setItem(WIDGET_ORDER_STORAGE_KEY, '{not json')
    expect(storedWidgetOrder()).toEqual([...ALL_WIDGET_KEYS])
  })

  it('resets to the catalogue order on request', () => {
    useDashboardWidgetPreferencesStore.getState().moveWidget('rework', 'type-donut')
    expect(useDashboardWidgetPreferencesStore.getState().widgetOrder[0]).toBe('rework')

    useDashboardWidgetPreferencesStore.getState().resetOrder()
    expect(useDashboardWidgetPreferencesStore.getState().widgetOrder).toEqual([...ALL_WIDGET_KEYS])
  })
})

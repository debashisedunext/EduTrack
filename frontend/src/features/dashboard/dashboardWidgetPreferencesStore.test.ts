import { beforeEach, describe, expect, it } from 'vitest'

import {
  WIDGET_PREFERENCES_STORAGE_KEY,
  storedHiddenWidgets,
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
  useDashboardWidgetPreferencesStore.setState({ hiddenWidgets: [] })
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

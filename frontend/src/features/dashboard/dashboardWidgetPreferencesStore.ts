import { create } from 'zustand'

import type { WidgetKey } from './WidgetFrame'
import { ALL_WIDGET_KEYS } from './widgetCatalog'

/**
 * Which dashboard widgets this browser has chosen to hide — per-user,
 * per-browser, and nothing the server needs to know about. Same reasoning as
 * `useListPreferences` for the ticket list's column chooser: this is "how do
 * I like my dashboard laid out", not data anyone else needs to see.
 *
 * A zustand store rather than a `useState`-backed hook (contrast
 * `useListPreferences`) because this preference has two independent readers —
 * `DashboardWidgetChooserMenu` writes it and `DashboardWidgets` reads it — and
 * they are not necessarily the same render tree. Two separate `useState`
 * hooks pointed at the same `localStorage` key would each keep their own
 * copy and go stale the moment the other one wrote; a shared store has one
 * value both subscribe to.
 */

export const WIDGET_PREFERENCES_STORAGE_KEY = 'edutrack.dashboard.hiddenWidgets.v1'

/**
 * Hidden, not visible, is what gets stored. A widget the contract adds next
 * sprint is absent from every browser's stored list either way, and "absent
 * means hidden" would silently drop it from every dashboard already in use —
 * "absent means shown" is the one that keeps working without a migration.
 *
 * Exported for tests, the way `themeStore.storedTheme` is — the store below
 * reads this once at module load, so a test that writes `localStorage` after
 * import has to assert through this function rather than through the store's
 * initial state.
 */
export function storedHiddenWidgets(): WidgetKey[] {
  if (typeof window === 'undefined') return []
  try {
    const raw = window.localStorage.getItem(WIDGET_PREFERENCES_STORAGE_KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    return parsed.filter((key): key is WidgetKey => ALL_WIDGET_KEYS.includes(key))
  } catch {
    // A corrupted or hand-edited value falls back to "nothing hidden" rather
    // than crashing the whole dashboard over a display preference.
    return []
  }
}

function persist(hidden: WidgetKey[]): void {
  try {
    window.localStorage.setItem(WIDGET_PREFERENCES_STORAGE_KEY, JSON.stringify(hidden))
  } catch {
    // Storage full or disabled (private browsing) — the choice still applies
    // for the rest of the session, it just will not survive a reload.
  }
}

interface DashboardWidgetPreferencesState {
  hiddenWidgets: WidgetKey[]
  toggleWidget: (key: WidgetKey) => void
}

export const useDashboardWidgetPreferencesStore = create<DashboardWidgetPreferencesState>(
  (set, get) => ({
    hiddenWidgets: storedHiddenWidgets(),
    toggleWidget: (key) => {
      const hidden = get().hiddenWidgets
      const next = hidden.includes(key)
        ? hidden.filter((k) => k !== key)
        : [...hidden, key]
      persist(next)
      set({ hiddenWidgets: next })
    },
  }),
)

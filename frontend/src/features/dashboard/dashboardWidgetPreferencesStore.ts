import { create } from 'zustand'

import type { WidgetKey } from './WidgetFrame'
import { ALL_WIDGET_KEYS } from './widgetCatalog'

/**
 * How this browser has arranged its dashboard — which widgets are hidden, and
 * what order the rest are drawn in. Per-user, per-browser, and nothing the
 * server needs to know about. Same reasoning as `useListPreferences` for the
 * ticket list's column chooser: this is "how do I like my dashboard laid out",
 * not data anyone else needs to see.
 *
 * A zustand store rather than a `useState`-backed hook (contrast
 * `useListPreferences`) because these preferences have two independent readers —
 * `DashboardWidgetChooserMenu` writes them and `DashboardWidgets` reads them —
 * and they are not necessarily the same render tree. Two separate `useState`
 * hooks pointed at the same `localStorage` key would each keep their own copy
 * and go stale the moment the other one wrote; a shared store has one value both
 * subscribe to.
 *
 * <h2>Two keys, not one object</h2>
 *
 * Visibility and order are stored under separate `localStorage` keys rather than
 * merged into one preferences document. They were written a release apart, and
 * separate keys mean the browsers that already hold a hidden-widget list keep it
 * — a combined key would have to either migrate those or silently discard them,
 * and discarding somebody's settings to add a feature they did not ask for is
 * the worse of the two.
 */

export const WIDGET_PREFERENCES_STORAGE_KEY = 'edutrack.dashboard.hiddenWidgets.v1'
export const WIDGET_ORDER_STORAGE_KEY = 'edutrack.dashboard.widgetOrder.v1'

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

/**
 * A stored order made safe to render from, whatever is actually in the key.
 *
 * This is the counterpart of "absent means shown" above, and it is what stops a
 * saved arrangement becoming a liability the next time the catalogue grows.
 * Three things are reconciled, and each has a way of going wrong that is silent
 * rather than loud:
 *
 * - **Keys that are no longer widgets are dropped.** A retired widget left in
 *   the array would be requested from a server that answers 404 for it.
 * - **Duplicates are collapsed to their first occurrence.** React would render
 *   two frames under one `key` and log a warning nobody reads.
 * - **Widgets the stored order has never heard of are appended.** This is the
 *   important one: without it, the widget added next sprint would be absent from
 *   every arranged browser for ever, which is `hiddenWidgets`' bug in a
 *   different costume — invisible, because the dashboard would look fine.
 *
 * Appended at the end rather than inserted at their catalogue position: the
 * stored order is somebody's deliberate arrangement, and inserting into the
 * middle of it would push their bottom row down to make room for a widget they
 * have never seen. The end is where a new thing goes.
 */
export function reconcileOrder(stored: readonly unknown[]): WidgetKey[] {
  const seen = new Set<WidgetKey>()
  const order: WidgetKey[] = []

  for (const key of stored) {
    if (typeof key === 'string' && ALL_WIDGET_KEYS.includes(key as WidgetKey) && !seen.has(key as WidgetKey)) {
      seen.add(key as WidgetKey)
      order.push(key as WidgetKey)
    }
  }
  for (const key of ALL_WIDGET_KEYS) {
    if (!seen.has(key)) {
      order.push(key)
    }
  }
  return order
}

/** The arrangement this browser holds, or the catalogue's own order if it holds none. */
export function storedWidgetOrder(): WidgetKey[] {
  if (typeof window === 'undefined') return [...ALL_WIDGET_KEYS]
  try {
    const raw = window.localStorage.getItem(WIDGET_ORDER_STORAGE_KEY)
    if (!raw) return [...ALL_WIDGET_KEYS]
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return [...ALL_WIDGET_KEYS]
    return reconcileOrder(parsed)
  } catch {
    return [...ALL_WIDGET_KEYS]
  }
}

/**
 * The order after `dragged` is dropped onto `target`'s position.
 *
 * <h2>By key and never by index</h2>
 *
 * The rendered grid is the stored order twice filtered — once to the reader's
 * variant, once to what they have not hidden — so position 3 on screen is
 * routinely not index 3 in this array. Moving by index would work perfectly
 * until the first hidden widget and then quietly drop things a row or two from
 * where they were released, which reads as a broken drag rather than as an
 * off-by-something.
 *
 * Keys are unambiguous under any filtering, so this takes the two keys and
 * resolves both positions itself. Out-of-range and no-op moves return the input
 * array unchanged rather than throwing — the keyboard controls call this on
 * every arrow press and the ends of the list are ordinary, not exceptional.
 */
export function moveWidgetInOrder(
  order: readonly WidgetKey[],
  dragged: WidgetKey,
  target: WidgetKey,
): WidgetKey[] {
  const from = order.indexOf(dragged)
  const to = order.indexOf(target)
  if (from < 0 || to < 0 || from === to) {
    return order as WidgetKey[]
  }
  const next = [...order]
  next.splice(from, 1)
  next.splice(to, 0, dragged)
  return next
}

function persist(storageKey: string, value: unknown): void {
  try {
    window.localStorage.setItem(storageKey, JSON.stringify(value))
  } catch {
    // Storage full or disabled (private browsing) — the choice still applies
    // for the rest of the session, it just will not survive a reload.
  }
}

interface DashboardWidgetPreferencesState {
  hiddenWidgets: WidgetKey[]
  widgetOrder: WidgetKey[]
  toggleWidget: (key: WidgetKey) => void
  /** Drop `dragged` onto `target`'s position. Both are keys — see `moveWidgetInOrder`. */
  moveWidget: (dragged: WidgetKey, target: WidgetKey) => void
  /** Back to the catalogue's §S-05 order. */
  resetOrder: () => void
}

export const useDashboardWidgetPreferencesStore = create<DashboardWidgetPreferencesState>(
  (set, get) => ({
    hiddenWidgets: storedHiddenWidgets(),
    widgetOrder: storedWidgetOrder(),
    toggleWidget: (key) => {
      const hidden = get().hiddenWidgets
      const next = hidden.includes(key)
        ? hidden.filter((k) => k !== key)
        : [...hidden, key]
      persist(WIDGET_PREFERENCES_STORAGE_KEY, next)
      set({ hiddenWidgets: next })
    },
    moveWidget: (dragged, target) => {
      const next = moveWidgetInOrder(get().widgetOrder, dragged, target)
      // Reference equality means the move was a no-op — the same widget dropped
      // on itself, or a key that is not in the order. Writing anyway would be
      // harmless but would tell every subscriber the layout changed.
      if (next === get().widgetOrder) return
      persist(WIDGET_ORDER_STORAGE_KEY, next)
      set({ widgetOrder: next })
    },
    resetOrder: () => {
      const next = [...ALL_WIDGET_KEYS]
      persist(WIDGET_ORDER_STORAGE_KEY, next)
      set({ widgetOrder: next })
    },
  }),
)

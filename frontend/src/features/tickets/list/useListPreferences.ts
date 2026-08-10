import * as React from 'react'
import { DEFAULT_VISIBLE_COLUMNS, type ColumnKey } from './columns'

export type Density = 'comfortable' | 'compact'

interface ListPreferences {
  density: Density
  visibleColumns: ColumnKey[]
}

const STORAGE_KEY = 'edutrack.ticketList.preferences.v1'

const DEFAULT_PREFERENCES: ListPreferences = {
  density: 'comfortable',
  visibleColumns: DEFAULT_VISIBLE_COLUMNS,
}

function load(): ListPreferences {
  if (typeof window === 'undefined') return DEFAULT_PREFERENCES
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    if (!raw) return DEFAULT_PREFERENCES
    const parsed = JSON.parse(raw) as Partial<ListPreferences>
    return {
      density: parsed.density === 'compact' ? 'compact' : 'comfortable',
      visibleColumns: Array.isArray(parsed.visibleColumns) ? parsed.visibleColumns : DEFAULT_VISIBLE_COLUMNS,
    }
  } catch {
    // A corrupted or hand-edited value falls back to the default rather than
    // crashing the whole list screen over a display preference.
    return DEFAULT_PREFERENCES
  }
}

/**
 * Density and column visibility — per-user, per-browser, and nothing the
 * server needs to know about. `localStorage` rather than a saved-view style
 * server record; C-015's saved views are named, shared filter sets, a
 * different concern from "how wide do I like my rows".
 */
export function useListPreferences() {
  const [preferences, setPreferences] = React.useState<ListPreferences>(load)

  React.useEffect(() => {
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences))
    } catch {
      // Storage can be full or disabled (private browsing); the preference
      // still works for the rest of the session, it just will not persist.
    }
  }, [preferences])

  const setDensity = React.useCallback((density: Density) => {
    setPreferences((p) => ({ ...p, density }))
  }, [])

  const toggleColumn = React.useCallback((key: ColumnKey) => {
    setPreferences((p) => ({
      ...p,
      visibleColumns: p.visibleColumns.includes(key)
        ? p.visibleColumns.filter((k) => k !== key)
        : [...p.visibleColumns, key],
    }))
  }, [])

  return { ...preferences, setDensity, toggleColumn }
}

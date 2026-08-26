import * as React from 'react'

import { FilterDropdown } from '@/components/ui/filter-dropdown'

import {
  DATE_RANGE_PRESETS,
  dateRangeForPreset,
  matchDateRangePreset,
  type DateRange,
  type DateRangePreset,
} from './dateRangePresets'

export type { DateRange }

export interface DashboardDateRangeProps {
  value: DateRange
  onChange: (value: DateRange) => void
  /**
   * The day presets resolve against. Injected only by tests — a component that
   * reads the clock itself cannot be tested without freezing time globally.
   */
  today?: Date
}

/** The one option that is not a window — it reveals the inputs instead of setting a range. */
const CUSTOM_OPTION = { id: 'custom', label: 'Custom range…' }

type RangeOption = Pick<DateRangePreset, 'id' | 'label'>

const OPTIONS: RangeOption[] = [
  ...DATE_RANGE_PRESETS.map(({ id, label }) => ({ id, label })),
  CUSTOM_OPTION,
]

const inputClass =
  'rounded-control border border-[color:var(--border)] bg-[color:var(--bg-surface)] ' +
  'px-2 py-1 text-sm text-[color:var(--text-primary)] ' +
  'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1 ' +
  'focus-visible:outline-[color:var(--primary)]'

/**
 * A-054 · the date-range control of §S-05's filter bar.
 *
 * <h2>A dropdown of relative windows, not two date boxes</h2>
 *
 * Almost every range anybody actually wants from this screen is "the last N of
 * something" — and picking that out of two calendars costs two dialogs, some
 * mental arithmetic about what today's date is, and a range that is off by a
 * day often enough to matter. So the primary control is a list of the windows
 * people ask for (`dateRangePresets.ts`), and one click sets both ends.
 *
 * The two date inputs have not gone anywhere; they are behind **Custom range**,
 * which is also what an arbitrary `?from=…&to=…` in the URL opens as. Removing
 * them outright would have made a report for one particular fortnight — the
 * thing this screen is exported for at month end — impossible to ask for.
 *
 * <h2>What goes in the URL is still two dates</h2>
 *
 * Not the preset id. See the header of `dateRangePresets.ts` for why, and for
 * the one visible consequence: a link shared yesterday opens as a custom range
 * over the same days rather than as the preset it was picked from.
 *
 * <h2>Why this is not `features/tickets/list/DateRangeFilter`</h2>
 *
 * That component exists and does a related job, but it lives in Stream C's
 * feature folder. Importing across features couples this screen to changes
 * Divyansh makes for his own reasons, in a directory Stream A does not own —
 * and the first time he adds a ticket-specific preset, this breaks for a reason
 * nobody looking at the dashboard would guess. If the two converge, the answer
 * is to promote one into `components/ui/`, which is a conversation with him
 * rather than an import.
 *
 * <h2>Native date inputs, deliberately</h2>
 *
 * `<input type="date">` brings its own keyboard handling, locale formatting and
 * mobile picker. A custom calendar would need all three rebuilding to reach the
 * same accessibility floor CLAUDE.md requires, and it is not what this task is
 * for.
 */
export function DashboardDateRange({ value, onChange, today }: DashboardDateRangeProps) {
  const fromId = React.useId()
  const toId = React.useId()

  // Resolved once for the life of the control rather than per render: presets
  // are date-granular, so recomputing cannot change an answer, and a fresh
  // `new Date()` in the render body is a new dependency on every pass.
  const now = React.useMemo(() => today ?? new Date(), [today])

  const matchedPreset = matchDateRangePreset(value, now)
  const hasRange = value.from != null || value.to != null

  // Sticky, because choosing "Custom range…" over an empty range has nothing to
  // derive from — there are no dates yet to fail to match a preset. Once dates
  // are picked the derived half holds it open on its own.
  const [customPicked, setCustomPicked] = React.useState(false)
  const showCustom = customPicked || (hasRange && matchedPreset == null)

  const selected: RangeOption | null = showCustom
    ? CUSTOM_OPTION
    : matchedPreset
      ? { id: matchedPreset.id, label: matchedPreset.label }
      : null

  function select(option: RangeOption | null) {
    // null is the dropdown's "All dates" row, and its inline clear button.
    if (option == null) {
      setCustomPicked(false)
      onChange({ from: null, to: null })
      return
    }

    if (option.id === CUSTOM_OPTION.id) {
      // Deliberately leaves `value` alone: switching to Custom from a preset
      // hands over that preset's dates to edit, rather than an empty pair.
      setCustomPicked(true)
      return
    }

    const preset = DATE_RANGE_PRESETS.find((p) => p.id === option.id)
    if (!preset) return

    setCustomPicked(false)
    // Both keys in one call — see `useDashboardFilters`. Two calls is what
    // emptied the From box on every range picked.
    onChange(dateRangeForPreset(preset, now))
  }

  return (
    <div className="flex items-center gap-2">
      <FilterDropdown
        label="Dates"
        options={OPTIONS}
        value={selected}
        onChange={select}
        getKey={(o) => o.id}
        getLabel={(o) => o.label}
        // Eight fixed rows — nothing to type-ahead over.
        searchable={false}
      />

      {showCustom && (
        <>
          <label htmlFor={fromId} className="text-sm text-[color:var(--text-secondary)]">
            From
          </label>
          <input
            id={fromId}
            type="date"
            value={value.from ?? ''}
            // The upper bound stops "from" being pushed past "to", which would
            // silently return nothing at all.
            max={value.to ?? undefined}
            onChange={(e) => onChange({ ...value, from: e.target.value || null })}
            className={inputClass}
          />

          <label htmlFor={toId} className="text-sm text-[color:var(--text-secondary)]">
            To
          </label>
          <input
            id={toId}
            type="date"
            value={value.to ?? ''}
            min={value.from ?? undefined}
            onChange={(e) => onChange({ ...value, to: e.target.value || null })}
            className={inputClass}
          />
        </>
      )}
    </div>
  )
}

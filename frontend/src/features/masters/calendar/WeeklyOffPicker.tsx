import * as React from 'react'
import { cn } from '@/lib/utils'
import { DAY_ABBREVIATIONS, DAY_NAMES, ISO_DAYS, type IsoDay } from './workingWeek'

/**
 * B-023 · the weekly-off pattern, as seven toggles.
 *
 * **Feature-local, not a shared control** — the same call C-010 made for its
 * pickers. `components/ui/` is Stream C's and additive-only, and a
 * seven-day-of-week toggle group has exactly one caller.
 *
 * Built as a `role="group"` of toggle buttons rather than checkboxes: the
 * question is "which days are off", and seven labelled buttons answer it at a
 * glance where a checkbox column would need reading. Each button carries
 * `aria-pressed`, so a screen reader announces the state without relying on
 * colour — which is the only other thing distinguishing on from off.
 */
export interface WeeklyOffPickerProps {
  value: readonly number[]
  onChange: (days: IsoDay[]) => void
  disabled?: boolean
  /** Set when the selection is invalid, so the group can be described by it. */
  errorId?: string
}

export function WeeklyOffPicker({ value, onChange, disabled, errorId }: WeeklyOffPickerProps) {
  const toggle = React.useCallback(
    (day: IsoDay) => {
      const next = value.includes(day) ? value.filter((d) => d !== day) : [...value, day]
      onChange([...next].sort((a, b) => a - b) as IsoDay[])
    },
    [value, onChange],
  )

  return (
    <div
      role="group"
      aria-label="Non-working days of the week"
      aria-describedby={errorId}
      className="flex flex-wrap gap-2"
    >
      {ISO_DAYS.map((day) => {
        const isOff = value.includes(day)
        return (
          <button
            key={day}
            type="button"
            disabled={disabled}
            aria-pressed={isOff}
            // The full name is the accessible label; the abbreviation is only
            // what fits in the button.
            aria-label={DAY_NAMES[day]}
            onClick={() => toggle(day)}
            className={cn(
              'min-w-14 rounded-md border px-3 py-2 text-sm font-medium transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2',
              'disabled:cursor-not-allowed disabled:opacity-50',
              isOff
                ? 'border-transparent bg-slate-900 text-white'
                : 'border-slate-300 bg-white text-slate-700 hover:bg-slate-50',
            )}
          >
            {DAY_ABBREVIATIONS[day]}
          </button>
        )
      })}
    </div>
  )
}

import * as React from 'react'

export interface DateRange {
  from: string | null
  to: string | null
}

export interface DashboardDateRangeProps {
  value: DateRange
  onChange: (value: DateRange) => void
}

/**
 * A-054 · the date-range control of §S-05's filter bar.
 *
 * <h2>Why this is not `features/tickets/list/DateRangeFilter`</h2>
 *
 * That component exists and does the same job, but it lives in Stream C's
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
export function DashboardDateRange({ value, onChange }: DashboardDateRangeProps) {
  const fromId = React.useId()
  const toId = React.useId()

  return (
    <div className="flex items-center gap-2">
      <label htmlFor={fromId} className="text-sm text-[color:var(--text-secondary)]">
        From
      </label>
      <input
        id={fromId}
        type="date"
        value={value.from ?? ''}
        // The upper bound stops a range that cannot contain data — the summary
        // tables hold no future days — and stops "to" being pushed before
        // "from", which would silently return nothing at all.
        max={value.to ?? undefined}
        onChange={(e) => onChange({ ...value, from: e.target.value || null })}
        className="rounded-control border border-[color:var(--border)] bg-[color:var(--bg-surface)]
                   px-2 py-1 text-sm text-[color:var(--text-primary)]
                   focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1
                   focus-visible:outline-[color:var(--primary)]"
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
        className="rounded-control border border-[color:var(--border)] bg-[color:var(--bg-surface)]
                   px-2 py-1 text-sm text-[color:var(--text-primary)]
                   focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-1
                   focus-visible:outline-[color:var(--primary)]"
      />
    </div>
  )
}

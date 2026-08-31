import { formatWeek, type Week } from '../../weeklyRange'

/**
 * S-05 tab 3 · which ISO week the figures cover.
 *
 * <h2>Two choices, by decision</h2>
 *
 * This week and last week, and nothing else. The tab reports one week against
 * the one before it, so a longer history would be a different screen —
 * Analytics already answers "how has this trended". A date input here would
 * also let somebody pick a Wednesday, which the endpoint refuses with a 400.
 *
 * <h2>Real buttons, and the selected one says so</h2>
 *
 * `aria-pressed` rather than colour alone: the two options differ only by fill,
 * and a screen reader user otherwise has no way to tell which week they are
 * looking at. The week's own dates are in the accessible name, so "This week"
 * announces what it actually means.
 */
export interface WeekPickerProps {
  weeks: { label: string; week: Week }[]
  selected: Week
  onSelect: (week: Week) => void
}

export function WeekPicker({ weeks, selected, onSelect }: WeekPickerProps) {
  return (
    <div
      role="group"
      aria-label="Week"
      className="inline-flex rounded-ctl border border-[color:var(--border)] overflow-hidden"
    >
      {weeks.map(({ label, week }) => {
        const isSelected = week.start === selected.start
        return (
          <button
            key={week.start}
            type="button"
            aria-pressed={isSelected}
            onClick={() => onSelect(week)}
            className={[
              'px-3 py-1.5 text-sm transition-colors',
              'focus-visible:outline focus-visible:outline-2 focus-visible:-outline-offset-2',
              'focus-visible:outline-[color:var(--primary)]',
              isSelected
                ? 'bg-[color:var(--primary)] text-white'
                : 'bg-[color:var(--bg-surface)] text-[color:var(--text-secondary)] hover:bg-[color:var(--bg-subtle)]',
            ].join(' ')}
          >
            {label}
          </button>
        )
      })}
      <span className="sr-only" aria-live="polite">
        {`Showing ${formatWeek(selected)}, ${selected.start} to ${selected.end}`}
      </span>
    </div>
  )
}

/**
 * The dates behind the choice, shown beside the picker.
 *
 * The picker says "This week"; this says which week that is. On the last
 * Monday of December those two disagree in a way worth reading — the week
 * beginning 2026-12-28 is week 53 of 2026 even though most of its days are in
 * 2027, and a header saying only "This week" would leave that invisible.
 */
export function WeekCaption({ week }: { week: Week }) {
  return (
    <p className="text-sm text-[color:var(--text-secondary)]">
      {formatWeek(week)} · {week.start} to {week.end}
    </p>
  )
}

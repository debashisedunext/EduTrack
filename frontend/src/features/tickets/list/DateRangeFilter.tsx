import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { format, parseISO } from 'date-fns'
import { CalendarRange, X } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface DateRange {
  from: string | null
  to: string | null
}

export interface DateRangeFilterProps {
  label: string
  value: DateRange
  onChange: (value: DateRange) => void
  className?: string
}

/** The wireframe's "Dates▾" — filters `dueFrom`/`dueTo` against the planned close date. */
export function DateRangeFilter({ label, value, onChange, className }: DateRangeFilterProps) {
  const [open, setOpen] = React.useState(false)
  const [draft, setDraft] = React.useState(value)

  React.useEffect(() => {
    if (open) setDraft(value)
  }, [open, value])

  const set = value.from != null || value.to != null
  const summary = set
    ? [value.from && format(parseISO(value.from), 'd MMM'), value.to && format(parseISO(value.to), 'd MMM')]
        .filter(Boolean)
        .join(' – ')
    : null

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          className={cn(
            'flex h-9 items-center gap-1.5 rounded-control border px-3 text-sm transition-colors',
            'focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1',
            set ? 'border-primary bg-primary-soft text-primary' : 'border-border bg-surface text-content hover:bg-subtle',
            className,
          )}
        >
          <CalendarRange className="h-3.5 w-3.5 shrink-0" />
          <span className="max-w-[10rem] truncate">{summary ?? label}</span>
          {set && (
            <span
              role="button"
              tabIndex={0}
              aria-label={`Clear ${label} filter`}
              onClick={(e) => {
                e.stopPropagation()
                onChange({ from: null, to: null })
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  e.stopPropagation()
                  onChange({ from: null, to: null })
                }
              }}
              className="rounded-full p-0.5 hover:bg-primary/15"
            >
              <X className="h-3.5 w-3.5" />
            </span>
          )}
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          className="z-50 w-64 rounded-control border border-border bg-surface p-3 shadow-modal"
        >
          <div className="flex flex-col gap-2">
            <label className="flex flex-col gap-1 text-caption text-content-muted">
              From
              <input
                type="date"
                value={draft.from ?? ''}
                onChange={(e) => setDraft((d) => ({ ...d, from: e.target.value || null }))}
                className="h-9 rounded-control border border-border bg-surface px-2 text-sm text-content"
              />
            </label>
            <label className="flex flex-col gap-1 text-caption text-content-muted">
              To
              <input
                type="date"
                value={draft.to ?? ''}
                onChange={(e) => setDraft((d) => ({ ...d, to: e.target.value || null }))}
                className="h-9 rounded-control border border-border bg-surface px-2 text-sm text-content"
              />
            </label>
            <div className="mt-1 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  onChange({ from: null, to: null })
                  setOpen(false)
                }}
                className="rounded-control px-2 py-1 text-caption text-content-muted hover:bg-subtle"
              >
                Clear
              </button>
              <button
                type="button"
                onClick={() => {
                  onChange(draft)
                  setOpen(false)
                }}
                className="rounded-control bg-primary px-3 py-1 text-caption font-medium text-white hover:bg-primary/90"
              >
                Apply
              </button>
            </div>
          </div>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

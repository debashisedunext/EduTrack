import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Check, ChevronDown, Search, X } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface FilterDropdownProps<T> {
  /** Shown on the trigger when nothing is selected, e.g. "Project". */
  label: string
  options: T[]
  value: T | null
  onChange: (value: T | null) => void
  getKey: (option: T) => string
  getLabel: (option: T) => string
  getSearchable?: (option: T) => string[]
  /** Hides the search box for short enum lists (Level, Status) — nothing to type-ahead over four options. */
  searchable?: boolean
  placeholder?: string
  disabled?: boolean
  className?: string
}

/**
 * The filter bar's own dropdown, not `searchable-dropdown.tsx`.
 *
 * The shared one models a required field — pick exactly one option, no way
 * back to "nothing". Every filter in S-17 needs the opposite: unset is the
 * default and every row has an explicit way to return to it, both from the
 * open list (an "All …" row) and closed (an inline clear button on the
 * trigger itself, so clearing a set filter never costs opening the popup).
 * A second caller earns this its own promotion to `components/ui/`.
 */
export function FilterDropdown<T>({
  label,
  options,
  value,
  onChange,
  getKey,
  getLabel,
  getSearchable,
  searchable = true,
  placeholder = 'Search…',
  disabled,
  className,
}: FilterDropdownProps<T>) {
  const [open, setOpen] = React.useState(false)
  const [query, setQuery] = React.useState('')
  const [activeIndex, setActiveIndex] = React.useState(0)

  const filtered = React.useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return options
    return options.filter((o) => {
      const haystack = [getLabel(o), ...(getSearchable?.(o) ?? [])]
      return haystack.some((s) => s.toLowerCase().includes(q))
    })
  }, [options, query, getLabel, getSearchable])

  React.useEffect(() => setActiveIndex(0), [query, open])

  function select(option: T | null) {
    onChange(option)
    setOpen(false)
    setQuery('')
  }

  function onKeyDown(e: React.KeyboardEvent) {
    // Index 0 is the synthetic "All …" row, so the list itself starts at 1.
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((i) => Math.min(i + 1, filtered.length))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      if (activeIndex === 0) select(null)
      else {
        const option = filtered[activeIndex - 1]
        if (option) select(option)
      }
    }
  }

  const selectedLabel = value != null ? getLabel(value) : null

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          disabled={disabled}
          className={cn(
            'flex h-9 items-center gap-1.5 rounded-control border px-3 text-sm transition-colors',
            'focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1',
            'disabled:cursor-not-allowed disabled:opacity-50',
            selectedLabel
              ? 'border-primary bg-primary-soft text-primary'
              : 'border-border bg-surface text-content hover:bg-subtle',
            className,
          )}
        >
          <span className="max-w-[12rem] truncate">{selectedLabel ? `${label}: ${selectedLabel}` : label}</span>
          {selectedLabel ? (
            <span
              role="button"
              tabIndex={0}
              aria-label={`Clear ${label} filter`}
              onClick={(e) => {
                e.stopPropagation()
                select(null)
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  e.stopPropagation()
                  select(null)
                }
              }}
              className="rounded-full p-0.5 hover:bg-primary/15"
            >
              <X className="h-3.5 w-3.5" />
            </span>
          ) : (
            <ChevronDown className="h-3.5 w-3.5 shrink-0 text-content-muted" />
          )}
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          className="z-50 w-56 overflow-hidden rounded-control border border-border bg-surface shadow-modal"
        >
          {searchable && (
            <div className="flex items-center gap-2 border-b border-border px-3">
              <Search className="h-4 w-4 shrink-0 text-content-muted" />
              <input
                autoFocus
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={onKeyDown}
                placeholder={placeholder}
                role="combobox"
                aria-expanded={open}
                className="h-9 w-full bg-transparent text-sm text-content outline-none placeholder:text-content-muted"
              />
            </div>
          )}
          <ul role="listbox" aria-label={label} className="max-h-64 overflow-y-auto p-1" onKeyDown={onKeyDown}>
            <li
              role="option"
              aria-selected={value == null}
              onMouseEnter={() => setActiveIndex(0)}
              onClick={() => select(null)}
              className={cn(
                'relative flex cursor-pointer select-none items-center rounded-control py-1.5 pl-8 pr-2 text-sm text-content-muted',
                activeIndex === 0 && 'bg-primary-soft text-primary',
              )}
            >
              {value == null && <Check className="absolute left-2 h-4 w-4" />}
              All {label.toLowerCase()}
            </li>
            {filtered.length === 0 && (
              <li className="px-2 py-3 text-center text-sm text-content-muted">No results</li>
            )}
            {filtered.map((option, index) => {
              const key = getKey(option)
              const selected = value != null && getKey(value) === key
              return (
                <li
                  key={key}
                  role="option"
                  aria-selected={selected}
                  onMouseEnter={() => setActiveIndex(index + 1)}
                  onClick={() => select(option)}
                  className={cn(
                    'relative flex cursor-pointer select-none items-center rounded-control py-1.5 pl-8 pr-2 text-sm text-content',
                    index + 1 === activeIndex && 'bg-primary-soft text-primary',
                  )}
                >
                  {selected && <Check className="absolute left-2 h-4 w-4" />}
                  {getLabel(option)}
                </li>
              )
            })}
          </ul>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

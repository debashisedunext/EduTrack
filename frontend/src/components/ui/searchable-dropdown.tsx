import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Check, ChevronDown, Search } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface SearchableDropdownProps<T> {
  options: T[]
  value: T | null
  onChange: (value: T) => void
  getKey: (option: T) => string
  getLabel: (option: T) => string
  /** Additional fields to match against, e.g. code/domain for the client dropdown (§4B.2). */
  getSearchable?: (option: T) => string[]
  placeholder?: string
  emptyText?: string
  disabled?: boolean
  className?: string
}

export function SearchableDropdown<T>({
  options,
  value,
  onChange,
  getKey,
  getLabel,
  getSearchable,
  placeholder = 'Search…',
  emptyText = 'No results',
  disabled,
  className,
}: SearchableDropdownProps<T>) {
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

  function select(option: T) {
    onChange(option)
    setOpen(false)
    setQuery('')
  }

  function onKeyDown(e: React.KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault()
      setActiveIndex((i) => Math.min(i + 1, filtered.length - 1))
    } else if (e.key === 'ArrowUp') {
      e.preventDefault()
      setActiveIndex((i) => Math.max(i - 1, 0))
    } else if (e.key === 'Enter') {
      e.preventDefault()
      const option = filtered[activeIndex]
      if (option) select(option)
    }
  }

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          disabled={disabled}
          className={cn(
            'flex h-10 w-full items-center justify-between rounded-control border border-border bg-surface px-3 text-sm text-content',
            'focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1',
            'disabled:cursor-not-allowed disabled:opacity-50',
            className,
          )}
        >
          <span className={value ? 'text-content' : 'text-content-muted'}>
            {value ? getLabel(value) : placeholder}
          </span>
          <ChevronDown className="h-4 w-4 shrink-0 text-content-muted" />
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          className="z-50 w-[--radix-popover-trigger-width] overflow-hidden rounded-control border border-border bg-surface shadow-modal"
        >
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
          <ul role="listbox" className="max-h-64 overflow-y-auto p-1">
            {filtered.length === 0 && (
              <li className="px-2 py-3 text-center text-sm text-content-muted">{emptyText}</li>
            )}
            {filtered.map((option, index) => {
              const key = getKey(option)
              const selected = value != null && getKey(value) === key
              return (
                <li
                  key={key}
                  role="option"
                  aria-selected={selected}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => select(option)}
                  className={cn(
                    'relative flex cursor-pointer select-none items-center rounded-control py-1.5 pl-8 pr-2 text-sm text-content',
                    index === activeIndex && 'bg-primary-soft text-primary',
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

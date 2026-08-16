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
  /**
   * B-028 · why an option cannot be chosen, or `null` if it can.
   *
   * A disabled option is still **listed, still searchable and still read out**
   * — it is the reason that makes it useful. The client dropdown's rule is "at
   * least one primary contact before the client can be selected on a ticket",
   * and a client that silently vanishes from the list is indistinguishable from
   * a list that has lost its data; one that is visible and greyed with "No
   * primary contact" beside it tells the user what to go and fix.
   *
   * The returned string is rendered in the row and is what the option's
   * `title` carries, so it reaches both sighted users and a pointer hover
   * without the caller wiring anything.
   */
  getOptionDisabled?: (option: T) => string | null
  placeholder?: string
  emptyText?: string
  disabled?: boolean
  className?: string
  /**
   * Wired to a `<label htmlFor>`. A `button` is a labelable element, so the
   * label associates with the trigger the same way it would with an `input`.
   * Without it the control reaches a screen reader unnamed, which fails AA.
   */
  id?: string
  'aria-labelledby'?: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
}

export function SearchableDropdown<T>({
  options,
  value,
  onChange,
  getKey,
  getLabel,
  getSearchable,
  getOptionDisabled,
  placeholder = 'Search…',
  emptyText = 'No results',
  disabled,
  className,
  id,
  'aria-labelledby': ariaLabelledBy,
  'aria-describedby': ariaDescribedBy,
  'aria-invalid': ariaInvalid,
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
    // B-028 · the single gate. Click, Enter and any future path all arrive
    // here, so a disabled option cannot be chosen by a route somebody forgot to
    // guard — which is what a `pointer-events-none` in the row would have left
    // open for the keyboard.
    if (getOptionDisabled?.(option)) return
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
          id={id}
          aria-labelledby={ariaLabelledBy}
          aria-describedby={ariaDescribedBy}
          aria-invalid={ariaInvalid}
          className={cn(
            'flex h-10 w-full items-center justify-between rounded-control border border-border bg-surface px-3 text-sm text-content',
            'focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1',
            'disabled:cursor-not-allowed disabled:opacity-50',
            'aria-[invalid=true]:border-danger aria-[invalid=true]:focus:ring-danger',
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
              // B-028 · `aria-disabled`, never the `disabled` attribute —
              // `<li>` has none, and removing the row from the tab order would
              // hide the reason from the users most dependent on being told it.
              const unselectable = getOptionDisabled?.(option) ?? null
              return (
                <li
                  key={key}
                  role="option"
                  aria-selected={selected}
                  aria-disabled={unselectable != null || undefined}
                  title={unselectable ?? undefined}
                  onMouseEnter={() => setActiveIndex(index)}
                  onClick={() => select(option)}
                  className={cn(
                    'relative flex select-none items-center justify-between gap-2 rounded-control py-1.5 pl-8 pr-2 text-sm text-content',
                    unselectable ? 'cursor-not-allowed' : 'cursor-pointer',
                    index === activeIndex && !unselectable && 'bg-primary-soft text-primary',
                    // Highlighted differently rather than not at all: the row
                    // still takes arrow-key focus, because reading why it
                    // cannot be picked is the point of leaving it in the list.
                    index === activeIndex && unselectable && 'bg-subtle',
                  )}
                >
                  {selected && <Check className="absolute left-2 h-4 w-4" />}
                  <span className={unselectable ? 'text-content-muted' : undefined}>
                    {getLabel(option)}
                  </span>
                  {unselectable && (
                    <span className="shrink-0 text-xs text-content-muted">{unselectable}</span>
                  )}
                </li>
              )
            })}
          </ul>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

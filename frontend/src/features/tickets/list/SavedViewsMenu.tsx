import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Check, ChevronDown } from 'lucide-react'
import { cn } from '@/lib/utils'
import { EMPTY_FILTERS, type TicketListFilters } from './useTicketListFilters'

/** `YYYY-MM-DD` in the viewer's local timezone — matches `<input type="date">`'s own format. */
function localDate(d: Date): string {
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
}

function firstOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1)
}

interface SavedView {
  id: string
  label: string
  /** `null` when the view cannot be built yet (My Open before `useGetMe` resolves) — rendered disabled rather than applying a broken `assigneeId`. */
  recipe: TicketListFilters | null
  disabledReason?: string
}

/**
 * The six S-17 saved views (blueprint §7.5). Fixed, built-in presets over the
 * existing URL-based filter state from C-014 — there is no `SavedView`
 * schema and no persistence; see the folder README for why these are not
 * user-creatable searches.
 */
function buildViews(myUserId: number | null): SavedView[] {
  const now = new Date()
  const today = localDate(now)
  const monthStart = localDate(firstOfMonth(now))

  return [
    {
      id: 'my-open',
      label: 'My Open',
      recipe:
        myUserId != null ? { ...EMPTY_FILTERS, assigneeId: myUserId, excludeClosed: true } : null,
      disabledReason: 'Loading your profile…',
    },
    { id: 'due-today', label: 'Due Today', recipe: { ...EMPTY_FILTERS, dueFrom: today, dueTo: today } },
    { id: 'overdue', label: 'Overdue', recipe: { ...EMPTY_FILTERS, isDelayed: true } },
    { id: 'unassigned', label: 'Unassigned', recipe: { ...EMPTY_FILTERS, unassigned: true } },
    { id: 'reopened', label: 'Reopened', recipe: { ...EMPTY_FILTERS, reopenedOnly: true } },
    {
      id: 'closed-this-month',
      label: 'Closed This Month',
      recipe: { ...EMPTY_FILTERS, status: 'CLOSED', closedFrom: monthStart, closedTo: today },
    },
  ]
}

/** Every non-`q` key matches the recipe exactly — computed from the URL-backed `filters`, never stored separately, so it cannot drift from what is actually applied. */
function matchesRecipe(filters: TicketListFilters, recipe: TicketListFilters): boolean {
  return (Object.keys(EMPTY_FILTERS) as (keyof TicketListFilters)[])
    .filter((key) => key !== 'q')
    .every((key) => filters[key] === recipe[key])
}

export interface SavedViewsMenuProps {
  filters: TicketListFilters
  onApply: (recipe: Partial<TicketListFilters>) => void
  /** Current user's id, from `useGetMe()` — `null` while it is still loading. */
  myUserId: number | null
  className?: string
}

/**
 * Replaces `TicketListPage`'s disabled "Saved views ▾" stub. A dropdown over
 * the six fixed S-17 views — picking one replaces the whole filter row (like
 * Reset) with that view's recipe, via `applyFilters`.
 */
export function SavedViewsMenu({ filters, onApply, myUserId, className }: SavedViewsMenuProps) {
  const [open, setOpen] = React.useState(false)
  const views = React.useMemo(() => buildViews(myUserId), [myUserId])
  const activeView = views.find((v) => v.recipe && matchesRecipe(filters, v.recipe))

  function select(view: SavedView) {
    if (!view.recipe) return
    onApply(view.recipe)
    setOpen(false)
  }

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          aria-label="Saved views"
          className={cn(
            'flex h-9 items-center gap-1.5 rounded-control border px-3 text-sm transition-colors',
            'focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1',
            activeView
              ? 'border-primary bg-primary-soft text-primary'
              : 'border-border bg-surface text-content hover:bg-subtle',
            className,
          )}
        >
          <span className="max-w-[12rem] truncate">{activeView ? activeView.label : 'Saved views'}</span>
          <ChevronDown className="h-3.5 w-3.5 shrink-0" />
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          className="z-50 w-56 overflow-hidden rounded-control border border-border bg-surface p-1 shadow-modal"
        >
          <ul role="menu" aria-label="Saved views">
            {views.map((view) => {
              const isActive = activeView?.id === view.id
              const disabled = view.recipe == null
              return (
                <li key={view.id} role="none">
                  <button
                    type="button"
                    role="menuitemradio"
                    aria-checked={isActive}
                    disabled={disabled}
                    title={disabled ? view.disabledReason : undefined}
                    onClick={() => select(view)}
                    className={cn(
                      'relative flex w-full cursor-pointer select-none items-center rounded-control py-1.5 pl-8 pr-2 text-left text-sm',
                      'focus:outline-none focus-visible:bg-primary-soft focus-visible:text-primary',
                      'disabled:cursor-not-allowed disabled:opacity-50',
                      isActive ? 'bg-primary-soft text-primary' : 'text-content hover:bg-subtle',
                    )}
                  >
                    {isActive && <Check className="absolute left-2 h-4 w-4" />}
                    {view.label}
                  </button>
                </li>
              )
            })}
          </ul>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

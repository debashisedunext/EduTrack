import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { Check, ChevronDown } from 'lucide-react'

import { cn } from '@/lib/utils'

export interface DependsOnOption {
  activeTemplateId: number
  name: string
}

/**
 * OB-07's "Depends on" cell — the cross-service dependency (plan §5.5), now a
 * **set** rather than one id.
 *
 * <h2>Why this is not a `<select multiple>`</h2>
 *
 * It was a single `<select>`, and the obvious multi-select translation is the
 * native `multiple` attribute. That control is a fixed-height scrolling box
 * inside a table row, it needs ctrl-click to add a second value and
 * ctrl-click to remove one — which nothing on the page says and most people
 * do not know — and a plain click silently discards the whole selection. A
 * dependency set an admin can wipe by clicking the wrong row is worse than
 * the single-select it replaces.
 *
 * <p>So: a trigger that reads the current set at a glance, and a popover of
 * tick-able options. The same shape `components/ui/filter-dropdown.tsx` uses
 * one level up, written here rather than by widening that component, because
 * `components/ui/` is Stream C's (TEAM-PLAN §6) and a shared-component change
 * needs their sign-off. If a second caller wants this, that is the moment to
 * promote it — which is the argument `FilterDropdown`'s own javadoc records
 * for the move that put it there.
 *
 * <h2>Each tick saves</h2>
 *
 * Rather than collecting a draft set and writing it when the popover closes.
 * Closing is not an action a reader takes — Escape, an outside click and
 * picking the next row all do it — so "close commits" and "close cancels" are
 * equally defensible readings of the same gesture, and whichever is chosen,
 * half of the readers lose an edit. A tick is unambiguous.
 *
 * <p>The cost is one request per tick, which this screen can afford: it is an
 * admin catalogue of a handful of services, the route replaces the whole set
 * so no two requests can interleave into a state neither asked for, and every
 * intermediate set is one the server accepts — cycle-freedom is a property of
 * each candidate against this template, so any subset of the offered options
 * is legal.
 *
 * <p>The list is disabled while a save is in flight, because the next tick
 * needs the `ETag` the save's own invalidation refetches. Without that, a
 * reader ticking two options quickly sends the second with the first's tag
 * and is answered `412`.
 */
export function DependsOnMultiSelect({
  id,
  label,
  options,
  selectedIds,
  disabled,
  busy,
  onToggle,
}: {
  /** Ties the trigger to the row, so two rows' controls have distinct ids. */
  id: string
  /** The control's accessible name — the column heading is only visual. */
  label: string
  /** Every service this one may legally wait behind, already cycle-filtered. */
  options: DependsOnOption[]
  selectedIds: readonly number[]
  disabled?: boolean
  /** A save is in flight: the options stay visible but refuse a second tick. */
  busy?: boolean
  onToggle: (templateId: number, next: boolean) => void
}) {
  const [open, setOpen] = React.useState(false)
  const selected = new Set(selectedIds)

  /*
    Named from the options rather than from `selectedIds` directly: an id
    selected but no longer on offer — a service retired since the dependency
    was declared — has no name here, and a trigger reading "⛓ 41" would be
    worse than one that counts it. `chosen` therefore drives the label and
    `selected.size` drives nothing; the two agree except in that case, and in
    that case the names are the honest half.
  */
  const chosen = options.filter((o) => selected.has(o.activeTemplateId))

  const triggerLabel =
    chosen.length === 0
      ? '∥ Runs parallel'
      : chosen.length === 1
        ? `⛓ ${chosen[0].name}`
        : `⛓ ${chosen[0].name} +${chosen.length - 1}`

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          id={id}
          type="button"
          disabled={disabled}
          aria-label={
            chosen.length === 0
              ? `${label} — nothing selected, runs parallel`
              : `${label} — ${chosen.map((o) => o.name).join(', ')}`
          }
          /*
            `min-w-0` is load-bearing here for the reason the `<select>` it
            replaces needed it: a flex item defaults to `min-width: auto`,
            which for a control sized by its content is the width of the
            longest service name. Without it one long name pushes the control
            out of its cell and over the column beside it. `truncate` then
            ellipsises the label rather than letting it set the width.
          */
          className={cn(
            'flex h-9 w-full min-w-0 items-center justify-between gap-1.5 rounded-control border px-2 text-sm transition-colors',
            'focus:outline-none focus:ring-2 focus:ring-primary focus:ring-offset-1',
            'disabled:cursor-not-allowed disabled:opacity-50',
            chosen.length > 0
              ? 'border-primary bg-primary-soft text-primary'
              : 'border-border bg-surface text-content-muted hover:bg-subtle',
          )}
        >
          <span className="truncate">{triggerLabel}</span>
          <ChevronDown className="h-3.5 w-3.5 shrink-0 text-content-muted" />
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          /*
            The row underneath is clickable and navigates to the service. The
            popover is portalled out of that row, but the events it raises
            still bubble through React's tree to the row's handler, so they
            are stopped here — the same guard the cell itself applies.
          */
          onClick={(e) => e.stopPropagation()}
          className="z-50 w-64 overflow-hidden rounded-control border border-border bg-surface shadow-modal"
        >
          {/*
            `role="option"` sits on the button itself rather than on a list
            item wrapping it, so the thing a reader — or a test — finds by
            that role is the thing that responds to a click. With the role on
            an outer element and the handler on an inner one they are two
            different nodes, and clicking the one the accessibility tree names
            does nothing at all. That also rules out `<ul>`/`<li>`: a listbox's
            children have to be its options, with nothing in between.
          */}
          <div
            role="listbox"
            aria-multiselectable
            aria-label={label}
            className="flex max-h-64 flex-col overflow-y-auto p-1"
          >
            {options.length === 0 && (
              <p className="px-2 py-3 text-center text-sm text-content-muted">
                No other service can be depended on here.
              </p>
            )}
            {options.map((option) => {
              const isSelected = selected.has(option.activeTemplateId)
              return (
                <button
                  key={option.activeTemplateId}
                  type="button"
                  role="option"
                  aria-selected={isSelected}
                  disabled={busy}
                  onClick={() => onToggle(option.activeTemplateId, !isSelected)}
                  className={cn(
                    'relative flex w-full cursor-pointer select-none items-center rounded-control py-1.5 pl-8 pr-2 text-left text-sm',
                    'focus:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
                    'disabled:cursor-not-allowed disabled:opacity-60',
                    isSelected ? 'text-primary' : 'text-content hover:bg-subtle',
                  )}
                >
                  {isSelected && <Check aria-hidden className="absolute left-2 h-4 w-4" />}
                  {option.name}
                </button>
              )
            })}
          </div>
          {/* Says what an empty set means, which the trigger can only hint at
              once it is closed. */}
          <p className="border-t border-border px-3 py-2 text-caption text-content-muted">
            Tick every service this one waits for. None ticked means it runs in parallel.
          </p>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

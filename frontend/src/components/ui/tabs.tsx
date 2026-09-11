import * as React from 'react'
import { cn } from '@/lib/utils'

export interface TabItem {
  /** Also the `?tab=` value, so a tab is a link a colleague can paste. */
  id: string
  label: string
  content: React.ReactNode
}

export interface TabsProps {
  tabs: TabItem[]
  activeId: string
  onSelect: (id: string) => void
  /** Accessible name for the tablist — every screen's strip needs its own. */
  ariaLabel: string
  className?: string
  /**
   * How the strip is drawn. `underline` is the original and stays the default,
   * so the two screens that already mount this control are untouched.
   *
   * `segmented` draws each tab as its own bordered cell, divided from its
   * neighbours — asked for on OB-02, where the strip is the page's only
   * navigation now that the board's heading and its action button are gone
   * and a row of four flat labels had nothing to read as a control.
   */
  variant?: 'underline' | 'segmented'
}

/**
 * The shared tab strip, promoted from `features/tickets/detail/TicketDetailTabs.tsx`
 * once a second screen needed the same control — see that file's history for why
 * it started hand-rolled rather than pulled from a component library.
 *
 * Keyboard behaviour follows the APG tabs pattern: one tab stop for the whole
 * strip, arrows move between tabs, Home/End jump to the ends. Selection follows
 * focus, which only suits a caller whose panels are all already loaded — a
 * caller that fetches per-tab should not assume that holds without checking.
 */
export function Tabs({
  tabs,
  activeId,
  onSelect,
  ariaLabel,
  className,
  variant = 'underline',
}: TabsProps) {
  const segmented = variant === 'segmented'
  const instanceId = React.useId()
  const refs = React.useRef<Record<string, HTMLButtonElement | null>>({})
  const activeIndex = Math.max(
    0,
    tabs.findIndex((t) => t.id === activeId),
  )
  const active = tabs[activeIndex]

  function move(nextIndex: number) {
    const next = tabs[(nextIndex + tabs.length) % tabs.length]
    onSelect(next.id)
    refs.current[next.id]?.focus()
  }

  function onKeyDown(event: React.KeyboardEvent<HTMLDivElement>) {
    switch (event.key) {
      case 'ArrowRight':
        move(activeIndex + 1)
        break
      case 'ArrowLeft':
        move(activeIndex - 1)
        break
      case 'Home':
        move(0)
        break
      case 'End':
        move(tabs.length - 1)
        break
      default:
        return
    }
    event.preventDefault()
  }

  return (
    <div className={cn('rounded-card border border-border bg-surface shadow-rest', className)}>
      <div
        role="tablist"
        aria-label={ariaLabel}
        onKeyDown={onKeyDown}
        className={cn(
          'flex overflow-x-auto border-b border-border',
          segmented ? 'gap-0' : 'gap-1 px-2',
        )}
      >
        {tabs.map((tab) => {
          const selected = tab.id === active.id
          return (
            <button
              key={tab.id}
              ref={(node) => {
                refs.current[tab.id] = node
              }}
              type="button"
              role="tab"
              id={`${instanceId}-tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`${instanceId}-panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(tab.id)}
              className={cn(
                '-mb-px whitespace-nowrap border-b-2 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
                segmented
                  ? 'flex-1 border-r border-r-border px-4 py-3 last:border-r-0'
                  : 'px-3 py-2.5',
                selected
                  ? cn('border-b-primary text-primary', segmented && 'bg-primary-soft')
                  : cn(
                      'border-b-transparent text-content-muted hover:text-content',
                      segmented && 'hover:bg-subtle',
                    ),
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>

      <div
        role="tabpanel"
        id={`${instanceId}-panel-${active.id}`}
        aria-labelledby={`${instanceId}-tab-${active.id}`}
        tabIndex={0}
        className="p-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
      >
        {active.content}
      </div>
    </div>
  )
}

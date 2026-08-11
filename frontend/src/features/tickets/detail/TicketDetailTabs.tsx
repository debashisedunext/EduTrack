import * as React from 'react'
import { cn } from '@/lib/utils'

export interface DetailTab {
  /** Also the `?tab=` value, so a tab is a link a colleague can paste. */
  id: string
  label: string
  content: React.ReactNode
}

export interface TicketDetailTabsProps {
  tabs: DetailTab[]
  activeId: string
  onSelect: (id: string) => void
}

/**
 * The detail page's tab strip.
 *
 * Hand-rolled rather than pulled from the shared library because there is no
 * `components/ui/tabs.tsx` yet and `@radix-ui/react-tabs` is not a dependency
 * — adding one to `package.json` for a control this page can express in forty
 * lines would put a lockfile change in a frontend-only PR. If a second screen
 * needs tabs, that is the moment to promote this into `components/ui` with a
 * Storybook entry, which is what makes a component shared.
 *
 * Keyboard behaviour follows the APG tabs pattern: one tab stop for the whole
 * strip, arrows move between tabs, Home/End jump to the ends. Selection
 * follows focus, which is right here because every panel is already loaded
 * from the one aggregated `/full` call — nothing is fetched by moving.
 */
export function TicketDetailTabs({ tabs, activeId, onSelect }: TicketDetailTabsProps) {
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
    <div className="rounded-card border border-border bg-surface shadow-rest">
      <div
        role="tablist"
        aria-label="Ticket detail"
        onKeyDown={onKeyDown}
        className="flex gap-1 overflow-x-auto border-b border-border px-2"
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
              id={`ticket-tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`ticket-panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(tab.id)}
              className={cn(
                '-mb-px whitespace-nowrap border-b-2 px-3 py-2.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
                selected
                  ? 'border-b-primary text-primary'
                  : 'border-b-transparent text-content-muted hover:text-content',
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>

      <div
        role="tabpanel"
        id={`ticket-panel-${active.id}`}
        aria-labelledby={`ticket-tab-${active.id}`}
        tabIndex={0}
        className="p-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
      >
        {active.content}
      </div>
    </div>
  )
}

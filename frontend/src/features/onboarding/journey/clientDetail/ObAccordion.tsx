import * as React from 'react'
import { ChevronRight } from 'lucide-react'

import { cn } from '@/lib/utils'
import { useAnchoredToggle } from './useAnchoredToggle'

/**
 * C-110 · one accordion on OB-05 — a summary strip that is always visible and
 * a region that expands under it.
 *
 * ## Local to this feature, not promoted to `components/ui/`
 *
 * CLAUDE.md's line on the shared library is that Storybook is its contract and
 * that other streams consume it, so a component put there is a component whose
 * props are a cross-stream commitment. This one has exactly two consumers, both
 * on this screen, and its whole reason for existing is a rule specific to it
 * (the anchoring below). Promoting it would buy three other streams a contract
 * they have not asked for; if CP-03's read-only view turns out to want the same
 * thing, that is the moment to move it, with two call sites to check rather
 * than a guess.
 *
 * ## Disclosure, not tabs
 *
 * `aria-expanded` on the trigger and `aria-controls` pointing at the region —
 * the standard disclosure pattern. Not `role="region"` on a collapsed panel
 * that is still in the DOM: a screen-reader user landing in hidden content is
 * worse than not finding it, so the body is unmounted when closed rather than
 * hidden with a class. That also means the ribbon inside costs nothing until
 * somebody opens it, which is the same call `ObJourneyStrip` makes on the wire
 * ("a client with six journeys does not pay for six ribbons on first paint").
 *
 * ## Every toggle is anchored
 *
 * The header element is handed to `useAnchoredToggle` before the state change,
 * so plan §9's "expanding/collapsing never scrolls the page" holds for the
 * *header the user clicked* rather than for a scroll offset that means nothing
 * once the content above it has changed height. See that hook for why the two
 * are not the same thing.
 */
export interface ObAccordionProps {
  id: string
  isOpen: boolean
  onToggle: () => void
  /** The always-visible strip. Rendered inside the trigger, so it must not
   * contain interactive elements of its own — a button inside a button is
   * invalid HTML and unreachable by keyboard. */
  summary: React.ReactNode
  /** What the trigger announces. The strip is a row of chips and numbers, and
   * their concatenated text is not a sentence. */
  label: string
  children: React.ReactNode
  className?: string
}

export function ObAccordion({ id, isOpen, onToggle, summary, label, children, className }: ObAccordionProps) {
  const headerRef = React.useRef<HTMLDivElement | null>(null)
  const { anchor } = useAnchoredToggle()

  return (
    <section
      ref={headerRef}
      className={cn('rounded-card border border-border bg-surface shadow-rest', className)}
    >
      <h3 className="m-0">
        <button
          type="button"
          id={`${id}-trigger`}
          aria-expanded={isOpen}
          aria-controls={`${id}-panel`}
          aria-label={label}
          onClick={() => {
            anchor(headerRef.current)
            onToggle()
          }}
          className="flex w-full items-center gap-3 rounded-card px-4 py-3 text-left outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <ChevronRight
            aria-hidden="true"
            className={cn(
              'h-4 w-4 shrink-0 text-content-muted transition-transform motion-reduce:transition-none',
              isOpen && 'rotate-90',
            )}
          />
          <span className="flex min-w-0 flex-1 items-center gap-3">{summary}</span>
        </button>
      </h3>

      {isOpen && (
        <div
          id={`${id}-panel`}
          role="region"
          aria-labelledby={`${id}-trigger`}
          className="border-t border-border px-4 py-4"
        >
          {children}
        </div>
      )}
    </section>
  )
}

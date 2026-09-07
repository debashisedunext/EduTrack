import * as React from 'react'

import { EmptyState } from '@/components/ui/empty-state'
import type { JourneyStep } from './types'
import { JourneyRibbonSegment } from './JourneyRibbonSegment'
import { useRovingFocus } from './rovingFocus'

/**
 * C-109 · lays the journey's steps out in order — Onboarding-Module-Plan.md
 * §9's "journey accordions … expanded: ribbon + step panel". The step panel
 * itself, and which journey/cycle feeds this strip, are C-110's (OB-05).
 *
 * ## Keyboard, same shape as the phase-1 ribbon, built fresh
 *
 * One tab stop for the whole strip, `←`/`→` between steps, `Home`/`End` to
 * the ends — CLAUDE.md's keyboard-navigation line and a journey that seeds at
 * 8 steps (the default template, §2) make this the identical problem
 * `components/ribbon/RibbonStrip.tsx` solved, solved again here rather than
 * imported from there. The tab stop starts on the **current** step, not step
 * 1, for the same reason: landing a reader on "Kickoff call", finished days
 * ago, and making them arrow forward to find the journey's actual state
 * would be a working keyboard interface answering the wrong question.
 *
 * A journey with no CURRENT step (fully DONE, or still LOCKED behind the
 * prerequisite gate — §5.2/§5.3) starts the tab stop on step 1.
 *
 * ## Auto-centring
 *
 * Keyed on the current step's own id, exactly like the phase-1 strip's own
 * effect — arrowing across the strip to read a step must not drag the scroll
 * position with it, so this only re-centres when the journey's current step
 * genuinely changes, never on a focus move.
 *
 * It moves the strip's own `scrollLeft` and nothing else. C-110 mounts this
 * inside an accordion that must never scroll the page (plan §9, OB-05), and
 * `scrollIntoView` would have scrolled every ancestor including the document.
 *
 * ## What this task is not
 *
 * No collapsed-grouping pass (`components/ribbon/collapsedGroup.ts`'s
 * equivalent) — the module plan's own default template tops out at 8 steps
 * and nothing in Onboarding-Module-Plan.md asks for one; §17's "unreachable
 * ribbon" mitigation this codebase already ships lives on the horizontal
 * scroll alone, matching the prototype's own `.ribbon-wrap{overflow-x:auto}`.
 * If a longer template makes this worth adding, that is its own task rather
 * than scope quietly folded into this one.
 */
export function JourneyRibbonStrip({
  steps,
  selectedStepId,
  onSelectStep,
}: {
  steps: JourneyStep[]
  selectedStepId?: string
  onSelectStep?: (step: JourneyStep) => void
}) {
  const currentIndex = steps.findIndex((step) => step.status === 'CURRENT')
  const currentStep = currentIndex >= 0 ? steps[currentIndex] : undefined

  const roving = useRovingFocus(steps.length, Math.max(currentIndex, 0))

  const scrollRef = React.useRef<HTMLDivElement | null>(null)
  const currentId = currentStep?.id

  React.useEffect(() => {
    if (!currentId) return
    const container = scrollRef.current
    const node = container?.querySelector<HTMLElement>('[data-journey-current="true"]')
    if (!container || !node) return

    // Horizontal only, and deliberately not `scrollIntoView`.
    //
    // C-110 mounts this strip inside an accordion whose own rule (plan §9,
    // OB-05) is that expanding one or selecting a step **never scrolls the
    // page**. `scrollIntoView` walks every scrollable ancestor, the document
    // included, so on the expand that mounts this it would drag the page to
    // the ribbon — defeating the anchoring C-110 does one effect earlier, and
    // for a reason no reader could connect to the accordion they clicked.
    //
    // Setting `scrollLeft` on the strip's own container cannot move anything
    // but the strip, which is all "auto-centred" ever meant here.
    const target = node.offsetLeft - (container.clientWidth - node.offsetWidth) / 2
    const left = Math.max(0, Math.min(target, container.scrollWidth - container.clientWidth))
    if (typeof container.scrollTo === 'function') {
      container.scrollTo({ left, behavior: prefersReducedMotion() ? 'auto' : 'smooth' })
    } else {
      container.scrollLeft = left
    }
    // Only the current step's own identity — see the docstring above for why
    // the roving tab stop must not be an input here too.
  }, [currentId])

  if (steps.length === 0) {
    return (
      <EmptyState
        title="No journey template"
        description="This service has no journey template pinned yet, so there is no step-by-step ribbon to show."
      />
    )
  }

  return (
    <div
      ref={scrollRef}
      role="list"
      aria-label="Journey steps"
      onKeyDown={roving.onKeyDown}
      className="flex items-start overflow-x-auto pb-1"
    >
      {steps.map((step, index) => {
        const { ref, tabIndex, onFocus } = roving.itemProps(index)
        const isCurrent = step.status === 'CURRENT'
        return (
          <div
            role="listitem"
            key={step.id}
            data-journey-current={isCurrent || undefined}
          >
            <JourneyRibbonSegment
              ref={ref}
              tabIndex={tabIndex}
              onFocus={onFocus}
              step={step}
              isLast={index === steps.length - 1}
              onSelect={onSelectStep}
              isSelected={selectedStepId === step.id}
            />
          </div>
        )
      })}
    </div>
  )
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  )
}

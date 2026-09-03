import * as React from 'react'
import { format, parseISO } from 'date-fns'

import { cn } from '@/lib/utils'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import type { JourneyStep } from './types'
import { dependencyBadge, ownerLabel, statusEmoji, stepAriaLabel, tatBarLevel, treatmentFor } from './stepState'

/**
 * C-109 · one tile of the onboarding journey ribbon — Onboarding-Module-Plan.md
 * §9, OB-05:
 *
 * ```
 * ┌─────────────────────┐
 * │ ▶ In progress    👏 │  ← state icon + label, animated status emoji
 * │ 3. Data migration    │  ← seq + name
 * │ 👤 Priya Nair         │  ← owner
 * │ 8d TAT · ↳2           │  ← TAT budget or SD/FD, dependency badge
 * │ ▓▓▓▓▓▓░░░░            │  ← TAT-consumed bar (running steps only)
 * └─────────────────────┘
 * ```
 *
 * Built fresh, not imported from `components/ribbon/` — PHASE-2-BUILD-PLAN.md
 * names this the one decision on this task worth getting right the first
 * time, since four screens (OB-05's client detail, the client portal's CP-03
 * read-only view, and whichever two OB4 sign-off screens end up embedding it)
 * end up depending on whichever shape this file settles on. See the
 * directory README for the fuller "why fresh" note.
 *
 * `onSelect` absent renders a read-only `<div role="group">` rather than a
 * `<button>` — CP-03's own line, "read-only journey accordions — step status
 * only", is exactly this mode, not a hypothetical one.
 */
export interface JourneyRibbonSegmentProps {
  step: JourneyStep
  isLast?: boolean
  onSelect?: (step: JourneyStep) => void
  isSelected?: boolean
  tabIndex?: number
  onFocus?: () => void
  className?: string
}

function formatShortDate(value: string | null | undefined): string | null {
  if (!value) return null
  const parsed = parseISO(value)
  if (Number.isNaN(parsed.getTime())) return null
  return format(parsed, 'd MMM')
}

function closedMarker(step: JourneyStep): string {
  if (step.closed === 'early') return '✓ early'
  if (step.closed === 'late') return '⚠ delayed'
  return '✓ on time'
}

export const JourneyRibbonSegment = React.forwardRef<HTMLElement, JourneyRibbonSegmentProps>(
  function JourneyRibbonSegment({ step, isLast = false, onSelect, isSelected = false, tabIndex, onFocus, className }, ref) {
    const treatment = treatmentFor(step)
    const isCurrent = step.status === 'CURRENT'
    const isDone = step.status === 'DONE'
    const emoji = statusEmoji(step)
    const { Icon } = treatment
    const owner = ownerLabel(step)
    const barLevel = tatBarLevel(step.tatPercent)
    const subTasksDone =
      !isDone && step.subTasksTotal ? `${step.subTasksAnswered ?? 0}/${step.subTasksTotal}` : null

    const meta = isDone
      ? [
          step.startedOn && `SD ${formatShortDate(step.startedOn)}`,
          step.finishedOn && `FD ${formatShortDate(step.finishedOn)}`,
          closedMarker(step),
        ]
          .filter(Boolean)
          .join(' · ')
      : [
          `${step.tatDays}d TAT`,
          step.startedOn && `SD ${formatShortDate(step.startedOn)}`,
          subTasksDone && `☑ ${subTasksDone}`,
        ]
          .filter(Boolean)
          .join(' · ')

    const body = (
      <>
        <div className="flex items-center gap-1.5">
          <Icon className={cn('h-4 w-4 shrink-0', isCurrent && 'animate-pulse motion-reduce:animate-none')} aria-hidden="true" />
          <span className={cn('shrink-0 text-caption font-semibold', treatment.title)}>{treatment.label}</span>
          {emoji && (
            <span
              className={cn('ml-auto shrink-0 text-base leading-none motion-reduce:animate-none', emoji.animationClass)}
              title={emoji.label}
              aria-hidden="true"
            >
              {emoji.glyph}
            </span>
          )}
        </div>

        <div className="min-h-[32px] text-xs font-semibold leading-4">
          {step.seqNo}. {step.name}
        </div>

        <div className="flex items-center gap-1.5 text-caption text-content-muted">
          <span aria-hidden="true">👤</span>
          <span className="truncate">{owner}</span>
        </div>

        <div className="flex items-center gap-1 text-caption text-content-muted">
          <span className="truncate">{meta}</span>
          <span
            className="ml-auto shrink-0"
            title={step.dependsOnSeqNo != null ? `Depends on step ${step.dependsOnSeqNo}` : 'No dependency — runs parallel'}
          >
            {dependencyBadge(step)}
          </span>
        </div>

        {!isDone && step.tatPercent != null && (
          <div className="mt-1 h-1 overflow-hidden rounded-full bg-subtle">
            <div
              className={cn(
                'h-full rounded-full',
                barLevel === 'red' ? 'bg-danger' : barLevel === 'amber' ? 'bg-warning' : 'bg-success',
              )}
              style={{ width: `${Math.min(100, Math.max(0, step.tatPercent))}%` }}
            />
          </div>
        )}
      </>
    )

    const cardClass = cn(
      'flex w-40 shrink-0 flex-col gap-1 rounded-card border p-2.5 text-left shadow-rest transition',
      treatment.card,
      onSelect && 'hover:shadow-modal',
      'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary has-[:focus-visible]:ring-offset-1',
      isSelected && 'ring-2 ring-primary',
      className,
    )

    const triggerClass = cn('flex min-w-0 flex-col gap-1 text-left outline-none', onSelect && 'cursor-pointer')

    const trigger = onSelect ? (
      <button
        ref={ref as React.Ref<HTMLButtonElement>}
        type="button"
        className={triggerClass}
        onClick={() => onSelect(step)}
        onFocus={onFocus}
        tabIndex={tabIndex}
        aria-label={stepAriaLabel(step)}
        aria-current={isCurrent ? 'step' : undefined}
        aria-pressed={isSelected}
        data-state={step.status}
      >
        {body}
      </button>
    ) : (
      <div
        ref={ref as React.Ref<HTMLDivElement>}
        className={triggerClass}
        role="group"
        onFocus={onFocus}
        tabIndex={tabIndex}
        aria-label={stepAriaLabel(step)}
        aria-current={isCurrent ? 'step' : undefined}
        data-state={step.status}
      >
        {body}
      </div>
    )

    return (
      <div className="flex min-w-0 items-center" data-testid="journey-ribbon-segment">
        <div className={cardClass}>
          <TooltipProvider delayDuration={300}>
            <Tooltip>
              <TooltipTrigger asChild>{trigger}</TooltipTrigger>
              <TooltipContent>
                <dl className="grid grid-cols-[auto_1fr] gap-x-2 gap-y-0.5">
                  <dt className="text-content-muted">Owner</dt>
                  <dd>{owner}</dd>
                  <dt className="text-content-muted">TAT</dt>
                  <dd>{step.tatDays}d budget{step.tatPercent != null && ` · ${Math.round(step.tatPercent)}% used`}</dd>
                  {step.status === 'BLOCKED' && step.note && (
                    <>
                      <dt className="text-content-muted">Hold reason</dt>
                      <dd>{step.note}</dd>
                    </>
                  )}
                </dl>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>

        {!isLast && (
          <span
            aria-hidden="true"
            className={cn('mx-1 h-0.5 w-4 shrink-0 rounded-full', treatment.connector)}
            data-testid="journey-ribbon-connector"
          />
        )}
      </div>
    )
  },
)

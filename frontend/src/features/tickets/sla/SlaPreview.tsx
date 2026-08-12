import { format, parseISO } from 'date-fns'
import { AlertTriangle, CalendarClock } from 'lucide-react'

import { SlaSource } from '@/api/generated/model/slaSource'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import type { PlannedCloseDateState } from './usePlannedCloseDate'

/** Matches the detail page's `DATE_FORMAT`, with the time the SLA clock needs. */
const DATE_TIME_FORMAT = 'd MMM yyyy, HH:mm'

/**
 * Where the figure came from, in the words the person reading it would use.
 *
 * Prose rather than the enum, because the enum is a storage detail and
 * "ORG_DEFAULT" on a form tells a support agent nothing. The wording says which
 * master to go and edit if the number is wrong, which is the only action any of
 * these labels can lead to.
 */
const SOURCE_LABEL: Record<SlaSource, string> = {
  [SlaSource.PROJECT_TASK_TYPE]: "this project's policy for this task type and priority",
  [SlaSource.PROJECT_LEVEL]: "this project's default for this priority",
  [SlaSource.ORG_DEFAULT]: 'the organisation-wide default for this priority',
  [SlaSource.PRIORITY_DEFAULT]: 'the priority master — no SLA policy covers this combination',
  [SlaSource.TASK_TYPE_DEFAULT]: 'the task type master — no SLA policy covers this combination',
  [SlaSource.NONE]: 'nothing',
}

/** "16 working hours", "0.5 working hours", "1 working hour". */
function workingHours(hours: number): string {
  const rounded = Math.round(hours * 100) / 100
  return `${rounded} working ${rounded === 1 ? 'hour' : 'hours'}`
}

export interface SlaPreviewProps extends PlannedCloseDateState {
  /**
   * The date the user typed over the top, if any — PM and Admin only, per §7.5.
   * When set, the computed date is shown as the alternative rather than as the
   * answer, because the form is going to send theirs.
   */
  overrideValue?: string
  /** Offered only alongside an override; omitted, no reset button renders. */
  onUseComputed?: (isoValue: string) => void
  className?: string
}

/**
 * C-012 · the inline planned close date, shown **before the user commits**.
 *
 * §4B.1's rule is that changing the priority recomputes and previews the date
 * while the form is still being filled in — the whole point being that a
 * support agent picking Critical can see it means "by Monday 11:00" before
 * committing someone else to it.
 *
 * Three states are worth spelling out because each one is a different fact:
 *
 * - **A date** — with the target in working hours and which rung produced it, so
 *   the number is checkable rather than merely authoritative.
 * - **No SLA at all** — a warning, not a blank. `plannedCloseDate: null` takes
 *   the ticket out of the breach sweep, the delayed KPI and Due Today; a blank
 *   field reads as "not computed yet" and hides that entirely.
 * - **Could not be reached** — muted, and explicitly non-blocking. The preview
 *   is advisory; the server computes the real date on save either way.
 */
export function SlaPreview({
  preview,
  isPending,
  isError,
  isReady,
  overrideValue,
  onUseComputed,
  className,
}: SlaPreviewProps) {
  const base = cn('rounded-control border px-3 py-2.5 text-caption', className)

  if (!isReady) {
    return (
      <p className={cn(base, 'border-dashed border-border bg-subtle text-content-muted')}>
        Pick a project and a priority to see the planned close date.
      </p>
    )
  }

  if (isPending) {
    return (
      <div className={cn(base, 'border-border bg-subtle')} role="status" aria-label="Computing the planned close date">
        <Skeleton className="h-4 w-56" />
      </div>
    )
  }

  if (isError || !preview) {
    return (
      <p className={cn(base, 'border-dashed border-border bg-subtle text-content-muted')}>
        The planned close date could not be previewed. It is still computed on save.
      </p>
    )
  }

  if (preview.source === SlaSource.NONE || !preview.plannedCloseDate) {
    return (
      <div
        className={cn(base, 'flex gap-2 border-warning/40 bg-level-high-soft text-warning-text')}
        role="status"
      >
        <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden />
        <span>
          No SLA target covers this project, task type and priority, so this ticket will have{' '}
          <strong className="font-medium">no planned close date</strong> — it will not appear in delay
          reports or the Due Today view. Set one in the project&apos;s SLA matrix, or enter a date below.
        </span>
      </div>
    )
  }

  const computed = parseISO(preview.plannedCloseDate)
  const isOverridden = Boolean(overrideValue)

  return (
    <div
      className={cn(base, 'border-border bg-subtle text-content-muted')}
      // Polite, not assertive: this updates on every priority change, and an
      // assertive region would interrupt a screen-reader user mid-field.
      role="status"
      aria-live="polite"
    >
      <div className="flex items-start gap-2">
        <CalendarClock className="mt-0.5 h-4 w-4 shrink-0 text-content-muted" aria-hidden />
        <div className="min-w-0">
          <p className="text-content">
            {isOverridden ? 'The SLA would give ' : 'Planned close date '}
            <strong className="font-medium">{format(computed, DATE_TIME_FORMAT)}</strong>
            {isOverridden && ' — your date will be used instead'}
          </p>
          <p className="mt-0.5">
            {preview.resolutionHrs != null && <>{workingHours(preview.resolutionHrs)} from </>}
            {SOURCE_LABEL[preview.source]}. Weekends, holidays and approved leave are taken out of the
            clock.
          </p>
          {preview.firstResponseDue && (
            <p className="mt-0.5">
              First response due {format(parseISO(preview.firstResponseDue), DATE_TIME_FORMAT)}
              {preview.responseHrs != null && <> · {workingHours(preview.responseHrs)}</>}
            </p>
          )}
        </div>
      </div>
      {isOverridden && onUseComputed && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="mt-2 h-7 px-2"
          onClick={() => onUseComputed(toDateTimeLocal(computed))}
        >
          Use the computed date
        </Button>
      )}
    </div>
  )
}

/**
 * An ISO instant back into what a `datetime-local` input accepts.
 *
 * Local components rather than a `toISOString().slice(0, 16)`, which would hand
 * the input a UTC wall-clock reading and shift the date the user sees by their
 * offset — 05:00 IST on the wire becoming 23:30 the previous day in the box.
 */
function toDateTimeLocal(date: Date): string {
  return format(date, "yyyy-MM-dd'T'HH:mm")
}

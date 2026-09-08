import type { ObJourneyStepView, UserRef } from '@/api/generated/model'
import { format, parseISO } from 'date-fns'

import { Chip } from '@/components/ui/chip'
import { ragLabel, ragVariant } from './journeyStrip'
import type { ResolveUser } from './ribbonSteps'

/**
 * C-110 · the panel under the expanded ribbon — §9's "expanded: ribbon + step
 * panel", for the step the reader selected.
 *
 * ## Read-only, and that is the task boundary rather than an omission
 *
 * **OB-06, the step *update* panel, is C-111** — start/complete/block/waiting/
 * resume, the task-list gate, communications and history. That task's own
 * dependency row names this one, so the sequence is deliberate: this screen
 * has to be able to *show* a step before the panel that changes it is worth
 * building, and shipping the actions here would mean C-111 rewriting a panel
 * rather than filling one in.
 *
 * What that boundary must not do is put a dead control on the page. There are
 * no disabled buttons here and no "coming soon" — the same call B-121 made for
 * the dashboard tiles, whose reasoning applies exactly: "a dead control teaches
 * the user the board is broken". C-111 adds an action row under this summary;
 * nothing here has to be rebuilt for it.
 */
function formatDate(value: string | null | undefined): string | null {
  if (!value) return null
  const parsed = parseISO(value)
  return Number.isNaN(parsed.getTime()) ? null : format(parsed, 'd MMM yyyy')
}

const STATUS_WORDS: Record<string, string> = {
  PENDING: 'Pending',
  IN_PROGRESS: 'In progress',
  BLOCKED: 'Blocked',
  WAITING_ON_CLIENT: 'Waiting on client',
  DONE: 'Done',
  SKIPPED: 'Skipped',
}

export function JourneyStepPanel({
  step,
  resolveUser,
}: {
  step: ObJourneyStepView
  resolveUser?: ResolveUser
}) {
  const owner: UserRef | undefined = step.ownerUserId != null ? resolveUser?.(step.ownerUserId) : undefined
  const backup: UserRef | undefined =
    step.backupOwnerUserId != null ? resolveUser?.(step.backupOwnerUserId) : undefined

  return (
    <div className="mt-4 rounded-card border border-border bg-app p-4" data-testid="journey-step-panel">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="m-0 text-sm font-semibold text-content">
          {step.sequence}. {step.name}
        </h4>
        <Chip variant="neutral">{STATUS_WORDS[step.status] ?? step.status}</Chip>
        {/*
          Health only where there is health to report. `ObRag` is explicit that
          null is not a fourth colour but the absence of anything running to
          colour, so a "Not started" chip on every pending step would be four
          words asserting what the status chip beside it already said.
        */}
        {step.rag && <Chip variant={ragVariant(step.rag)}>{ragLabel(step.rag)}</Chip>}
        {step.requiresSignoff && <Chip variant="info">Sign-off required</Chip>}
      </div>

      {step.description && <p className="mt-2 text-sm text-content-muted">{step.description}</p>}

      <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-content-muted">Owner</dt>
        <dd className="text-content">
          {owner?.displayName ?? (step.ownerUserId != null ? 'Assigned' : 'Unassigned')}
          {backup && <span className="text-content-muted"> · backup {backup.displayName}</span>}
        </dd>

        <dt className="text-content-muted">TAT budget</dt>
        <dd className="text-content">{step.tatDays ?? 0} working days</dd>

        {/*
          `dueAt` is the working-calendar deadline C-105 computed, not
          `startedAt + tatDays`. Printing it beside the budget rather than
          instead of it is the point: the two differ by every weekend, holiday
          and pause in between, and a reader comparing them is reading the
          calendar working.
        */}
        {formatDate(step.dueAt) && (
          <>
            <dt className="text-content-muted">Due</dt>
            <dd className="text-content">{formatDate(step.dueAt)}</dd>
          </>
        )}

        {formatDate(step.startedAt) && (
          <>
            <dt className="text-content-muted">Started</dt>
            <dd className="text-content">{formatDate(step.startedAt)}</dd>
          </>
        )}

        {formatDate(step.finishedAt) && (
          <>
            <dt className="text-content-muted">Finished</dt>
            <dd className="text-content">{formatDate(step.finishedAt)}</dd>
          </>
        )}

        {step.status === 'BLOCKED' && step.blockedNote && (
          <>
            <dt className="text-content-muted">Hold reason</dt>
            <dd className="text-content">{step.blockedNote}</dd>
          </>
        )}

        {/*
          A waiver is the field a later dispute turns on — `ObClientPrereqTask`
          makes the same point about its own `skipReason`. It is never elided
          behind a tooltip.
        */}
        {step.status === 'SKIPPED' && (
          <>
            <dt className="text-content-muted">Waived because</dt>
            <dd className="text-content">{step.skipReason ?? '—'}</dd>
          </>
        )}
      </dl>
    </div>
  )
}

import type { ObJourneyStepView, UserRef } from '@/api/generated/model'
import { format, parseISO } from 'date-fns'

import { useGetMe } from '@/api/generated/auth/auth'
import { useGetObJourneyStep } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { Chip } from '@/components/ui/chip'
import { ragLabel, ragVariant, type JourneyHold } from './journeyStrip'
import type { ResolveUser } from './ribbonSteps'
import { StepActionBar } from './StepActionBar'
import { StepTaskList } from './StepTaskList'
import { completionGate, mayActOnStep } from './stepActions'

/**
 * OB-06 — the step panel under the expanded ribbon (§9's "expanded: ribbon +
 * step panel"), for the step the reader selected.
 *
 * C-110 built this read-only and said C-111 would "add an action row under
 * this summary; nothing here has to be rebuilt for it". **C-111 is this
 * change**, and that prediction held: the summary below is untouched, and what
 * is new is a second read, a task list and an action bar.
 *
 * ## The second read, and why the panel makes it rather than the accordion
 *
 * `getObJourney` returns `ObJourneyStepView` for every step — enough for the
 * ribbon, and deliberately without the Task List or the documents. Those come
 * from `GET /onboarding/journey-steps/{stepId}`, one step at a time, which is
 * the read this panel fires for **the selected step only**. A journey of eight
 * services does not fetch eight task lists to draw one panel — the same
 * argument the contract makes for splitting the strip from the ribbon, applied
 * one level down.
 *
 * Until it arrives the summary renders from the view it already has. There is
 * no spinner over the whole panel: the fields above the task list are already
 * known and blanking them to load a checklist would be a regression on a
 * screen that was previously instant.
 *
 * ## Who may act, decided once
 *
 * `mayActOnStep` is computed here and passed to both children, so the task
 * list's checkboxes and the action bar's buttons cannot disagree about
 * permission — the failure mode where a step is tickable but not completable,
 * or the reverse.
 *
 * ## What OB-06 does not include
 *
 * **Communications and history are C-112**, whose backlog line is explicit
 * that it owns "per-step, **plus** the client-level stitched view". The
 * endpoints exist (`listObStepCommunications`, `listObStepHistory`) and are
 * deliberately not called here: splitting the per-step timeline from the
 * stitched one across two tasks would mean building the same component twice.
 *
 * **Skip is absent, and that is a gap in the caller's identity rather than a
 * choice.** `POST /skip` requires the onboarding module role `OB_MANAGER` or
 * `OB_ADMIN` (`NotAnOnboardingModeratorException`, 403), and **`Me` carries no
 * module role** — `MeAllOf` has `permissions`, `projectIds` and `reporteeIds`,
 * none of which say anything about `ONBOARDING`. The grants list that does is
 * Admin-only. So there is no honest way for this panel to decide who should
 * see the button, and rendering it for everybody would put a 403 behind a
 * control most callers can never use. It lands when `/me` carries the module
 * claim (A-111's guard is built; nothing populates the caller's own view of
 * it).
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
  hold = null,
}: {
  step: ObJourneyStepView
  resolveUser?: ResolveUser
  /** The journey's own hold, from `journeyHold(journey)`. Decides whether
   * `start` is offered — a `PENDING` step on a locked or held journey is not
   * startable, and the server answers `journey-not-open` for two quite
   * different reasons this separates. */
  hold?: JourneyHold
}) {
  const owner: UserRef | undefined = step.ownerUserId != null ? resolveUser?.(step.ownerUserId) : undefined
  const backup: UserRef | undefined =
    step.backupOwnerUserId != null ? resolveUser?.(step.backupOwnerUserId) : undefined

  const me = useGetMe()
  const detail = useGetObJourneyStep(step.id)
  const stepDetail = detail.data?.data

  const canAct = mayActOnStep(step, me.data?.data?.id)
  const gate = completionGate(stepDetail?.items, stepDetail?.docs)

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

      <StepTaskList
        stepId={step.id}
        items={stepDetail?.items ?? []}
        docs={stepDetail?.docs ?? []}
        canEdit={canAct}
      />

      <StepActionBar step={step} hold={hold} gate={gate} canAct={canAct} />

      {/*
        Whose step it is, for everybody who cannot act on it. The action bar
        renders nothing at all for them — see its docstring on why absent beats
        disabled — so without this line the panel would simply be quiet about
        why there is nothing to press, and "who do I chase" is the question a
        reader on somebody else's step actually has.
      */}
      {!canAct && owner && (
        <p className="mt-4 border-t border-border pt-3 text-caption text-content-muted">
          {owner.displayName} owns this service
          {backup && `, with ${backup.displayName} as backup`}. Only they can update it.
        </p>
      )}
    </div>
  )
}

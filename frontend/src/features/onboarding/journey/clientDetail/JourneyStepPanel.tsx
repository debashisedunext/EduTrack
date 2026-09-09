import type { ObJourneyStepView, UserRef } from '@/api/generated/model'
import { format, parseISO } from 'date-fns'

import { useGetMe } from '@/api/generated/auth/auth'
import { useGetObJourneyStep } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { Chip } from '@/components/ui/chip'
import { StepCommunicationsPanel } from '../communications/StepCommunicationsPanel'
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
 * **History is still absent.** `listObStepHistory` exists and nothing calls
 * it; the append-only narrative of a step's state changes has no screen yet.
 * C-112 took the communications half of that line — see below — and left this
 * one, because a history tab is a different read with a different shape and
 * folding it into the communications component would be building the wrong
 * thing to save a file.
 *
 * ## C-112 · the communications timeline, under everything else
 *
 * Plan §6's per-step half, and it sits **below the action bar** rather than
 * between the summary and the actions. The order is the argument the whole
 * panel is built on: what the service *is*, then what you can *do* to it,
 * then what has been *said* about it. A reader who came here to act should
 * not have to scroll a conversation to reach the button.
 *
 * `StepCommunicationsPanel` is self-contained — its own read, its own empty
 * state, its own form — so this file stays a renderer of
 * `ObJourneyStepView` and gains no third data fetch of its own.
 *
 * **It is not gated on `canAct`,** unlike the task list and the action bar.
 * Those two change the service, and only its owner or backup may. Recording
 * that a call happened is not changing the service: a PM or a manager
 * chasing a client has every reason to write one down on a step they do not
 * own, and the server scopes that read and write by journey visibility
 * rather than by step ownership. Hiding the form from them would be the UI
 * inventing a rule the service does not have.
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
  totalSteps,
  resolveUser,
  hold = null,
  obClientId,
}: {
  step: ObJourneyStepView
  /** How many services the journey has — the mockup's "Service i of n"
   * eyebrow. Optional: a caller rendering one step outside a journey (a
   * story, a preview) has no n, and the eyebrow simply drops the total. */
  totalSteps?: number
  resolveUser?: ResolveUser
  /** The journey's own hold, from `journeyHold(journey)`. Decides whether
   * `start` is offered — a `PENDING` step on a locked or held journey is not
   * startable, and the server answers `journey-not-open` for two quite
   * different reasons this separates. */
  hold?: JourneyHold
  /**
   * C-112 · lets an entry recorded here also refresh the client-level stitched
   * view. Optional, because a caller that renders a step panel outside OB-05
   * (a preview, a story) has no client in hand and the per-step timeline is
   * still correct without one.
   */
  obClientId?: number
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
      {/* The mockup's `stepCard` eyebrow — "Service i of n". */}
      <p className="m-0 mb-0.5 text-caption font-semibold uppercase tracking-wide text-content-muted">
        Service {step.sequence}
        {totalSteps != null && ` of ${totalSteps}`}
      </p>
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

      {/* The mockup `stepCard`'s meta tiles — an auto-fit grid of eyebrowed
          facts rather than a two-column definition list. "TAT used %" is not
          here: no read on this panel carries a per-step percentage (the
          server computes RAG instead), and a figure derived locally could
          disagree with the tile colours beside it. */}
      <dl className="mt-3 grid gap-x-4 gap-y-3 text-sm [grid-template-columns:repeat(auto-fit,minmax(140px,1fr))]">
        <div>
          <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Responsible</dt>
          <dd className="m-0 mt-1 text-content">
            {owner?.displayName ?? (step.ownerUserId != null ? 'Assigned' : 'Unassigned')}
            {backup && <span className="text-content-muted"> · backup {backup.displayName}</span>}
          </dd>
        </div>

        <div>
          <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">TAT</dt>
          <dd className="m-0 mt-1 text-content">{step.tatDays ?? 0} working day{(step.tatDays ?? 0) === 1 ? '' : 's'}</dd>
        </div>

        {/*
          `dueAt` is the working-calendar deadline C-105 computed, not
          `startedAt + tatDays`. Printing it beside the budget rather than
          instead of it is the point: the two differ by every weekend, holiday
          and pause in between, and a reader comparing them is reading the
          calendar working.
        */}
        {formatDate(step.dueAt) && (
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Due</dt>
            <dd className="m-0 mt-1 text-content">
              {step.status === 'DONE' && formatDate(step.finishedAt)
                ? `Completed ${formatDate(step.finishedAt)}`
                : formatDate(step.dueAt)}
            </dd>
          </div>
        )}

        {formatDate(step.startedAt) && (
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Started</dt>
            <dd className="m-0 mt-1 text-content">{formatDate(step.startedAt)}</dd>
          </div>
        )}

        {step.status === 'DONE' && formatDate(step.finishedAt) && (
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Finished</dt>
            <dd className="m-0 mt-1 text-content">{formatDate(step.finishedAt)}</dd>
          </div>
        )}

        {stepDetail && stepDetail.items.length > 0 && (
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Task list</dt>
            <dd className="m-0 mt-1 text-content">
              ☑ {stepDetail.items.filter((i) => i.isDone).length}/{stepDetail.items.length} done
            </dd>
          </div>
        )}

        {/*
          A waiver is the field a later dispute turns on — `ObClientPrereqTask`
          makes the same point about its own `skipReason`. It is never elided
          behind a tooltip.
        */}
        {step.status === 'SKIPPED' && (
          <div>
            <dt className="text-caption font-semibold uppercase tracking-wide text-content-muted">Waived because</dt>
            <dd className="m-0 mt-1 text-content">{step.skipReason ?? '—'}</dd>
          </div>
        )}
      </dl>

      {/* The mockup's `err` banner — the hold reason as a statement across
          the card, not one more row in the fact grid. */}
      {step.status === 'BLOCKED' && step.blockedNote && (
        <p className="m-0 mt-3 rounded-control bg-level-critical-soft px-3 py-2 text-sm text-danger-text">
          ⛔ Blocked: {step.blockedNote}
        </p>
      )}

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

      {/* Plan §6, per-step, and last for the reason the class docstring gives.
          The stitched client-level view is the same entries seen from the
          other end — `ClientCommunicationsPanel`. */}
      <StepCommunicationsPanel stepId={step.id} obClientId={obClientId} />
    </div>
  )
}

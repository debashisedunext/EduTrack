import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import type { UserRef } from '@/api/generated/model/userRef'
import {
  getGetObJourneyQueryKey,
  useBlockObJourneyStep,
  useCompleteObJourneyStep,
  useMarkObJourneyStepWaitingOnClient,
  useResumeObJourneyStep,
  useStartObJourneyStep,
  useUpdateObJourneyStep,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { BLOCK_REASONS } from '@/features/onboarding/journey/clientDetail/blockReasons'
import { cn } from '@/lib/utils'

import type { ProjectTask } from './useProjectTasks'

/**
 * The five things that can happen to a task, and who may do them.
 *
 * <h2>Complete and Waiting on client are the owner's; Reassign is not</h2>
 *
 * <p>`ObJourneyStepLifecycleService.update` — the PATCH behind Reassign — is
 * gated by `requireModerator`, **not** `requireOwnership`, and its javadoc says
 * why: "reassigning a step off its own owner, or re-planning its TAT, is
 * exactly the kind of override a step's owner should not be able to grant
 * themselves." So this bar has two permissions in it, not one.
 *
 * <p>The page cannot yet tell an OB Admin from a Sales user, because `/me`
 * carries no onboarding module role — the same gap that keeps Skip off the step
 * panel. Until it does, Reassign is rendered and disabled for everyone, naming
 * the role that would be allowed, rather than offered to a caller the server
 * will refuse.
 *
 * <h2>Blocked and Waiting on client are not interchangeable</h2>
 *
 * <p>Plan §5.7: an internal `BLOCKED` keeps the TAT clock **running** and
 * charges the delay to us; `WAITING_ON_CLIENT` **pauses** it and attributes the
 * wait to the client. Picking the wrong one moves a breach onto the wrong
 * party's account, so both controls say which is which, and every reason code
 * offered is an internal one.
 *
 * <h2>Attach has no route yet</h2>
 *
 * <p>`ob_attachments.step_id` exists, `ObAttachmentOwner.STEP` exists, and the
 * completion gate already counts a task's clean attachments to decide whether
 * its required documents are satisfied — but the only upload controllers are
 * client-level and the portal's prerequisite one. The control is rendered
 * disabled and says so, rather than being left off a screen the design calls
 * for; it needs `POST /onboarding/journey-steps/{stepId}/attachments`.
 */
export interface ObTaskTransitionsProps {
  task: ProjectTask
  users: readonly UserRef[]
  yours: boolean
  /** What is stopping Complete, already computed by the panel above. */
  blockers: readonly string[]
}

const INPUT =
  'min-w-0 flex-1 rounded-[6px] border border-border bg-surface px-2.5 py-1.5 text-caption text-content ' +
  'placeholder:text-content-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ' +
  'disabled:cursor-not-allowed disabled:bg-subtle'

const BTN =
  'shrink-0 whitespace-nowrap rounded-control px-3.5 py-1.5 text-caption font-semibold ' +
  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ' +
  'disabled:cursor-not-allowed disabled:border-border disabled:bg-subtle disabled:text-content-muted'

export function ObTaskTransitions({ task, users, yours, blockers }: ObTaskTransitionsProps) {
  const queryClient = useQueryClient()
  const [reasonCode, setReasonCode] = React.useState(BLOCK_REASONS[0]?.code ?? '')
  const [note, setNote] = React.useState('')
  const [assignee, setAssignee] = React.useState<number | ''>(task.ownerUserId ?? '')

  const refresh = {
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: getGetObJourneyQueryKey(task.journeyId) })
    },
  }
  const start = useStartObJourneyStep({ mutation: refresh })
  const complete = useCompleteObJourneyStep({ mutation: refresh })
  const waiting = useMarkObJourneyStepWaitingOnClient({ mutation: refresh })
  const resume = useResumeObJourneyStep({ mutation: refresh })
  const block = useBlockObJourneyStep({ mutation: refresh })
  const reassign = useUpdateObJourneyStep({ mutation: refresh })

  const busy =
    start.isPending || complete.isPending || waiting.isPending || resume.isPending ||
    block.isPending || reassign.isPending

  const terminal = task.status === 'DONE' || task.status === 'SKIPPED'
  const paused = task.status === 'WAITING_ON_CLIENT' || task.status === 'BLOCKED'
  const ownerLabel = users.find((u) => u.id === task.ownerUserId)?.displayName ?? 'the task owner'
  const notYours = `Only ${ownerLabel} can do this`

  return (
    <div className="flex flex-col gap-1.5">
      {/* The two state changes an owner makes most. A task nobody has started
          gets Start instead of Complete — offering Complete on a PENDING task
          is offering a transition the server refuses. */}
      <div className="flex flex-wrap items-center gap-1.5">
        {task.status === 'PENDING' ? (
          <button
            type="button"
            disabled={!yours || busy}
            title={yours ? 'Start this task and run its TAT clock' : notYours}
            onClick={() => start.mutate({ stepId: task.id })}
            className={cn(BTN, 'bg-primary text-white')}
          >
            ▶ Start task
          </button>
        ) : (
          <button
            type="button"
            disabled={!yours || busy || terminal || blockers.length > 0}
            title={
              !yours ? notYours
                : terminal ? 'This task is already closed'
                  : blockers.length ? `Outstanding: ${blockers.join(', ')}`
                    : 'Complete this task'
            }
            onClick={() => complete.mutate({ stepId: task.id })}
            className={cn(BTN, 'bg-primary text-white')}
          >
            ✓ Mark complete
          </button>
        )}

        {paused ? (
          <button
            type="button"
            disabled={!yours || busy}
            title={yours ? 'Put this task back in progress and restart its clock' : notYours}
            onClick={() => resume.mutate({ stepId: task.id })}
            className={cn(BTN, 'border border-primary bg-surface text-primary')}
          >
            ▶ Resume
          </button>
        ) : (
          <button
            type="button"
            disabled={!yours || busy || terminal}
            title={
              yours
                ? 'Pauses the TAT clock and attributes the wait to the client — unlike Block, which keeps it running'
                : notYours
            }
            onClick={() => waiting.mutate({ stepId: task.id })}
            className={cn(BTN, 'border border-primary bg-surface text-primary')}
          >
            ⏸ Waiting on client
          </button>
        )}

        {yours && blockers.length > 0 && (
          <span className="text-caption text-danger-text">Outstanding: {blockers.join(', ')}.</span>
        )}
      </div>

      {/* Block, whose reason the server requires. Every code offered is an
          internal one — see `blockReasons`. */}
      <div className="flex flex-wrap items-center gap-1.5">
        <select
          value={reasonCode}
          disabled={!yours || busy || terminal}
          aria-label="Block reason"
          onChange={(e) => setReasonCode(e.target.value)}
          className={cn(INPUT, 'max-w-[15rem] flex-none')}
        >
          {BLOCK_REASONS.map((r) => (
            <option key={r.code} value={r.code} title={r.hint}>
              {r.label}
            </option>
          ))}
        </select>
        <input
          type="text"
          value={note}
          disabled={!yours || busy || terminal}
          aria-label="Block note"
          placeholder="Block note (optional) — e.g. awaiting vendor assets"
          onChange={(e) => setNote(e.target.value)}
          className={INPUT}
        />
        <button
          type="button"
          disabled={!yours || busy || terminal || !reasonCode}
          title={
            yours
              ? 'Keeps the TAT clock running and charges the delay to us — not the same as Waiting on client'
              : notYours
          }
          onClick={() => block.mutate({ stepId: task.id, data: { reasonCode, note: note.trim() || undefined } })}
          className={cn(BTN, 'border border-danger bg-surface text-danger-text')}
        >
          ⛔ Block
        </button>
      </div>

      {/* Reassign — a moderator's override, not the owner's. */}
      <div className="flex flex-wrap items-center gap-1.5">
        <select
          value={assignee}
          disabled
          aria-label="Reassign to"
          onChange={(e) => setAssignee(e.target.value === '' ? '' : Number(e.target.value))}
          className={INPUT}
        >
          <option value="">Unassigned</option>
          {users.map((u) => (
            <option key={u.id} value={u.id}>
              {u.displayName}
            </option>
          ))}
        </select>
        <button
          type="button"
          disabled
          title="Reassigning a task off its owner is an OB Manager or OB Admin action, and /me does not yet carry the module role that would prove it"
          onClick={() =>
            reassign.mutate({
              stepId: task.id,
              data: { ownerUserId: assignee === '' ? null : assignee },
            })
          }
          className={cn(BTN, 'border border-border bg-surface text-content-muted')}
        >
          Reassign
        </button>
      </div>

      {/* Attach — no route yet; see this file's own note. */}
      <div className="flex flex-wrap items-center gap-1.5">
        <input
          type="text"
          disabled
          aria-label="Attach a document"
          placeholder="Attach a document… e.g. Validation report"
          className={INPUT}
        />
        <button
          type="button"
          disabled
          title="Uploading against a task needs POST /onboarding/journey-steps/{stepId}/attachments, which does not exist yet"
          className={cn(BTN, 'border border-border bg-surface text-content-muted')}
        >
          📎 Attach
        </button>
      </div>

      {(complete.isError || block.isError || waiting.isError || start.isError) && (
        <p className="text-caption text-danger-text" role="alert">
          That transition was refused. The server may have moved this task since the page loaded —
          reload and try again.
        </p>
      )}
    </div>
  )
}

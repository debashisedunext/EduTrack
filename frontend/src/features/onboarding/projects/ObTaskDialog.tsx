import type * as React from 'react'

import type { UserRef } from '@/api/generated/model/userRef'
import { Modal, ModalContent, ModalTitle } from '@/components/ui/modal'
import { Skeleton } from '@/components/ui/skeleton'

import { TaskStatusPill } from './TaskStatusPill'
import { ObProjectTaskPanel } from './ObProjectTaskPanel'
import { formatDue, overdue } from './taskDates'
import { Avatar } from './taskFacts'
import type { ProjectTask } from './useProjectTasks'

/**
 * One task, as a popup: where it sits, its name and state, its vital signs,
 * then everything that can be done to it.
 *
 * <h2>Two screens, one popup</h2>
 *
 * <p>The project page opens it from a Step's task list; My Tasks opens it from
 * the queue. Both hand it the same `ProjectTask` — the project page's from its
 * fold, My Tasks' from `focusedTask` over the same journey read — so the
 * check list, the facts and the action bar cannot differ by screen.
 *
 * <p>Wider than the app's default modal — the check list puts a label, a
 * remark field and two answers on one row, and at the default width the
 * remark had no room — and taller than its content, so a short list does not
 * leave a cramped box. It scrolls inside itself rather than growing past the
 * viewport, so the action bar at the bottom is always reachable on a task with
 * a long list.
 *
 * <h2>Closed by the reader, never by an action</h2>
 *
 * <p>The ×, Escape, or the scrim. The task's own dialogs — Block, Reassign,
 * Attach, Communication — open over this one and hand back to it, and a
 * transition re-renders it at the new status rather than dismissing it:
 * somebody answering four items and then marking complete should not have to
 * find the row four times.
 *
 * <h2>The facts band is here, not on the row</h2>
 *
 * <p>It came off the panel when tasks opened inline under their rows, because
 * the row already carried them. In a popup the row is behind a scrim, so the
 * band is where a reader looking at the check list can see who owns it and
 * when it is due. TAT used has a home here too — it never fitted on a row.
 */
export interface ObTaskDialogProps {
  /** Null closes the dialog — unless `pending` or `problem` say otherwise. */
  task: ProjectTask | null
  /** "SIS · Data Migration · Task 1 of 2" — where the task sits. */
  crumb: string
  users: readonly UserRef[]
  /** Owner or backup owner — the rule the server enforces on writes. */
  yours: boolean
  /**
   * Whether the viewer may record review verdicts — passed straight through
   * to {@link ObProjectTaskPanel}, which is where it means anything.
   *
   * <p>Threaded rather than read from `/me` here, on `yours`' own precedent:
   * every caller of this dialog already knows who is looking, and a component
   * that asks again is a second answer to one question.
   */
  canReview?: boolean
  /** This task's open client escalation, where it has one. */
  escalation?: { id: number; raisedBy: string; raisedAt: string; note: string } | null
  /**
   * The task is on its way — My Tasks reads the journey after the row is
   * picked. The popup opens at once with a placeholder rather than appearing
   * a beat after the click.
   */
  pending?: boolean
  /** Why there is no task to show, when the read came back without one. */
  problem?: string | null
  onClose: () => void
}

export function ObTaskDialog({
  task,
  crumb,
  users,
  yours,
  canReview = false,
  escalation = null,
  pending = false,
  problem = null,
  onClose,
}: ObTaskDialogProps) {
  const open = task != null || pending || problem != null
  /*
    Rows a review actually sent back — `rowState`, not `reviewState`. A
    reviewer who has pressed Rejected and not yet released the row has changed
    nothing its implementor may see, and a title announcing a verdict still
    being drafted would be the screen leaking a decision mid-thought. The panel
    below counts the same field for the same reason.
  */
  const cameBack = task?.items.filter((i) => i.rowState === 'REJECTED').length ?? 0

  return (
    <Modal
      open={open}
      onOpenChange={(next) => {
        if (!next) onClose()
      }}
    >
      {open && (
        <ModalContent
          data-testid="ob-task-dialog"
          className="flex max-h-[calc(100vh-4rem)] min-h-[36rem] w-[calc(100vw-2rem)] max-w-[78rem] flex-col gap-4 overflow-y-auto p-5"
        >
          {task ? (
            <>
              <div className="flex flex-col gap-1 pr-8">
                <p className="m-0 text-caption text-content-muted">{crumb}</p>
                <div className="flex flex-wrap items-center gap-2">
                  <ModalTitle className="m-0 text-h2 text-content">{task.name}</ModalTitle>
                  <TaskStatusPill status={task.status} />
                  {/*
                    A returned task says so beside its status, not only in the
                    banner below. The status pill reads "In progress" on a task
                    that has just come back — true, and the least useful of the
                    two facts: the reader's question on opening it is what
                    happened, and the pill answers what the state machine calls
                    it. The banner explains; this is what the eye lands on first.
                  */}
                  {cameBack > 0 && (
                    <span
                      data-testid="ob-task-came-back"
                      className="rounded-chip border border-danger bg-danger-soft px-2 py-px text-[10.5px] font-semibold text-danger-text"
                    >
                      <span aria-hidden="true">↻</span>{' '}
                      {cameBack === 1 ? '1 row came back' : `${cameBack} rows came back`}
                    </span>
                  )}
                  {task.requiresSignoff && (
                    <span className="rounded-chip bg-level-high-soft px-2 py-px text-[10.5px] font-semibold text-warning-text">
                      Client sign-off
                    </span>
                  )}
                </div>
              </div>

              <ObTaskFacts task={task} users={users} />

              <ObProjectTaskPanel
                task={task}
                users={users}
                yours={yours}
                canReview={canReview}
                escalation={escalation}
              />
            </>
          ) : problem ? (
            <>
              <ModalTitle className="m-0 text-h3 text-content">Task</ModalTitle>
              <p className="m-0 text-sm text-content-muted">{problem}</p>
            </>
          ) : (
            <>
              <ModalTitle className="sr-only">Loading task</ModalTitle>
              <Skeleton className="h-6 w-1/3" />
              <Skeleton className="h-16 w-full" />
              <Skeleton className="h-40 w-full" />
            </>
          )}
        </ModalContent>
      )}
    </Modal>
  )
}

/**
 * The task's vital signs — Assignee, TAT, Due, TAT used — as one row of facts.
 *
 * <p>Drawn by the popup and by the standalone My Tasks page, so a task reads
 * the same whichever way somebody reached it.
 */
export function ObTaskFacts({ task, users }: { task: ProjectTask; users: readonly UserRef[] }) {
  const owner =
    task.ownerUserId == null
      ? null
      : (users.find((u) => u.id === task.ownerUserId)?.displayName ?? `User ${task.ownerUserId}`)
  const missed = overdue(task.dueAt)

  return (
    <dl className="m-0 grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-4">
      <Fact label="Assignee">
        {owner ? (
          <span className="inline-flex flex-wrap items-center gap-1.5">
            <Avatar name={owner} />
            <span>{owner}</span>
            {task.ownerIsInherited && (
              <span
                title="No responsible on the module service — the project's implementor picks it up"
                className="rounded-chip border border-primary bg-surface px-1.5 text-[10.5px] font-medium text-primary"
              >
                project implementor
              </span>
            )}
          </span>
        ) : (
          <span className="text-danger-text">Nobody responsible</span>
        )}
      </Fact>
      <Fact label="TAT">{`${task.tatDays} working ${task.tatDays === 1 ? 'day' : 'days'}`}</Fact>
      <Fact label="Due">
        {task.dueAt ? (
          <span className={missed ? 'text-danger-text' : undefined}>
            {formatDue(task.dueAt)}
            {missed ? ' (missed)' : ''}
          </span>
        ) : (
          <span className="text-content-muted">Not started</span>
        )}
      </Fact>
      <Fact label="TAT used">
        {task.tatUsedPercent == null ? (
          <span className="text-content-muted">—</span>
        ) : (
          /*
            Whole percent. The server divides elapsed working minutes by the
            TAT and sends the quotient, so a perfectly ordinary task printed
            "17.666666666666668%" — seventeen significant figures of a number
            nobody reads past the first two, and the one fact on this band that
            made the popup look unfinished. Rounded for the eye only: the
            comparison against 100 below is still the exact figure, so a task
            at 100.4% is late on screen as well as in the data.
          */
          <span className={task.tatUsedPercent > 100 ? 'text-danger-text' : undefined}>
            {Math.round(task.tatUsedPercent)}%
          </span>
        )}
      </Fact>
    </dl>
  )
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-[10.5px] uppercase leading-4 tracking-wide text-content-muted">{label}</dt>
      <dd className="m-0 mt-0.5 text-sm font-semibold tabular-nums text-content">{children}</dd>
    </div>
  )
}

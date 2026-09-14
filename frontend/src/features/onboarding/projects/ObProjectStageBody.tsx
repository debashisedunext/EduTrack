import * as React from 'react'

import type { ObProjectStage } from '@/api/generated/model/obProjectStage'
import type { UserRef } from '@/api/generated/model/userRef'
import { cn } from '@/lib/utils'

import { ObProjectTaskPanel } from './ObProjectTaskPanel'
import { isMine, type ProjectTask } from './useProjectTasks'

/**
 * One implementation stage's tasks — the fourth level of the page, and the
 * disclosure that opens onto the fifth.
 *
 * <h2>The row is the disclosure, and it opens in place</h2>
 *
 * <p>Clicking a task expands its panel underneath it rather than navigating:
 * the checklist, the remark and the transitions are all the same piece of work,
 * and sending somebody to another screen to tick a box they are already looking
 * at is a round trip to answer a question this row raised. The whole row is the
 * control rather than a chevron, because a 9px glyph is a poor target.
 *
 * <h2>The collapsed row says what is inside</h2>
 *
 * <p>Answered count, TAT breach, escalation and the comment count ride on the
 * row itself, so a reader scanning ten tasks does not have to open each one to
 * find the two that need them.
 *
 * <h2>Your own work is lifted; nothing else is dimmed</h2>
 *
 * <p>A task you own gets a tinted ground, an indigo edge and a `Yours` pill. The
 * contrast comes from lifting one row rather than suppressing the rest — an
 * admin or a sales person reading this page needs to see what colleagues are
 * doing, which is the whole reason the stage ribbon above no longer locks.
 */
export interface ObProjectStageBodyProps {
  stage: ObProjectStage | undefined
  tasks: readonly ProjectTask[]
  meId: number | null | undefined
  users: readonly UserRef[]
  isPending: boolean
  /** More than one module service on this project, so rows name theirs. */
  showServiceName: boolean
  /**
   * This client's open escalations, keyed by the task they were raised against.
   * C-126 mirrors each onto the task's own timeline too; this is the banner.
   */
  escalations?: ReadonlyMap<number, { id: number; raisedBy: string; raisedAt: string; note: string }>
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: 'Not started',
  IN_PROGRESS: 'In progress',
  BLOCKED: 'Blocked',
  WAITING_ON_CLIENT: 'Waiting on client',
  DONE: 'Complete',
  SKIPPED: 'Waived',
}

const STATUS_PILL: Record<string, string> = {
  PENDING: 'bg-subtle text-content-muted',
  IN_PROGRESS: 'border border-primary bg-surface text-primary',
  BLOCKED: 'bg-danger-soft text-danger-text',
  WAITING_ON_CLIENT: 'bg-level-high-soft text-warning-text',
  DONE: 'bg-level-low-soft text-success-text',
  SKIPPED: 'bg-subtle text-content-muted',
}

const PILL = 'rounded-chip px-2 py-px text-[10.5px] font-semibold whitespace-nowrap'

function settled(task: ProjectTask): boolean {
  return task.status === 'DONE' || task.status === 'SKIPPED'
}

export function ObProjectStageBody({
  stage,
  tasks,
  meId,
  users,
  isPending,
  showServiceName,
  escalations,
}: ObProjectStageBodyProps) {
  /**
   * Which tasks are open, as a set.
   *
   * <p>A set rather than one id because a reader comparing two tasks in a stage
   * wants both, and keyed by task id rather than index so it survives the list
   * re-ordering under a refetch.
   */
  const [open, setOpen] = React.useState<ReadonlySet<number>>(() => new Set())

  const toggle = (taskId: number) =>
    setOpen((current) => {
      const next = new Set(current)
      if (next.has(taskId)) next.delete(taskId)
      else next.add(taskId)
      return next
    })

  const nameOf = React.useCallback(
    (userId: number | null | undefined) =>
      userId == null ? null : (users.find((u) => u.id === userId)?.displayName ?? null),
    [users],
  )

  if (!stage) return null

  const totalTat = tasks.reduce((sum, t) => sum + t.tatDays, 0)

  return (
    <section className="rounded-card border border-border bg-surface px-4 py-3 shadow-rest">
      <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1 border-b border-border pb-2.5">
        <h2 className="m-0 text-h3 text-content">{stage.name}</h2>
        <span className="text-caption tabular-nums text-content-muted">
          {tasks.length === 0
            ? 'Nothing scheduled in this stage'
            : `${tasks.length} ${tasks.length === 1 ? 'task' : 'tasks'} · ${totalTat} ${totalTat === 1 ? 'day' : 'days'} TAT`}
        </span>
      </div>

      {isPending && tasks.length === 0 ? (
        <p className="py-6 text-center text-sm text-content-muted" role="status">
          Loading tasks…
        </p>
      ) : tasks.length === 0 ? (
        <p className="py-6 text-center text-sm text-content-muted">
          This stage carries no tasks for the services on this project. Add one on the workflow
          template.
        </p>
      ) : (
        <ol className="m-0 list-none p-0">
          {tasks.map((task, index) => {
            const yours = isMine(task, meId)
            const isOpen = open.has(task.id)
            const owner = nameOf(task.ownerUserId)
            const answered = task.items.filter((i) => i.answer != null).length
            const last = index === tasks.length - 1

            return (
              <li
                key={task.id}
                data-testid="ob-project-task"
                className={cn(
                  'relative py-2 pl-6',
                  !last && 'border-b border-border',
                  yours && 'rounded-control bg-primary-soft pr-2.5 shadow-[inset_3px_0_0_var(--primary)]',
                )}
              >
                {/* The stage rail, and this task's bead on it. */}
                <span
                  aria-hidden="true"
                  className={cn(
                    'absolute left-[7px] top-[22px] w-[1.5px] bg-border',
                    last ? 'h-0' : 'bottom-[-1px]',
                  )}
                />
                <span
                  aria-hidden="true"
                  className={cn(
                    'absolute left-[1px] top-[11px] size-[13px] rounded-chip border-[1.5px] bg-surface',
                    settled(task) && 'border-ribbon-done bg-ribbon-done',
                    !settled(task) && yours && 'border-[4px] border-ribbon-current',
                    !settled(task) && !yours && 'border-ribbon-pending',
                  )}
                />

                <button
                  type="button"
                  aria-expanded={isOpen}
                  aria-controls={`ob-task-panel-${task.id}`}
                  onClick={() => toggle(task.id)}
                  className="flex w-full flex-wrap items-center gap-2 rounded-[4px] bg-transparent p-0 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                >
                  <span
                    aria-hidden="true"
                    className={cn(
                      'shrink-0 text-[9px] text-content-muted transition-transform',
                      isOpen && 'rotate-90',
                    )}
                  >
                    ▶
                  </span>
                  <span
                    className={cn(
                      'text-sm',
                      yours ? 'font-semibold' : 'font-medium',
                      settled(task) ? 'text-content-muted' : 'text-content',
                    )}
                  >
                    {task.name}
                  </span>

                  {yours && <span className={cn(PILL, 'bg-primary text-white')}>Yours</span>}
                  <span className={cn(PILL, STATUS_PILL[task.status] ?? STATUS_PILL.PENDING)}>
                    {STATUS_LABEL[task.status] ?? task.status}
                  </span>
                  {task.requiresSignoff && (
                    <span className={cn(PILL, 'bg-level-high-soft text-warning-text')}>Sign-off</span>
                  )}
                  {task.ownerUserId == null && (
                    <span className={cn(PILL, 'bg-danger-soft text-danger-text')}>Unassigned</span>
                  )}
                  {task.tatUsedPercent != null && task.tatUsedPercent > 100 && (
                    <span className={cn(PILL, 'bg-danger-soft tabular-nums text-danger-text')}>
                      TAT {Math.round(task.tatUsedPercent)}%
                    </span>
                  )}
                  {escalations?.has(task.id) && (
                    <span className={cn(PILL, 'bg-danger-soft text-danger-text')}>🔔 Escalated</span>
                  )}
                  {task.items.length > 0 && (
                    <span className={cn(PILL, 'bg-subtle tabular-nums text-content-muted')}>
                      ☑ {answered}/{task.items.length}
                    </span>
                  )}
                  {showServiceName && (
                    <span className={cn(PILL, 'bg-subtle font-medium text-content-muted')}>
                      {task.serviceName}
                    </span>
                  )}

                  <span className="min-w-0 flex-1" aria-hidden="true" />

                  <span className="whitespace-nowrap text-caption tabular-nums text-content-muted">
                    {owner ?? 'No owner'} · {task.tatDays}d
                  </span>
                </button>

                {isOpen && (
                  <div
                    id={`ob-task-panel-${task.id}`}
                    className={cn(
                      'border-l-[1.5px] pl-3.5',
                      yours ? 'border-primary' : 'border-border',
                    )}
                  >
                    <ObProjectTaskPanel
                      task={task}
                      index={index}
                      total={tasks.length}
                      users={users}
                      yours={yours}
                      escalation={escalations?.get(task.id) ?? null}
                    />
                  </div>
                )}
              </li>
            )
          })}
        </ol>
      )}
    </section>
  )
}

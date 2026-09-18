import * as React from 'react'
import { ChevronRight } from 'lucide-react'
import { Link, useParams } from 'react-router-dom'

import { useGetMe } from '@/api/generated/auth/auth'
import { useGetObJourney } from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { useGetObMyTask } from '@/api/generated/onboarding/onboarding'
import { useListUsers } from '@/api/generated/users/users'

import { Chip } from '@/components/ui/chip'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import {
  escalationForStep,
  useOpenEscalations,
} from '@/features/onboarding/journey/clientDetail/useOpenEscalations'
import { ObProjectTaskPanel } from '@/features/onboarding/projects/ObProjectTaskPanel'
import { ObTaskFacts } from '@/features/onboarding/projects/ObTaskDialog'
import { isMine } from '@/features/onboarding/projects/useProjectTasks'
import { isObAdmin } from '@/features/onboarding/projects/viewerScope'

import { focusedTask, formatDueDate, myTaskStatusLabel } from './myTasks'

/**
 * One task, on its own — `/onboarding/my-tasks/:taskId`.
 *
 * <h2>Why this is a screen and not the project page with a filter</h2>
 *
 * <p>The project page can already open onto a single task — `?task=1841`
 * selects the path and opens it in a popup — but every other row is still
 * there to be opened, which is right for somebody reading a project and wrong
 * for somebody finishing a task. This screen has one job: the thing to do, the
 * list to answer, and the controls to close it. Nothing else is drawn, so
 * there is nothing else to wander into.
 *
 * <p>It is what the task popup shows, with the whole screen to itself — the
 * same facts, the same check list, the same action bar — so a task reads the
 * same whether somebody pressed it in the queue or followed a mailed link
 * here. The two rows above it are context, not navigation: neither opens
 * anything, because there is nothing under them but this.
 *
 * <h2>A task that is not yours is a 404</h2>
 *
 * <p>`/onboarding/my-tasks/{taskId}` applies the ownership predicate inside the
 * query, so somebody else's task and a task that does not exist answer
 * identically. That is CONVENTIONS.md §7 — a 403 would confirm the task exists,
 * and these ids are sequential.
 *
 * <h2>Two reads, one of them already cached</h2>
 *
 * <p>The row carries the labels; the journey read carries the check list, the
 * documents and the TAT figures. The second is the same query key the project
 * page and the queue's popup use, so arriving here from either costs nothing
 * — and going the other way warms it.
 */
export function ObMyTaskFocusPage() {
  const params = useParams<{ taskId: string }>()
  const taskId = Number(params.taskId)

  const row = useGetObMyTask(taskId, { query: { enabled: Number.isFinite(taskId) } })
  const task = row.data?.data

  /*
    Fresh on arrival, for the reason `MyTaskDialog` spells out: this screen is
    opened to work a task, and the verdict that last moved it was recorded in
    somebody else's session. A mailed "your row came back" link landing on a
    cached pre-verdict read is the worst version of that — the message and the
    screen it points at disagreeing.
  */
  const journey = useGetObJourney(task?.journeyId ?? 0, {
    query: { enabled: task?.journeyId != null, staleTime: 0, refetchOnMount: 'always' },
  })

  /* NaN, not 0, while the row is loading — `useOpenEscalations` gates on
     `Number.isFinite`, and 0 would ask for client zero's escalations. */
  const { escalations } = useOpenEscalations(task?.obClientId ?? Number.NaN)

  const me = useGetMe()
  const meId = me.data?.data?.id
  const users = useListUsers({ isActive: true, limit: 200 })
  const userList = React.useMemo(() => users.data?.data ?? [], [users.data?.data])

  const focused = React.useMemo(
    () => (task ? focusedTask(task, journey.data?.data?.steps) : null),
    [task, journey.data?.data?.steps],
  )

  if (row.isPending) {
    return (
      <div className="mx-auto flex max-w-4xl flex-col gap-4 p-6">
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    )
  }

  if (row.isError || !task) {
    return (
      <div className="mx-auto max-w-3xl p-6">
        <h1 className="text-xl font-semibold text-content">Task not found</h1>
        <p className="mt-2 text-sm text-content-muted">
          It may have been removed, or it may belong to somebody else.{' '}
          <Link to="/onboarding/my-tasks" className="text-primary hover:underline">
            Back to My Tasks
          </Link>
          .
        </p>
      </div>
    )
  }

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-4 p-6">
      <header className="flex flex-col gap-2">
        <p className="text-caption text-content-muted">
          <Link to="/onboarding/my-tasks" className="hover:underline">
            My Tasks
          </Link>
          <Sep />
          <Link
            to={`/onboarding/projects/${task.projectId}`}
            className="hover:underline"
            title="The whole project"
          >
            {task.projectName}
          </Link>
          <Sep />
          <span>{task.obClientName}</span>
        </p>

        <div className="flex flex-wrap items-center gap-2">
          <h1 className="text-2xl font-semibold text-content [text-wrap:balance]">
            {task.taskName}
          </h1>
          <Chip variant={task.status === 'BLOCKED' ? 'danger' : 'neutral'}>
            {myTaskStatusLabel(task.status)}
          </Chip>
          {task.isOverdue ? <Chip variant="danger">Overdue</Chip> : null}
        </div>

        <p className="text-sm text-content-muted">
          Due {formatDueDate(task.dueAt)}
        </p>
      </header>

      {/*
        The two rows above the task are context, not controls. They carry no
        chevron that opens anything, because on this screen there is nothing
        underneath them but the task itself — a disclosure that can only ever
        reveal what is already on screen is a control that lies.
      */}
      <ol className="m-0 flex list-none flex-col p-0">
        <li>
          <ContextRow level="service" label={task.serviceName} caption="Module service" />
          <ol className="m-0 ml-[7px] list-none border-l border-border pl-[15px] pt-1">
            <li>
              <ContextRow
                level="step"
                label={task.stepName}
                caption="Step"
                sequence={task.stepSequence}
                status={task.status}
              />
              <div className="ml-[7px] border-l border-border pl-[15px] pt-1">
                {journey.isPending ? (
                  <Skeleton className="h-40 w-full" />
                ) : focused ? (
                  <div className="flex flex-col gap-4 rounded-card border border-border bg-surface px-5 py-4 shadow-rest">
                    <ObTaskFacts task={focused} users={userList} />
                    <ObProjectTaskPanel
                      task={focused}
                      users={userList}
                      // Passed here as well as from the queue's popup: this is
                      // the same task with the whole screen to itself, and a
                      // client escalation that showed in the popup and not here
                      // would be the one fact that depends on how you arrived.
                      escalation={escalationForStep(escalations, task.taskId)}
                      yours={isMine(focused, meId)}
                      canReview={
                        isObAdmin(me.data?.data) ||
                        (journey.data?.data?.implementorManagerUserId != null &&
                          journey.data?.data?.implementorManagerUserId === me.data?.data?.id)
                      }
                    />
                  </div>
                ) : (
                  <p className="rounded-card border border-border bg-surface px-4 py-6 text-center text-sm text-content-muted">
                    This task&rsquo;s check list could not be loaded. Reload to try again.
                  </p>
                )}
              </div>
            </li>
          </ol>
        </li>
      </ol>
    </div>
  )
}

/**
 * The bead's fill by the task's status — the project ribbon's own colours,
 * so the Step row here reads like a segment there: green done, indigo running,
 * amber waiting, red blocked, an outline for not started.
 */
const BEAD: Record<string, string> = {
  DONE: 'border-ribbon-done bg-ribbon-done text-white',
  SKIPPED: 'border-ribbon-done bg-ribbon-done text-white',
  IN_PROGRESS: 'border-primary bg-primary text-white',
  WAITING_ON_CLIENT: 'border-ribbon-waiting bg-ribbon-waiting text-white',
  BLOCKED: 'border-ribbon-blocked bg-ribbon-blocked text-white',
  PENDING: 'border-border bg-surface text-content-muted',
}

/**
 * One of the two rows above the task. Styled like the project page's own
 * levels so the hierarchy reads the same on both screens, and deliberately not
 * a button.
 */
function ContextRow({
  level,
  label,
  caption,
  sequence,
  status,
}: {
  level: 'service' | 'step'
  label: string
  caption: string
  sequence?: number
  /** The task's status, which is what colours the Step's bead. */
  status?: string
}) {
  return (
    <div
      data-testid={`ob-focus-${level}`}
      className={cn(
        'flex flex-wrap items-center gap-2 rounded-control px-3 py-2',
        level === 'service'
          ? 'border border-border bg-surface shadow-[inset_3px_0_0_var(--primary)]'
          : 'bg-subtle',
      )}
    >
      {/* Rotated to point down: this level is open, and it is the only state
          it has. Reserved rather than removed so both rows share one left edge. */}
      <ChevronRight aria-hidden="true" className="size-3.5 shrink-0 rotate-90 text-content-muted" />
      {sequence != null ? (
        <span
          aria-hidden="true"
          className={cn(
            'grid size-[18px] shrink-0 place-items-center rounded-chip border text-[10px] font-semibold tabular-nums',
            BEAD[status ?? 'PENDING'] ?? BEAD.PENDING,
          )}
        >
          {sequence >= 9999 ? '·' : sequence}
        </span>
      ) : null}
      <span className={cn(level === 'service' ? 'text-h3 text-content' : 'text-sm font-medium')}>
        {label}
      </span>
      <span className="min-w-2 flex-1" aria-hidden="true" />
      <span className="text-caption text-content-muted">{caption}</span>
    </div>
  )
}

/** The faint interpunct between crumbs. Hidden from the accessibility tree. */
function Sep() {
  return (
    <span aria-hidden="true" className="px-1.5 text-border">
      ·
    </span>
  )
}

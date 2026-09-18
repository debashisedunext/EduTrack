import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { useQueryClient } from '@tanstack/react-query'

import { useGetMe } from '@/api/generated/auth/auth'
import type { UserRef } from '@/api/generated/model/userRef'
import {
  useBlockObJourneyStep,
  useCloseObJourneyStepReview,
  useCompleteObJourneyStep,
  useMarkObJourneyStepWaitingOnClient,
  useResumeObJourneyStep,
  useStartObJourneyStep,
  useUpdateObJourneyStep,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { cn } from '@/lib/utils'

import { invalidateAfterTaskWrite } from './taskQueries'
import {
  DEPENDENCY_REASON,
  ObTaskBlockDialog,
  ObTaskCommunicationsDialog,
  ObTaskDocsDialog,
  ObTaskReassignDialog,
  ObTaskWaitingOnServiceDialog,
} from './ObTaskActionDialogs'
import { refusalMessage, taskActions, type TaskAction, type TaskActionKey } from './taskActions'
import type { ProjectTask } from './useProjectTasks'
import { isObModerator } from './viewerScope'

/**
 * Everything that can be done to one task, on one row.
 *
 * <h2>What it replaces</h2>
 *
 * <p>Five stacked rows, four of them input controls sitting empty in case
 * somebody wanted them: two transition buttons, a block-reason select beside a
 * note field, a person select beside Reassign, a file field beside Attach, and
 * the whole communications panel with its own form and timeline. About four
 * hundred pixels of controls under every open task, whether or not the reader
 * intended to use any of them — and three of those rows were permanently
 * disabled, which is the most expensive thing a screen can show: the space of a
 * control and none of the function.
 *
 * <p>Now: one row of buttons, and a dialog behind each that needs input. The
 * two that need none — Complete and Waiting on client, whose endpoints take no
 * body — just act.
 *
 * <h2>Three on the row, three behind More, and Communication on its own</h2>
 *
 * <p>The row carries the transition a task is waiting for — Start, Complete or
 * Resume — beside Waiting on client and Waiting on service. Block, Reassign
 * and Attach sit behind <b>More</b>: each is a dialog rather than a
 * transition, and none is what somebody opens a task for most days. Past the
 * divider, Communication stands alone, because it is the one thing every
 * reader can do on every task. Seven undifferentiated buttons was a toolbar
 * somebody re-read every visit; this is three, a menu and one.
 *
 * <p>`taskActions` still decides which of the seven exist and which are
 * enabled; this only decides where each is drawn, so the table the tests
 * assert on is unchanged.
 *
 * <h2>Both screens, one component</h2>
 *
 * <p>`ObMyTaskFocusPage` renders `ObProjectTaskPanel`, which renders this. My
 * Tasks gets the bar with no second implementation to keep in step.
 *
 * <h2>Why it reads `/me` itself</h2>
 *
 * <p>Reassign is a moderator's action, so the bar needs the caller's onboarding
 * module role. The alternative was threading a scope through the tree, the
 * stage body and the panel — three layers and two entry points for one boolean.
 * `useGetMe` is already resolved and cached by the time any task is open, so
 * this costs a cache read rather than a request.
 */
export interface ObTaskActionBarProps {
  task: ProjectTask
  users: readonly UserRef[]
  /** Owner or backup owner — the rule the server enforces on writes. */
  yours: boolean
  /**
   * This reader is the reviewer and the task is waiting on them.
   *
   * <p>**Mark complete** then means something different without looking
   * different: it closes the review rather than submitting the work — and
   * what it never does is close the task, which stays the implementor's press
   * whichever way the verdicts went. One button because it is one idea — *I
   * am finished with this task* — and a second one beside it would have been
   * two ways to say so, one of them always wrong for whoever is reading.
   */
  reviewing?: boolean
  /** What holds the reviewer's Mark complete, in `blockers`' own shape. */
  reviewBlockers?: readonly string[]
  /** What Complete is waiting on, already computed by the panel above. */
  blockers: readonly string[]
}

export function ObTaskActionBar({
  task,
  users,
  yours,
  blockers,
  reviewing = false,
  reviewBlockers = [],
}: ObTaskActionBarProps) {
  const queryClient = useQueryClient()
  const me = useGetMe()
  const isModerator = isObModerator(me.data?.data)

  /*
    Refetched after a refusal as well as after a success, which is why this is
    onSettled and not onSuccess. A transition is very often refused *because*
    the server's copy of this task has moved on — somebody else completed it, a
    sign-off landed and carried it to DONE — and the refusal is the first moment
    the page learns its row is stale. Leaving that row on screen invites the
    reader to press the same dead button again and read the same message, which
    is how "cannot complete from status DONE" gets reported as the button being
    broken. Repainting from the server settles it: the bar re-renders at the
    real status, disables what is no longer on offer, and the message below says
    what happened.
  */
  const refresh = {
    onSettled: () => {
      // The journey, and every queue that lists this task — a transition moves
      // the My Tasks row's status dot and its due date, and that row is what
      // the reader is standing on when they press one of these from the popup.
      invalidateAfterTaskWrite(queryClient, task.journeyId, task.id)
    },
  }
  const start = useStartObJourneyStep({ mutation: refresh })
  const complete = useCompleteObJourneyStep({ mutation: refresh })
  const closeReview = useCloseObJourneyStepReview({ mutation: refresh })
  const waiting = useMarkObJourneyStepWaitingOnClient({ mutation: refresh })
  const resume = useResumeObJourneyStep({ mutation: refresh })
  const block = useBlockObJourneyStep({ mutation: refresh })
  const reassign = useUpdateObJourneyStep({ mutation: refresh })

  const busy =
    start.isPending ||
    complete.isPending ||
    closeReview.isPending ||
    waiting.isPending ||
    resume.isPending ||
    block.isPending ||
    reassign.isPending

  const refusal = refusalMessage([start, complete, closeReview, waiting, resume, block, reassign])

  /** Which dialog is open. One at a time — they are all about this one task. */
  const [openDialog, setOpenDialog] = React.useState<TaskActionKey | null>(null)
  const close = () => setOpenDialog(null)

  const ownerName = users.find((u) => u.id === task.ownerUserId)?.displayName ?? 'the task owner'

  const actions = reviewGateActions(taskActions({ task, yours, isModerator, blockers, ownerName, busy }), {
    reviewing,
    underReview: task.status === 'PENDING_REVIEW',
    blockers: reviewBlockers,
    busy,
  })

  const run = (key: TaskActionKey) => {
    switch (key) {
      case 'start':
        return start.mutate({ stepId: task.id })
      case 'complete':
        // Same button, two meanings, decided by who is looking. The reviewer's
        // press closes the review — which either completes the task or hands it
        // back — and the owner's submits the work.
        return reviewing
          ? closeReview.mutate({ stepId: task.id })
          : complete.mutate({ stepId: task.id })
      case 'waitingOnClient':
        return waiting.mutate({ stepId: task.id })
      case 'resume':
        return resume.mutate({ stepId: task.id })
      default:
        return setOpenDialog(key)
    }
  }

  const MORE: readonly TaskActionKey[] = ['block', 'reassign', 'attach']
  const inline = actions.filter((a) => a.group === 'state' && !MORE.includes(a.key))
  const more = actions.filter((a) => MORE.includes(a.key))
  const communication = actions.find((a) => a.key === 'communication')

  /*
    Complete's reason in text as well as in `title`. "The button is grey" is not
    an answer a reader can act on, and a tooltip is not one they will find — and
    this is the single most asked question on the panel.
  */
  const completeAction = actions.find((a) => a.key === 'complete')
  const completeWhy =
    completeAction && !completeAction.enabled && reviewing && reviewBlockers.length > 0
      ? `Mark complete is waiting on ${reviewBlockers.join(' and ')}.`
      : completeAction && !completeAction.enabled && yours && blockers.length > 0
        ? `Mark complete is waiting on ${blockers.join(' and ')}.`
        : null

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap items-center gap-1.5" data-testid="ob-task-action-bar">
        {inline.map((action) => (
          <ActionButton key={action.key} action={action} onPress={() => run(action.key)} />
        ))}

        <MoreMenu actions={more} onPress={run} />

        <span
          aria-hidden="true"
          className="mx-1 hidden h-5 w-px self-center bg-border sm:block"
        />

        {communication && (
          <ActionButton action={communication} onPress={() => run(communication.key)} />
        )}
      </div>

      {completeWhy && <p className="m-0 text-caption text-danger-text">{completeWhy}</p>}

      {reviewing && (
        <p className="m-0 text-caption text-content-muted">
          {`${ownerName} marked this complete. Set every row to Verified or Rejected, then press Mark complete — it goes back to ${ownerName} either way: verified for them to close, rejected to redo.`}
        </p>
      )}

      {!yours && !reviewing && (
        <p className="m-0 text-caption text-content-muted">
          {task.ownerUserId != null
            ? `Read-only — ${ownerName} is the implementor on this task. You can still log communication.`
            : 'Read-only — nobody is assigned to this task yet. You can still log communication.'}
        </p>
      )}

      {refusal && (
        <p className="m-0 text-caption text-danger-text" role="alert">
          {refusal}
        </p>
      )}

      <ObTaskWaitingOnServiceDialog
        open={openDialog === 'waitingOnService'}
        onOpenChange={(open) => (open ? setOpenDialog('waitingOnService') : close())}
        isPending={block.isPending}
        onConfirm={(note) => {
          block.mutate(
            { stepId: task.id, data: { reasonCode: DEPENDENCY_REASON, note: note ?? undefined } },
            { onSuccess: close },
          )
        }}
      />

      <ObTaskBlockDialog
        open={openDialog === 'block'}
        onOpenChange={(open) => (open ? setOpenDialog('block') : close())}
        isPending={block.isPending}
        onConfirm={(reasonCode, note) => {
          block.mutate(
            { stepId: task.id, data: { reasonCode, note: note ?? undefined } },
            { onSuccess: close },
          )
        }}
      />

      <ObTaskReassignDialog
        open={openDialog === 'reassign'}
        onOpenChange={(open) => (open ? setOpenDialog('reassign') : close())}
        isPending={reassign.isPending}
        task={task}
        users={users}
        ownerName={ownerName}
        onConfirm={(ownerUserId) => {
          reassign.mutate({ stepId: task.id, data: { ownerUserId } }, { onSuccess: close })
        }}
      />

      <ObTaskDocsDialog
        open={openDialog === 'attach'}
        onOpenChange={(open) => (open ? setOpenDialog('attach') : close())}
        task={task}
      />

      <ObTaskCommunicationsDialog
        open={openDialog === 'communication'}
        onOpenChange={(open) => (open ? setOpenDialog('communication') : close())}
        task={task}
      />
    </div>
  )
}

const TONE: Record<TaskAction['tone'], string> = {
  primary: 'border-primary bg-primary text-white hover:bg-primary/90',
  outline: 'border-primary bg-surface text-primary hover:bg-primary-soft',
  danger: 'border-danger bg-surface text-danger-text hover:bg-level-critical-soft',
  plain: 'border-border bg-surface text-content hover:bg-subtle',
}

/**
 * Block, Reassign and Attach, behind one button.
 *
 * <p>A popover holding a menu rather than a native `<select>`: each entry keeps
 * its own disabled state and its own reason as `title`, and Attach keeps its
 * satisfied-over-required badge, exactly as the buttons did on the row. The
 * entries carry the same `data-testid`s the row buttons had, so a test reaches
 * Block by opening More first and nothing else about it changes.
 */
function MoreMenu({
  actions,
  onPress,
}: {
  actions: readonly TaskAction[]
  onPress: (key: TaskActionKey) => void
}) {
  const [open, setOpen] = React.useState(false)
  if (actions.length === 0) return null

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={setOpen}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          data-testid="ob-task-action-more"
          aria-haspopup="menu"
          aria-expanded={open}
          className={cn(
            'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control border px-2.5 py-1 text-caption font-semibold',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
            TONE.plain,
          )}
        >
          <span aria-hidden="true" className="text-[11px] opacity-90">
            ⋯
          </span>
          More
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="start"
          sideOffset={4}
          className="z-50 w-52 overflow-hidden rounded-control border border-border bg-surface p-1 shadow-modal"
        >
          <div role="menu" aria-label="More actions" className="flex flex-col">
            {actions.map((action) => (
              <button
                key={action.key}
                type="button"
                role="menuitem"
                disabled={!action.enabled}
                title={action.reason}
                data-testid={`ob-task-action-${action.key}`}
                aria-haspopup={action.opensDialog ? 'dialog' : undefined}
                onClick={() => {
                  setOpen(false)
                  onPress(action.key)
                }}
                className={cn(
                  'flex w-full items-center gap-2 rounded-control px-2.5 py-1.5 text-left text-caption font-medium',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary',
                  action.enabled
                    ? action.tone === 'danger'
                      ? 'text-danger-text hover:bg-level-critical-soft'
                      : 'text-content hover:bg-subtle'
                    : 'cursor-not-allowed text-content-muted',
                )}
              >
                <span aria-hidden="true" className="w-4 text-center text-[11px] opacity-90">
                  {action.icon}
                </span>
                {action.label}
                {action.badge && (
                  <span className="ml-auto rounded-chip bg-subtle px-1.5 text-[10.5px] tabular-nums text-content-muted">
                    {action.badge}
                  </span>
                )}
              </button>
            ))}
          </div>
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

function ActionButton({ action, onPress }: { action: TaskAction; onPress: () => void }) {
  return (
    <button
      type="button"
      disabled={!action.enabled}
      title={action.reason}
      onClick={onPress}
      data-testid={`ob-task-action-${action.key}`}
      aria-haspopup={action.opensDialog ? 'dialog' : undefined}
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-control border px-2.5 py-1 text-caption font-semibold',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary',
        action.enabled
          ? TONE[action.tone]
          : 'cursor-not-allowed border-border bg-subtle text-content-muted',
      )}
    >
      <span aria-hidden="true" className="text-[11px] opacity-90">
        {action.icon}
      </span>
      {action.label}
      {action.badge && (
        <span
          className={cn(
            'rounded-chip px-1.5 text-[10.5px] tabular-nums',
            action.enabled ? 'bg-subtle text-content-muted' : 'bg-border text-content-muted',
          )}
        >
          {action.badge}
        </span>
      )}
    </button>
  )
}

/**
 * The bar as the review gate leaves it.
 *
 * <h2>One override, not a second table</h2>
 *
 * <p>{@link taskActions} answers for the task's owner, and its answer for
 * anybody else is "read-only" — correct for every reader until the review gate
 * existed, and wrong for exactly one: the person the task is now waiting on.
 * Rather than branch that table on a second role, this adjusts the single
 * button whose meaning changes and leaves the rest of its answer alone. The
 * table stays the owner's, and its tests stay about the owner.
 *
 * <h2>Two readers, two opposite answers, one button</h2>
 *
 * <p><b>The reviewer gets Mark complete back.</b> It is the only control they
 * get: Start, Waiting on client, Block and Reassign all move work that is not
 * theirs to move, and a bar offering them would be four buttons the server
 * refuses beside one it accepts.
 *
 * <p><b>The implementor loses it entirely</b> while the task is out for
 * review — not greyed, removed. They pressed it already; that press is what
 * sent the task away, and the check list beside it is locked for the same
 * reason. A disabled Mark complete on a submitted task reads as *something of
 * mine is unfinished* and invites a hunt through the rows for whatever it is,
 * when in fact nothing there is theirs to do until a verdict comes back. The
 * banner above the rows says where the task went; an absent button says the
 * same thing in the place the reader would otherwise argue with it. It returns
 * the moment the task does — a rejection sets the task back to
 * {@code IN_PROGRESS}, and with it every button this hid.
 *
 * @param blockers what still holds the reviewer's press — an unreviewed row,
 *                 or a rejection with no reason. Named rather than left as a
 *                 grey button, on the bar's own standing rule that "it is
 *                 grey" is not an answer anybody can act on.
 */
function reviewGateActions(
  actions: readonly TaskAction[],
  {
    reviewing,
    underReview,
    blockers,
    busy,
  }: { reviewing: boolean; underReview: boolean; blockers: readonly string[]; busy: boolean },
): TaskAction[] {
  if (reviewing) {
    return actions.map((action) => {
      if (action.key !== 'complete') return action
      const held = busy
        ? 'Working…'
        : blockers.length > 0
          ? `Outstanding: ${blockers.join(', ')}`
          : undefined
      return { ...action, enabled: !held, reason: held }
    })
  }
  if (underReview) return actions.filter((action) => action.key !== 'complete')
  return [...actions]
}

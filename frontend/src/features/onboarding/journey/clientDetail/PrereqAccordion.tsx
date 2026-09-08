import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { format, parseISO } from 'date-fns'

import type { ObClientPrereqs, ObClientPrereqTask } from '@/api/generated/model'
import {
  getGetObClientPrereqsQueryKey,
  getGetObClientQueryKey,
  useReturnObClientPrereqTask,
  useSkipObClientPrereqTask,
  useVerifyObClientPrereqTask,
} from '@/api/generated/onboarding/onboarding'
import { Button } from '@/components/ui/button'
import { Chip } from '@/components/ui/chip'
import { cn } from '@/lib/utils'
import { toast } from '@/components/ui/use-toast'

import { ObAccordion } from './ObAccordion'
import { PrereqReasonDialog } from './PrereqReasonDialog'
import { prereqProgress } from './journeyStrip'

/**
 * C-110 · the prerequisites accordion at the top of OB-05 —
 * Onboarding-Module-Plan.md §9:
 *
 * > **Prerequisites as an accordion on top** (strip: gate chip +
 * > mandatory-progress bar; expands to task rows with verify/return/skip;
 * > defaults open until the gate clears, collapsed after)
 *
 * ## It is on top because it is the only thing that can be acted on
 *
 * While the gate is locked every journey below is fully drawn and completely
 * inert — steps, owners and TATs visible, no clock running, the scanner
 * ignoring all of it (plan §5.2/§5.3). The one action that changes anything is
 * up here. That is also why the default open state is derived from the gate
 * rather than remembered: a client whose prerequisites are outstanding opens on
 * the work, and one whose gate cleared opens on the journeys, with the
 * checklist collapsed to a line that still says it cleared.
 *
 * ## `gateOpened`, never `gateStatus`
 *
 * `ObPrereqGateResult` is emphatic about this and it is worth restating at the
 * call site: `gateStatus` reads `OPEN` on every call after the gate opens, so a
 * screen that refreshed the journeys whenever it saw `OPEN` would refresh them
 * forever. `gateOpened` is true at most once in a client's life — on the
 * transition that cleared the last outstanding task — and that is the one
 * moment the journeys below have genuinely changed underneath us.
 */
export interface PrereqAccordionProps {
  obClientId: number
  prereqs: ObClientPrereqs
  isOpen: boolean
  onToggle: () => void
}

const STATUS_CHIP: Record<string, { label: string; variant: 'neutral' | 'info' | 'success' | 'warning' }> = {
  PENDING: { label: 'Pending', variant: 'neutral' },
  SUBMITTED: { label: 'Submitted', variant: 'info' },
  VERIFIED: { label: 'Verified', variant: 'success' },
  SKIPPED: { label: 'Waived', variant: 'warning' },
}

function formatDate(value: string | null | undefined): string | null {
  if (!value) return null
  const parsed = parseISO(value)
  return Number.isNaN(parsed.getTime()) ? null : format(parsed, 'd MMM yyyy')
}

export function PrereqAccordion({ obClientId, prereqs, isOpen, onToggle }: PrereqAccordionProps) {
  const queryClient = useQueryClient()
  const progress = prereqProgress(prereqs)

  /**
   * Both reads, and only on the transition that opened the gate.
   *
   * The checklist always: every action here changes a task on it. The client
   * document only when `gateOpened` — that is the read carrying the journey
   * strips, and it is stale exactly once, when a set of journeys flipped
   * `LOCKED` to `OPEN` in the same transaction.
   */
  const refresh = React.useCallback(
    (gateOpened: boolean) => {
      void queryClient.invalidateQueries({ queryKey: getGetObClientPrereqsQueryKey(obClientId) })
      if (gateOpened) {
        void queryClient.invalidateQueries({ queryKey: getGetObClientQueryKey(obClientId) })
      }
    },
    [obClientId, queryClient],
  )

  const onGateResult = React.useCallback(
    (result: { data?: { gateOpened?: boolean } }) => {
      const gateOpened = result.data?.gateOpened ?? false
      refresh(gateOpened)
      if (gateOpened) {
        toast({
          variant: 'success',
          title: 'Prerequisites cleared',
          description: 'The journeys below have opened and their clocks are running.',
        })
      }
    },
    [refresh],
  )

  const verify = useVerifyObClientPrereqTask({ mutation: { onSuccess: onGateResult } })
  const skip = useSkipObClientPrereqTask({ mutation: { onSuccess: onGateResult } })
  const back = useReturnObClientPrereqTask({ mutation: { onSuccess: () => refresh(false) } })

  const [dialog, setDialog] = React.useState<{ kind: 'return' | 'skip'; task: ObClientPrereqTask } | null>(null)

  const tasks = prereqs.tasks ?? []

  return (
    <>
      <ObAccordion
        id="ob-prereqs"
        isOpen={isOpen}
        onToggle={onToggle}
        label={
          progress.isCleared
            ? 'Prerequisites — cleared'
            : `Prerequisites — ${progress.verified} of ${progress.total} mandatory tasks verified`
        }
        summary={
          <>
            <span className="shrink-0 text-sm font-semibold text-content">Prerequisites</span>

            <Chip variant={progress.isCleared ? 'success' : 'warning'}>
              {progress.isCleared ? 'Gate open' : 'Gate locked'}
            </Chip>

            <span className="flex min-w-0 flex-1 items-center gap-2">
              {/*
                A meter, not a progress bar: this is a measurement of a known
                set, not the progress of an operation the page is running.
                `aria-valuetext` says it in the units a reader cares about —
                "3 of 5 verified", never "60%".
              */}
              <span
                role="meter"
                aria-label="Mandatory prerequisites verified"
                aria-valuemin={0}
                aria-valuemax={progress.total}
                aria-valuenow={progress.verified}
                aria-valuetext={`${progress.verified} of ${progress.total} verified`}
                className="h-1.5 w-32 shrink-0 overflow-hidden rounded-full bg-subtle"
              >
                <span
                  className={cn('block h-full rounded-full', progress.isCleared ? 'bg-success' : 'bg-warning')}
                  style={{ width: `${progress.percent}%` }}
                />
              </span>
              <span className="shrink-0 text-caption tabular-nums text-content-muted">
                {progress.verified}/{progress.total} mandatory
              </span>
              {/*
                Without this line a screen reading "4/4 mandatory" beside a
                locked gate looks broken — `ObClientPrereqs.optionalOutstanding`
                exists for exactly this sentence.
              */}
              {progress.optionalOutstanding > 0 && (
                <span className="shrink-0 text-caption text-content-muted">
                  · {progress.optionalOutstanding} optional outstanding
                </span>
              )}
            </span>
          </>
        }
      >
        <ul role="list" className="flex flex-col gap-2">
          {tasks.map((task) => {
            const chip = STATUS_CHIP[task.status] ?? STATUS_CHIP.PENDING
            const isBusy =
              (verify.isPending && verify.variables?.prereqTaskId === task.id) ||
              (back.isPending && back.variables?.prereqTaskId === task.id) ||
              (skip.isPending && skip.variables?.prereqTaskId === task.id)

            return (
              <li
                key={task.id}
                className="flex flex-wrap items-center gap-2 rounded-card border border-border bg-app px-3 py-2"
              >
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-medium text-content">
                    {task.sequence}. {task.title}
                  </span>
                  <span className="block text-caption text-content-muted">
                    {task.isMandatory ? 'Mandatory' : 'Optional'}
                    {task.isAdHoc && ' · added for this client'}
                    {formatDate(task.dueAt) && ` · due ${formatDate(task.dueAt)}`}
                    {task.status === 'SKIPPED' && task.skipReason && ` · waived: ${task.skipReason}`}
                  </span>
                </span>

                {/*
                  Overdue is the server's own derived field, never a date
                  comparison made here — `ObClientPrereqTask.isOverdue` is
                  computed on read "so it cannot disagree with the timestamp
                  beside it", and a browser clock in another timezone is
                  exactly the disagreement that guards against.
                */}
                {task.isOverdue && <Chip variant="danger">Overdue</Chip>}
                <Chip variant={chip.variant}>{chip.label}</Chip>

                {/*
                  Verify and return exist only on a SUBMITTED task, and the
                  server says 422 otherwise. Rendering them disabled would put
                  two dead controls on every pending row — B-121's line about a
                  dead control teaching the user the board is broken.
                */}
                {task.status === 'SUBMITTED' && (
                  <>
                    <Button
                      size="sm"
                      disabled={isBusy}
                      onClick={() => verify.mutate({ prereqTaskId: task.id, data: {} })}
                    >
                      Verify
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={isBusy}
                      onClick={() => setDialog({ kind: 'return', task })}
                    >
                      Return
                    </Button>
                  </>
                )}

                {/*
                  A mandatory task is never skippable — the server answers 422
                  calling it out as a fact about the row, and plan §5.3 has no
                  override at all. Offering the button and letting the refusal
                  arrive from the network would advertise a valve that does not
                  exist.
                */}
                {!task.isMandatory && task.status !== 'VERIFIED' && task.status !== 'SKIPPED' && (
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={isBusy}
                    onClick={() => setDialog({ kind: 'skip', task })}
                  >
                    Waive
                  </Button>
                )}
              </li>
            )
          })}
        </ul>
      </ObAccordion>

      <PrereqReasonDialog
        open={dialog?.kind === 'return'}
        onOpenChange={(open) => !open && setDialog(null)}
        title="Send this back to the client"
        description="The client reads this, and it is the only message they are guaranteed to see. The clock is not reset — prerequisite time stays attributed to the client."
        fieldLabel="What is wrong"
        confirmLabel="Return submission"
        isPending={back.isPending}
        onConfirm={(comment) => {
          if (!dialog) return
          back.mutate({ prereqTaskId: dialog.task.id, data: { comment } })
          setDialog(null)
        }}
      />

      <PrereqReasonDialog
        open={dialog?.kind === 'skip'}
        onOpenChange={(open) => !open && setDialog(null)}
        title="Waive this prerequisite"
        description="Waiving an optional task settles it for the gate. The reason is the field a later dispute turns on, and it is kept for good."
        fieldLabel="Why is this being waived"
        confirmLabel="Waive task"
        isPending={skip.isPending}
        onConfirm={(reason) => {
          if (!dialog) return
          skip.mutate({ prereqTaskId: dialog.task.id, data: { reason } })
          setDialog(null)
        }}
      />
    </>
  )
}

import * as React from 'react'
import { useQueryClient } from '@tanstack/react-query'

import type { ObJourneyStepView } from '@/api/generated/model'
import {
  getGetObJourneyQueryKey,
  getGetObJourneyStepQueryKey,
  useBlockObJourneyStep,
  useCompleteObJourneyStep,
  useMarkObJourneyStepWaitingOnClient,
  useResumeObJourneyStep,
  useStartObJourneyStep,
} from '@/api/generated/onboarding-journeys/onboarding-journeys'
import { ApiError } from '@/api/http'
import { Button } from '@/components/ui/button'
import { toast } from '@/components/ui/use-toast'

import type { JourneyHold } from './journeyStrip'
import { BlockStepDialog } from './BlockStepDialog'
import {
  ACTION_LABELS,
  actionRefusal,
  allowedActions,
  type CompletionGate,
  type StepAction,
} from './stepActions'

/**
 * C-111 · OB-06's action row — the owner's working surface.
 *
 * ## Only the owner sees buttons at all
 *
 * Not disabled buttons: absent ones. `ObStepOwnership.mayAct` admits the owner
 * and the backup owner and nobody else, so for everybody else this step is
 * somebody else's work and a row of greyed-out controls would suggest a
 * permission they might acquire by asking. The panel says who owns it instead,
 * which is the actionable fact.
 *
 * A control that *is* rendered disabled is a different case: Complete on a step
 * whose Task List is outstanding is the owner's own button, blocked by
 * something the owner can go and fix, and the tooltip names it.
 *
 * ## Every action refetches the journey, not just the step
 *
 * A transition changes the step's status, and the journey's RAG,
 * `percentComplete` and step dots are computed from the set of statuses. The
 * ribbon above this panel is drawn from `getObJourney`; invalidating only the
 * step would leave the tile the reader just acted on disagreeing with the
 * panel underneath it.
 *
 * The client document is deliberately **not** invalidated. Its journey strips
 * carry the same figures, but re-reading a whole client on every tick would
 * make the page heavier for a number the accordion strip shows collapsed. It
 * refreshes on the next visit, which is the right trade for a summary.
 */
export interface StepActionBarProps {
  step: ObJourneyStepView
  hold: JourneyHold
  gate: CompletionGate
  /** `mayActOnStep(step, me?.id)`, computed once by the panel so the task
   * list's checkboxes and these buttons can never disagree. */
  canAct: boolean
}

export function StepActionBar({ step, hold, gate, canAct }: StepActionBarProps) {
  const queryClient = useQueryClient()
  const [blocking, setBlocking] = React.useState(false)

  const refresh = React.useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: getGetObJourneyStepQueryKey(step.id) })
    void queryClient.invalidateQueries({ queryKey: getGetObJourneyQueryKey(step.journeyId) })
  }, [queryClient, step.id, step.journeyId])

  /**
   * The server's refusal, shown verbatim.
   *
   * `ObCompletionGateProblem` carries a third gate this screen cannot see —
   * `signoffMissing`, which depends on whether a `SIGNED` sign-off exists and
   * is on no read the panel makes. So when the server refuses anyway, its
   * `detail` is the only accurate account of why, and paraphrasing it into
   * something friendlier would be inventing a reason.
   */
  const onError = React.useCallback((error: unknown) => {
    /*
      `ApiError` exposes the RFC 9457 body as `problem`, not as `error`. This
      read used to be `error.error.detail`, a property that does not exist on
      anything the generated client throws — so `detail` was always undefined
      and every refusal, from a 404 on an unbuilt route to a 422 naming the
      exact gate that held, rendered as the same generic sentence.

      That defeated the whole point of the rule this handler exists to keep:
      surface the server's own words, because `signoffMissing` is a gate this
      screen cannot see and its `detail` is the only accurate account of it.
    */
    const problem = error instanceof ApiError ? error.problem : null
    toast({
      variant: 'danger',
      title: 'That did not go through',
      description:
        problem?.detail ?? problem?.title ?? 'The server refused the change. Reload and try again.',
    })
  }, [])

  const mutationOptions = { mutation: { onSuccess: refresh, onError } }

  const start = useStartObJourneyStep(mutationOptions)
  const complete = useCompleteObJourneyStep(mutationOptions)
  const waiting = useMarkObJourneyStepWaitingOnClient(mutationOptions)
  const resume = useResumeObJourneyStep(mutationOptions)
  const block = useBlockObJourneyStep({
    mutation: {
      onSuccess: () => {
        setBlocking(false)
        refresh()
      },
      onError,
    },
  })

  const isPending =
    start.isPending || complete.isPending || waiting.isPending || resume.isPending || block.isPending

  const actions = allowedActions(step.status)

  // Someone else's step, or a terminal one. Nothing to offer, and nothing
  // greyed out pretending otherwise.
  if (!canAct || actions.length === 0) return null

  const run = (action: StepAction) => {
    if (action === 'block') return setBlocking(true)
    const vars = { stepId: step.id }
    if (action === 'start') start.mutate(vars)
    else if (action === 'complete') complete.mutate(vars)
    else if (action === 'waiting') waiting.mutate(vars)
    else resume.mutate(vars)
  }

  return (
    <>
      <div className="mt-4 flex flex-wrap items-center gap-2 border-t border-border pt-3">
        {actions.map((action) => {
          const refusal = actionRefusal(action, { hold, gate })
          return (
            <Button
              key={action}
              size="sm"
              variant={action === 'complete' ? 'primary' : action === 'block' ? 'danger' : 'secondary'}
              disabled={refusal != null || isPending}
              title={refusal ?? undefined}
              onClick={() => run(action)}
            >
              {ACTION_LABELS[action]}
            </Button>
          )
        })}

        {/*
          The refusal in words, beside the button rather than only inside its
          tooltip. A disabled control with a title attribute is unreachable by
          keyboard and invisible on touch, and this is the one sentence that
          tells the owner what to go and do.
        */}
        {actions.map((action) => {
          const refusal = actionRefusal(action, { hold, gate })
          return refusal ? (
            <p key={`${action}-why`} role="status" className="w-full text-caption text-content-muted">
              {refusal}
            </p>
          ) : null
        })}
      </div>

      <BlockStepDialog
        open={blocking}
        onOpenChange={setBlocking}
        isPending={block.isPending}
        onConfirm={(reasonCode, note) => block.mutate({ stepId: step.id, data: { reasonCode, note } })}
      />
    </>
  )
}
